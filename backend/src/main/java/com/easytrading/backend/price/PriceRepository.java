package com.easytrading.backend.price;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceRepository extends JpaRepository<Price, PriceId> {

    /** Cache lookup for one symbol at one interval, oldest first (chart order). */
    List<Price> findBySymbolAndIntervalOrderByDatetime(String symbol, String interval);
}
