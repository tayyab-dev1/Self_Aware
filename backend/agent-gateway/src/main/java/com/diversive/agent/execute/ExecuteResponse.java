package com.diversive.agent.execute;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code POST /agent/execute} once the plan was accepted: what happened to each step, in order.
 * Steps are never rolled back automatically, so a failure after a success is reported as {@code partial}.
 *
 * @param outcome {@code completed} when every step succeeded or was replayed, {@code failed} when the first
 *                step failed, {@code partial} when a later one did
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExecuteResponse(@NotNull String planId, @NotNull ExecuteOutcome outcome, @NotNull List<ExecutedStep> steps) {

    public ExecuteResponse {
        steps = List.copyOf(steps);
    }
}
