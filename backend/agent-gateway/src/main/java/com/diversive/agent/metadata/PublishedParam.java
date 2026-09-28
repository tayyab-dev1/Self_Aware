package com.diversive.agent.metadata;

import com.diversive.agent.spi.LookupField;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One input a plan may supply, as {@code GET /agent/metadata} publishes it.
 *
 * <p>Leaves out two fields of {@link ParamMetadata} that only this side uses: {@code multiple},
 * which the plan reader checks when it reads a value, and {@code label}, the template key the
 * resolved record's label is published under, which the confirmation composer fills in.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishedParam(
        @NotNull String name,
        @NotNull ParamType type,
        @NotNull Boolean required,
        @NotNull String meaning,
        String resolver,
        String lookup,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LookupField> lookupFields,
        String filledBy,
        @NotNull List<String> allowed,
        String defaultValue) {

    public PublishedParam {
        allowed = List.copyOf(allowed);
        lookupFields = lookupFields == null ? List.of() : List.copyOf(lookupFields);
    }

    public static PublishedParam of(ParamMetadata param) {
        return new PublishedParam(
                param.name(),
                param.type(),
                param.required(),
                param.meaning(),
                param.resolver(),
                param.lookup(),
                param.lookupFields(),
                param.filledBy(),
                param.allowed(),
                param.defaultValue());
    }
}
