package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/confirm} response: the action a client or an administrator initiated
 * on the signed-in user's behalf and asked them to approve. Passed to a {@link ConfirmHandler} so a
 * test can decide whether to {@link ConfirmDecision#CONFIRM approve} or {@link ConfirmDecision#CANCEL
 * cancel} it, and assert on who initiated it.
 *
 * <p>While the action is pending, {@link #action()} (e.g. {@code "ENROLL_MFA"}) and
 * {@link #initiatingClientId()} are set and {@link #redirectUrl()} is {@code null}; once already
 * confirmed (or when there is no confirm step), only {@link #redirectUrl()} is set. A {@code null}
 * {@link #initiatingClientId()} means an administrator, rather than a client, initiated the action.
 */
public final class ConfirmFlowResource {

    private final String action;
    private final String initiatingClientId;
    private final String redirectUrl;
    private final Map<String, Object> raw;

    private ConfirmFlowResource(String action, String initiatingClientId, String redirectUrl,
            Map<String, Object> raw) {
        this.action = action;
        this.initiatingClientId = initiatingClientId;
        this.redirectUrl = redirectUrl;
        this.raw = raw;
    }

    static ConfirmFlowResource fromMap(Map<String, Object> map) {
        return new ConfirmFlowResource(
                str(map.get("action")),
                str(map.get("initiating_client_id")),
                str(map.get("redirect_url")),
                map);
    }

    /** The action to confirm (e.g. {@code "ENROLL_MFA"}), or {@code null} once already confirmed. */
    public String action() {
        return action;
    }

    /** The id of the client that initiated the action, or {@code null} when an administrator did. */
    public String initiatingClientId() {
        return initiatingClientId;
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
