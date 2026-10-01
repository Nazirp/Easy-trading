package com.easytrading.backend.user;

/** Username or password fails the registration rules in AuthService. */
public class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException(String message) {
        super(message);
    }
}
