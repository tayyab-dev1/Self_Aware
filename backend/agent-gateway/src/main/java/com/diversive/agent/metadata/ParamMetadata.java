package com.diversive.agent.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.diversive.agent.spi.LookupField;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One input a plan may supply.
 *
 * @param multiple     true when the value is a list of {@code type}
 * @param required     read from the record field ({@code @NotNull}, {@code @NotBlank}, primitive)
 * @param resolver     entity type preflight resolves a name into an id for; absent for literals
 * @param label        template key the resolved record's label is published under
 * @param lookup       what the resolver searches by, in plain words (its {@code EntityResolver#lookup()});
 *                     absent for literals, and for a resolver that says nothing
 * @param lookupFields the labelled parts the resolver searches by (its {@code EntityResolver#fields()}),
 *                     which the plan fills one by one; empty for literals and for a resolver with none
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
        String lookup,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LookupField> lookupFields,
        @NotNull List<String> allowed,
        String defaultValue) {

    public ParamMetadata {
        allowed = List.copyOf(allowed);
        lookupFields = lookupFields == null ? List.of() : List.copyOf(lookupFields);
    }

    public ParamMetadata withLookup(String newLookup, List<LookupField> newFields) {
        return new ParamMetadata(name, type, multiple, required, meaning, resolver, label, newLookup, newFields,
                allowed, defaultValue);
    }

    /** The part by that name, or null when this parameter's resolver does not declare it. */
    public LookupField lookupField(String field) {
        return lookupFields.stream().filter(declared -> declared.name().equals(field)).findFirst().orElse(null);
    }
}
