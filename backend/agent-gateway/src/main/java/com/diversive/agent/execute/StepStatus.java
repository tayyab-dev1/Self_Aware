package com.diversive.agent.execute;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What happened to one step at execute. */
public enum StepStatus {

    /** Every check passed again, the handler ran, and its result matched what it declares. */
    SUCCEEDED,

    /** This write already succeeded under the same session, plan and step; its recorded result is returned and nothing ran again. */
    REPLAYED,

    /** A check refused it or it broke; nothing of it was written. The step carries the error. */
    FAILED,

    /** An earlier step failed, so this one did not run. */
    NOT_RUN;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
