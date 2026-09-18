package com.diversive.agent.spi;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Counts what one capability will touch, for its confirmation text. Required for every capability
 * whose blast radius is above SINGLE; the application refuses to start without it. Any other write
 * counts as 1.
 *
 * <p>Must call the same repository method as the capability's handler (invariant 8). Writing the
 * predicate twice is how a confirmation says 17 and the send reaches 14.
 */
public interface AffectedCount {

    /** The {@code @AgentCapability} id this bean counts for. */
    String capabilityId();

    /** @param resolvedParams as for {@link PreconditionCheck#holds} */
    CountResult count(Map<String, Object> resolvedParams, UserContext user);

    static AffectedCount of(String capabilityId, BiFunction<Map<String, Object>, UserContext, CountResult> counter) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(counter, "counter");
        return new AffectedCount() {
            @Override
            public String capabilityId() {
                return capabilityId;
            }

            @Override
            public CountResult count(Map<String, Object> resolvedParams, UserContext user) {
                return counter.apply(resolvedParams, user);
            }
        };
    }

    /**
     * @param count how many
     * @param unit  what is counted, e.g. "guardians"; may be null
     * @param facts extra template values, each declared in {@code @AgentEffect(facts)}
     */
    record CountResult(long count, String unit, Map<String, Object> facts) {

        public CountResult {
            if (count < 0) {
                throw new IllegalArgumentException("A count cannot be negative: " + count);
            }
            facts = Map.copyOf(facts);
        }
    }
}
