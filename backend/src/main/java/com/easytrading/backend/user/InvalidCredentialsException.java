package com.easytrading.backend.user;

/**
 * Login failed.
 *
 * Thrown for an unknown username and for a wrong password alike, with the same
 * message — distinguishing them would turn the login form into a way of
 * checking which accounts exist.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
