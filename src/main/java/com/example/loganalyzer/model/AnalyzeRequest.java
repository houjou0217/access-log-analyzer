package com.example.loganalyzer.model;

/**
 * REST API(POST /api/analyze)のリクエストボディ。
 * 03_detailed_design.md 2.2 / 5.1 に対応。
 *
 * @param rawLog     貼り付けられたログ本文(複数行)
 * @param topN       上位何件を出すか(省略時は10、範囲外は境界に丸める)
 * @param bucketUnit 時間バケット単位(省略時は HOUR)
 */
public record AnalyzeRequest(String rawLog, Integer topN, String bucketUnit) {
}
