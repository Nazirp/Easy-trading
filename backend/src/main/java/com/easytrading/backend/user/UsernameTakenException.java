package com.easytrading.backend.user;

/** The requested username already belongs to someone. */
public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
