package com.diversive.agent.preflight;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Body of a successful {@code POST /agent/preflight}: every name resolved, every precondition holding.
 *
 * @param requiresConfirmation true when any step writes; a plan of reads only needs no confirmation
 * @param confirmation         what the user is asked to approve, fully written by the backend (invariant 3):
 *                             one line per write, then the warnings. Absent for a plan of reads only
 * @param warnings             the warnings already in the confirmation, for a UI that shows them apart
 * @param token                opaque to the AI layer: keep it and send it back to execute with the same plan
 * @param expiresAt            when execute stops accepting the token; after that, run preflight again
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreflightResponse(
        @NotNull String planId,
        @NotNull Boolean requiresConfirmation,
        String confirmation,
        @NotNull List<String> warnings,
        @NotNull List<PreflightStepResult> steps,
        @NotNull String token,
        @NotNull Instant expiresAt) {

    public PreflightResponse {
        warnings = List.copyOf(warnings);
        steps = List.copyOf(steps);
    }
}
