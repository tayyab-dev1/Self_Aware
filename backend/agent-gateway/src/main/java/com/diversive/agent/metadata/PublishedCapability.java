package com.diversive.agent.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One capability as {@code GET /agent/metadata} publishes it: only what the AI layer reads.
 *
 * <p>{@link CapabilityMetadata} is the registry's own entry and keeps everything, because the
 * version is a SHA-256 of all of it. What this record leaves out is therefore still covered by the
 * version: a changed precondition or reply template changes the version the AI layer sees, even
 * though it cannot see what changed. Never hash or store this view instead of the full entry.
 *
 * <p>Left out on purpose, because nothing in the AI layer reads it:
 * <ul>
 *   <li>{@code preconditions}: preflight checks them and sends the failing one's hint back as
 *       words, so the AI layer is told what stopped a plan rather than what might.</li>
 *   <li>{@code blast_radius}: the scanner checks it against {@code read_only}, and the confirmation
 *       composer decides from it how loudly to warn. Both run here.</li>
 *   <li>{@code reverses}: declared for a later undo feature, unused by either side today.</li>
 *   <li>the effect's templates, {@code creates} and {@code notifies}: the backend fills them and
 *       sends the finished sentence, because no model writes the words a user reads (invariant 3).
 *       Only {@code facts} is published, since a plan may feed one step's fact into the next.</li>
 * </ul>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishedCapability(
        @NotNull String id,
        @NotNull String version,
        @NotNull String module,
        @NotNull Boolean readOnly,
        @NotNull String description,
        @NotNull List<String> disambiguateFrom,
        @NotNull List<PublishedParam> params,
        @NotNull PublishedEffect effect) {

    public PublishedCapability {
        disambiguateFrom = List.copyOf(disambiguateFrom);
        params = List.copyOf(params);
    }

    public static PublishedCapability of(CapabilityMetadata entry) {
        return new PublishedCapability(
                entry.id(),
                entry.version(),
                entry.module(),
                entry.readOnly(),
                entry.description(),
                entry.disambiguateFrom(),
                entry.params().stream().map(PublishedParam::of).toList(),
                PublishedEffect.of(entry.effect()));
    }
}
