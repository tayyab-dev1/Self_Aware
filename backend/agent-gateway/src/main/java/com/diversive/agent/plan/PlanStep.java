package com.diversive.agent.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step of a plan.
 *
 * @param step              its position, starting at 1
 * @param capabilityId      what to run, e.g. {@code fee.reminder.send}
 * @param capabilityVersion the metadata version the plan was made with; an older one is {@code STALE_VERSION}
 * @param params            by parameter name. A parameter left out takes its declared default, or is absent
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanStep(
        @NotNull Integer step,
        @NotNull String capabilityId,
        @NotNull String capabilityVersion,
        @NotNull Map<String, ParamValue> params) {

    public PlanStep {
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
