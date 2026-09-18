package com.diversive.agent.preflight;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What preflight found for one step.
 *
 * @param pending  true when the step takes a value from an earlier step, so it is confirmed with its
 *                 pending template and its checks and count wait for execute
 * @param resolved every name turned into a record, with the label the confirmation uses
 * @param count    how many records the step touches; absent for reads and pending steps
 * @param unit     what is counted, e.g. "guardians"; absent when the capability has no count of its own
 * @param line     the step's line in the confirmation; absent for reads
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreflightStepResult(
        @NotNull Integer step,
        @NotNull String capabilityId,
        @NotNull Boolean pending,
        @NotNull List<ResolvedEntity> resolved,
        Long count,
        String unit,
        String line) {

    public PreflightStepResult {
        resolved = List.copyOf(resolved);
    }
}
