package com.diversive.agent.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/**
 * One record an ambiguous name matched, for the user to choose from. The choice goes back in the
 * plan as {@code chosen_id}, with the same words.
 *
 * @param context what tells it apart from the other candidates; absent when there is nothing to add
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntityCandidate(@NotNull String id, @NotNull String label, String context) {
}
