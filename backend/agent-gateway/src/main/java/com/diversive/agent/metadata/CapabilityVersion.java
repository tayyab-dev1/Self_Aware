package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/** A capability id and the version of its current metadata. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CapabilityVersion(@NotNull String id, @NotNull String version) {
}
