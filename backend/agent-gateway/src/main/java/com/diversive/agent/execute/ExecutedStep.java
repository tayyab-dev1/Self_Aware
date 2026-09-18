package com.diversive.agent.execute;

import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.step.PreparedStep;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/**
 * What happened to one step.
 *
 * @param reply the capability's reply template filled with real values, for a step that succeeded or was replayed
 * @param count how many records the step touched, as verified; for a read, how many it found when it says so
 * @param data  what the handler returned, as the endpoint would answer; for a replay, as it was recorded
 * @param error why the step failed; the same body and codes as any gateway refusal
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutedStep(
        @NotNull Integer step,
        @NotNull String capabilityId,
        @NotNull StepStatus status,
        String reply,
        Long count,
        Object data,
        AgentErrorResponse error) {

    static ExecutedStep succeeded(PreparedStep step, String reply, Long count, Object data) {
        return new ExecutedStep(step.number(), step.metadata().id(), StepStatus.SUCCEEDED, reply, count, data, null);
    }

    static ExecutedStep failed(PreparedStep step, AgentErrorResponse error) {
        return new ExecutedStep(step.number(), step.metadata().id(), StepStatus.FAILED, null, null, null, error);
    }

    static ExecutedStep notRun(PreparedStep step) {
        return new ExecutedStep(step.number(), step.metadata().id(), StepStatus.NOT_RUN, null, null, null, null);
    }

    ExecutedStep replayed() {
        return new ExecutedStep(step, capabilityId, StepStatus.REPLAYED, reply, count, data, null);
    }

    boolean done() {
        return status == StepStatus.SUCCEEDED || status == StepStatus.REPLAYED;
    }
}
