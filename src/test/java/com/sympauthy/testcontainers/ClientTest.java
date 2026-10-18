package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTest {

    @Test
    void publicClientSendsOnlyTheClientId() {
        Map<String, String> form = new LinkedHashMap<>();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost/token"));

        Client client = Client.publicClient("app");
        client.authenticate(form, request);

        assertTrue(client.isPublic());
        assertEquals("app", form.get("client_id"));
        assertFalse(form.containsKey("client_secret"));
        assertNull(request.build().headers().firstValue("Authorization").orElse(null));
    }

    @Test
    void confidentialClientPostsTheSecretInTheForm() {
        Map<String, String> form = new LinkedHashMap<>();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost/token"));

        Client client = Client.confidentialClient("app", "s3cr3t");
        client.authenticate(form, request);

        assertFalse(client.isPublic());
        assertEquals("app", form.get("client_id"));
        assertEquals("s3cr3t", form.get("client_secret"));
        assertNull(request.build().headers().firstValue("Authorization").orElse(null));
    }

    @Test
    void confidentialClientCanUseBasicAuth() {
        Map<String, String> form = new LinkedHashMap<>();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost/token"));

        Client.confidentialClient("app", "s3cr3t", Client.ClientAuthMethod.BASIC).authenticate(form, request);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("app:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, request.build().headers().firstValue("Authorization").orElse(null));
        assertEquals("app", form.get("client_id"));
        assertFalse(form.containsKey("client_secret"));
    }

    @Test
    void basicAuthFormEncodesBothCredentials() {
        Map<String, String> form = new LinkedHashMap<>();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost/token"));

        // A secret holding the characters form encoding escapes, and an id holding the separator.
        Client.confidentialClient("client:1", "a+b/c= \u00e9", Client.ClientAuthMethod.BASIC)
                .authenticate(form, request);

        String header = request.build().headers().firstValue("Authorization").orElseThrow();
        String credentials = new String(
                Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        // RFC 6749 2.3.1: each half form-encoded, so the first ':' left is the separator SympAuthy splits on.
        assertEquals("client%3A1:a%2Bb%2Fc%3D+%C3%A9", credentials);
    }
}
