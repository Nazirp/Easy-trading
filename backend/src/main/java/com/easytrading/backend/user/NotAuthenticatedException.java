package com.easytrading.backend.user;

/**
 * A user-scoped endpoint was called without a logged-in session.
 *
 * The frontend treats this as "show the login prompt", not as an error — which
 * is why it is its own exception rather than a plain 401 from
 * InvalidCredentialsException: "your credentials were wrong" and "you have not
 * logged in yet" need different words on screen.
 */
public class NotAuthenticatedException extends RuntimeException {
    public NotAuthenticatedException(String message) {
        super(message);
    }
}
