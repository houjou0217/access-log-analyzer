package com.example.loganalyzer.service;

import com.example.loganalyzer.model.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LogParser のテスト。
 * 03_detailed_design.md 第3章 / 8.1 のテスト必須項目(F-PRS-01 / F-PRS-02 / F-PRS-03)に対応。
 */
class LogParserTest {

    /** テスト対象 */
    private final LogParser parser = new LogParser();

    /** 03 3.1 に例示されている正常な1行 */
    private static final String VALID_LINE =
            "192.168.0.10 - - [30/Jul/2026:10:15:32 +0900] "
            + "\"GET /index.html HTTP/1.1\" 200 1043 \"https://example.com/\" \"Mozilla/5.0\"";

    /** 設計書がテストデータとして指定するサンプルログ(03 8.1) */
    private static final Path SAMPLE_LOG = Path.of("sample-logs", "access.log");

    /** サンプルログの期待値(全26行・うち1行が壊れた行) */
    private static final int SAMPLE_TOTAL_LINES = 26;
    private static final int SAMPLE_PARSED_COUNT = 25;
    private static final int SAMPLE_SKIPPED_COUNT = 1;

    @Nested
    @DisplayName("F-PRS-01: ログ1行のパース")
    class ParseLineTest {

        @Test
        @DisplayName("正常行が9項目に正しく分解される")
        void parsesAllFields() {
            LogEntry entry = parser.parseLine(VALID_LINE).orElseThrow();

            assertEquals("192.168.0.10", entry.ip());
            assertEquals("GET", entry.method());
            assertEquals("/index.html", entry.path());
            assertEquals("HTTP/1.1", entry.protocol());
            assertEquals(200, entry.status());
            assertEquals(1043L, entry.size());
            assertEquals("https://example.com/", entry.referer());
            assertEquals("Mozilla/5.0", entry.userAgent());
        }

        @Test
        @DisplayName("サイズが '-' のときは0になる(03 2.1)")
        void treatsHyphenSizeAsZero() {
            String line = "10.0.0.1 - - [30/Jul/2026:10:15:32 +0900] "
                    + "\"GET /favicon.ico HTTP/1.1\" 304 - \"-\" \"curl/8.4.0\"";

            LogEntry entry = parser.parseLine(line).orElseThrow();

            assertEquals(0L, entry.size());
        }

        @Test
        @DisplayName("リファラが '-' のときは空文字になる(03 3.2)")
        void treatsHyphenRefererAsEmpty() {
            String line = "10.0.0.1 - - [30/Jul/2026:10:15:32 +0900] "
                    + "\"GET /login HTTP/1.1\" 200 880 \"-\" \"curl/8.4.0\"";

            LogEntry entry = parser.parseLine(line).orElseThrow();

            assertEquals("", entry.referer());
        }

        @Test
        @DisplayName("パスはクエリ文字列を含めて保持される(03 2.1)")
        void keepsQueryStringInPath() {
            String line = "192.168.0.12 - - [30/Jul/2026:10:25:40 +0900] "
                    + "\"GET /products?category=network HTTP/1.1\" 200 5120 \"-\" \"Mozilla/5.0\"";

            LogEntry entry = parser.parseLine(line).orElseThrow();

            assertEquals("/products?category=network", entry.path());
        }

        @Test
        @DisplayName("GET以外のメソッドも分解できる")
        void parsesNonGetMethod() {
            String line = "203.0.113.9 - - [30/Jul/2026:11:41:55 +0900] "
                    + "\"DELETE /api/orders/9001 HTTP/1.1\" 204 - \"-\" \"PostmanRuntime/7.39.0\"";

            LogEntry entry = parser.parseLine(line).orElseThrow();

            assertEquals("DELETE", entry.method());
            assertEquals("/api/orders/9001", entry.path());
            assertEquals(204, entry.status());
        }
    }

    @Nested
    @DisplayName("F-PRS-03: 日時とタイムゾーン")
    class DateTimeTest {

        @Test
        @DisplayName("日時が +0900 のオフセット付きで解釈される")
        void parsesDateTimeWithOffset() {
            LogEntry entry = parser.parseLine(VALID_LINE).orElseThrow();

            OffsetDateTime expected =
                    OffsetDateTime.of(2026, 7, 30, 10, 15, 32, 0, ZoneOffset.ofHours(9));
            assertEquals(expected, entry.dateTime());
            assertEquals(ZoneOffset.ofHours(9), entry.dateTime().getOffset());
        }

        @Test
        @DisplayName("+0900 以外のタイムゾーンもそのまま保持される")
        void keepsOtherOffsets() {
            String utcLine = "10.0.0.1 - - [30/Jul/2026:01:15:32 +0000] "
                    + "\"GET /index.html HTTP/1.1\" 200 1043 \"-\" \"Mozilla/5.0\"";
            String minusLine = "10.0.0.1 - - [30/Jul/2026:01:15:32 -0500] "
                    + "\"GET /index.html HTTP/1.1\" 200 1043 \"-\" \"Mozilla/5.0\"";

            assertEquals(ZoneOffset.UTC, parser.parseLine(utcLine).orElseThrow().dateTime().getOffset());
            assertEquals(ZoneOffset.ofHours(-5),
                    parser.parseLine(minusLine).orElseThrow().dateTime().getOffset());
        }

        @Test
        @DisplayName("英語の月名(Jul等)がロケールに依存せず解釈される")
        void parsesEnglishMonthName() {
            String decemberLine = "10.0.0.1 - - [05/Dec/2026:23:59:59 +0900] "
                    + "\"GET /index.html HTTP/1.1\" 200 1043 \"-\" \"Mozilla/5.0\"";

            LogEntry entry = parser.parseLine(decemberLine).orElseThrow();

            assertEquals(12, entry.dateTime().getMonthValue());
            assertEquals(5, entry.dateTime().getDayOfMonth());
            assertEquals(23, entry.dateTime().getHour());
        }
    }

    @Nested
    @DisplayName("F-PRS-02: 解析不能行のスキップ")
    class SkipTest {

        @Test
        @DisplayName("形式が全く違う行は解析不能になる")
        void skipsBrokenLine() {
            assertEquals(Optional.empty(),
                    parser.parseLine("this-is-a-broken-line-that-should-be-skipped"));
        }

        @Test
        @DisplayName("日時が不正な行は解析不能になる(03 3.3)")
        void skipsInvalidDateTime() {
            String line = "10.0.0.1 - - [99/XXX/2026:99:99:99 +0900] "
                    + "\"GET /index.html HTTP/1.1\" 200 1043 \"-\" \"Mozilla/5.0\"";

            assertEquals(Optional.empty(), parser.parseLine(line));
        }

        @Test
        @DisplayName("ステータスが整数でない行は解析不能になる(03 3.3)")
        void skipsNonNumericStatus() {
            String line = "10.0.0.1 - - [30/Jul/2026:10:15:32 +0900] "
                    + "\"GET /index.html HTTP/1.1\" abc 1043 \"-\" \"Mozilla/5.0\"";

            assertEquals(Optional.empty(), parser.parseLine(line));
        }

        @Test
        @DisplayName("リクエスト行が3要素に分解できない行は解析不能になる(03 3.2)")
        void skipsMalformedRequestLine() {
            String twoParts = "10.0.0.1 - - [30/Jul/2026:10:15:32 +0900] "
                    + "\"GET /index.html\" 200 1043 \"-\" \"Mozilla/5.0\"";
            String empty = "10.0.0.1 - - [30/Jul/2026:10:15:32 +0900] "
                    + "\"\" 200 1043 \"-\" \"Mozilla/5.0\"";

            assertEquals(Optional.empty(), parser.parseLine(twoParts));
            assertEquals(Optional.empty(), parser.parseLine(empty));
        }

        @Test
        @DisplayName("壊れた行があっても処理は止まらず、後続の行が解析される")
        void continuesAfterBrokenLine() {
            String rawLog = String.join("\n",
                    VALID_LINE,
                    "this-is-a-broken-line-that-should-be-skipped",
                    VALID_LINE);

            LogParser.ParseResult result = parser.parse(rawLog);

            assertEquals(3, result.totalLines());
            assertEquals(2, result.parsedCount());
            assertEquals(1, result.skippedCount());
            assertEquals(2, result.entries().size());
        }

        @Test
        @DisplayName("空行は総行数に数えない(03 3.3)")
        void ignoresBlankLines() {
            String rawLog = "\n\n" + VALID_LINE + "\n   \n\n" + VALID_LINE + "\n";

            LogParser.ParseResult result = parser.parse(rawLog);

            assertEquals(2, result.totalLines());
            assertEquals(2, result.parsedCount());
            assertEquals(0, result.skippedCount());
        }

        @Test
        @DisplayName("CRLF 改行でも行を分割できる")
        void handlesCrLf() {
            String rawLog = VALID_LINE + "\r\n" + VALID_LINE;

            LogParser.ParseResult result = parser.parse(rawLog);

            assertEquals(2, result.totalLines());
            assertEquals(2, result.parsedCount());
        }

        @Test
        @DisplayName("入力が null / 空のときは総行数0の結果を返す(03 5.1)")
        void returnsEmptyResultForEmptyInput() {
            for (String input : new String[]{null, "", "   ", "\n\n"}) {
                LogParser.ParseResult result = parser.parse(input);

                assertEquals(0, result.totalLines());
                assertEquals(0, result.parsedCount());
                assertEquals(0, result.skippedCount());
                assertTrue(result.entries().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("サンプルログ(sample-logs/access.log)全体のパース")
    class SampleLogTest {

        @Test
        @DisplayName("全26行のうち25行が解析成功し、1行がスキップされる")
        void parsesSampleLog() throws IOException {
            assertTrue(Files.exists(SAMPLE_LOG),
                    "テストデータが見つかりません: " + SAMPLE_LOG.toAbsolutePath());
            String rawLog = Files.readString(SAMPLE_LOG, StandardCharsets.UTF_8);

            LogParser.ParseResult result = parser.parse(rawLog);

            assertEquals(SAMPLE_TOTAL_LINES, result.totalLines());
            assertEquals(SAMPLE_PARSED_COUNT, result.parsedCount());
            assertEquals(SAMPLE_SKIPPED_COUNT, result.skippedCount());
            assertEquals(SAMPLE_PARSED_COUNT, result.entries().size());
        }

        @Test
        @DisplayName("先頭行と末尾行が期待どおりに解析される")
        void parsesFirstAndLastEntry() throws IOException {
            String rawLog = Files.readString(SAMPLE_LOG, StandardCharsets.UTF_8);

            LogParser.ParseResult result = parser.parse(rawLog);
            LogEntry first = result.entries().get(0);
            LogEntry last = result.entries().get(result.entries().size() - 1);

            assertEquals("192.168.0.10", first.ip());
            assertEquals("/index.html", first.path());
            assertEquals(200, first.status());

            assertEquals("192.168.0.15", last.ip());
            assertEquals(200, last.status());
        }
    }
}
