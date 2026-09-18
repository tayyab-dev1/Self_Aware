package com.diversive.agent.step;

import com.diversive.agent.error.AgentErrorCodes;
import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.error.AgentRejectionException;
import com.diversive.agent.error.EntityCandidate;
import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.metadata.PreconditionMetadata;
import com.diversive.agent.spi.EntityMatch;
import java.util.List;

/**
 * Every way preflight and execute refuse a plan or a step, each as an error body with its code. The HTTP
 * status comes from the code ({@link AgentErrorCodes#status}).
 */
public final class StepRejections {

    /** Enough to choose from; a name matching more than this needs more words, not a longer list. */
    public static final int MAX_CANDIDATES = 10;

    private StepRejections() {
    }

    public static AgentRejectionException reject(AgentErrorResponse error) {
        return new AgentRejectionException(AgentErrorCodes.status(error.code()), error);
    }

    public static AgentRejectionException invalidPlan(String message) {
        return reject(new AgentErrorResponse(AgentErrorCodes.INVALID_PLAN, message));
    }

    public static AgentRejectionException invalidStep(int step, String message) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.INVALID_PLAN, step, "Step " + step + ": " + message));
    }

    public static AgentRejectionException invalidParam(int step, String param, String message) {
        return reject(AgentErrorResponse.forParam(AgentErrorCodes.INVALID_PLAN,
                step, param, "Step " + step + ", parameter '" + param + "': " + message));
    }

    public static AgentRejectionException staleVersion(int step, String capabilityId) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.STALE_VERSION, step,
                "Step " + step + " was planned with an older version of " + capabilityId));
    }

    public static AgentRejectionException notPermitted(int step, String capabilityId) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.NOT_PERMITTED, step,
                "Step " + step + ": this user may not use " + capabilityId));
    }

    public static AgentRejectionException notImplemented(int step, String capabilityId) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.NOT_IMPLEMENTED,
                step, "Step " + step + ": " + capabilityId + " is not available yet"));
    }

    public static AgentRejectionException notFound(int step, ParamMetadata param, String raw) {
        return reject(AgentErrorResponse.forParam(AgentErrorCodes.NOT_FOUND,
                step, param.name(), "Step " + step + ": no " + entityWord(param) + " matches \"" + raw + "\""));
    }

    public static AgentRejectionException chosenNotAMatch(int step, ParamMetadata param, String raw) {
        return reject(AgentErrorResponse.forParam(AgentErrorCodes.NOT_FOUND,
                step, param.name(), "Step " + step + ": the chosen " + entityWord(param) + " is not one that \"" + raw
                        + "\" matches"));
    }

    public static AgentRejectionException ambiguous(int step, ParamMetadata param, String raw, List<EntityMatch> matches) {
        List<EntityCandidate> candidates = matches.stream()
                .limit(MAX_CANDIDATES)
                .map(match -> new EntityCandidate(match.id(), match.label(), match.context()))
                .toList();
        String message = "Step " + step + ": \"" + raw + "\" matches " + matches.size() + " " + entityWord(param) + " records"
                + (matches.size() > MAX_CANDIDATES ? "; the first " + MAX_CANDIDATES + " are listed" : "");
        return reject(AgentErrorResponse.forParam(AgentErrorCodes.AMBIGUOUS_ENTITY, step, param.name(), message)
                .withCandidates(candidates));
    }

    public static AgentRejectionException preconditionFailed(int step, PreconditionMetadata precondition) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.PRECONDITION_FAILED, step, precondition.hint())
                .withPrecondition(precondition.id(), precondition.hint()));
    }

    /** A record the user confirmed is no longer one they may see. Says nothing about whether it exists. */
    public static AgentRejectionException outOfScope(int step, ParamMetadata param) {
        return reject(AgentErrorResponse.forParam(AgentErrorCodes.OUT_OF_SCOPE, step, param.name(),
                "Step " + step + ": the " + entityWord(param) + " that was confirmed is not available"));
    }

    public static AgentRejectionException countChanged(int step, long confirmed, long current, String unit) {
        String what = unit == null ? "records" : unit;
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.COUNT_CHANGED, step,
                "Step " + step + ": the confirmation said " + confirmed + " " + what + ", but it would now be " + current
                        + ". Nothing was done; confirm again").withCounts(confirmed, current));
    }

    public static AgentRejectionException conflict(int step) {
        return reject(AgentErrorResponse.forStep(AgentErrorCodes.CONFLICT, step,
                "Step " + step + ": the data kept changing while it ran, so nothing of it was written; run preflight again"));
    }

    public static AgentErrorResponse executionFailed(int step) {
        return AgentErrorResponse.forStep(AgentErrorCodes.EXECUTION_FAILED, step,
                "Step " + step + " failed while running, and nothing of it was written");
    }

    public static AgentErrorResponse verificationFailed(int step, String capabilityId) {
        return AgentErrorResponse.forStep(AgentErrorCodes.VERIFICATION_FAILED, step,
                "Step " + step + ": " + capabilityId + " did not do what it declares, so it was rolled back");
    }

    /** "late_fee" reads as "late fee". */
    private static String entityWord(ParamMetadata param) {
        return param.resolver().replace('_', ' ');
    }
}
