package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code GET /agent/metadata/versions}: ids and versions only, sorted by id. Cheap enough to
 * poll every 30 seconds; the AI layer pulls full entries only for what changed.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CapabilityVersionsResponse(@NotNull List<CapabilityVersion> versions) {

    public CapabilityVersionsResponse {
        versions = List.copyOf(versions);
    }
}
