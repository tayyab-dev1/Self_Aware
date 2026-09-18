package com.diversive.agent.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What the AI layer wants done: up to a few steps, each naming a capability by id, never a URL
 * (invariant 2). The same plan goes to preflight and then, unchanged, to execute; the preflight
 * token carries its hash, so a plan edited after confirmation is refused.
 *
 * @param planId    the AI layer's id for this plan; with {@code sessionId} it keys idempotency at execute
 * @param sessionId the conversation the plan belongs to
 * @param steps     in the order they run, numbered from 1
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Plan(@NotNull String planId, @NotNull String sessionId, @NotNull List<PlanStep> steps) {

    public Plan {
        steps = steps == null ? null : List.copyOf(steps);
    }
}
