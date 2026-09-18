package com.diversive.school.platform.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The POC's stand-in for sign-in: whoever presents {@code token} acts as {@code username}.
 *
 * @param token    shared development token; blank disables it, so nobody is authenticated
 * @param username the {@code app_users.username} the token stands for
 */
@ConfigurationProperties("school.agent.dev-user")
public record DevUserProperties(String token, String username) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "DevUserProperties[token=" + (enabled() ? "[REDACTED]" : "<unset>") + ", username=" + username + "]";
    }
}
