package com.easytrading.backend.price;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The four candle intervals the app stores, matching both the CHECK
 * constraint on price_candle.interval in db/schema.sql AND Twelve Data's own
 * `interval` request parameter — so the same string travels from the frontend
 * query param, through here, into the DB, and out to Twelve Data untranslated.
 *
 * Chart range -> interval:
 *   1w -> 2h,  1m -> 4h,  6m -> 1day,  1yr -> 1week
 *
 * Each range owns exactly one interval, so the interval alone identifies the
 * range. That is why displayCandles() can live here at all: the frontend sends
 * only symbol + interval and never a candle count (see backend/CONTRACTS.md).
 */
public enum Interval {

    TWO_HOUR("2h", 84),
    FOUR_HOUR("4h", 180),
    ONE_DAY("1day", 180),
    ONE_WEEK("1week", 52);

    private final String code;
    private final int displayCandles;

    Interval(String code, int displayCandles) {
        this.code = code;
        this.displayCandles = displayCandles;
    }

    public String code() {
        return code;
    }

    /**
     * How many candles the chart shows for the range this interval serves:
     * 1w@2h ~= 84, 1m@4h ~= 180, 6m@1day ~= 180, 1yr@1week = 52.
     *
     * This is a CAP, not a target. The numbers assume a market that never
     * closes; forex shuts at weekends and stocks trade ~6.5h a day, so a 1w
     * chart is ~84 points for BTC/USD but ~18 for AAPL. Callers ask for this
     * many and render whatever comes back -- deliberately no market-calendar
     * logic.
     */
    public int displayCandles() {
        return displayCandles;
    }

    /** The length of one candle at this interval. */
    public Duration candleDuration() {
        return switch (this) {
            case TWO_HOUR -> Duration.ofHours(2);
            case FOUR_HOUR -> Duration.ofHours(4);
            case ONE_DAY -> Duration.ofDays(1);
            case ONE_WEEK -> Duration.ofDays(7);
        };
    }

    /**
     * Cached data whose newest candle is older than this counts as stale and
     * triggers re-ingestion (see PriceService.needsIngestion).
     *
     * 1.5x the candle length, NOT exactly one candle. At exactly one candle any
     * closed market reads as stale: on a Sunday the newest 2h candle for a
     * forex pair is legitimately hours old. 1.5x absorbs the normal gap between
     * candles without needing a market calendar.
     */
    public Duration stalenessThreshold() {
        return candleDuration().multipliedBy(3).dividedBy(2);
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
