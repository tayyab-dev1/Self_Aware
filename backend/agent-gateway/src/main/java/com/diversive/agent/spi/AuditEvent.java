package com.diversive.agent.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One thing execute did with one plan step. Holds the user's original sentence, ids rather than names, and
 * the codes the AI layer received; never a token or a credential.
 *
 * @param idempotencyKey    {@code session_id:plan_id:step}
 * @param writes            whether the capability writes; only a write may succeed once per key
 * @param sentence          what the user typed, as the AI layer received it
 * @param params            the step's values by parameter name, as text: ids, not names
 * @param labels            what the resolved records were shown as, by label key
 * @param confirmedCount    what the confirmation said the step would touch; null for reads and pending steps
 * @param count             what the step touched; null until it has run
 * @param errorCode         the error code of a refused or failed step
 * @param result            the step result as JSON, for a succeeded write: what a replay answers with
 */
public record AuditEvent(
        String idempotencyKey,
        String sessionId,
        String planId,
        int step,
        String capabilityId,
        String capabilityVersion,
        boolean writes,
        String userId,
        String sentence,
        Kind kind,
        Map<String, String> params,
        Map<String, String> labels,
        Long confirmedCount,
        Long count,
        String errorCode,
        String result) {

    public AuditEvent {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(kind, "kind");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    public enum Kind {
        /** Every check passed and the handler is about to run. Commits only together with SUCCEEDED. */
        STARTED,
        /** The handler ran and its result was verified. */
        SUCCEEDED,
        /** A write already succeeded under this key; its recorded result was returned and nothing ran. */
        REPLAYED,
        /** A check refused the step: token, version, permission, scope, precondition, count, or conflict. */
        REFUSED,
        /** The handler broke or did not do what it declared; its writes were rolled back. */
        FAILED
    }
}
