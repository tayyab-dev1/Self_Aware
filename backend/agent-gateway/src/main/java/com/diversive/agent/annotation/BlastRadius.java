package com.diversive.agent.annotation;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How much a capability can change when it runs, ordered from nothing to everything.
 *
 * <p>Anything above {@link #SINGLE} must declare an {@code AffectedCount}, so a confirmation
 * never says "1" while touching four hundred rows.
 */
public enum BlastRadius {

    /** Changes nothing. Every read-only capability, and only those. */
    NONE,

    /** One record, such as one invoice or one payment. */
    SINGLE,

    /** A bounded group of records, such as everyone in one class. */
    GROUP,

    /** Everything of its kind in one branch. */
    BRANCH,

    /** Everything of its kind in every branch. */
    ORGANISATION;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
