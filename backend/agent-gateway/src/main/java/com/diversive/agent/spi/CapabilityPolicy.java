package com.diversive.agent.spi;

/**
 * Decides which capabilities a user may use. Supplied by the host application.
 *
 * <p>Asked when the AI layer loads a user's allow-list, and again at preflight and execute:
 * the backend re-checks everything (invariant 4).
 */
public interface CapabilityPolicy {

    boolean permits(UserContext user, String capabilityId);
}
