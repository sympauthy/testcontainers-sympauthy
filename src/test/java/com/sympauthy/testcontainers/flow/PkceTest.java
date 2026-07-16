package com.sympauthy.testcontainers.flow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PkceTest {

    @Test
    void challengeIsUnpaddedBase64UrlSha256OfVerifier() throws Exception {
        Pkce pkce = Pkce.generate();

        assertEquals("S256", pkce.method);
        assertFalse(pkce.codeVerifier.isBlank());
        assertFalse(pkce.codeChallenge.contains("="), "challenge must be unpadded");
        assertFalse(
                pkce.codeChallenge.contains("+") || pkce.codeChallenge.contains("/"),
                "challenge must be URL-safe base64");

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(pkce.codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        assertEquals(expected, pkce.codeChallenge);
    }

    @Test
    void generatesDistinctVerifiers() {
        assertNotEquals(Pkce.generate().codeVerifier, Pkce.generate().codeVerifier);
    }
}
