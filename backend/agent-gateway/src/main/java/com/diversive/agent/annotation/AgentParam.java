package com.diversive.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one field of the capability's request record for the planner.
 *
 * <p>The type and whether it is required are not declared here: they are read from the record
 * field itself ({@code @NotNull} and friends), so they cannot disagree with what the endpoint
 * accepts.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(AgentParams.class)
public @interface AgentParam {

    /** The field's JSON name on the request record, e.g. {@code section_id}. */
    String name();

    /** What the value means, in the user's words. */
    String meaning();

    /**
     * Entity type the preflight resolver turns a name into an id for, e.g. {@code section}.
     * Empty when the value is used as given.
     */
    String resolver() default "";

    /**
     * Template key the resolved record's label is published under, e.g. {@code section_name}
     * ("Class 5 Blue"). Required exactly when {@link #resolver()} is set.
     */
    String label() default "";

    /** The only accepted values, for a text field. Enum fields list their constants themselves. */
    String[] allowed() default {};

    /** Value the planner uses when the user does not say. Empty means no default. */
    String defaultValue() default "";
}
