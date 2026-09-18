package com.diversive.agent.preflight;

import com.diversive.agent.metadata.EffectMetadata;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.step.PlanReader;
import com.diversive.agent.step.PreparedStep;
import com.diversive.agent.step.StepPasses;
import com.diversive.agent.token.PreflightToken;
import com.diversive.agent.token.PreflightTokens;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Preflight: everything that must be known and true before a person is asked to approve a plan. No AI.
 *
 * <p>After {@link PlanReader} has read the plan against the registry, the passes run in order, because
 * each needs the one before it:
 *
 * <ol>
 *   <li><b>Resolve</b> every name into one record the user may see ({@code NOT_FOUND}, {@code AMBIGUOUS_ENTITY}).</li>
 *   <li><b>Validate</b> each whole request, for rules across fields ({@code INVALID_PLAN}).</li>
 *   <li><b>Check</b> every precondition, now that ids exist (invariant 5; {@code PRECONDITION_FAILED}).</li>
 *   <li><b>Count</b> what each write touches, through the host's count bean, or 1.</li>
 *   <li><b>Compose</b> the confirmation from each capability's template and real values (invariant 3).</li>
 * </ol>
 *
 * <p>The database passes run in one read-only snapshot: the count sees the same state the checks passed
 * on. Nothing is stored. The result carries a signed token for execute.
 */
public class PreflightService {

    private static final Logger log = LoggerFactory.getLogger(PreflightService.class);

    private final PlanReader reader;
    private final StepPasses passes;
    private final PreflightTokens tokens;
    private final TransactionOperations snapshot;

    /**
     * @param snapshot runs the database passes together, e.g. a read-only repeatable-read transaction;
     *                 {@link TransactionOperations#withoutTransaction()} when there is no database
     */
    public PreflightService(PlanReader reader, StepPasses passes, PreflightTokens tokens, TransactionOperations snapshot) {
        this.reader = reader;
        this.passes = passes;
        this.tokens = tokens;
        this.snapshot = snapshot;
    }

    /**
     * @throws com.diversive.agent.error.AgentRejectionException with the error code, when the plan cannot be confirmed
     */
    public PreflightResponse preflight(Plan plan, UserContext user) {
        List<PreparedStep> steps = reader.read(plan, user);
        snapshot.executeWithoutResult(status -> {
            passes.resolve(steps, user);
            for (PreparedStep step : steps) {
                if (!step.pending()) {
                    passes.request(step);
                }
            }
            for (PreparedStep step : steps) {
                if (!step.pending()) {
                    passes.check(step, user);
                }
            }
            for (PreparedStep step : steps) {
                if (step.writes() && !step.pending()) {
                    passes.count(step, user);
                }
            }
        });
        steps.forEach(this::compose);

        ConfirmationComposer.Confirmation confirmation = ConfirmationComposer.compose(steps);
        PreflightTokens.Issued issued = tokens.issue(plan, user, steps.stream()
                .map(step -> new PreflightToken.Step(step.number(), step.resolvedIds(), countShown(step), step.pending()))
                .toList());
        log.info("Preflight passed for {} step(s): {}", steps.size(),
                steps.stream().map(step -> step.metadata().id()).toList());

        return new PreflightResponse(plan.planId(), confirmation.text() != null, confirmation.text(),
                confirmation.warnings(), results(steps), issued.token(), issued.expiresAt());
    }

    private void compose(PreparedStep step) {
        if (!step.writes()) {
            return;
        }
        EffectMetadata effect = step.metadata().effect();
        step.line(step.pending() ? effect.pendingTemplate() : passes.render(step, effect.confirmationTemplate(), step.facts()));
    }

    private static Long countShown(PreparedStep step) {
        return step.count() == null ? null : step.count().count();
    }

    private static List<PreflightStepResult> results(List<PreparedStep> steps) {
        return steps.stream().map(step -> {
            CountResult count = step.count();
            List<ResolvedEntity> resolved = step.resolvedEntities().stream()
                    .map(entity -> new ResolvedEntity(entity.param(), entity.id(), entity.label()))
                    .toList();
            return new PreflightStepResult(step.number(), step.metadata().id(), step.pending(), resolved,
                    count == null ? null : count.count(), count == null ? null : count.unit(), step.line());
        }).toList();
    }
}
