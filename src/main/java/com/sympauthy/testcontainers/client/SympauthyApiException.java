package com.sympauthy.testcontainers.client;

/** Thrown when a call to a SympAuthy server API (the token endpoint or the Flow API) fails. */
public class SympauthyApiException extends RuntimeException {

    public SympauthyApiException(String message) {
        super(message);
    }

    public SympauthyApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
