package com.diversive.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What running the capability does, and the templates the backend fills with real values.
 *
 * <p>A model never writes confirmation or reply text (invariant 3). Every {@code {placeholder}}
 * must be a parameter name, a parameter's resolved label, {@code count}, or one of {@link #facts()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentEffect {

    /** Records it creates, e.g. "one message log row per guardian". Empty for a read. */
    String creates() default "";

    /** Who hears about it, e.g. "guardians of students with unpaid fees". Empty for a read. */
    String notifies() default "";

    /** Shown before a write runs. Required for writes, empty for reads. */
    String confirmationTemplate() default "";

    /**
     * Used instead of the confirmation when a step depends on an earlier step, so its values are
     * not known yet. It may contain no placeholders.
     */
    String pendingTemplate() default "";

    /** The answer after it has run. */
    String replyTemplate();

    /** Extra values the count or the handler publishes for the templates, e.g. total_outstanding. */
    String[] facts() default {};
}
