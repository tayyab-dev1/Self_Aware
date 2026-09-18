package com.diversive.agent.execute;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code agent.gateway.execute}.
 *
 * @param smallCount            at or below this many records, any change since the confirmation stops the step
 * @param countTolerancePercent above {@code smallCount}, a change of more than this share of the confirmed count stops it
 * @param maxAttempts           how many times a step is tried when the data changes underneath it (a serialization conflict)
 * @param maxSentenceLength     the longest sentence the audit trail accepts
 */
@ConfigurationProperties("agent.gateway.execute")
public record ExecuteProperties(
        @DefaultValue("20") long smallCount,
        @DefaultValue("5") int countTolerancePercent,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2000") int maxSentenceLength) {

    public ExecuteProperties {
        if (smallCount < 0 || countTolerancePercent < 0 || maxAttempts < 1 || maxSentenceLength < 1) {
            throw new IllegalArgumentException("agent.gateway.execute settings must be positive");
        }
    }

    public DeltaRule deltaRule() {
        return new DeltaRule(smallCount, countTolerancePercent);
    }
}
