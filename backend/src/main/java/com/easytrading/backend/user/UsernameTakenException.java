package com.easytrading.backend.user;

/** SCRUM-39: the requested username already belongs to someone. */
public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
