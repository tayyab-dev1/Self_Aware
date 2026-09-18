package com.diversive.agent.web;

import com.diversive.agent.plan.Plan;
import com.diversive.agent.step.PlanReader;
import org.slf4j.MDC;

/**
 * Tags every log line written while a plan is handled with its {@code plan_id} and {@code session_id}
 * (CLAUDE.md conventions). Ids come from the AI layer, so only a plain id is written, never arbitrary text.
 *
 * <pre>
 * try (PlanLogContext ignored = PlanLogContext.open(plan)) { ... }
 * </pre>
 */
public final class PlanLogContext implements AutoCloseable {

    private static final String PLAN_ID = "plan_id";
    private static final String SESSION_ID = "session_id";

    private PlanLogContext() {
    }

    public static PlanLogContext open(Plan plan) {
        put(PLAN_ID, plan == null ? null : plan.planId());
        put(SESSION_ID, plan == null ? null : plan.sessionId());
        return new PlanLogContext();
    }

    @Override
    public void close() {
        MDC.remove(PLAN_ID);
        MDC.remove(SESSION_ID);
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, PlanReader.PLAIN_ID.matcher(value).matches() ? value : "(not a plain id)");
        }
    }
}
