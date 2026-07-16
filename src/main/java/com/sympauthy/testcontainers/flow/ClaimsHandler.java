package com.sympauthy.testcontainers.flow;

import java.util.List;
import java.util.Map;

/**
 * Supplies values for the collect-claims step, given the claims the flow requested. Return a map of
 * claim id to value; omit or map to {@code null} to decline an optional claim. Register with
 * {@code InteractiveFlow.withClaimsHandler}.
 */
@FunctionalInterface
public interface ClaimsHandler {

    Map<String, Object> provide(List<Claim> requested);
}
