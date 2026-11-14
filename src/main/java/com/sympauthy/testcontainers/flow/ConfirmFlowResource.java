package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/confirm} response: the action a client or an administrator initiated
 * on the signed-in user's behalf and asked them to approve. Passed to a {@link ConfirmHandler} so a
 * test can decide whether to {@link ConfirmDecision#CONFIRM approve} or {@link ConfirmDecision#CANCEL
 * cancel} it, and assert on who initiated it.
 *
 * <p>While the action is pending, {@link #action()} (e.g. {@code "enroll_mfa"}) and {@link #client()}
 * are set and {@link #redirectUrl()} is {@code null}; once already confirmed (or when there is no
 * confirm step), only {@link #redirectUrl()} is set. A {@code null} {@link #client()} on a pending action
 * means an administrator, rather than a client, initiated it.
 */
public final class ConfirmFlowResource {

    private final String action;
    private final ClientReference client;
    private final String redirectUrl;
    private final Map<String, Object> raw;

    private ConfirmFlowResource(String action, ClientReference client, String redirectUrl,
            Map<String, Object> raw) {
        this.action = action;
        this.client = client;
        this.redirectUrl = redirectUrl;
        this.raw = raw;
    }

    static ConfirmFlowResource fromMap(Map<String, Object> map) {
        return new ConfirmFlowResource(
                str(map.get("action")),
                ClientReference.fromBody(map),
                str(map.get("redirect_url")),
                map);
    }

    /** The action to confirm (e.g. {@code "enroll_mfa"}), or {@code null} once already confirmed. */
    public String action() {
        return action;
    }

    /** The client that initiated the action, or {@code null} when an administrator did. */
    public ClientReference client() {
        return client;
    }

    /** The URL the server points to when the action is already confirmed (or there is no confirm step). */
    public String redirectUrl() {
        return redirectUrl;
    }

    /** The full parsed response body. */
    public Map<String, Object> raw() {
        return raw;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
