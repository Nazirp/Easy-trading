package com.easytrading.backend.price;

import com.easytrading.backend.price.dto.SignalResponse;

import java.util.List;

/**
 * What PriceService hands back: the candles the chart should draw, and the
 * signal computed alongside them.
 *
 * The two travel together on purpose. UC02 BR1 says chart and signal are one
 * view, never two steps — returning them as one value means a caller cannot
 * fetch the prices and forget the signal, which is the same guarantee the REST
 * response shape makes to the frontend, held one layer further in.
 *
 * Note `prices` is the DISPLAY window only, while the signal was computed over
 * the display window plus its warm-up candles. That asymmetry is the point: the
 * extra history shapes the verdict without being drawn.
 */
public record PriceResult(List<Price> prices, SignalResponse signal) {}
