package com.diversive.agent.spi;

import java.util.Objects;

/**
 * One record a name could mean.
 *
 * @param id      the record's id, as text so any key type fits; preflight converts it to the
 *                parameter's type
 * @param label   how the record reads in a confirmation, e.g. "Class 5 Blue". Kept with the id,
 *                because a confirmation that says "section 2" is useless
 * @param context what tells two similar matches apart when the user must choose, e.g. "12 students";
 *                may be null
 */
public record EntityMatch(String id, String label, String context) {

    public EntityMatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("An entity match needs an id and a label");
        }
    }
}
