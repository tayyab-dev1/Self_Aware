package com.diversive.agent.preflight;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/**
 * A name preflight turned into one record.
 *
 * @param param the parameter, e.g. {@code section_id}
 * @param id    the record's id
 * @param label how it reads, e.g. "Class 5 Blue"; published to templates under the parameter's label key
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResolvedEntity(@NotNull String param, @NotNull String id, @NotNull String label) {
}
