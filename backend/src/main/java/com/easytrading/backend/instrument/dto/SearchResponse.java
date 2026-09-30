package com.easytrading.backend.instrument.dto;

import java.util.List;

public record SearchResponse(List<InstrumentMatchResponse> results) {}
