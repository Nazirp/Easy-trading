package com.easytrading.backend.price;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three candle intervals the app stores, matching both the CHECK
 * constraint on price_candle.interval in db/schema.sql AND Twelve Data's own
 * `interval` request parameter — so the same string travels from the frontend
 * query param, through here, into the DB, and out to Twelve Data untranslated.
 *
 * Chart range -> interval (SCRUM-20):
 *   1w -> 4h,  1m -> 1day,  3m -> 1day,  6m -> 1week
 */
public enum Interval {

    FOUR_HOUR("4h"),
    ONE_DAY("1day"),
    ONE_WEEK("1week");

    private final String code;

    Interval(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Interval> fromCode(String code) {
        return Arrays.stream(values())
                .filter(i -> i.code.equalsIgnoreCase(code))
                .findFirst();
    }

    public static String supportedCodes() {
        return String.join(", ", Arrays.stream(values()).map(Interval::code).toList());
    }
}
