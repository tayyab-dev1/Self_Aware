package com.diversive.agent.preflight;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code agent.gateway.preflight}.
 *
 * @param tokenSecret HMAC key for preflight tokens, at least 32 bytes. Blank uses a random key per
 *                    start: safe, but tokens then die with a restart and are not shared between instances
 * @param tokenTtl    how long a confirmation may wait before execute; after that, preflight runs again
 * @param maxSteps    the most steps one plan may have
 */
@ConfigurationProperties("agent.gateway.preflight")
public record PreflightProperties(
        String tokenSecret,
        @DefaultValue("5m") Duration tokenTtl,
        @DefaultValue("3") int maxSteps) {

    public PreflightProperties {
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("agent.gateway.preflight.token-ttl must be positive");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("agent.gateway.preflight.max-steps must be at least 1");
        }
    }

    @Override
    public String toString() {
        boolean secretSet = tokenSecret != null && !tokenSecret.isBlank();
        return "PreflightProperties[tokenSecret=" + (secretSet ? "[REDACTED]" : "<random per start>")
                + ", tokenTtl=" + tokenTtl + ", maxSteps=" + maxSteps + "]";
    }
}
