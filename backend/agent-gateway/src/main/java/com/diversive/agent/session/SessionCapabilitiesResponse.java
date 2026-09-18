package com.diversive.agent.session;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code GET /agent/session/capabilities}: the capability ids this user may use, sorted.
 * The AI layer's allow-list. Retrieval never offers the planner anything outside it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SessionCapabilitiesResponse(@NotNull String userId, @NotNull List<String> capabilityIds) {

    public SessionCapabilitiesResponse {
        capabilityIds = List.copyOf(capabilityIds);
    }
}
