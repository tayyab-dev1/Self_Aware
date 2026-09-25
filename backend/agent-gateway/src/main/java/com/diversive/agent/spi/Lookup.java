package com.diversive.agent.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the plan says to name one record: the user's own words, and the parts they were split into.
 *
 * <p>{@code parts} holds the {@link LookupField}s the plan filled, e.g. {@code {student_name: "Hasan
 * Ali", class: "Class 5", section: "Blue"}}, always in the user's own words. It is empty when the plan
 * sent only {@code raw}, which a resolver may still take apart itself; {@link #raw()} is never empty,
 * because it is what the chat shows the user and what the audit trail keeps.
 */
public record Lookup(String raw, Map<String, String> parts) {

    public Lookup {
        Objects.requireNonNull(raw, "raw");
        parts = Map.copyOf(parts);
    }

    /** Only the user's words, with no parts: what a plan that names no parts sends. */
    public static Lookup of(String raw) {
        return new Lookup(raw, Map.of());
    }

    /** The part, or null when the plan did not fill it. Blank parts never reach a resolver. */
    public String part(String field) {
        return parts.get(field);
    }

    /** The part, or {@code fallback} when the plan did not fill it. */
    public String partOr(String field, String fallback) {
        return parts.getOrDefault(field, fallback);
    }

    public boolean has(String field) {
        return parts.containsKey(field);
    }

    /** The parts joined in the order given, for a resolver that searches text: "Hasan Ali Class 5 Blue". */
    public String joined(String... fields) {
        StringBuilder joined = new StringBuilder();
        for (String field : fields) {
            String part = parts.get(field);
            if (part != null && !part.isBlank()) {
                joined.append(joined.isEmpty() ? "" : " ").append(part.strip());
            }
        }
        return joined.toString();
    }

    /** The same lookup with one part set, for tests and for resolvers that fill in a default. */
    public Lookup with(String field, String value) {
        Map<String, String> next = new LinkedHashMap<>(parts);
        next.put(field, value);
        return new Lookup(raw, next);
    }
}
