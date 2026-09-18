package com.diversive.agent.error;

import com.diversive.agent.execute.ExecuteController;
import com.diversive.agent.metadata.AgentMetadataController;
import com.diversive.agent.preflight.PreflightController;
import com.diversive.agent.session.AgentSessionController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns gateway exceptions into {@link AgentErrorResponse} bodies, for gateway endpoints only. */
@RestControllerAdvice(assignableTypes = {
        AgentMetadataController.class, AgentSessionController.class, PreflightController.class, ExecuteController.class})
public class AgentGatewayExceptionHandler {

    @ExceptionHandler(AgentUnauthenticatedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public AgentErrorResponse unauthenticated(AgentUnauthenticatedException exception) {
        return exception.error();
    }

    /** Every other refusal, with the status its code maps to ({@link AgentErrorCodes#status}). */
    @ExceptionHandler(AgentRejectionException.class)
    public ResponseEntity<AgentErrorResponse> rejected(AgentRejectionException exception) {
        return ResponseEntity.status(exception.status()).body(exception.error());
    }
}
