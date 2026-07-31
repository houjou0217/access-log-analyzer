package com.example.loganalyzer.model;

/**
 * 名称と件数の組。上位パス・上位IP・ステータス別集計で共通利用する。
 * 03_detailed_design.md 2.3 に対応。
 *
 * @param key   名称(パス / IP / ステータスコード等)
 * @param count 件数
 */
public record CountItem(String key, long count) {
}
