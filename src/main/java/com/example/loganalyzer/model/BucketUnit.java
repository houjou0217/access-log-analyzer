package com.example.loganalyzer.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 時間バケットの単位。
 * 03_detailed_design.md 2.2 / 3.5 に対応。
 */
public enum BucketUnit {
    /** 1時間ごと */
    HOUR,
    /** 10分ごと */
    TEN_MIN,
    /** 1分ごと */
    MINUTE;

    /** バケット表示用のフォーマット(例: 2026-07-30 10:00) */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 指定された日時を、この単位に合わせて切り捨てる。
     *
     * @param dateTime 対象日時
     * @return 切り捨て後の日時
     */
    public OffsetDateTime truncate(OffsetDateTime dateTime) {
        switch (this) {
            case HOUR:
                return dateTime.truncatedTo(ChronoUnit.HOURS);
            case MINUTE:
                return dateTime.truncatedTo(ChronoUnit.MINUTES);
            case TEN_MIN:
                OffsetDateTime byMinute = dateTime.truncatedTo(ChronoUnit.MINUTES);
                int flooredMinute = (byMinute.getMinute() / 10) * 10;
                return byMinute.withMinute(flooredMinute);
            default:
                return dateTime.truncatedTo(ChronoUnit.HOURS);
        }
    }

    /**
     * 切り捨て後の日時を表示用文字列に整形する。
     *
     * @param dateTime 対象日時
     * @return 表示用文字列(例: 2026-07-30 10:00)
     */
    public String formatBucket(OffsetDateTime dateTime) {
        return truncate(dateTime).format(FORMAT);
    }

    /**
     * 文字列から BucketUnit を得る。不正値・null の場合は HOUR にフォールバックする。
     *
     * @param value 文字列(HOUR / TEN_MIN / MINUTE)
     * @return 対応する BucketUnit(不正なら HOUR)
     */
    public static BucketUnit fromString(String value) {
        if (value == null) {
            return HOUR;
        }
        try {
            return BucketUnit.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return HOUR;
        }
    }
}
