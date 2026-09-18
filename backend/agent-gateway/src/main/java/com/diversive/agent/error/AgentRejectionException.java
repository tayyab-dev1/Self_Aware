package com.diversive.agent.error;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** The gateway refuses a request with an error code. Answered with {@link #error()} and {@link #status()}. */
public class AgentRejectionException extends RuntimeException {

    private final transient HttpStatus status;
    private final transient AgentErrorResponse error;

    public AgentRejectionException(HttpStatus status, AgentErrorResponse error) {
        super(error.code() + ": " + error.message());
        this.status = Objects.requireNonNull(status, "status");
        this.error = error;
    }

    public HttpStatus status() {
        return status;
    }

    public AgentErrorResponse error() {
        return error;
    }
}
