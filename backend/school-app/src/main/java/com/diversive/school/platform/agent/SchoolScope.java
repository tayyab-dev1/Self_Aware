package com.diversive.school.platform.agent;

import com.diversive.agent.spi.UserContext;

/**
 * Where a signed-in user may act, read from their {@link UserContext}. Resolvers, checks and counts put
 * it in their SQL {@code WHERE} clause, never in a filter afterwards (invariant 7).
 */
public final class SchoolScope {

    /** The user's branch (campus), set by the sign-in resolver. */
    public static final String BRANCH_ID = "branch_id";

    private SchoolScope() {
    }

    public static long branchId(UserContext user) {
        String branchId = user.scope().get(BRANCH_ID);
        if (branchId == null) {
            throw new IllegalStateException("User " + user.userId() + " has no branch in their scope");
        }
        return Long.parseLong(branchId);
    }
}
