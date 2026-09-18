package com.diversive.agent.preflight;

import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.error.AgentRejectionException;
import com.diversive.agent.error.AgentUnauthenticatedException;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.spi.UserContextResolver;
import com.diversive.agent.web.PlanLogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /agent/preflight}: resolve, check, count and compose a plan's confirmation, and sign a
 * token for execute. Nothing is written.
 *
 * <p>Every log line while it runs carries {@code plan_id} and {@code session_id}. The user's words,
 * the token and the credential are never logged.
 */
@RestController
@RequestMapping("/agent/preflight")
public class PreflightController {

    private static final Logger log = LoggerFactory.getLogger(PreflightController.class);

    private final PreflightService preflight;
    private final UserContextResolver userContextResolver;

    public PreflightController(PreflightService preflight, UserContextResolver userContextResolver) {
        this.preflight = preflight;
        this.userContextResolver = userContextResolver;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PreflightResponse preflight(@RequestBody PreflightRequest request, HttpServletRequest httpRequest) {
        UserContext user = userContextResolver.resolve(httpRequest).orElseThrow(AgentUnauthenticatedException::new);
        try (PlanLogContext ignored = PlanLogContext.open(request == null ? null : request.plan())) {
            try {
                return preflight.preflight(request == null ? null : request.plan(), user);
            } catch (AgentRejectionException rejection) {
                AgentErrorResponse error = rejection.error();
                log.info("Preflight refused: {}{}{}", error.code(), error.step() == null ? "" : " at step " + error.step(),
                        error.param() == null ? "" : ", param " + error.param());
                throw rejection;
            }
        }
    }
}
