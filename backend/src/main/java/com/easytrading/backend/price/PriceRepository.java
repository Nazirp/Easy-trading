package com.easytrading.backend.price;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceRepository extends JpaRepository<Price, PriceId> {

    /** Cache lookup for one symbol at one interval, oldest first (chart order). */
    List<Price> findBySymbolAndIntervalOrderByDatetime(String symbol, String interval);

    /**
     * The most recent N candles for one symbol at one interval, NEWEST FIRST.
     *
     * Newest-first is what makes the limit mean "the last N candles" rather than
     * "the N oldest rows we happen to have". PriceService reverses the
     * result into chronological order before returning it, since a chart plots
     * left-to-right. Pass PageRequest.of(0, n) for the limit.
     */
    List<Price> findBySymbolAndIntervalOrderByDatetimeDesc(String symbol, String interval, Pageable pageable);
}
