package com.diversive.agent.execute;

import com.diversive.agent.plan.Plan;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /agent/execute}: the plan the user confirmed, exactly as it went to preflight, with
 * the token preflight returned.
 *
 * @param token    from the preflight response, sent back unopened
 * @param sentence what the user typed, as the AI layer received it; kept in the audit trail
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExecuteRequest(@NotNull Plan plan, @NotNull String token, @NotNull String sentence) {
}
