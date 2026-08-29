package com.easytrading.backend.instrument.dto;

/** `type` is serialized lowercase ("forex"/"crypto"/"stock") to match BR1 wording and the DB's own convention. */
public record InstrumentMatchResponse(String symbol, String name, String type) {}
