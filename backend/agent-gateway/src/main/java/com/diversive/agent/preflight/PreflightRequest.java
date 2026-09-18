package com.diversive.agent.preflight;

import com.diversive.agent.plan.Plan;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /agent/preflight}. Execute (Phase 3) takes the same plan back, with the token. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PreflightRequest(@NotNull Plan plan) {
}
