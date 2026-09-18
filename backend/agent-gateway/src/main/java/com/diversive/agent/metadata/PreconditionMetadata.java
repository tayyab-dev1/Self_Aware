package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/** Something that must hold before the capability runs, and what the user is told when it does not. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PreconditionMetadata(@NotNull String id, @NotNull String text, @NotNull String hint) {
}
