package com.example.loganalyzer.service;

import com.example.loganalyzer.model.LogEntry;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combined Log Format のアクセスログをパースするサービス。
 * 03_detailed_design.md 第3章 に対応(機能ID: F-PRS-01 / F-PRS-02 / F-PRS-03)。
 *
 * <p>1行の構造(03 3.1):
 * {@code %h %l %u [%t] "%r" %>s %b "%{Referer}i" "%{User-Agent}i"}</p>
 *
 * <p>解析できない行は例外を投げずにスキップし、件数だけを数える(03 3.3 / F-PRS-02)。
 * 貼り付けられたログの一部が壊れていてもアプリ全体を止めないための方針。</p>
 */
@Service
public class LogParser {

    /**
     * ログ1行を分解する正規表現(03 3.3「正規表現1本で1行を分解する」)。
     * 捕捉グループは9個で、%l(ident)と%u(user)も形式検証のために捕捉するが値は使わない(03 3.2)。
     */
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\S+)"              // 1: %h        クライアントIP
            + "\\s+(\\S+)"         // 2: %l        ident(未使用)
            + "\\s+(\\S+)"         // 3: %u        user(未使用)
            + "\\s+\\[([^\\]]+)\\]"// 4: [%t]      日時
            + "\\s+\"([^\"]*)\""   // 5: "%r"      リクエスト行
            + "\\s+(\\S+)"         // 6: %>s       ステータスコード
            + "\\s+(\\d+|-)"       // 7: %b        サイズ('-' は0)
            + "\\s+\"([^\"]*)\""   // 8: Referer
            + "\\s+\"([^\"]*)\""   // 9: User-Agent
            + "\\s*$");

    /** 正規表現の捕捉グループ番号(可読性のため名前を付ける) */
    private static final int GROUP_IP = 1;
    private static final int GROUP_DATE_TIME = 4;
    private static final int GROUP_REQUEST = 5;
    private static final int GROUP_STATUS = 6;
    private static final int GROUP_SIZE = 7;
    private static final int GROUP_REFERER = 8;
    private static final int GROUP_USER_AGENT = 9;

    /**
     * 日時のフォーマット(03 3.2)。月名が "Jul" 等の英語表記のため Locale.ENGLISH を指定する。
     */
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** ログ中で「値なし」を表す記号(サイズ・リファラで使われる) */
    private static final String EMPTY_MARK = "-";

    /** サイズが '-' のときに採用する値(03 2.1) */
    private static final long SIZE_WHEN_EMPTY = 0L;

    /** リクエスト行 "%r" の要素数(メソッド・パス・プロトコルの3つ。03 3.2) */
    private static final int REQUEST_PART_COUNT = 3;

    /** リクエスト行を分解したときの各要素の位置 */
    private static final int REQUEST_INDEX_METHOD = 0;
    private static final int REQUEST_INDEX_PATH = 1;
    private static final int REQUEST_INDEX_PROTOCOL = 2;

    /** 改行(CRLF / LF / CR)で行を分割する正規表現 */
    private static final String LINE_SEPARATOR_REGEX = "\\R";

    /** 空白の連続(リクエスト行の分解に使う) */
    private static final String WHITESPACE_REGEX = "\\s+";

    /**
     * ログ本文全体をパースする。
     *
     * <p>空行(トリム後に空)は総行数に数えない(03 3.3)。
     * 解析できない行はスキップして数え、後続の行の処理を続ける(F-PRS-02)。</p>
     *
     * @param rawLog 貼り付けられたログ本文(複数行)。null や空文字も受け付ける
     * @return パース結果(成功した行のリストと、総行数・成功数・スキップ数)
     */
    public ParseResult parse(String rawLog) {
        List<LogEntry> entries = new ArrayList<>();
        int totalLines = 0;
        int skippedCount = 0;

        // 入力が空の場合も例外にせず、総行数0の結果を返す(03 5.1)
        if (rawLog == null || rawLog.isBlank()) {
            return new ParseResult(List.of(), 0, 0, 0);
        }

        for (String line : rawLog.split(LINE_SEPARATOR_REGEX)) {
            // 空行は総行数に数えない(03 3.3)
            if (line.isBlank()) {
                continue;
            }
            totalLines++;

            Optional<LogEntry> entry = parseLine(line);
            if (entry.isPresent()) {
                entries.add(entry.get());
            } else {
                // 解析不能行。理由を問わずスキップして次の行へ進む(03 3.3 / F-PRS-02)
                skippedCount++;
            }
        }

        return new ParseResult(entries, totalLines, entries.size(), skippedCount);
    }

    /**
     * ログ1行をパースして LogEntry を生成する(F-PRS-01)。
     *
     * <p>次の場合は解析不能とみなし、空の Optional を返す(03 3.2 / 3.3)。
     * 例外を呼び出し元へ投げないことで、1行の破損が全体の処理を止めないようにしている。</p>
     * <ul>
     *   <li>正規表現に一致しない行</li>
     *   <li>日時のパースに失敗した行</li>
     *   <li>ステータスコードが整数でない行</li>
     *   <li>リクエスト行が「メソッド・パス・プロトコル」の3要素に分解できない行</li>
     * </ul>
     *
     * @param line ログ1行
     * @return 解析できた場合は LogEntry、解析不能な場合は空の Optional
     */
    public Optional<LogEntry> parseLine(String line) {
        if (line == null) {
            return Optional.empty();
        }

        Matcher matcher = LOG_PATTERN.matcher(line.trim());
        if (!matcher.matches()) {
            // 形式が Combined Log Format と一致しない(03 3.3)
            return Optional.empty();
        }

        // リクエスト行 "%r" は空白区切りの3要素に分解する(03 3.2)
        String[] requestParts = matcher.group(GROUP_REQUEST).trim().split(WHITESPACE_REGEX);
        if (requestParts.length != REQUEST_PART_COUNT) {
            // リクエスト行が異常(03 3.2)
            return Optional.empty();
        }

        OffsetDateTime dateTime = parseDateTime(matcher.group(GROUP_DATE_TIME));
        if (dateTime == null) {
            // 日時のパースに失敗(03 3.3)
            return Optional.empty();
        }

        int status;
        long size;
        try {
            status = Integer.parseInt(matcher.group(GROUP_STATUS));
            size = parseSize(matcher.group(GROUP_SIZE));
        } catch (NumberFormatException e) {
            // ステータスが整数でない、またはサイズが数値として扱えない(03 3.3)
            return Optional.empty();
        }

        LogEntry entry = new LogEntry(
                matcher.group(GROUP_IP),
                dateTime,
                requestParts[REQUEST_INDEX_METHOD],
                requestParts[REQUEST_INDEX_PATH],
                requestParts[REQUEST_INDEX_PROTOCOL],
                status,
                size,
                normalizeReferer(matcher.group(GROUP_REFERER)),
                matcher.group(GROUP_USER_AGENT));
        return Optional.of(entry);
    }

    /**
     * 日時文字列を OffsetDateTime に変換する(F-PRS-03、03 3.2 / 3.4)。
     *
     * <p>タイムゾーン込みで保持するため OffsetDateTime を使う。</p>
     *
     * @param value 日時文字列(例 30/Jul/2026:10:15:32 +0900)
     * @return 変換後の日時。パースできない場合は null
     */
    private OffsetDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value, DATE_TIME_FORMAT);
        } catch (DateTimeParseException e) {
            // 日付形式が不正な行は解析不能として扱う(03 3.3)
            return null;
        }
    }

    /**
     * サイズ文字列を数値に変換する(03 2.1 / 3.2)。
     *
     * @param value サイズ文字列。'-' の場合は0として扱う
     * @return レスポンスサイズ(バイト)
     * @throws NumberFormatException 数値として扱えない場合
     */
    private long parseSize(String value) {
        if (EMPTY_MARK.equals(value)) {
            return SIZE_WHEN_EMPTY;
        }
        return Long.parseLong(value);
    }

    /**
     * リファラを正規化する(03 3.2)。
     *
     * @param value リファラ文字列
     * @return '-' の場合は空文字、それ以外はそのままの値
     */
    private String normalizeReferer(String value) {
        return EMPTY_MARK.equals(value) ? "" : value;
    }

    /**
     * パース結果を運ぶホルダ。
     *
     * <p>03 2.4 の AnalyzeResult が totalLines / parsedCount / skippedCount を必要とするため、
     * パーサからそれらを受け渡すための型。新しい機能ではなく、設計上の集計項目を運ぶだけの器。</p>
     *
     * @param entries      解析に成功した行
     * @param totalLines   総行数(空行を除く)
     * @param parsedCount  解析成功行数
     * @param skippedCount 解析不能行数
     */
    public record ParseResult(
            List<LogEntry> entries,
            int totalLines,
            int parsedCount,
            int skippedCount
    ) {
    }
}
