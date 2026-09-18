package com.diversive.agent.metadata;

import com.diversive.agent.annotation.BlastRadius;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One capability as the AI layer sees it: everything needed to retrieve it, plan with it and
 * validate a plan against it.
 *
 * <p>Deliberately carries no URL, HTTP method or handler name: the backend resolves those from its
 * own registry, so a plan can only ever name a capability id (invariant 2).
 *
 * @param version  SHA-256 of every other field, so any change to the entry changes it and plans
 *                 stamped with an older version are rejected at execute
 * @param reverses id of the capability that undoes this one; absent when it cannot be undone
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityMetadata(
        @NotNull String id,
        @NotNull String version,
        @NotNull String module,
        @NotNull Boolean readOnly,
        @NotNull BlastRadius blastRadius,
        String reverses,
        @NotNull String description,
        @NotNull List<String> disambiguateFrom,
        @NotNull List<ParamMetadata> params,
        @NotNull List<PreconditionMetadata> preconditions,
        @NotNull EffectMetadata effect) {

    public CapabilityMetadata {
        disambiguateFrom = List.copyOf(disambiguateFrom);
        params = List.copyOf(params);
        preconditions = List.copyOf(preconditions);
    }

    public CapabilityMetadata withVersion(String newVersion) {
        return new CapabilityMetadata(id, newVersion, module, readOnly, blastRadius, reverses,
                description, disambiguateFrom, params, preconditions, effect);
    }
}
