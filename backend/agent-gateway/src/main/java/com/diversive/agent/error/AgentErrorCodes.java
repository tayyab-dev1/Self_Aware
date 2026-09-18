package com.diversive.agent.error;

import org.springframework.http.HttpStatus;

/**
 * The error codes the AI layer branches on (CLAUDE.md, "Error codes"). Part of the contract: add a
 * code here and to that table together.
 */
public final class AgentErrorCodes {

    /** No valid user credential on the request. HTTP 401. */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** The plan does not fit the capability metadata: unknown capability or parameter, wrong type, missing value. HTTP 400. */
    public static final String INVALID_PLAN = "INVALID_PLAN";

    /** A step was planned with an older capability version. HTTP 409. */
    public static final String STALE_VERSION = "STALE_VERSION";

    /** The user may not use a step's capability. HTTP 403. */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";

    /** A step's capability is declared but cannot run yet. HTTP 501. */
    public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    /** A name matched no record the user may see. HTTP 422. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** A name matched several records; the user must choose. HTTP 422. */
    public static final String AMBIGUOUS_ENTITY = "AMBIGUOUS_ENTITY";

    /** A precondition does not hold; the response carries its hint. HTTP 422. */
    public static final String PRECONDITION_FAILED = "PRECONDITION_FAILED";

    /** The preflight token is past its expiry; run preflight again. HTTP 409. */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";

    /** The preflight token was altered, or was issued for another plan or user. HTTP 403. */
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    /**
     * At execute, a record the user confirmed is no longer one they may see. HTTP 403. Preflight never
     * returns it: a resolver cannot see outside the user's scope, so a name outside it is {@code NOT_FOUND}
     * (invariant 7).
     */
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";

    /** At execute, what a step would touch moved materially from what the user confirmed. HTTP 409. */
    public static final String COUNT_CHANGED = "COUNT_CHANGED";

    /** At execute, the data kept changing while the step ran, even after retrying; nothing of it was written. HTTP 409. */
    public static final String CONFLICT = "CONFLICT";

    /** At execute, the step broke while running; nothing of it was written. HTTP 500. */
    public static final String EXECUTION_FAILED = "EXECUTION_FAILED";

    /** At execute, the step did not do what it declared, so it was rolled back. A bug, not bad luck. HTTP 500. */
    public static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";

    private AgentErrorCodes() {
    }

    /** The HTTP status each code is sent with. The AI layer branches on the code, never on the status. */
    public static HttpStatus status(String code) {
        return switch (code) {
            case INVALID_PLAN -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case NOT_PERMITTED, OUT_OF_SCOPE, TOKEN_INVALID -> HttpStatus.FORBIDDEN;
            case STALE_VERSION, TOKEN_EXPIRED, COUNT_CHANGED, CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND, AMBIGUOUS_ENTITY, PRECONDITION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case EXECUTION_FAILED, VERIFICATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> throw new IllegalArgumentException("Unknown agent error code " + code);
        };
    }
}
