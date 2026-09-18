package com.diversive.agent.execute;

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
 * {@code POST /agent/execute}: run a plan the user confirmed, with the token preflight issued for it.
 *
 * <p>A plan refused as a whole (token, version, permission, parameters) is an error response. Once it is
 * accepted the answer is 200, and says step by step what succeeded, what failed and what did not run.
 * Every log line carries {@code plan_id} and {@code session_id}; the sentence, the token and the credential
 * are never logged.
 */
@RestController
@RequestMapping("/agent/execute")
public class ExecuteController {

    private static final Logger log = LoggerFactory.getLogger(ExecuteController.class);

    private final ExecuteService execute;
    private final UserContextResolver userContextResolver;

    public ExecuteController(ExecuteService execute, UserContextResolver userContextResolver) {
        this.execute = execute;
        this.userContextResolver = userContextResolver;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ExecuteResponse execute(@RequestBody ExecuteRequest request, HttpServletRequest httpRequest) {
        UserContext user = userContextResolver.resolve(httpRequest).orElseThrow(AgentUnauthenticatedException::new);
        try (PlanLogContext ignored = PlanLogContext.open(request == null ? null : request.plan())) {
            try {
                return execute.execute(request, user);
            } catch (AgentRejectionException rejection) {
                AgentErrorResponse error = rejection.error();
                log.info("Execute refused: {}{}", error.code(), error.step() == null ? "" : " at step " + error.step());
                throw rejection;
            }
        }
    }
}
