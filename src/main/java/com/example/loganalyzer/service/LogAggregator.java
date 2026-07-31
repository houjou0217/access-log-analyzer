package com.example.loganalyzer.service;

import com.example.loganalyzer.model.AnalyzeRequest;
import com.example.loganalyzer.model.AnalyzeResult;
import com.example.loganalyzer.model.BucketUnit;
import com.example.loganalyzer.model.CountItem;
import com.example.loganalyzer.model.LogEntry;
import com.example.loganalyzer.model.TimeBucket;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * パース済みのログを集計するサービス。
 * 03_detailed_design.md 第4章 に対応(機能ID: F-AGG-01〜F-AGG-06)。
 *
 * <p>入力は {@link LogParser.ParseResult} と {@link AnalyzeRequest}、出力は {@link AnalyzeResult}。
 * 設計 第4章は入力を「List&lt;LogEntry&gt; と AnalyzeRequest」と記述しているが、
 * AnalyzeResult の totalLines / skippedCount は解析に失敗した行の情報を含むため
 * List&lt;LogEntry&gt; だけでは算出できない。そのためパーサの戻り値をそのまま受け取る。</p>
 *
 * <p>解析成功が0件でも例外にせず、各集計を空(件数0・空リスト)で返す(03 第4章 エッジケース)。</p>
 */
@Service
public class LogAggregator {

    /** Top N の既定値(03 9章) */
    private static final int DEFAULT_TOP_N = 10;

    /** Top N の下限(03 9章。1未満は1に丸める) */
    private static final int MIN_TOP_N = 1;

    /** Top N の上限(03 9章。100超は100に丸める) */
    private static final int MAX_TOP_N = 100;

    /** エラーとみなすステータスコードの下限(4xx/5xx。03 第4章) */
    private static final int ERROR_STATUS_THRESHOLD = 400;

    /** ステータス区分を求める除数(百の位を取り出す) */
    private static final int STATUS_CLASS_DIVISOR = 100;

    /** ステータス区分キーの接尾辞(例 "2xx" の "xx") */
    private static final String STATUS_CLASS_SUFFIX = "xx";

    /** 棒グラフ幅の最大値(％) */
    private static final int MAX_WIDTH_PERCENT = 100;

    /**
     * パース結果を集計して AnalyzeResult を組み立てる(F-AGG-01)。
     *
     * @param parseResult パーサの結果(解析成功行と、総行数・成功数・スキップ数)
     * @param request     集計パラメータ(topN / bucketUnit)。null 可(既定値を使う)
     * @return 集計結果
     */
    public AnalyzeResult aggregate(LogParser.ParseResult parseResult, AnalyzeRequest request) {
        List<LogEntry> entries = parseResult.entries();
        int topN = resolveTopN(request == null ? null : request.topN());
        BucketUnit bucketUnit = BucketUnit.fromString(request == null ? null : request.bucketUnit());

        return new AnalyzeResult(
                parseResult.totalLines(),
                parseResult.parsedCount(),
                parseResult.skippedCount(),
                countByStatusClass(entries),
                countByStatus(entries),
                topItems(entries, LogEntry::path, topN),
                topItems(entries, LogEntry::ip, topN),
                countByTimeBucket(entries, bucketUnit),
                extractErrorEntries(entries));
    }

    /**
     * Top N を設計の範囲に丸める(F-AGG-03 / F-AGG-04、03 9章)。
     *
     * @param topN 要求された件数。null の場合は既定値
     * @return 1〜100 に収めた件数
     */
    private int resolveTopN(Integer topN) {
        if (topN == null) {
            return DEFAULT_TOP_N;
        }
        if (topN < MIN_TOP_N) {
            return MIN_TOP_N;
        }
        if (topN > MAX_TOP_N) {
            return MAX_TOP_N;
        }
        return topN;
    }

    /**
     * ステータス区分ごとの件数を数える(F-AGG-02、03 第4章)。
     *
     * <p>百の位で区分し、"2xx" のようなキーにする。1xx が来た場合も "1xx" として数える。
     * 出現しなかった区分はキーを作らない(画面側で 0 として表示される)。</p>
     *
     * @param entries 解析成功行
     * @return 区分別の件数(区分の昇順)
     */
    private Map<String, Long> countByStatusClass(List<LogEntry> entries) {
        Map<Integer, Long> countsByClass = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.status() / STATUS_CLASS_DIVISOR,
                        TreeMap::new,
                        Collectors.counting()));

        // 表示順を安定させるため、区分の昇順を保つ LinkedHashMap に詰め替える
        Map<String, Long> result = new LinkedHashMap<>();
        countsByClass.forEach((statusClass, count) ->
                result.put(statusClass + STATUS_CLASS_SUFFIX, count));
        return result;
    }

    /**
     * 個別ステータスコードごとの件数を数える(F-AGG-02、03 第4章)。
     *
     * @param entries 解析成功行
     * @return コード昇順の件数リスト
     */
    private List<CountItem> countByStatus(List<LogEntry> entries) {
        return entries.stream()
                .collect(Collectors.groupingBy(LogEntry::status, TreeMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new CountItem(String.valueOf(entry.getKey()), entry.getValue()))
                .toList();
    }

    /**
     * 指定した項目の上位 N 件を求める(F-AGG-03 / F-AGG-04、03 第4章)。
     *
     * <p>件数降順で並べ、件数が同数のときはキーの辞書順にする。</p>
     *
     * @param entries      解析成功行
     * @param keyExtractor 集計キーの取り出し方(パス or IP)
     * @param topN         取得件数
     * @return 上位 N 件の件数リスト
     */
    private List<CountItem> topItems(List<LogEntry> entries,
                                     Function<LogEntry, String> keyExtractor,
                                     int topN) {
        Map<String, Long> counts = entries.stream()
                .collect(Collectors.groupingBy(keyExtractor, Collectors.counting()));

        return counts.entrySet().stream()
                .map(entry -> new CountItem(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(CountItem::count).reversed()
                        .thenComparing(CountItem::key))
                .limit(topN)
                .toList();
    }

    /**
     * 時間バケットごとの件数を数える(F-AGG-05、03 3.5 / 第4章)。
     *
     * <p>リクエストが無いバケットは出力しない。widthPercent は「集計内の最大件数を100%」として算出する。</p>
     *
     * @param entries    解析成功行
     * @param bucketUnit 丸めの単位
     * @return 時刻昇順のバケットリスト
     */
    private List<TimeBucket> countByTimeBucket(List<LogEntry> entries, BucketUnit bucketUnit) {
        // 丸めた日時をキーに数える。TreeMap により時刻昇順が保たれる
        Map<OffsetDateTime, Long> countsByBucket = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> bucketUnit.truncate(entry.dateTime()),
                        TreeMap::new,
                        Collectors.counting()));

        long maxCount = countsByBucket.values().stream().mapToLong(Long::longValue).max().orElse(0L);

        List<TimeBucket> buckets = new ArrayList<>();
        countsByBucket.forEach((bucketStart, count) -> buckets.add(new TimeBucket(
                bucketUnit.formatBucket(bucketStart),
                count,
                toWidthPercent(count, maxCount))));
        return buckets;
    }

    /**
     * 棒グラフの幅(％)を求める(F-AGG-05、03 2.3)。
     *
     * @param count    そのバケットの件数
     * @param maxCount 集計内の最大件数
     * @return 0〜100 の幅。最大件数が0のときは0
     */
    private int toWidthPercent(long count, long maxCount) {
        if (maxCount <= 0) {
            return 0;
        }
        return (int) Math.round((double) count * MAX_WIDTH_PERCENT / maxCount);
    }

    /**
     * エラー行(ステータス400以上)を抽出する(F-AGG-06、03 第4章)。
     *
     * @param entries 解析成功行
     * @return 時刻昇順のエラー行リスト
     */
    private List<LogEntry> extractErrorEntries(List<LogEntry> entries) {
        return entries.stream()
                .filter(entry -> entry.status() >= ERROR_STATUS_THRESHOLD)
                .sorted(Comparator.comparing(LogEntry::dateTime))
                .toList();
    }
}
