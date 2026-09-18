package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.school.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 3 "done when", against the real backend and the seeded school: a confirmed write runs once, is verified and
 * audited; state that changed after the confirmation stops it; a replay does not run it twice; and a count that moved
 * fails. Every test puts the seed back afterwards, so the other integration tests see the school as seeded.
 */
class ExecuteIT extends PostgresIntegrationTest {

    static final String SENTENCE = "class 5 blue ke defaulters ko whatsapp par reminder bhejo";
    static final int SEEDED_PAYMENTS = 46;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapabilityRegistry registry;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void putTheSeedBack() {
        jdbc.sql("DELETE FROM fee_reminders").update();
        jdbc.sql("DELETE FROM fee_payments WHERE id > :seeded").param("seeded", SEEDED_PAYMENTS).update();
        jdbc.sql("UPDATE guardians SET whatsapp_number = NULL WHERE family_code = 'FAM-0104'").update();
        jdbc.sql("UPDATE sections SET active = true").update();
    }

    // --- A write that runs -----------------------------------------------------------------------------------------

    @Test
    void aConfirmedReminderSendsOnceIsVerifiedAndIsAudited() throws Exception {
        Map<String, Object> plan = plan(reminder(1, "class 5 blue", "whatsapp"));

        JsonNode response = execute(plan, confirm(plan));

        JsonNode step = response.get("steps").get(0);
        assertThat(response.get("outcome").asText()).isEqualTo("completed");
        assertThat(step.get("status").asText()).isEqualTo("succeeded");
        assertThat(step.get("reply").asText()).isEqualTo("Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.");
        assertThat(step.get("count").asLong()).isEqualTo(5);
        assertThat(step.get("data").get("not_reached").asInt()).isEqualTo(2);
        assertThat(step.get("data").get("total_outstanding").decimalValue()).isEqualByComparingTo("71500");

        assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders WHERE delivery_status = 'Queued'").query(Long.class).single())
                .isEqualTo(5);
        assertThat(jdbc.sql("SELECT sum(amount_outstanding) FROM fee_reminders").query(BigDecimal.class).single())
                .isEqualByComparingTo("71500");
        assertThat(auditKinds(plan)).containsExactly("STARTED", "SUCCEEDED");
        Map<String, Object> succeeded = jdbc.sql("""
                        SELECT sentence, user_id, confirmed_count, actual_count, params ->> 'section_id' AS section_id,
                               labels ->> 'section_name' AS section_name
                        FROM agent_audit WHERE plan_id = :planId AND kind = 'SUCCEEDED'""")
                .param("planId", plan.get("plan_id")).query().singleRow();
        assertThat(succeeded).containsEntry("sentence", SENTENCE).containsEntry("user_id", "1")
                .containsEntry("confirmed_count", 5L).containsEntry("actual_count", 5L)
                .containsEntry("section_id", String.valueOf(sectionId("Class 5", "Blue")))
                .containsEntry("section_name", "Class 5 Blue");
    }

    @Test
    void replayingTheSameSessionPlanAndStepDoesNotSendAgain() throws Exception {
        Map<String, Object> plan = plan(reminder(1, "class 5 blue", "whatsapp"));
        String token = confirm(plan);

        JsonNode first = execute(plan, token);
        JsonNode second = execute(plan, token);

        assertThat(second.get("steps").get(0).get("status").asText()).isEqualTo("replayed");
        assertThat(second.get("steps").get(0).get("reply")).isEqualTo(first.get("steps").get(0).get("reply"));
        assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders").query(Long.class).single()).isEqualTo(5);
        assertThat(auditKinds(plan)).containsExactly("STARTED", "SUCCEEDED", "REPLAYED");
    }

    @Test
    void aPaymentIsRecordedWithTheNextReceiptNumber() throws Exception {
        Map<String, Object> plan = plan(payment(1, "Ahmed Raza's September invoice", "5000"));

        JsonNode step = execute(plan, confirm(plan)).get("steps").get(0);

        assertThat(step.get("status").asText()).isEqualTo("succeeded");
        assertThat(step.get("reply").asText()).isEqualTo(
                "Recorded PKR 5,000 against Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031. Receipt RCT/LHR/26-27/000047.");
        assertThat(step.get("count").asLong()).isEqualTo(1);
        assertThat(step.get("data").get("invoice_status").asText()).isEqualTo("Partially paid");
        assertThat(step.get("data").get("outstanding_balance").decimalValue()).isEqualByComparingTo("4000");
        assertThat(jdbc.sql("SELECT method FROM fee_payments WHERE receipt_no = 'RCT/LHR/26-27/000047'").query(String.class).single())
                .isEqualTo("Cash");
    }

    // --- What changed between preflight and execute ---------------------------------------------------------------

    @Test
    void aPaymentThatNoLongerFitsTheBalanceFailsItsPreconditionAndWritesNothing() throws Exception {
        Map<String, Object> plan = plan(payment(1, "Ahmed Raza's September invoice", "9000"));
        String token = confirm(plan);
        jdbc.sql("""
                INSERT INTO fee_payments (invoice_id, receipt_no, amount, method, paid_on, recorded_by)
                SELECT id, 'RCT/TEST/000001', 500, 'Cash', DATE '2026-09-14', 1 FROM fee_invoices
                WHERE invoice_no = 'INV/LHR/26-27/000031'""").update();

        JsonNode response = execute(plan, token);

        JsonNode step = response.get("steps").get(0);
        assertThat(response.get("outcome").asText()).isEqualTo("failed");
        assertThat(step.get("error").get("code").asText()).isEqualTo("PRECONDITION_FAILED");
        assertThat(step.get("error").get("precondition").asText()).isEqualTo("amount_within_balance");
        assertThat(jdbc.sql("SELECT count(*) FROM fee_payments WHERE id > :seeded AND receipt_no <> 'RCT/TEST/000001'")
                .param("seeded", SEEDED_PAYMENTS).query(Long.class).single()).isZero();
        assertThat(auditKinds(plan)).containsExactly("REFUSED");
    }

    @Test
    void aReminderWhoseAudienceGrewFailsTheDeltaRuleAndSendsNothing() throws Exception {
        Map<String, Object> plan = plan(reminder(1, "class 5 blue", "whatsapp"));
        String token = confirm(plan);
        jdbc.sql("UPDATE guardians SET whatsapp_number = '03000000104' WHERE family_code = 'FAM-0104'").update();

        JsonNode error = execute(plan, token).get("steps").get(0).get("error");

        assertThat(error.get("code").asText()).isEqualTo("COUNT_CHANGED");
        assertThat(error.get("confirmed_count").asLong()).isEqualTo(5);
        assertThat(error.get("current_count").asLong()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders").query(Long.class).single()).isZero();
    }

    @Test
    void aSectionClosedSinceTheConfirmationIsOutOfScope() throws Exception {
        Map<String, Object> plan = plan(reminder(1, "class 5 blue", "whatsapp"));
        String token = confirm(plan);
        jdbc.sql("UPDATE sections SET active = false WHERE id = :id").param("id", sectionId("Class 5", "Blue")).update();

        JsonNode error = execute(plan, token).get("steps").get(0).get("error");

        assertThat(error.get("code").asText()).isEqualTo("OUT_OF_SCOPE");
        assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders").query(Long.class).single()).isZero();
    }

    @Test
    void aFailedStepStopsThePlanAndThePaymentBeforeItStaysRecorded() throws Exception {
        Map<String, Object> plan = plan(payment(1, "Ahmed Raza's September invoice", "5000"), reminder(2, "class 5 blue", "whatsapp"));
        String token = confirm(plan);
        jdbc.sql("UPDATE guardians SET whatsapp_number = '03000000104' WHERE family_code = 'FAM-0104'").update();

        JsonNode response = execute(plan, token);

        assertThat(response.get("outcome").asText()).isEqualTo("partial");
        assertThat(response.get("steps").findValuesAsText("status")).containsExactly("succeeded", "failed");
        assertThat(jdbc.sql("SELECT count(*) FROM fee_payments WHERE id > :seeded").param("seeded", SEEDED_PAYMENTS)
                .query(Long.class).single()).isEqualTo(1);
    }

    // --- Refused as a whole --------------------------------------------------------------------------------------

    @Test
    void aTokenForAnotherPlanIsRefusedAndTheAttemptIsRecorded() throws Exception {
        Map<String, Object> confirmed = plan(reminder(1, "class 5 blue", "whatsapp"));
        Map<String, Object> other = plan(reminder(1, "class 5 green", "whatsapp"));

        sendExecute(other, confirm(confirmed))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        assertThat(auditKinds(other)).containsExactly("REFUSED");
        assertThat(jdbc.sql("SELECT count(*) FROM fee_reminders").query(Long.class).single()).isZero();
    }

    @Test
    void theAuditTrailCannotBeChangedOrEmptied() throws Exception {
        Map<String, Object> plan = plan(reminder(1, "class 5 blue", "whatsapp"));
        execute(plan, confirm(plan));

        assertThatThrownBy(() -> jdbc.sql("UPDATE agent_audit SET sentence = 'something else'").update())
                .hasMessageContaining("agent_audit is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent_audit").update())
                .hasMessageContaining("agent_audit is append-only");
    }

    // --- Reads, and the handlers as plain endpoints ---------------------------------------------------------------

    @Test
    void readsRunAndAnswerFromTheDataEveryTime() throws Exception {
        Map<String, Object> overdue = plan(step(1, "fee.overdue.list", Map.of("scope", value("section"), "section_id", raw("class 5 blue"))));
        JsonNode list = execute(overdue, confirm(overdue)).get("steps").get(0);
        assertThat(list.get("reply").asText()).isEqualTo("Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.");
        assertThat(list.get("data").get("rows")).hasSize(8);

        Map<String, Object> dashboard = plan(step(1, "dashboard.main.read", Map.of()));
        JsonNode figures = execute(dashboard, confirm(dashboard)).get("steps").get(0);
        assertThat(figures.get("reply").asText()).startsWith("Lahore Campus, as of ").contains("collected today", "still outstanding");
        assertThat(auditKinds(dashboard)).containsExactly("STARTED", "SUCCEEDED");
    }

    @Test
    void theHandlersStillWorkAsPlainEndpointsForASignedInUser() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + DEV_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch_name").value("Lahore Campus"))
                .andExpect(jsonPath("$.calculated_at", startsWith("20")));
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private String confirm(Map<String, Object> plan) throws Exception {
        String body = mockMvc.perform(post("/agent/preflight").header(HttpHeaders.AUTHORIZATION, "Bearer " + DEV_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("plan", plan))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private JsonNode execute(Map<String, Object> plan, String token) throws Exception {
        String body = sendExecute(plan, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions sendExecute(Map<String, Object> plan, String token) throws Exception {
        return mockMvc.perform(post("/agent/execute").header(HttpHeaders.AUTHORIZATION, "Bearer " + DEV_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("plan", plan, "token", token, "sentence", SENTENCE))));
    }

    private List<String> auditKinds(Map<String, Object> plan) {
        return jdbc.sql("SELECT kind FROM agent_audit WHERE plan_id = :planId ORDER BY id")
                .param("planId", plan.get("plan_id")).query(String.class).list();
    }

    /** A plan with fresh ids, so every test has its own idempotency keys. */
    @SafeVarargs
    private static Map<String, Object> plan(Map<String, Object>... steps) {
        return Map.of("plan_id", "plan-" + UUID.randomUUID(), "session_id", "session-execute-it", "steps", List.of(steps));
    }

    private Map<String, Object> step(int number, String capabilityId, Map<String, Object> params) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", number);
        step.put("capability_id", capabilityId);
        step.put("capability_version", registry.find(capabilityId).orElseThrow().metadata().version());
        step.put("params", params);
        return step;
    }

    private Map<String, Object> reminder(int number, String section, String channel) {
        return step(number, "fee.reminder.send", Map.of("section_id", raw(section), "channel", value(channel)));
    }

    private Map<String, Object> payment(int number, String invoice, String amount) {
        return step(number, "fee.payment.record", Map.of("invoice_id", raw(invoice), "route", value("cash"),
                "amount_received", value(amount), "payment_date", value("2026-09-14")));
    }

    private static Map<String, Object> raw(String words) {
        return Map.of("raw", words);
    }

    private static Map<String, Object> value(Object value) {
        return Map.of("value", value);
    }

    private long sectionId(String className, String sectionName) {
        return jdbc.sql("SELECT s.id FROM sections s JOIN classes c ON c.id = s.class_id WHERE c.name = :c AND s.name = :s")
                .param("c", className).param("s", sectionName).query(Long.class).single();
    }
}
