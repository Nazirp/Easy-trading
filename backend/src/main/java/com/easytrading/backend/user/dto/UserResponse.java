package com.easytrading.backend.user.dto;

import com.easytrading.backend.user.User;

import java.math.BigDecimal;

/**
 * The current account as the frontend sees it — response body of
 * /api/signup, /api/login and /api/me.
 *
 * Carries no id and, above all, no password hash: the browser has no use for
 * either, and a hash that never leaves the server cannot be attacked offline.
 * The id lives in the session instead.
 */
public record UserResponse(String username, BigDecimal cashBalance) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getUsername(), user.getCashBalance());
    }
}
