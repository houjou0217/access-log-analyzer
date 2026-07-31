package com.example.loganalyzer.model;

import java.time.OffsetDateTime;

/**
 * パース済みの1リクエスト(ログ1行)。
 * 03_detailed_design.md 2.1 に対応。
 *
 * @param ip        クライアントIP
 * @param dateTime  日時(タイムゾーン付き)
 * @param method    HTTPメソッド
 * @param path      リクエストパス(クエリ含む)
 * @param protocol  プロトコル(HTTP/1.1等)
 * @param status    ステータスコード
 * @param size      レスポンスサイズ(バイト。'-' の場合は0)
 * @param referer   リファラ('-' は空文字)
 * @param userAgent ユーザーエージェント
 */
public record LogEntry(
        String ip,
        OffsetDateTime dateTime,
        String method,
        String path,
        String protocol,
        int status,
        long size,
        String referer,
        String userAgent
) {
}
