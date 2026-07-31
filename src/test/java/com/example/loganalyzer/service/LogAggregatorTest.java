package com.example.loganalyzer.service;

import com.example.loganalyzer.model.AnalyzeRequest;
import com.example.loganalyzer.model.AnalyzeResult;
import com.example.loganalyzer.model.CountItem;
import com.example.loganalyzer.model.LogEntry;
import com.example.loganalyzer.model.TimeBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LogAggregator のテスト。
 * 03_detailed_design.md 第4章 / 8.1 のテスト必須項目(F-AGG-02〜F-AGG-05)を中心に、
 * F-AGG-01 / F-AGG-06 も含めて検証する。
 */
class LogAggregatorTest {

    /** テスト対象 */
    private final LogAggregator aggregator = new LogAggregator();

    /** サンプルログとの結合確認に使うパーサ */
    private final LogParser parser = new LogParser();

    /** 設計書がテストデータとして指定するサンプルログ(03 8.1) */
    private static final Path SAMPLE_LOG = Path.of("sample-logs", "access.log");

    /** テスト用の日付(時刻部分だけをテストごとに変える) */
    private static final String TEST_DATE = "2026-07-30T";

    /** テスト用のタイムゾーン(+09:00) */
    private static final String TEST_OFFSET = "+09:00";

    /**
     * テスト用の LogEntry を作る。集計に関係しない項目は固定値にする。
     *
     * @param ip     クライアントIP
     * @param time   時刻(例 "10:15:32")
     * @param path   リクエストパス
     * @param status ステータスコード
     * @return LogEntry
     */
    private static LogEntry entry(String ip, String time, String path, int status) {
        OffsetDateTime dateTime = OffsetDateTime.parse(TEST_DATE + time + TEST_OFFSET);
        return new LogEntry(ip, dateTime, "GET", path, "HTTP/1.1", status, 0L, "", "Mozilla/5.0");
    }

    /**
     * スキップ0件のパース結果を作る(集計だけを検証したい場合に使う)。
     *
     * @param entries 解析成功行
     * @return ParseResult
     */
    private static LogParser.ParseResult parsed(List<LogEntry> entries) {
        return new LogParser.ParseResult(entries, entries.size(), entries.size(), 0);
    }

    /**
     * 集計パラメータを作る。
     *
     * @param topN       上位件数(null 可)
     * @param bucketUnit 時間バケット単位の文字列(null 可)
     * @return AnalyzeRequest
     */
    private static AnalyzeRequest request(Integer topN, String bucketUnit) {
        return new AnalyzeRequest(null, topN, bucketUnit);
    }

    @Nested
    @DisplayName("F-AGG-01: 総数・成功・スキップ")
    class SummaryTest {

        @Test
        @DisplayName("パース結果の件数がそのまま引き継がれる")
        void carriesOverCounts() {
            LogParser.ParseResult parseResult = new LogParser.ParseResult(
                    List.of(entry("10.0.0.1", "10:00:00", "/a", 200)), 5, 1, 4);

            AnalyzeResult result = aggregator.aggregate(parseResult, request(null, null));

            assertEquals(5, result.totalLines());
            assertEquals(1, result.parsedCount());
            assertEquals(4, result.skippedCount());
        }
    }

    @Nested
    @DisplayName("F-AGG-02: ステータスコード別集計")
    class StatusTest {

        @Test
        @DisplayName("百の位で区分される(1xx も数える)")
        void countsByStatusClass() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "10:00:00", "/a", 100),
                    entry("10.0.0.1", "10:00:01", "/a", 200),
                    entry("10.0.0.1", "10:00:02", "/a", 201),
                    entry("10.0.0.1", "10:00:03", "/a", 302),
                    entry("10.0.0.1", "10:00:04", "/a", 404),
                    entry("10.0.0.1", "10:00:05", "/a", 404),
                    entry("10.0.0.1", "10:00:06", "/a", 500));

            Map<String, Long> classCounts =
                    aggregator.aggregate(parsed(entries), request(null, null)).statusClassCounts();

            assertEquals(1L, classCounts.get("1xx"));
            assertEquals(2L, classCounts.get("2xx"));
            assertEquals(1L, classCounts.get("3xx"));
            assertEquals(2L, classCounts.get("4xx"));
            assertEquals(1L, classCounts.get("5xx"));
        }

        @Test
        @DisplayName("出現しなかった区分はキーを作らない(画面側で0表示)")
        void omitsAbsentStatusClass() {
            List<LogEntry> entries = List.of(entry("10.0.0.1", "10:00:00", "/a", 200));

            Map<String, Long> classCounts =
                    aggregator.aggregate(parsed(entries), request(null, null)).statusClassCounts();

            assertEquals(Map.of("2xx", 1L), classCounts);
        }

        @Test
        @DisplayName("個別コードはコード昇順に並ぶ")
        void sortsStatusCountsAscending() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "10:00:00", "/a", 500),
                    entry("10.0.0.1", "10:00:01", "/a", 200),
                    entry("10.0.0.1", "10:00:02", "/a", 404),
                    entry("10.0.0.1", "10:00:03", "/a", 200));

            List<CountItem> statusCounts =
                    aggregator.aggregate(parsed(entries), request(null, null)).statusCounts();

            assertEquals(List.of("200", "404", "500"), statusCounts.stream().map(CountItem::key).toList());
            assertEquals(List.of(2L, 1L, 1L), statusCounts.stream().map(CountItem::count).toList());
        }
    }

    @Nested
    @DisplayName("F-AGG-03 / F-AGG-04: 上位パス・上位IP")
    class TopItemsTest {

        /** 件数に差を付けたパスの集合(/a=3, /b=2, /c=2, /d=1) */
        private List<LogEntry> entriesWithTies() {
            return List.of(
                    entry("10.0.0.1", "10:00:00", "/a", 200),
                    entry("10.0.0.1", "10:00:01", "/a", 200),
                    entry("10.0.0.1", "10:00:02", "/a", 200),
                    entry("10.0.0.2", "10:00:03", "/c", 200),
                    entry("10.0.0.2", "10:00:04", "/c", 200),
                    entry("10.0.0.3", "10:00:05", "/b", 200),
                    entry("10.0.0.3", "10:00:06", "/b", 200),
                    entry("10.0.0.4", "10:00:07", "/d", 200));
        }

        @Test
        @DisplayName("上位パスは件数降順、同数はパスの辞書順")
        void sortsTopPaths() {
            List<CountItem> topPaths =
                    aggregator.aggregate(parsed(entriesWithTies()), request(null, null)).topPaths();

            assertEquals(List.of("/a", "/b", "/c", "/d"), topPaths.stream().map(CountItem::key).toList());
            assertEquals(List.of(3L, 2L, 2L, 1L), topPaths.stream().map(CountItem::count).toList());
        }

        @Test
        @DisplayName("上位IPは件数降順、同数はIPの辞書順")
        void sortsTopIps() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.9", "10:00:00", "/a", 200),
                    entry("10.0.0.9", "10:00:01", "/a", 200),
                    entry("10.0.0.2", "10:00:02", "/a", 200),
                    entry("10.0.0.1", "10:00:03", "/a", 200));

            List<CountItem> topIps =
                    aggregator.aggregate(parsed(entries), request(null, null)).topIps();

            assertEquals(List.of("10.0.0.9", "10.0.0.1", "10.0.0.2"),
                    topIps.stream().map(CountItem::key).toList());
            assertEquals(List.of(2L, 1L, 1L), topIps.stream().map(CountItem::count).toList());
        }

        @Test
        @DisplayName("topN の件数だけに絞られる")
        void limitsToTopN() {
            AnalyzeResult result = aggregator.aggregate(parsed(entriesWithTies()), request(2, null));

            assertEquals(List.of("/a", "/b"), result.topPaths().stream().map(CountItem::key).toList());
        }

        @Test
        @DisplayName("topN が null / 範囲外なら 10・1・100 に丸められる(03 9章)")
        void clampsTopN() {
            List<LogEntry> entries = entriesWithTies();

            // null → 既定10(全4件が入る)
            assertEquals(4, aggregator.aggregate(parsed(entries), request(null, null)).topPaths().size());
            // 0 → 下限1
            assertEquals(1, aggregator.aggregate(parsed(entries), request(0, null)).topPaths().size());
            // -5 → 下限1
            assertEquals(1, aggregator.aggregate(parsed(entries), request(-5, null)).topPaths().size());
            // 999 → 上限100(全4件が入る)
            assertEquals(4, aggregator.aggregate(parsed(entries), request(999, null)).topPaths().size());
        }

        @Test
        @DisplayName("request が null でも既定値で集計できる")
        void handlesNullRequest() {
            AnalyzeResult result = aggregator.aggregate(parsed(entriesWithTies()), null);

            assertEquals(4, result.topPaths().size());
        }
    }

    @Nested
    @DisplayName("F-AGG-05: 時間帯別集計(バケット丸め)")
    class TimeBucketTest {

        /** 10時台に4件、11時台に1件(10分・1分単位でも差が出る並び) */
        private List<LogEntry> entriesAcrossTime() {
            return List.of(
                    entry("10.0.0.1", "10:05:10", "/a", 200),
                    entry("10.0.0.1", "10:05:40", "/a", 200),
                    entry("10.0.0.1", "10:15:00", "/a", 200),
                    entry("10.0.0.1", "10:27:33", "/a", 200),
                    entry("10.0.0.1", "11:40:03", "/a", 200));
        }

        @Test
        @DisplayName("HOUR は分・秒を0に丸め、時刻昇順で返る")
        void bucketsByHour() {
            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entriesAcrossTime()), request(null, "HOUR")).timeBuckets();

            assertEquals(List.of("2026-07-30 10:00", "2026-07-30 11:00"),
                    buckets.stream().map(TimeBucket::bucketStart).toList());
            assertEquals(List.of(4L, 1L), buckets.stream().map(TimeBucket::count).toList());
        }

        @Test
        @DisplayName("TEN_MIN は分を10分単位で切り捨てる")
        void bucketsByTenMinutes() {
            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entriesAcrossTime()), request(null, "TEN_MIN")).timeBuckets();

            assertEquals(List.of("2026-07-30 10:00", "2026-07-30 10:10",
                            "2026-07-30 10:20", "2026-07-30 11:40"),
                    buckets.stream().map(TimeBucket::bucketStart).toList());
            assertEquals(List.of(2L, 1L, 1L, 1L), buckets.stream().map(TimeBucket::count).toList());
        }

        @Test
        @DisplayName("MINUTE は秒だけを0に丸める")
        void bucketsByMinute() {
            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entriesAcrossTime()), request(null, "MINUTE")).timeBuckets();

            assertEquals(List.of("2026-07-30 10:05", "2026-07-30 10:15",
                            "2026-07-30 10:27", "2026-07-30 11:40"),
                    buckets.stream().map(TimeBucket::bucketStart).toList());
            assertEquals(List.of(2L, 1L, 1L, 1L), buckets.stream().map(TimeBucket::count).toList());
        }

        @Test
        @DisplayName("bucketUnit が不正・null なら HOUR にフォールバックする(03 5.1)")
        void fallsBackToHour() {
            List<String> invalidUnits = List.of("DAY", "", "hour_x");

            for (String unit : invalidUnits) {
                List<TimeBucket> buckets = aggregator
                        .aggregate(parsed(entriesAcrossTime()), request(null, unit)).timeBuckets();
                assertEquals(2, buckets.size(), "不正値 '" + unit + "' は HOUR 扱いになるべき");
            }
            assertEquals(2, aggregator
                    .aggregate(parsed(entriesAcrossTime()), request(null, null)).timeBuckets().size());
        }

        @Test
        @DisplayName("小文字の bucketUnit も解釈される")
        void acceptsLowerCaseUnit() {
            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entriesAcrossTime()), request(null, "minute")).timeBuckets();

            assertEquals(4, buckets.size());
        }

        @Test
        @DisplayName("リクエストが無い時間帯のバケットは出力しない")
        void skipsEmptyBuckets() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "10:00:00", "/a", 200),
                    entry("10.0.0.1", "13:00:00", "/a", 200));

            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entries), request(null, "HOUR")).timeBuckets();

            // 11時台・12時台は出さない
            assertEquals(List.of("2026-07-30 10:00", "2026-07-30 13:00"),
                    buckets.stream().map(TimeBucket::bucketStart).toList());
        }

        @Test
        @DisplayName("widthPercent は最大件数を100%として算出される(03 2.3)")
        void calculatesWidthPercent() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "10:00:00", "/a", 200),
                    entry("10.0.0.1", "10:00:01", "/a", 200),
                    entry("10.0.0.1", "10:00:02", "/a", 200),
                    entry("10.0.0.1", "10:00:03", "/a", 200),
                    entry("10.0.0.1", "11:00:00", "/a", 200));

            List<TimeBucket> buckets = aggregator
                    .aggregate(parsed(entries), request(null, "HOUR")).timeBuckets();

            // 最大は4件 → 100%、1件 → 25%
            assertEquals(100, buckets.get(0).widthPercent());
            assertEquals(25, buckets.get(1).widthPercent());
        }
    }

    @Nested
    @DisplayName("F-AGG-06: エラー行抽出")
    class ErrorEntriesTest {

        @Test
        @DisplayName("ステータス400以上だけを時刻昇順で抽出する")
        void extractsErrorsInTimeOrder() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "12:00:00", "/e", 503),
                    entry("10.0.0.1", "10:00:00", "/a", 200),
                    entry("10.0.0.1", "11:00:00", "/d", 500),
                    entry("10.0.0.1", "10:30:00", "/b", 404),
                    entry("10.0.0.1", "10:45:00", "/c", 399));

            List<LogEntry> errors =
                    aggregator.aggregate(parsed(entries), request(null, null)).errorEntries();

            assertEquals(List.of("/b", "/d", "/e"), errors.stream().map(LogEntry::path).toList());
            assertEquals(List.of(404, 500, 503), errors.stream().map(LogEntry::status).toList());
        }

        @Test
        @DisplayName("境界値: 400 は含み、399 は含まない")
        void includesStatus400Only() {
            List<LogEntry> entries = List.of(
                    entry("10.0.0.1", "10:00:00", "/ok", 399),
                    entry("10.0.0.1", "10:00:01", "/ng", 400));

            List<LogEntry> errors =
                    aggregator.aggregate(parsed(entries), request(null, null)).errorEntries();

            assertEquals(1, errors.size());
            assertEquals("/ng", errors.get(0).path());
        }
    }

    @Nested
    @DisplayName("エッジケース: 解析成功0件(03 第4章)")
    class EmptyTest {

        @Test
        @DisplayName("解析0件でも例外にせず、空の集計を返す")
        void returnsEmptyAggregations() {
            AnalyzeResult result =
                    aggregator.aggregate(parsed(List.of()), request(null, null));

            assertEquals(0, result.totalLines());
            assertEquals(0, result.parsedCount());
            assertTrue(result.statusClassCounts().isEmpty());
            assertTrue(result.statusCounts().isEmpty());
            assertTrue(result.topPaths().isEmpty());
            assertTrue(result.topIps().isEmpty());
            assertTrue(result.timeBuckets().isEmpty());
            assertTrue(result.errorEntries().isEmpty());
        }

        @Test
        @DisplayName("全行がスキップされた場合も総行数・スキップ数は保持される")
        void keepsCountsWhenAllSkipped() {
            LogParser.ParseResult allSkipped = new LogParser.ParseResult(List.of(), 3, 0, 3);

            AnalyzeResult result = aggregator.aggregate(allSkipped, request(null, null));

            assertEquals(3, result.totalLines());
            assertEquals(0, result.parsedCount());
            assertEquals(3, result.skippedCount());
            assertTrue(result.timeBuckets().isEmpty());
        }
    }

    @Nested
    @DisplayName("サンプルログ(sample-logs/access.log)の集計")
    class SampleLogTest {

        /**
         * サンプルログをパースして集計する。
         *
         * @return 集計結果
         * @throws IOException 読み込みに失敗した場合
         */
        private AnalyzeResult aggregateSampleLog() throws IOException {
            assertTrue(Files.exists(SAMPLE_LOG),
                    "テストデータが見つかりません: " + SAMPLE_LOG.toAbsolutePath());
            String rawLog = Files.readString(SAMPLE_LOG, StandardCharsets.UTF_8);
            return aggregator.aggregate(parser.parse(rawLog), request(null, "HOUR"));
        }

        @Test
        @DisplayName("サマリは 全26行・成功25件・スキップ1件")
        void summary() throws IOException {
            AnalyzeResult result = aggregateSampleLog();

            assertEquals(26, result.totalLines());
            assertEquals(25, result.parsedCount());
            assertEquals(1, result.skippedCount());
        }

        @Test
        @DisplayName("ステータス区分は 2xx=16 / 3xx=3 / 4xx=4 / 5xx=2")
        void statusClasses() throws IOException {
            Map<String, Long> classCounts = aggregateSampleLog().statusClassCounts();

            assertEquals(Map.of("2xx", 16L, "3xx", 3L, "4xx", 4L, "5xx", 2L), classCounts);
        }

        @Test
        @DisplayName("個別コードは9種類でコード昇順、200が14件")
        void statusCounts() throws IOException {
            List<CountItem> statusCounts = aggregateSampleLog().statusCounts();

            assertEquals(List.of("200", "201", "204", "302", "304", "403", "404", "500", "503"),
                    statusCounts.stream().map(CountItem::key).toList());
            assertEquals(14L, statusCounts.get(0).count());
        }

        @Test
        @DisplayName("上位パスは /api/status(5件)が首位で、同数は辞書順")
        void topPaths() throws IOException {
            List<CountItem> topPaths = aggregateSampleLog().topPaths();

            assertEquals(10, topPaths.size(), "topN 既定10で絞られる");
            assertEquals(new CountItem("/api/status", 5L), topPaths.get(0));
            // 2件タイの3つが辞書順で続く
            assertEquals(List.of("/api/orders/9001", "/index.html", "/login"),
                    topPaths.subList(1, 4).stream().map(CountItem::key).toList());
            assertTrue(topPaths.subList(1, 4).stream().allMatch(item -> item.count() == 2L));
        }

        @Test
        @DisplayName("上位IPは 203.0.113.5(4件)が首位")
        void topIps() throws IOException {
            List<CountItem> topIps = aggregateSampleLog().topIps();

            assertEquals(10, topIps.size());
            assertEquals(new CountItem("203.0.113.5", 4L), topIps.get(0));
            assertEquals(List.of("192.168.0.13", "198.51.100.22", "203.0.113.9"),
                    topIps.subList(1, 4).stream().map(CountItem::key).toList());
        }

        @Test
        @DisplayName("時間帯別(HOUR)は 10時=11件 / 11時=8件 / 12時=4件 / 13時=2件")
        void timeBuckets() throws IOException {
            List<TimeBucket> buckets = aggregateSampleLog().timeBuckets();

            assertEquals(List.of("2026-07-30 10:00", "2026-07-30 11:00",
                            "2026-07-30 12:00", "2026-07-30 13:00"),
                    buckets.stream().map(TimeBucket::bucketStart).toList());
            assertEquals(List.of(11L, 8L, 4L, 2L), buckets.stream().map(TimeBucket::count).toList());
            // 最大11件が100%、8件は round(8/11*100)=73%
            assertEquals(100, buckets.get(0).widthPercent());
            assertEquals(73, buckets.get(1).widthPercent());
        }

        @Test
        @DisplayName("エラー行は6件で時刻昇順")
        void errorEntries() throws IOException {
            List<LogEntry> errors = aggregateSampleLog().errorEntries();

            assertEquals(6, errors.size());
            assertEquals(List.of(403, 404, 404, 500, 404, 503),
                    errors.stream().map(LogEntry::status).toList());
            assertEquals("/admin", errors.get(0).path());
            assertEquals("/api/status", errors.get(5).path());
        }
    }
}
