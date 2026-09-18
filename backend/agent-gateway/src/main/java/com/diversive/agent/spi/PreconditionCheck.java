package com.diversive.agent.spi;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Implements one {@code @AgentPrecondition} id. One bean per id; the application refuses to start
 * when a declared id has no bean.
 */
public interface PreconditionCheck {

    /** The {@code @AgentPrecondition} id this bean implements. */
    String id();

    /**
     * Whether the precondition holds. Runs after parameters are resolved (invariant 5), and again
     * inside the write transaction at execute (invariant 6).
     *
     * @param resolvedParams parameter values by JSON name, each of its request field's type, with
     *                       entity names already turned into ids. A parameter the plan left out
     *                       is absent.
     */
    boolean holds(Map<String, Object> resolvedParams, UserContext user);

    static PreconditionCheck of(String id, BiPredicate<Map<String, Object>, UserContext> rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        return new PreconditionCheck() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean holds(Map<String, Object> resolvedParams, UserContext user) {
                return rule.test(resolvedParams, user);
            }
        };
    }
}
