package com.easytrading.backend.price;

import com.easytrading.backend.price.dto.InstrumentsResponse;
import com.easytrading.backend.price.dto.PriceResponse;
import com.easytrading.backend.price.dto.PricesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/getPrice?symbol=...&interval=... — see backend/CONTRACTS.md.
 *
 * Path and query params are the FINAL agreed shape: the frontend builds against
 * this now and nothing gets renamed later, only extended. The four chart ranges
 * are all served by varying `interval` -- each range owns exactly one interval,
 * so no range or candle-count parameter is needed; PriceService derives the
 * window from the interval (SCRUM-62). The signal field is already in the
 * response even though its value is a placeholder until SCRUM-46 lands. The
 * /api prefix separates JSON endpoints from the HTML pages this same
 * application serves (see WebConfig).
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
        var result = priceService.getPrices(symbol, interval);

        var prices = result.prices().stream()
                .map(p -> new PriceResponse(p.getDatetime(), p.getOpen(), p.getHigh(), p.getLow(),
                        p.getClose(), p.getVolume()))
                .toList();

        // Candles and signal come back from one service call and go out in one
        // response — the frontend structurally cannot render a chart without its
        // signal (UC02 BR1).
        return new PricesResponse(symbol, interval, prices, result.signal());
    }

    /**
     * GET /api/instruments — everything the user can pick, with its last cached
     * close. Backs the search dropdown's "here is what exists" list, so a
     * first-time visitor is not asked to guess a symbol into an empty box.
     *
     * No query parameters: the whole catalogue is six rows today, so paging or
     * filtering here would be machinery for a problem that does not exist. The
     * frontend filters the list it already has, and /api/search remains the
     * endpoint for matching text against the database.
     *
     * Served by PriceController rather than InstrumentController because the
     * rows carry prices -- see PriceService.browseInstruments() for why that
     * direction is the one that avoids a package cycle.
     */
    @GetMapping("/api/instruments")
    public InstrumentsResponse instruments() {
        return new InstrumentsResponse(priceService.browseInstruments());
    }
}
