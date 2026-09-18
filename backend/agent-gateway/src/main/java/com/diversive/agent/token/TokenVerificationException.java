package com.diversive.agent.token;

import com.diversive.agent.error.AgentErrorCodes;

/** A preflight token does not vouch for the plan and user in front of it. Execute must not run. */
public class TokenVerificationException extends Exception {

    private final Reason reason;

    TokenVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** {@code TOKEN_EXPIRED} when it is only too old; {@code TOKEN_INVALID} for everything else. */
    public String code() {
        return reason == Reason.EXPIRED ? AgentErrorCodes.TOKEN_EXPIRED : AgentErrorCodes.TOKEN_INVALID;
    }

    public enum Reason {
        /** Not two base64url parts, or the payload is not a token. */
        MALFORMED,
        /** The signature does not match the payload: the token was altered, or signed with another key. */
        BAD_SIGNATURE,
        /** Signed by this backend, but past its expiry. */
        EXPIRED,
        /** Issued for another user. */
        USER_MISMATCH,
        /** Issued for a different plan: the plan was changed after it was confirmed. */
        PLAN_MISMATCH
    }
}
