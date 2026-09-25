package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.plan.Plan;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.token.PreflightToken;
import com.diversive.agent.token.PreflightTokens;
import com.diversive.agent.token.TokenVerificationException;
import com.diversive.school.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2 "done when", against the real backend and the seeded school (V5__seed_demo_school.sql):
 * names resolve to ids and labels, ambiguity and failing preconditions come back with what the user needs,
 * the confirmation carries the real count, and the token binds the plan.
 */
class PreflightIT extends PostgresIntegrationTest {

    /** Class 5 Blue by WhatsApp: 8 students owe, 7 guardians, 5 of them on WhatsApp, owing PKR 71,500. */
    static final String CLASS_5_BLUE_WHATSAPP =
            "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapabilityRegistry registry;

    @Autowired
    private PreflightTokens tokens;

    @Autowired
    private JdbcClient jdbc;

    // --- Resolve --------------------------------------------------------------------------------------

    @Test
    void anUnambiguousNameResolvesToItsIdAndTheLabelTheConfirmationUses() throws Exception {
        preflight(plan(reminder(raw("class 5 blue"), "whatsapp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].param").value("section_id"))
                .andExpect(jsonPath("$.steps[0].resolved[0].id").value(String.valueOf(sectionId("Class 5", "Blue"))))
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Class 5 Blue"))
                .andExpect(jsonPath("$.confirmation").value(CLASS_5_BLUE_WHATSAPP));
    }

    @Test
    void anAmbiguousNameReturnsAmbiguousEntityWithCandidates() throws Exception {
        preflight(plan(reminder(raw("class 5"), "whatsapp")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_ENTITY"))
                .andExpect(jsonPath("$.param").value("section_id"))
                .andExpect(jsonPath("$.candidates[*].label", contains("Class 5 Blue", "Class 5 Green")))
                .andExpect(jsonPath("$.candidates[*].context", contains("12 students", "10 students")));

    }

    /** Class 6 Blue has no defaulters, so "blue" can only mean Class 5 Blue for a reminder: nothing to ask. */
    @Test
    void aRecordTheStepCouldNotActOnIsNotOfferedAndOneLeftIsTheRecord() throws Exception {
        preflight(plan(reminder(raw("blue"), "whatsapp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Class 5 Blue"))
                .andExpect(jsonPath("$.confirmation").value(CLASS_5_BLUE_WHATSAPP));

        // August is paid in full, so a payment against "ahmed raza" can only be for September.
        preflight(plan(payment(raw("ahmed raza"), "5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label")
                        .value("Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031"));
    }

    /**
     * More than September's balance fits neither invoice. August is not even open, so September gets further
     * through the rules: it is the record, and the refusal says the amount is too much instead of asking.
     */
    @Test
    void whenNoMatchPassesEveryRuleTheOneThatGetsFurthestIsRefusedWithItsHint() throws Exception {
        preflight(plan(payment(raw("ahmed raza"), "9500")))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("amount_within_balance"))
                .andExpect(jsonPath("$.hint").value("That is more than is outstanding on this invoice"));
    }

    /** Both of Omar Farooq's invoices are open, so a payment against either is possible: that is a real question. */
    @Test
    void matchesTheStepCouldActOnEquallyAreStillAskedAbout() throws Exception {
        preflight(plan(payment(raw("omar farooq"), "5000")))
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_ENTITY"))
                .andExpect(jsonPath("$.candidates[*].context", contains("PKR 6,500 outstanding", "PKR 6,500 outstanding")));
    }

    @Test
    void theUsersChoiceGoesBackWithTheSameWordsAndResolves() throws Exception {
        Map<String, Object> chosen = Map.of("raw", "class 5", "chosen_id", String.valueOf(sectionId("Class 5", "Blue")));

        preflight(plan(reminder(chosen, "whatsapp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation").value(CLASS_5_BLUE_WHATSAPP));
    }

    @Test
    void aNameMatchingNothingIsNotFound() throws Exception {
        preflight(plan(reminder(raw("class 9 red"), "whatsapp")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.param").value("section_id"));
    }

    @Test
    void everyEntityTypeInTheFeeCapabilitiesResolves() throws Exception {
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("class"), "class_id", raw("class 5")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Class 5"));
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("student"), "student_id", raw("2026-0512")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Omar Farooq"));
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("section"), "section_id", raw("class 5 ka blue section")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Class 5 Blue"));
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("student"), "student_id", raw("ahmed")))))
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_ENTITY"))
                .andExpect(jsonPath("$.candidates[*].label", contains("Ahmed Ali", "Ahmed Raza")))
                .andExpect(jsonPath("$.candidates[*].context",
                        contains("Class 5 Green, admission no. 2026-0521", "Class 5 Blue, admission no. 2026-0501")));
    }

    /** People add the class and section to a name; that narrows the search instead of finding nothing. */
    @Test
    void aNameWithTheStudentsClassAndSectionIsFoundAndNarrowed() throws Exception {
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("student"), "student_id", raw("ahmed class 5 blue")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Ahmed Raza"));
        preflight(plan(payment(raw("Ahmed Raza Class 5 Blue fees"), "2000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label")
                        .value("Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031"));

        // The wrong section finds nothing, and words that name no student are not a lookup at all.
        preflight(plan(payment(raw("Ahmed Raza class 6 blue"), "2000")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        preflight(plan(payment(raw("class 5 blue fees"), "2000")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("student"), "student_id", raw("class 5 blue")))))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * The plan may name each part of a lookup instead of one line of words, and then nothing has to be
     * guessed: the name matches the name, the class and section match the placement, the month the period.
     */
    @Test
    @Transactional
    void aLookupMadeOfNamedPartsFindsTheRecordWithoutTakingWordsApart() throws Exception {
        preflight(plan(payment(parts(Map.of("student_name", "Ahmed Raza", "class", "Class 5", "section", "Blue",
                "month", "September")), "2000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label")
                        .value("Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031"));

        // The invoice number alone is enough, and words that belong to no part never reach the search.
        preflight(plan(payment(parts(Map.of("invoice_no", "INV/LHR/26-27/000031")), "2000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].resolved[0].label")
                        .value("Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031"));

        // A part in the wrong place finds nothing, rather than matching by accident.
        preflight(plan(payment(parts(Map.of("student_name", "Ahmed Raza", "section", "Green")), "2000")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** A part the resolver never declared, or one that names no record, is the plan's mistake. */
    @Test
    @Transactional
    void aLookupWithAnUnknownPartOrWithNothingThatNamesARecordIsRefused() throws Exception {
        preflight(plan(payment(parts(Map.of("student_name", "Ahmed Raza", "colour", "blue")), "2000")))
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"))
                .andExpect(jsonPath("$.message").value(containsString("no lookup part \"colour\"")));

        preflight(plan(payment(parts(Map.of("month", "September")), "2000")))
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"))
                .andExpect(jsonPath("$.message").value(containsString("names no record")));

        Map<String, Object> blankMonth = Map.of("raw", "Ahmed Raza September",
                "lookup", Map.of("student_name", "Ahmed Raza", "month", " "));
        preflight(plan(payment(blankMonth, "2000")))
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"))
                .andExpect(jsonPath("$.message").value(containsString("is empty")));
    }

    /** Invariant 7: another branch's records are simply not there, so "not yours" looks like "no such record". */
    @Test
    @Transactional
    void anotherBranchsRecordsCannotBeFoundOrChosen() throws Exception {
        jdbc.sql("""
                WITH branch AS (INSERT INTO branches (code, name) VALUES ('KHI', 'Karachi Campus') RETURNING id),
                     session AS (INSERT INTO academic_sessions (branch_id, name, starts_on, ends_on, is_open)
                                 SELECT id, '2026-27', DATE '2026-08-01', DATE '2027-06-30', true FROM branch RETURNING id),
                     class AS (INSERT INTO classes (session_id, name, display_order) SELECT id, 'Class 7', 7 FROM session RETURNING id)
                INSERT INTO sections (class_id, name, capacity) SELECT id, 'Blue', 30 FROM class""").update();
        long karachiSection = jdbc.sql("SELECT s.id FROM sections s JOIN classes c ON c.id = s.class_id WHERE c.name = 'Class 7'")
                .query(Long.class).single();

        preflight(plan(reminder(raw("class 7 blue"), "whatsapp")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // A list has no preconditions, so every Blue section the user may see is offered, and Karachi's is not.
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("section"), "section_id", raw("blue")))))
                .andExpect(jsonPath("$.candidates[*].label", contains("Class 5 Blue", "Class 6 Blue")));
        preflight(plan(reminder(Map.of("raw", "blue", "chosen_id", String.valueOf(karachiSection)), "whatsapp")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- Check ------------------------------------------------------------------------------------------

    @Test
    void aFailingPreconditionReturnsPreconditionFailedWithItsHint() throws Exception {
        preflight(plan(reminder(raw("class 6 blue"), "whatsapp")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("section_has_defaulters"))
                .andExpect(jsonPath("$.hint").value("Nobody in this section has overdue fees right now"));
    }

    @Test
    void aChannelThatReachesNoDefaulterIsRefusedRatherThanConfirmedForNobody() throws Exception {
        preflight(plan(reminder(raw("class 5 green"), "email")))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("channel_reaches_defaulters"))
                .andExpect(jsonPath("$.hint").value("No guardian with overdue fees in this section can be reached by that channel"));
    }

    @Test
    void paymentPreconditionsReadTheInvoicesBalance() throws Exception {
        preflight(plan(payment(raw("Ahmed Raza's September invoice"), "9500")))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("amount_within_balance"))
                .andExpect(jsonPath("$.hint").value("That is more than is outstanding on this invoice"));
        preflight(plan(payment(raw("ayesha khan september"), "500")))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("invoice_is_open"))
                .andExpect(jsonPath("$.hint").value("This invoice has nothing left to pay"));
    }

    // --- Count and compose ------------------------------------------------------------------------------

    @Test
    void theConfirmationCarriesTheRealCountForTheChosenChannel() throws Exception {
        record Expected(String channel, long guardians, String text) {
        }
        List<Expected> expected = List.of(
                new Expected("whatsapp", 5, CLASS_5_BLUE_WHATSAPP),
                new Expected("sms", 6, "Send a fee reminder to 6 guardians in Class 5 Blue by SMS, covering PKR 78,000 outstanding. This cannot be undone."),
                new Expected("email", 1, "Send a fee reminder to 1 guardian in Class 5 Blue by email, covering PKR 6,500 outstanding. This cannot be undone."));

        for (Expected example : expected) {
            preflight(plan(reminder(raw("class 5 blue"), example.channel())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.steps[0].count").value(example.guardians()))
                    .andExpect(jsonPath("$.steps[0].unit").value("guardians"))
                    .andExpect(jsonPath("$.confirmation").value(example.text()));
            assertThat(guardiansReachable("Class 5", "Blue", example.channel()))
                    .as("the count must be what the send can reach, computed here independently")
                    .isEqualTo(example.guardians());
        }
    }

    @Test
    void aSingleRecordWriteReadsNaturallyAndIsNotWarnedAboutWhenItCanBeReversed() throws Exception {
        preflight(plan(payment(raw("Ahmed Raza's September invoice"), "5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].count").value(1))
                .andExpect(jsonPath("$.confirmation").value(
                        "Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice "
                                + "INV/LHR/26-27/000031."))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void twoWritesMakeOneNumberedConfirmation() throws Exception {
        Map<String, Object> reminder = new LinkedHashMap<>(reminder(raw("class 5 blue"), "whatsapp"));
        reminder.put("step", 2);

        preflight(plan(payment(raw("Ahmed Raza's September invoice"), "5000"), reminder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation").value("""
                        1. Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice \
                        INV/LHR/26-27/000031.
                        2. Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding.

                        Step 2 cannot be undone."""));
    }

    @Test
    void readsNeedNoConfirmationButStillGetATokenForExecute() throws Exception {
        preflight(plan(step(1, "fee.overdue.list", Map.of("scope", value("section"), "section_id", raw("class 5 blue")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_confirmation").value(false))
                .andExpect(jsonPath("$.confirmation").doesNotExist())
                .andExpect(jsonPath("$.token").isNotEmpty());
        preflight(plan(step(1, "dashboard.main.read", Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_confirmation").value(false));
    }

    @Test
    void aValueTheEndpointWouldRejectIsAnInvalidPlanBeforeAnyoneConfirmsIt() throws Exception {
        Map<String, Object> futurePayment = new LinkedHashMap<>(payment(raw("Ahmed Raza's September invoice"), "5000"));
        futurePayment.put("params", Map.of("invoice_id", raw("Ahmed Raza's September invoice"), "route", value("cash"),
                "amount_received", value("5000"), "payment_date", value("2099-01-01")));

        preflight(plan(futurePayment))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"))
                .andExpect(jsonPath("$.param").value("payment_date"))
                .andExpect(jsonPath("$.message").value("Step 1, parameter 'payment_date': must be a date in the past or in the present"));
    }

    // --- Refused before anything is looked up ----------------------------------------------------------

    @Test
    void theFourConfusableCorrectionsAreRefusedAsNotImplemented() throws Exception {
        for (String capability : List.of("fee.cancellation.raise", "fee.credit.raise", "fee.writeoff.propose", "fee.latefee.waive")) {
            preflight(plan(step(1, capability, Map.of("invoice_id", raw("Ahmed Raza's September invoice")))))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                    .andExpect(jsonPath("$.message").value("Step 1: " + capability + " is not available yet"));
        }
    }

    @Test
    void aStalePlanOrAMissingUserIsRefused() throws Exception {
        Map<String, Object> stale = new LinkedHashMap<>(reminder(raw("class 5 blue"), "whatsapp"));
        stale.put("capability_version", "0".repeat(64));
        preflight(plan(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        mockMvc.perform(post("/agent/preflight").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("plan", plan(reminder(raw("class 5 blue"), "whatsapp"))))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // --- Token ------------------------------------------------------------------------------------------

    @Test
    void theTokenBindsThePlanSoATamperedHashOrAnEditedPlanFailsVerification() throws Exception {
        Map<String, Object> planJson = plan(reminder(raw("class 5 blue"), "whatsapp"));
        JsonNode response = objectMapper.readTree(preflight(planJson).andReturn().getResponse().getContentAsString());
        String token = response.get("token").asText();
        Plan plan = objectMapper.convertValue(planJson, Plan.class);
        UserContext user = new UserContext("1", Set.of("accounts_officer"), Map.of("branch_id", "1"));

        PreflightToken payload = tokens.verify(token, plan, user);
        assertThat(payload.steps().getFirst().count()).isEqualTo(5L);
        assertThat(payload.steps().getFirst().resolvedIds()).containsEntry("section_id", String.valueOf(sectionId("Class 5", "Blue")));

        Plan edited = objectMapper.convertValue(plan(reminder(raw("class 5 green"), "whatsapp")), Plan.class);
        assertThat(catchThrowableOfType(TokenVerificationException.class, () -> tokens.verify(token, edited, user)).reason())
                .isEqualTo(TokenVerificationException.Reason.PLAN_MISMATCH);

        String[] parts = token.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
        String rehashed = payloadJson.replace(payload.planHash(), "f".repeat(64));
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rehashed.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[1];
        assertThat(catchThrowableOfType(TokenVerificationException.class, () -> tokens.verify(tampered, plan, user)).reason())
                .isEqualTo(TokenVerificationException.Reason.BAD_SIGNATURE);
    }

    // --- Helpers ----------------------------------------------------------------------------------------

    private ResultActions preflight(Map<String, Object> plan) throws Exception {
        return mockMvc.perform(post("/agent/preflight")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + DEV_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("plan", plan))));
    }

    @SafeVarargs
    private static Map<String, Object> plan(Map<String, Object>... steps) {
        return Map.of("plan_id", "plan-it", "session_id", "session-it", "steps", List.of(steps));
    }

    private Map<String, Object> step(int number, String capabilityId, Map<String, Object> params) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", number);
        step.put("capability_id", capabilityId);
        step.put("capability_version", registry.find(capabilityId).orElseThrow().metadata().version());
        step.put("params", params);
        return step;
    }

    private Map<String, Object> reminder(Map<String, Object> section, String channel) {
        return step(1, "fee.reminder.send", Map.of("section_id", section, "channel", value(channel)));
    }

    private Map<String, Object> payment(Map<String, Object> invoice, String amount) {
        return step(1, "fee.payment.record", Map.of("invoice_id", invoice, "route", value("cash"),
                "amount_received", value(amount), "payment_date", value("2026-09-14")));
    }

    private static Map<String, Object> raw(String words) {
        return Map.of("raw", words);
    }

    /** What the planner sends when the resolver declares parts: the words, and what each part of them is. */
    private static Map<String, Object> parts(Map<String, String> parts) {
        return Map.of("raw", String.join(" ", parts.values()), "lookup", parts);
    }

    private static Map<String, Object> value(Object value) {
        return Map.of("value", value);
    }

    private long sectionId(String className, String sectionName) {
        return jdbc.sql("SELECT s.id FROM sections s JOIN classes c ON c.id = s.class_id WHERE c.name = :c AND s.name = :s")
                .param("c", className).param("s", sectionName).query(Long.class).single();
    }

    /** Written here from the tables, not through the repository, so it checks the repository's answer. */
    private long guardiansReachable(String className, String sectionName, String channel) {
        String contact = switch (channel) {
            case "whatsapp" -> "g.whatsapp_number";
            case "sms" -> "g.mobile_number";
            default -> "g.email";
        };
        return jdbc.sql("""
                        SELECT count(DISTINCT g.id)
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        JOIN students st            ON st.id = fi.student_id
                        JOIN guardians g            ON g.id = st.guardian_id
                        JOIN sections s             ON s.id = fi.section_id
                        JOIN classes c              ON c.id = s.class_id
                        WHERE c.name = :className AND s.name = :sectionName
                          AND fi.status = 'Issued' AND b.outstanding_amount > 0 AND fi.due_on < current_date
                          AND %s IS NOT NULL""".formatted(contact))
                .param("className", className)
                .param("sectionName", sectionName)
                .query(Long.class)
                .single();
    }
}
