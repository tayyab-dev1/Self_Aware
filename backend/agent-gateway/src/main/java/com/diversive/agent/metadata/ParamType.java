package com.diversive.agent.metadata;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** The JSON type of a parameter value, read from the request record field's Java type. */
public enum ParamType {

    /** Text. With a non-empty {@code allowed} list it is one of those values. */
    STRING,

    /** A whole number, including every entity id. */
    INTEGER,

    /** An exact decimal, such as an amount of money. Never rounded. */
    DECIMAL,

    BOOLEAN,

    /** A calendar date, {@code YYYY-MM-DD}. */
    DATE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
