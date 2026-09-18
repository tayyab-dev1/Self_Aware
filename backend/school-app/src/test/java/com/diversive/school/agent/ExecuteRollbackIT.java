package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.execute.ExecuteRequest;
import com.diversive.agent.execute.ExecuteResponse;
import com.diversive.agent.execute.ExecuteService;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.preflight.PreflightService;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.SchoolApplication;
import com.diversive.school.support.SchoolPostgresContainer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Execute verifies a handler's result against what its capability declares, inside the step's transaction. A
 * handler that writes reminders but reports a different number than was counted and confirmed is rolled back: its
 * rows disappear, and the audit trail keeps only the failure. Uses a deliberately broken capability, so it boots its
 * own backend.
 */
class ExecuteRollbackIT {

    @Test
    void aHandlerThatDoesNotDoWhatItDeclaresIsRolledBackAndRecordedAsFailed() {
        try (ConfigurableApplicationContext backend = startBackendWithMiscountingReminders()) {
            JdbcClient jdbc = backend.getBean(JdbcClient.class);
            CapabilityRegistry registry = backend.getBean(CapabilityRegistry.class);
            long sectionId = jdbc.sql("SELECT s.id FROM sections s JOIN classes c ON c.id = s.class_id "
                    + "WHERE c.name = 'Class 5' AND s.name = 'Blue'").query(Long.class).single();
            UserContext user = new UserContext("1", Set.of("accounts_officer"), Map.of("branch_id", "1"));
            Plan plan = new Plan("plan-rollback", "session-rollback", List.of(new PlanStep(1, MiscountingReminders.CAPABILITY,
                    registry.find(MiscountingReminders.CAPABILITY).orElseThrow().metadata().version(),
                    Map.of("section_id", new ParamValue(null, "class 5 blue", null, null, null)))));

            String token = backend.getBean(PreflightService.class).preflight(plan, user).token();
            ExecuteResponse response = backend.getBean(ExecuteService.class)
                    .execute(new ExecuteRequest(plan, token, "remind class 5 blue again"), user);

            assertThat(response.steps().getFirst().error().code()).isEqualTo("VERIFICATION_FAILED");
            assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders WHERE section_id = :id").param("id", sectionId)
                    .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT kind FROM agent_audit WHERE plan_id = 'plan-rollback' ORDER BY id")
                    .query(String.class).list()).containsExactly("FAILED");
        }
    }

    private static ConfigurableApplicationContext startBackendWithMiscountingReminders() {
        SpringApplication backend = new SpringApplication(SchoolApplication.class);
        backend.addInitializers(context -> {
            GenericApplicationContext generic = (GenericApplicationContext) context;
            generic.registerBean(MiscountingReminders.class);
            generic.registerBean("miscountingRemindersCount", AffectedCount.class, () -> AffectedCount.of(
                    MiscountingReminders.CAPABILITY, (params, user) -> new CountResult(5, "guardians", Map.of())));
        });
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off"));
        SchoolPostgresContainer.applicationProperties().forEach((name, value) -> args.add("--" + name + "=" + value));
        return backend.run(args.toArray(String[]::new));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetryRequest(@NotNull Long sectionId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetryResult(long count) {
    }

    /** Writes a reminder row for every guardian, then claims it wrote 99. */
    static class MiscountingReminders {

        static final String CAPABILITY = "fee.reminder.retry";

        private final JdbcClient jdbc;

        MiscountingReminders(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @AgentCapability(id = CAPABILITY, module = "fee", readOnly = false, blastRadius = BlastRadius.GROUP,
                description = "Sends the same section's fee reminders again.")
        @AgentParam(name = "section_id", meaning = "The section", resolver = "section", label = "section_name")
        @AgentEffect(confirmationTemplate = "Remind {count} guardians in {section_name} again.",
                replyTemplate = "Reminded {count} guardians again.")
        @PostMapping("/test/reminders/retry")
        public RetryResult retry(@RequestBody RetryRequest request, UserContext user) {
            jdbc.sql("""
                    INSERT INTO fee_reminders (branch_id, section_id, guardian_id, channel, contact, students, amount_outstanding,
                                               oldest_due_on, sent_by)
                    SELECT 1, :sectionId, g.id, 'SMS', '0300', 1, 1, DATE '2026-09-10', 1
                    FROM guardians g ORDER BY g.id LIMIT 5""").param("sectionId", request.sectionId()).update();
            return new RetryResult(99);
        }
    }
}
