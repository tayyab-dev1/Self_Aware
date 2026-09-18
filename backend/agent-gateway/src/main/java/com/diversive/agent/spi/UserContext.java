package com.diversive.agent.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The person a request is made for, as the host application knows them.
 *
 * @param userId stable id of the user
 * @param roles  what they are, for {@link CapabilityPolicy}
 * @param scope  where they may act, e.g. {@code branch_id -> 1}. Resolvers put this in the SQL
 *               WHERE clause, never in a filter afterwards (invariant 7).
 */
public record UserContext(String userId, Set<String> roles, Map<String, String> scope) {

    public UserContext {
        Objects.requireNonNull(userId, "userId");
        roles = Set.copyOf(roles);
        scope = Map.copyOf(scope);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
