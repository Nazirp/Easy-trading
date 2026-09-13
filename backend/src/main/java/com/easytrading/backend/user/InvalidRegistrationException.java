package com.easytrading.backend.user;

/** SCRUM-39: username or password fails the registration rules in AuthService. */
public class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException(String message) {
        super(message);
    }
}
