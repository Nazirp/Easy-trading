package com.easytrading.backend.common;

/** Uniform error body for the REST API -- see backend/CONTRACTS.md. */
public record ApiError(String code, String message) {}
