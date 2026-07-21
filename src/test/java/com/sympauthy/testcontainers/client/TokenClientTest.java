package com.sympauthy.testcontainers.client;

import com.sun.net.httpserver.HttpServer;
import com.sympauthy.testcontainers.Client;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-free unit tests for {@link TokenClient}: each drives a grant against a tiny recording HTTP
 * server and asserts on the request the token endpoint received.
 */
class TokenClientTest {

    private static final String TOKEN_JSON =
            "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"rt\"}";

    @Test
    void publicClientExchangeSendsPkceButNoSecret() {
        try (RecordingServer server = new RecordingServer(TOKEN_JSON)) {
            TokenClient tokens = new TokenClient(server.url, HttpClient.newHttpClient(), Client.publicClient("app"));

            TokenResponse response = tokens.exchangeAuthorizationCode("code-1", "http://cb", "verifier-1");

            assertEquals("at", response.accessToken());
            assertEquals("rt", response.refreshToken());
            assertTrue(server.body.contains("grant_type=authorization_code"));
            assertTrue(server.body.contains("code=code-1"));
            assertTrue(server.body.contains("code_verifier=verifier-1"));
            assertTrue(server.body.contains("client_id=app"));
            assertFalse(server.body.contains("client_secret="), server.body);
            assertNull(server.headers.get("Authorization"));
        }
    }

    @Test
    void confidentialClientExchangePostsTheSecret() {
        try (RecordingServer server = new RecordingServer(TOKEN_JSON)) {
            TokenClient tokens = new TokenClient(server.url, HttpClient.newHttpClient(),
                    Client.confidentialClient("app", "s3cr3t"));

            tokens.exchangeAuthorizationCode("code-1", "http://cb", "verifier-1");

            assertTrue(server.body.contains("client_secret=s3cr3t"), server.body);
            assertNull(server.headers.get("Authorization"));
        }
    }

    @Test
    void confidentialClientExchangeCanUseBasicAuth() {
        try (RecordingServer server = new RecordingServer(TOKEN_JSON)) {
            TokenClient tokens = new TokenClient(server.url, HttpClient.newHttpClient(),
                    Client.confidentialClient("app", "s3cr3t", Client.ClientAuthMethod.BASIC));

            tokens.exchangeAuthorizationCode("code-1", "http://cb", "verifier-1");

            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("app:s3cr3t".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, server.headers.get("Authorization"));
            assertFalse(server.body.contains("client_secret="), server.body);
        }
    }

    @Test
    void clientCredentialsGrantAuthenticatesAndSendsScopes() {
        try (RecordingServer server = new RecordingServer(TOKEN_JSON)) {
            TokenClient tokens = new TokenClient(server.url, HttpClient.newHttpClient(),
                    Client.confidentialClient("svc", "s3cr3t"));

            TokenResponse response = tokens.clientCredentials("client.read", "client.write");

            assertEquals("at", response.accessToken());
            assertTrue(server.body.contains("grant_type=client_credentials"));
            assertTrue(server.body.contains("client_secret=s3cr3t"));
            // scopes are space-joined then form-encoded (space -> '+').
            assertTrue(server.body.contains("scope=client.read+client.write"), server.body);
        }
    }

    @Test
    void nonSuccessResponseThrows() {
        try (RecordingServer server = new RecordingServer(400, "{\"error\":\"invalid_client\"}")) {
            TokenClient tokens = new TokenClient(server.url, HttpClient.newHttpClient(),
                    Client.confidentialClient("app", "wrong"));

            SympauthyApiException failure =
                    assertThrows(SympauthyApiException.class, tokens::clientCredentials);
            assertTrue(failure.getMessage().contains("400"), failure.getMessage());
        }
    }

    /** A tiny HTTP server that records the single request it receives and returns a canned response. */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        final String url;
        volatile String body;
        volatile Map<String, String> headers;

        RecordingServer(String responseJson) {
            this(200, responseJson);
        }

        RecordingServer(int status, String responseJson) {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/token", exchange -> {
                Map<String, String> recorded = new HashMap<>();
                exchange.getRequestHeaders().forEach((key, values) -> {
                    if (!values.isEmpty()) {
                        recorded.put(key, values.get(0));
                    }
                });
                headers = recorded;
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            url = "http://localhost:" + server.getAddress().getPort() + "/token";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
