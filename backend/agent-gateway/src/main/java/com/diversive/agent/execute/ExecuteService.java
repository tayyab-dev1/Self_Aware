package com.diversive.agent.execute;

import com.diversive.agent.error.AgentErrorCodes;
import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.error.AgentRejectionException;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.AuditEvent;
import com.diversive.agent.spi.AuditTrail;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.step.PlanReader;
import com.diversive.agent.step.PreparedStep;
import com.diversive.agent.step.StepPasses;
import com.diversive.agent.step.StepRejections;
import com.diversive.agent.token.PreflightToken;
import com.diversive.agent.token.PreflightTokens;
import com.diversive.agent.token.TokenVerificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Execute: run a confirmed plan, re-checking everything preflight checked, because preflight was for showing
 * the user and this is enforcement (invariant 4). No AI.
 *
 * <p>Before any step runs, for the whole plan: the token must vouch for this exact plan and user and be
 * unexpired, and every step must name a current, permitted, implemented capability with valid parameters.
 * A refusal here is an error response and nothing runs.
 *
 * <p>Then each step, in order, in one serializable transaction of its own:
 *
 * <ol>
 *   <li>a write that already succeeded under {@code session_id:plan_id:step} is answered from the audit trail,
 *       and nothing runs twice;</li>
 *   <li>every name must still find the record the user confirmed ({@code OUT_OF_SCOPE});</li>
 *   <li>the whole request is validated, and preconditions are checked again, inside the write transaction
 *       (invariant 6; {@code PRECONDITION_FAILED});</li>
 *   <li>a write is counted again, and refused if the count moved materially from the confirmed one
 *       ({@code COUNT_CHANGED});</li>
 *   <li>a {@code STARTED} audit event is appended, the handler runs, and its result is verified against what
 *       the capability declares ({@code VERIFICATION_FAILED} rolls it back);</li>
 *   <li>the reply is filled from real values, and a {@code SUCCEEDED} event records it.</li>
 * </ol>
 *
 * <p>A refused or failed step is rolled back, recorded in the audit trail in a transaction of its own, and
 * stops the plan. Steps that already succeeded stay done: there is no automatic rollback, and the response
 * says what ran and what did not. A serialization conflict is retried a few times before it becomes
 * {@code CONFLICT}.
 */
public class ExecuteService {

    private static final Logger log = LoggerFactory.getLogger(ExecuteService.class);
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01", "23505");

    private final CapabilityRegistry registry;
    private final PlanReader reader;
    private final StepPasses passes;
    private final PreflightTokens tokens;
    private final AuditTrail audit;
    private final CapabilityHandlers handlers;
    private final ResponseReader responses;
    private final TransactionOperations stepTransactions;
    private final TransactionOperations auditTransactions;
    private final ExecuteProperties properties;

    /**
     * @param stepTransactions  runs one step, e.g. a serializable transaction
     * @param auditTransactions records a refusal or failure after its step rolled back, e.g. a new transaction
     */
    public ExecuteService(CapabilityRegistry registry, PlanReader reader, StepPasses passes, PreflightTokens tokens,
                          AuditTrail audit, CapabilityHandlers handlers, ObjectMapper objectMapper,
                          TransactionOperations stepTransactions, TransactionOperations auditTransactions,
                          ExecuteProperties properties) {
        this.registry = registry;
        this.reader = reader;
        this.passes = passes;
        this.tokens = tokens;
        this.audit = audit;
        this.handlers = handlers;
        this.responses = new ResponseReader(objectMapper);
        this.stepTransactions = stepTransactions;
        this.auditTransactions = auditTransactions;
        this.properties = properties;
    }

    /**
     * @throws AgentRejectionException when the plan as a whole is refused before any step runs
     */
    public ExecuteResponse execute(ExecuteRequest request, UserContext user) {
        if (request == null || request.plan() == null) {
            throw StepRejections.invalidPlan("The request has no plan");
        }
        Plan plan = request.plan();
        String sentence = request.sentence();
        if (sentence == null || sentence.isBlank() || sentence.length() > properties.maxSentenceLength()) {
            throw StepRejections.invalidPlan("The request needs the user's sentence, at most "
                    + properties.maxSentenceLength() + " characters, for the audit trail");
        }

        PreflightToken token;
        try {
            token = tokens.verify(request.token(), plan, user);
        } catch (TokenVerificationException e) {
            recordPlanRefusal(plan, sentence, user, e.code());
            throw StepRejections.reject(new AgentErrorResponse(e.code(), e.getMessage()));
        }
        List<PreparedStep> steps;
        try {
            steps = reader.read(plan, user);
        } catch (AgentRejectionException rejection) {
            recordPlanRefusal(plan, sentence, user, rejection.error().code());
            throw rejection;
        }

        List<ExecutedStep> results = new ArrayList<>();
        for (PreparedStep step : steps) {
            boolean earlierFailed = results.stream().anyMatch(result -> result.status() == StepStatus.FAILED);
            if (earlierFailed) {
                results.add(ExecutedStep.notRun(step));
                continue;
            }
            StepRun run = new StepRun(plan, sentence, step, token.steps().get(step.number() - 1), user);
            results.add(runWithRetries(run, results));
        }

        ExecuteOutcome outcome = outcome(results);
        log.info("Execute {}: {}", outcome.wireValue(),
                results.stream().map(result -> result.step() + " " + result.status().wireValue()).toList());
        return new ExecuteResponse(plan.planId(), outcome, results);
    }

    // --- One step ----------------------------------------------------------------------------------------------

    private ExecutedStep runWithRetries(StepRun run, List<ExecutedStep> earlier) {
        for (int attempt = 1; ; attempt++) {
            try {
                return stepTransactions.execute(status -> runOnce(run, earlier));
            } catch (AgentRejectionException rejection) {
                record(run, AuditEvent.Kind.REFUSED, rejection.error().code());
                return ExecutedStep.failed(run.step(), rejection.error());
            } catch (VerificationFailure failure) {
                log.error("Step {} ({}) did not do what it declares, so it was rolled back: {}", run.step().number(),
                        run.step().metadata().id(), failure.getMessage());
                record(run, AuditEvent.Kind.FAILED, AgentErrorCodes.VERIFICATION_FAILED);
                return ExecutedStep.failed(run.step(),
                        StepRejections.verificationFailed(run.step().number(), run.step().metadata().id()));
            } catch (RuntimeException e) {
                if (retryable(e)) {
                    if (attempt < properties.maxAttempts()) {
                        log.info("Step {} met a concurrent change; trying again ({} of {})", run.step().number(),
                                attempt + 1, properties.maxAttempts());
                        continue;
                    }
                    record(run, AuditEvent.Kind.REFUSED, AgentErrorCodes.CONFLICT);
                    return ExecutedStep.failed(run.step(), StepRejections.conflict(run.step().number()).error());
                }
                log.error("Step {} ({}) failed while running, and was rolled back", run.step().number(),
                        run.step().metadata().id(), e);
                record(run, AuditEvent.Kind.FAILED, AgentErrorCodes.EXECUTION_FAILED);
                return ExecutedStep.failed(run.step(), StepRejections.executionFailed(run.step().number()));
            }
        }
    }

    private ExecutedStep runOnce(StepRun run, List<ExecutedStep> earlier) {
        PreparedStep step = run.step();
        if (step.writes()) {
            Optional<String> recorded = audit.succeededWrite(run.key());
            if (recorded.isPresent()) {
                ExecutedStep replayed = responses.recordedStep(recorded.get()).replayed();
                audit.append(event(run, AuditEvent.Kind.REPLAYED, replayed.count(), null, null));
                return replayed;
            }
        }

        passes.resolveConfirmed(step, run.confirmed().resolvedIds(), run.user());
        for (Map.Entry<String, ParamValue> input : step.fromEarlierSteps().entrySet()) {
            ExecutedStep source = earlier.get(input.getValue().fromStep() - 1);
            Object value = responses.properties(source.data()).get(input.getValue().field());
            if (value == null) {
                throw new IllegalStateException("Step " + source.step() + " published no '" + input.getValue().field() + "'");
            }
            passes.bindEarlierValue(step, input.getKey(), value);
        }
        Object request = passes.request(step);
        passes.check(step, run.user());

        Long counted = null;
        if (step.writes()) {
            CountResult now = passes.count(step, run.user());
            counted = now.count();
            Long confirmed = run.confirmed().count();
            if (confirmed != null && properties.deltaRule().movedMaterially(confirmed, counted)) {
                throw StepRejections.countChanged(step.number(), confirmed, counted, now.unit());
            }
        }

        audit.append(event(run, AuditEvent.Kind.STARTED, counted, null, null));
        Object response = handlers.invoke(step.capability(), request, run.user());
        Map<String, Object> published = responses.properties(response);
        Long count = verify(step, published, counted);

        Map<String, Object> facts = new LinkedHashMap<>(step.values());
        facts.putAll(step.labels());
        published.forEach((name, value) -> {
            if (value != null) {
                facts.put(name, value);
            }
        });
        if (count != null) {
            facts.put("count", count);
        }
        String reply = passes.render(step, step.metadata().effect().replyTemplate(), facts);

        ExecutedStep result = ExecutedStep.succeeded(step, reply, count, response);
        audit.append(event(run, AuditEvent.Kind.SUCCEEDED, count, null, step.writes() ? responses.json(result) : null));
        return result;
    }

    /**
     * Every declared fact is published, and a write touched exactly what was counted: the confirmed and re-counted
     * number for a capability with a count, one record for any other write.
     *
     * @return the verified count; for a read, the count it reports, if any
     */
    private Long verify(PreparedStep step, Map<String, Object> published, Long counted) {
        for (String fact : step.metadata().effect().facts()) {
            if (published.get(fact) == null) {
                throw new VerificationFailure(step.metadata().id() + " published no '" + fact + "'");
            }
        }
        Long reported = published.get("count") instanceof Number number ? number.longValue() : null;
        if (!step.writes()) {
            return reported;
        }
        if (passes.hasCount(step)) {
            if (reported == null || !reported.equals(counted)) {
                throw new VerificationFailure(step.metadata().id() + " was counted at " + counted
                        + " inside its transaction, but its handler reports " + reported);
            }
            return counted;
        }
        if (reported != null && reported != 1L) {
            throw new VerificationFailure(step.metadata().id() + " writes one record, but its handler reports " + reported);
        }
        return 1L;
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConcurrencyFailureException || cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static ExecuteOutcome outcome(List<ExecutedStep> results) {
        if (results.stream().allMatch(ExecutedStep::done)) {
            return ExecuteOutcome.COMPLETED;
        }
        return results.getFirst().done() ? ExecuteOutcome.PARTIAL : ExecuteOutcome.FAILED;
    }

    // --- Audit ---------------------------------------------------------------------------------------------------

    /** A refusal or failure, recorded after the step's own transaction rolled back, so it stays. */
    private void record(StepRun run, AuditEvent.Kind kind, String code) {
        appendAlone(event(run, kind, null, code, null));
    }

    /** The step's values go in as text, ids rather than names; the labels say what the ids were shown as. */
    private AuditEvent event(StepRun run, AuditEvent.Kind kind, Long count, String errorCode, String result) {
        PreparedStep step = run.step();
        Map<String, String> params = new LinkedHashMap<>();
        step.values().forEach((name, value) -> params.put(name, responses.text(value)));
        return new AuditEvent(run.key(), run.plan().sessionId(), run.plan().planId(), step.number(), step.metadata().id(),
                step.metadata().version(), step.writes(), run.user().userId(), run.sentence(), kind, params, step.labels(),
                run.confirmed().count(), count, errorCode, result);
    }

    /** A plan refused before any step ran: one event per step it names, when it is identifiable at all. */
    private void recordPlanRefusal(Plan plan, String sentence, UserContext user, String code) {
        if (plan.steps() == null || plan.planId() == null || plan.sessionId() == null
                || !PlanReader.PLAIN_ID.matcher(plan.planId()).matches() || !PlanReader.PLAIN_ID.matcher(plan.sessionId()).matches()) {
            return;
        }
        for (PlanStep planned : plan.steps()) {
            if (planned == null || planned.step() == null || planned.capabilityId() == null) {
                continue;
            }
            boolean writes = registry.find(planned.capabilityId()).map(found -> !found.metadata().readOnly()).orElse(false);
            appendAlone(new AuditEvent(key(plan, planned.step()), plan.sessionId(), plan.planId(), planned.step(),
                    planned.capabilityId(), String.valueOf(planned.capabilityVersion()), writes, user.userId(), sentence,
                    AuditEvent.Kind.REFUSED, Map.of(), Map.of(), null, null, code, null));
        }
    }

    private void appendAlone(AuditEvent event) {
        try {
            auditTransactions.executeWithoutResult(status -> audit.append(event));
        } catch (RuntimeException e) {
            log.error("Could not record a {} audit event for step {} of plan {}", event.kind(), event.step(), event.planId(), e);
        }
    }

    private static String key(Plan plan, int step) {
        return plan.sessionId() + ":" + plan.planId() + ":" + step;
    }

    /** Everything one step's run needs to know. */
    private record StepRun(Plan plan, String sentence, PreparedStep step, PreflightToken.Step confirmed, UserContext user) {

        String key() {
            return ExecuteService.key(plan, step.number());
        }
    }
}
