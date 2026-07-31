package com.example.loganalyzer.model;

import java.util.List;
import java.util.Map;

/**
 * 集計結果のまとまり。
 * 03_detailed_design.md 2.4 に対応。
 *
 * @param totalLines        総行数(空行を除く)
 * @param parsedCount       解析成功行数
 * @param skippedCount      解析不能行数
 * @param statusClassCounts 区分別件数(キー "2xx"/"3xx"/"4xx"/"5xx" 等)
 * @param statusCounts      個別コード別件数(コード昇順)
 * @param topPaths          上位パス(件数降順、Top N)
 * @param topIps            上位IP(件数降順、Top N)
 * @param timeBuckets       時間帯別件数(時刻昇順)
 * @param errorEntries      4xx/5xx の行(時刻昇順)
 */
public record AnalyzeResult(
        int totalLines,
        int parsedCount,
        int skippedCount,
        Map<String, Long> statusClassCounts,
        List<CountItem> statusCounts,
        List<CountItem> topPaths,
        List<CountItem> topIps,
        List<TimeBucket> timeBuckets,
        List<LogEntry> errorEntries
) {
}
