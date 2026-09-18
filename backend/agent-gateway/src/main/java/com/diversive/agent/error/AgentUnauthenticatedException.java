package com.diversive.agent.error;

/** The request carries no valid user credential. Answered with {@code UNAUTHENTICATED}. */
public class AgentUnauthenticatedException extends AgentRejectionException {

    public AgentUnauthenticatedException() {
        super(AgentErrorCodes.status(AgentErrorCodes.UNAUTHENTICATED),
                new AgentErrorResponse(AgentErrorCodes.UNAUTHENTICATED, "A valid user credential is required"));
    }
}
