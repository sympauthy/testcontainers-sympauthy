package com.sympauthy.testcontainers.flow;

/** Login/password credentials supplied to the password sign-in step. */
public record Credentials(String login, String password) {

    public static Credentials of(String login, String password) {
        return new Credentials(login, password);
    }
}
