package com.diversive.agent.execute;

import static com.diversive.agent.fixtures.PreflightFixtures.EDITOR;

import com.diversive.agent.fixtures.MemoryAuditTrail;
import com.diversive.agent.fixtures.NotesStore;
import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.preflight.PreflightService;
import com.diversive.agent.preflight.PreflightTestSupport;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.step.StepPasses;
import com.diversive.agent.token.PreflightTokens;
import java.util.Map;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A preflight and an execute over one {@link NotesStore}, with an audit trail in memory: confirm a plan, change
 * the store as the world would, then execute. No database, so nothing here proves a rollback; the school-app
 * integration tests do.
 */
final class ExecuteTestSupport {

    static final String SENTENCE = "share my recipes folder by sms";

    final NotesStore store = new NotesStore();
    final MemoryAuditTrail audit = new MemoryAuditTrail();
    final Map<Class<?>, Object> handlers = new java.util.HashMap<>(PreflightFixtures.handlerInstances(store));
    final PreflightService preflight;
    ExecuteService execute;

    ExecuteTestSupport() {
        preflight = new PreflightService(PreflightTestSupport.reader(), passes(), PreflightTestSupport.tokens(),
                TransactionOperations.withoutTransaction());
        execute = execute(PreflightTestSupport.REGISTRY, PreflightTestSupport.tokens());
    }

    StepPasses passes() {
        return PreflightTestSupport.passes(PreflightFixtures.checks(store), PreflightFixtures.counts(store),
                PreflightFixtures.resolvers(store));
    }

    ExecuteService execute(CapabilityRegistry registry, PreflightTokens tokens) {
        return new ExecuteService(registry, PreflightTestSupport.reader(registry), passes(), tokens, audit,
                new CapabilityHandlers(handlers::get), PreflightTestSupport.MAPPER, TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), new ExecuteProperties(20, 5, 3, 2000));
    }

    String confirm(Plan plan) {
        return preflight.preflight(plan, EDITOR).token();
    }

    ExecuteResponse run(Plan plan, String token) {
        return execute.execute(new ExecuteRequest(plan, token, SENTENCE), EDITOR);
    }
}
