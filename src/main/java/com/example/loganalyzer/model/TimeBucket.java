package com.example.loganalyzer.model;

/**
 * 時間帯別の集計単位。
 * 03_detailed_design.md 2.3 に対応。widthPercent は棒グラフ表示用。
 *
 * @param bucketStart  バケットの開始時刻(表示用文字列。例 2026-07-30 10:00)
 * @param count        件数
 * @param widthPercent 棒グラフの幅(0〜100。集計内の最大件数を100%とした割合)
 */
public record TimeBucket(String bucketStart, long count, int widthPercent) {
}
