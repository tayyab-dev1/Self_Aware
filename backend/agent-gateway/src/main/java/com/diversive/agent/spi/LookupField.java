package com.diversive.agent.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/**
 * One labelled part of what a user says to name a record, declared by an {@link EntityResolver}.
 *
 * <p>Instead of one line of words the resolver has to take apart ("Hasan Ali in Class 5 Blue", where
 * "in" means nothing and "Class 5" is not part of the name), the plan names each part: the student's
 * name here, the class there. The parts are published with every parameter that uses the resolver, so
 * the planner knows exactly which ones exist and fills only what the user said.
 *
 * @param name       what the plan calls this part, e.g. {@code student_name}
 * @param meaning    what belongs in it, in the words the user would use
 * @param identifies true when this part alone can name the record (an invoice number does; a month
 *                   does not). Preflight refuses a lookup that has no identifying part.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupField(@NotNull String name, @NotNull String meaning, @NotNull Boolean identifies) {

    public LookupField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(meaning, "meaning");
        Objects.requireNonNull(identifies, "identifies");
    }

    /** A part that alone names the record, such as a name or a printed number. */
    public static LookupField identifying(String name, String meaning) {
        return new LookupField(name, meaning, true);
    }

    /** A part that only narrows what an identifying part found, such as a month or a section. */
    public static LookupField narrowing(String name, String meaning) {
        return new LookupField(name, meaning, false);
    }
}
