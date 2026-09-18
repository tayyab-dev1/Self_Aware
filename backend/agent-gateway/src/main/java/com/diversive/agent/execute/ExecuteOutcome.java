package com.diversive.agent.execute;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How a whole plan went at execute. */
public enum ExecuteOutcome {

    /** Every step succeeded, or had already succeeded and was replayed. */
    COMPLETED,

    /** At least one step succeeded before a later one failed; what succeeded stays done. */
    PARTIAL,

    /** The first step failed, so nothing was done. */
    FAILED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
