package com.easytrading.backend.price;

import com.easytrading.backend.price.dto.PriceResponse;
import com.easytrading.backend.price.dto.PricesResponse;
import com.easytrading.backend.price.dto.SignalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/getPrice?symbol=...&interval=... — see backend/CONTRACTS.md.
 *
 * Path and query params are the FINAL agreed shape: the frontend builds against
 * this now and nothing gets renamed later, only extended (SCRUM-20's four chart
 * ranges are all served by varying `interval`, and the signal field is already
 * in the response even though its value is a placeholder until MS4). The /api
 * prefix separates JSON endpoints from the HTML pages this same application
 * serves (see WebConfig).
 *
 * Unknown symbol -> InstrumentNotFoundException -> 404 NOT_FOUND.
 * Unknown interval -> InvalidIntervalException -> 400 INVALID_INTERVAL.
 * Both handled centrally in com.easytrading.backend.common.ApiExceptionHandler.
 */
@RestController
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/api/getPrice")
    public PricesResponse getPrice(@RequestParam("symbol") String symbol,
                                   @RequestParam(value = "interval", defaultValue = "1day") String interval) {
        var prices = priceService.getPrices(symbol, interval).stream()
                .map(p -> new PriceResponse(p.getDatetime(), p.getOpen(), p.getHigh(), p.getLow(),
                        p.getClose(), p.getVolume()))
                .toList();

        // Signal computation is SCRUM-20 / MS4. The field is here now so the
        // response shape is final — only the values change later.
        return new PricesResponse(symbol, interval, prices, SignalResponse.notEnoughData());
    }
}
