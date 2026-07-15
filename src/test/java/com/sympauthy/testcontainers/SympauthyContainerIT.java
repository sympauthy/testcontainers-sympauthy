package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that starts a real SympAuthy container (default: in-memory H2) and verifies it
 * serves a valid OpenID Connect discovery document. Requires Docker; tagged {@code integration} so
 * it is excluded from the default {@code test} task and run via {@code ./gradlew integrationTest}.
 */
@Tag("integration")
@Testcontainers
class SympauthyContainerIT {

    @Container
    private final SympauthyContainer sympauthy = new SympauthyContainer();

    @Test
    void servesOpenIdConfiguration() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(sympauthy.getOpenIdConfigurationUrl()))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(
                response.body().contains("\"issuer\""),
                "discovery document should advertise an issuer");
        assertTrue(
                response.body().contains(sympauthy.getIssuerUrl()),
                "issuer should match the container's URL");
    }
}
