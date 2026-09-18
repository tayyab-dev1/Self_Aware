package com.diversive.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as something the AI layer may put in a plan.
 *
 * <p>Sits on the handler method itself so the metadata cannot drift from the code. The method
 * must also carry a request mapping, and take its inputs as at most one record, whose fields are
 * described one by one with {@link AgentParam}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentCapability {

    /**
     * Dotted, lower-case id, e.g. {@code fee.reminder.send}. Plans carry this id, never a URL
     * (invariant 2).
     */
    String id();

    /** The business module that owns the capability, e.g. {@code fee}. */
    String module();

    /** True when running it changes nothing. Read-only capabilities have blast radius NONE. */
    boolean readOnly();

    BlastRadius blastRadius();

    /** Id of the capability that undoes this one. Empty means it cannot be undone. */
    String reverses() default "";

    /**
     * What it does in the user's words, and what it is NOT, naming the alternative. The single
     * biggest lever on retrieval quality.
     */
    String description();

    /**
     * Capabilities easily confused with this one. Retrieval always pulls siblings in together.
     * Must be symmetric: if A names B, B names A.
     */
    String[] disambiguateFrom() default {};
}
