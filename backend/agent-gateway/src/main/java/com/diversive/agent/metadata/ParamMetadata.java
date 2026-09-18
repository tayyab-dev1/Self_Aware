package com.diversive.agent.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One input a plan may supply.
 *
 * @param multiple     true when the value is a list of {@code type}
 * @param required     read from the record field ({@code @NotNull}, {@code @NotBlank}, primitive)
 * @param resolver     entity type preflight resolves a name into an id for; absent for literals
 * @param label        template key the resolved record's label is published under
 * @param allowed      the only accepted values; empty when any value of the type is accepted
 * @param defaultValue what the planner uses when the user does not say; absent when none
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamMetadata(
        @NotNull String name,
        @NotNull ParamType type,
        @NotNull Boolean multiple,
        @NotNull Boolean required,
        @NotNull String meaning,
        String resolver,
        String label,
        @NotNull List<String> allowed,
        String defaultValue) {

    public ParamMetadata {
        allowed = List.copyOf(allowed);
    }
}
