package com.sympauthy.testcontainers.flow;

/**
 * Supplies the one-time code delivered to a media channel (e.g. {@code EMAIL}) during the claim
 * validation step. Not exercised by the v1 password happy path — provided as the seam for a future
 * email/SMS-validation tier. Register with {@code InteractiveFlow.withValidationCodeHandler}.
 */
@FunctionalInterface
public interface ValidationCodeHandler {

    String provide(String media);
}
