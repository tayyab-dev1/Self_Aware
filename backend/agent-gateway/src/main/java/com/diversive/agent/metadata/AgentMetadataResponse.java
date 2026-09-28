package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code GET /agent/metadata}: every capability the gateway exposes, published as
 * {@link PublishedCapability} so the AI layer is sent only what it reads.
 *
 * <p>Wire format is snake_case, set on the type rather than globally, so the
 * contract does not change with the host application's Jackson settings.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentMetadataResponse(@NotNull List<PublishedCapability> capabilities) {

    public AgentMetadataResponse {
        capabilities = List.copyOf(capabilities);
    }

    /** The registry's own entries, each narrowed to what is published. */
    public static AgentMetadataResponse of(List<CapabilityMetadata> entries) {
        return new AgentMetadataResponse(entries.stream().map(PublishedCapability::of).toList());
    }
}
