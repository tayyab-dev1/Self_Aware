package com.diversive.agent.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What running the capability does, and the templates the backend fills with real values.
 * Absent fields are empty in the annotation: reads create, notify and confirm nothing.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EffectMetadata(
        String creates,
        String notifies,
        String confirmationTemplate,
        String pendingTemplate,
        @NotNull String replyTemplate,
        @NotNull List<String> facts) {

    public EffectMetadata {
        facts = List.copyOf(facts);
    }
}
