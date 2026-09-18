package com.diversive.agent.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Every gateway failure has this body. The AI layer branches on {@code code}, so codes are part of
 * the contract ({@link AgentErrorCodes}); {@code message} is for people. The other fields appear
 * only where they apply.
 *
 * @param step           the plan step the problem is in
 * @param param          the parameter the problem is in, e.g. for {@code AMBIGUOUS_ENTITY}
 * @param precondition   the failed precondition's id, for {@code PRECONDITION_FAILED}
 * @param hint           what the user is told, for {@code PRECONDITION_FAILED}
 * @param candidates     the records a name matched, for {@code AMBIGUOUS_ENTITY}
 * @param confirmedCount what the user confirmed the step would touch, for {@code COUNT_CHANGED}
 * @param currentCount   what it would touch now, for {@code COUNT_CHANGED}
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentErrorResponse(
        @NotNull String code,
        @NotNull String message,
        Integer step,
        String param,
        String precondition,
        String hint,
        List<EntityCandidate> candidates,
        Long confirmedCount,
        Long currentCount) {

    public AgentErrorResponse {
        candidates = candidates == null ? null : List.copyOf(candidates);
    }

    public AgentErrorResponse(String code, String message) {
        this(code, message, null, null, null, null, null, null, null);
    }

    public static AgentErrorResponse forStep(String code, int step, String message) {
        return new AgentErrorResponse(code, message, step, null, null, null, null, null, null);
    }

    public static AgentErrorResponse forParam(String code, int step, String param, String message) {
        return new AgentErrorResponse(code, message, step, param, null, null, null, null, null);
    }

    public AgentErrorResponse withCandidates(List<EntityCandidate> matches) {
        return new AgentErrorResponse(code, message, step, param, precondition, hint, matches, confirmedCount, currentCount);
    }

    public AgentErrorResponse withPrecondition(String id, String userHint) {
        return new AgentErrorResponse(code, message, step, param, id, userHint, candidates, confirmedCount, currentCount);
    }

    public AgentErrorResponse withCounts(long confirmed, long current) {
        return new AgentErrorResponse(code, message, step, param, precondition, hint, candidates, confirmed, current);
    }
}
