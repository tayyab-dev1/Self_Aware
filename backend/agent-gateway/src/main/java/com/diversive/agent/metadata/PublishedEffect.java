package com.diversive.agent.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a capability publishes after it runs, as {@code GET /agent/metadata} publishes it.
 *
 * <p>Only the fact names: a plan may feed one step's fact into a later step's parameter, and the
 * validator holds it to this list. The templates that turn those facts into a sentence, and what
 * the capability creates or notifies, stay here (invariant 3: no model writes what a user reads).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishedEffect(@NotNull List<String> facts) {

    public PublishedEffect {
        facts = List.copyOf(facts);
    }

    public static PublishedEffect of(EffectMetadata effect) {
        return new PublishedEffect(effect.facts());
    }
}
