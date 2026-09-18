package com.diversive.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Something that must be true before the capability may run.
 *
 * <p>Checked after parameters are resolved (invariant 5) and again inside the write transaction
 * (invariant 6). Every id must be implemented by a {@code PreconditionCheck} bean, or the
 * application refuses to start.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(AgentPreconditions.class)
public @interface AgentPrecondition {

    /** Lower snake_case id, shared by every capability that needs the same check. */
    String id();

    /** The rule, stated plainly. */
    String text();

    /** What the user is told when it fails. Never a suggestion to route around it. */
    String hint();
}
