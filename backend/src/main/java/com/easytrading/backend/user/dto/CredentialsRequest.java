package com.easytrading.backend.user.dto;

/**
 * Request body of POST /api/signup and POST /api/login — see
 * backend/CONTRACTS.md.
 *
 * One record for both endpoints: they take the same two fields, and keeping
 * them identical means the frontend can use one function to send either.
 *
 * Deliberately not validated with annotations here — the rules (lengths,
 * trimming) live in AuthService, so they hold no matter which caller arrives,
 * and so the error message is written once.
 */
public record CredentialsRequest(String username, String password) {}
