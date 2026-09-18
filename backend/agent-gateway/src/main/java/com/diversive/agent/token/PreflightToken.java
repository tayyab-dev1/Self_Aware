package com.diversive.agent.token;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a preflight token vouches for: this plan, for this user, with these resolved ids and counts,
 * until this time. Signed, not stored, so preflight stays stateless. Readable by anyone holding the
 * token, so it carries ids and numbers, never names.
 *
 * @param format    payload format, so a later change can refuse older tokens
 * @param planHash  {@link com.diversive.agent.plan.PlanHasher#hash} of the confirmed plan
 * @param expiresAt seconds since the epoch
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreflightToken(
        int format,
        String planId,
        String planHash,
        String userId,
        long expiresAt,
        List<Step> steps) {

    public PreflightToken {
        steps = List.copyOf(steps);
    }

    /**
     * @param resolvedIds the id each name resolved to, by parameter name
     * @param count       what the confirmation said the step touches; absent for reads and pending steps
     * @param pending     true when the step takes a value from an earlier step, so its checks and count wait for execute
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(int step, Map<String, String> resolvedIds, Long count, boolean pending) {

        public Step {
            resolvedIds = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedIds));
        }
    }
}
