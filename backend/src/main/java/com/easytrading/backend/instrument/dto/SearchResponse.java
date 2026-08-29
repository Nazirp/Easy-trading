package com.easytrading.backend.instrument.dto;

import java.util.List;

/** Empty `results` is a valid 200 response (UC01 extension 7a: no results is not an error). */
public record SearchResponse(List<InstrumentMatchResponse> results) {}
