package com.diversive.school.platform.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the handler of a capability that is declared but not built answers when called directly: the four
 * confusable fee corrections, which carry {@code @AgentNotImplemented}, so the agent gateway never runs them.
 */
public final class PendingImplementations {

    private PendingImplementations() {
    }

    public static ResponseStatusException handlerNotImplemented(String capabilityId) {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED: " + capabilityId);
    }
}
