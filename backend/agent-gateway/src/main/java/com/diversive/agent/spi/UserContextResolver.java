package com.diversive.agent.spi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Works out who a gateway request is for. Supplied by the host application, which already
 * knows how its users authenticate. The gateway never reads or logs the credential itself.
 */
public interface UserContextResolver {

    /** The user, or empty when the request carries no valid credential. */
    Optional<UserContext> resolve(HttpServletRequest request);
}
