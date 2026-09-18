package com.diversive.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The capability is declared, so it can be retrieved and planned, but it cannot run yet. Preflight
 * refuses it with {@code NOT_IMPLEMENTED} before resolving or checking anything.
 *
 * <p>Deliberately not part of the published metadata: the planner must tell capabilities apart by
 * what they mean, never by whether they happen to be built. The registry skips the rules that need
 * running code (check, count and resolver beans) for it, and applies them again the moment this
 * annotation is removed.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentNotImplemented {
}
