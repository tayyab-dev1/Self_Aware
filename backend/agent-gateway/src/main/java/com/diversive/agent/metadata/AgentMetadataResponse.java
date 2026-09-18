package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code GET /agent/metadata}: every capability the gateway exposes.
 *
 * <p>Wire format is snake_case, set on the type rather than globally, so the
 * contract does not change with the host application's Jackson settings.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentMetadataResponse(@NotNull List<CapabilityMetadata> capabilities) {

    public AgentMetadataResponse {
        capabilities = List.copyOf(capabilities);
    }
}
