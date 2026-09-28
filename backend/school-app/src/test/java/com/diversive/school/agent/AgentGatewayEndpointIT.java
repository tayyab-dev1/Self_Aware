package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.school.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 1: the running backend registers the fee capabilities and serves them through the gateway. */
class AgentGatewayEndpointIT extends PostgresIntegrationTest {

    /** The POC capability set (README decision 1), sorted by id as the registry serves it. */
    static final List<String> CAPABILITY_IDS = List.of(
            "dashboard.main.read",
            "fee.cancellation.raise",
            "fee.credit.raise",
            "fee.latefee.waive",
            "fee.overdue.list",
            "fee.payment.record",
            "fee.reminder.send",
            "fee.writeoff.propose");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapabilityRegistry registry;

    @Test
    void registersEveryAnnotatedCapability() {
        assertThat(registry.ids()).containsExactlyElementsOf(CAPABILITY_IDS);
    }

    @Test
    void servesEveryCapabilityAsTheAiLayerReadsIt() throws Exception {
        String body = mockMvc.perform(get("/agent/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities[*].id", contains(CAPABILITY_IDS.toArray())))
                .andExpect(jsonPath("$.capabilities[*].version", everyItem(matchesPattern("[0-9a-f]{64}"))))
                .andReturn().getResponse().getContentAsString();

        JsonNode reminder = capability(body, "fee.reminder.send");
        assertThat(reminder.get("read_only").asBoolean()).isFalse();
        assertThat(reminder.get("module").asText()).isEqualTo("fee");
        assertThat(reminder.get("disambiguate_from").toString()).isEqualTo("[\"fee.overdue.list\"]");
        assertThat(reminder.get("params").toString()).isEqualTo("""
                [{"name":"section_id","type":"integer","required":true,\
                "meaning":"The section whose families with overdue fees are reminded, e.g. Class 5 Blue",\
                "resolver":"section",\
                "lookup":"the class and section together, e.g. class 5 blue, or only the section, e.g. blue","lookup_fields":[{"name":"section","meaning":"the section's name on its own, e.g. blue","identifies":true},{"name":"class","meaning":"the class the section is in, e.g. class 5","identifies":false}],"allowed":[]},\
                {"name":"channel","type":"string","required":true,\
                "meaning":"How the reminder is delivered","filled_by":"message_channel",\
                "allowed":["whatsapp","sms","email"],"default_value":"whatsapp"}]""");
        assertThat(reminder.get("effect").toString())
                .isEqualTo("{\"facts\":[\"guardians\",\"total_outstanding\"]}");

        JsonNode payment = capability(body, "fee.payment.record");
        assertThat(payment.get("params").valueStream().map(param -> param.get("name").asText()).toList())
                .containsExactly("invoice_id", "route", "amount_received", "payment_date", "bank_stamp_date",
                        "remarks");
        assertThat(payment.get("params").get(0).get("lookup_fields").valueStream()
                .map(field -> field.get("name").asText()).toList())
                .containsExactly("student_name", "invoice_no", "month", "year", "class", "section");
        assertThat(payment.get("params").findValuesAsText("type")).containsExactly(
                "integer", "string", "decimal", "date", "date", "string");
    }

    /** What only this side uses is kept here, not sent: the AI layer reads none of it. */
    @Test
    void keepsWhatTheAiLayerDoesNotReadOffTheWire() throws Exception {
        String body = mockMvc.perform(get("/agent/metadata")).andReturn().getResponse().getContentAsString();
        JsonNode reminder = capability(body, "fee.reminder.send");

        assertThat(reminder.has("blast_radius")).isFalse();
        assertThat(reminder.has("preconditions")).isFalse();
        assertThat(capability(body, "fee.payment.record").has("reverses")).isFalse();
        assertThat(reminder.get("effect").has("confirmation_template")).isFalse();
        assertThat(reminder.get("params").get(0).has("multiple")).isFalse();
        assertThat(reminder.get("params").get(0).has("label")).isFalse();

        // All of it is still here, and still hashed into the version the AI layer is sent.
        var kept = registry.find("fee.reminder.send").orElseThrow().metadata();
        assertThat(kept.blastRadius().wireValue()).isEqualTo("group");
        assertThat(kept.preconditions().stream().map(precondition -> precondition.id()).toList())
                .containsExactly("section_has_defaulters", "channel_reaches_defaulters");
        assertThat(kept.effect().confirmationTemplate()).isEqualTo(
                "Send a fee reminder to {guardians} in {section_name} by {channel}, covering {total_outstanding} outstanding.");
        assertThat(kept.params().get(0).label()).isEqualTo("section_name");
        assertThat(registry.find("fee.payment.record").orElseThrow().metadata().reverses())
                .isEqualTo("fee.payment.correction.raise");
    }

    @Test
    void neverPublishesAnEndpointAddress() throws Exception {
        String body = mockMvc.perform(get("/agent/metadata")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("/api/").doesNotContain("Controller");
    }

    @Test
    void versionsMatchTheFullMetadata() throws Exception {
        mockMvc.perform(get("/agent/metadata/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[*].id", contains(CAPABILITY_IDS.toArray())))
                .andExpect(jsonPath("$.versions[0].version").value(registry.versions().get(0).version()));
    }

    @Test
    void theSignedInUserMayUseEveryCapability() throws Exception {
        mockMvc.perform(get("/agent/session/capabilities").header(HttpHeaders.AUTHORIZATION, "Bearer " + DEV_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("1"))
                .andExpect(jsonPath("$.capability_ids", contains(CAPABILITY_IDS.toArray())));
    }

    @Test
    void sessionCapabilitiesRefuseAMissingOrWrongToken() throws Exception {
        mockMvc.perform(get("/agent/session/capabilities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/agent/session/capabilities").header(HttpHeaders.AUTHORIZATION, "Bearer not-the-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsHealthyWhenTheDatabaseIsReachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void rejectsUnknownJsonFields() {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    private JsonNode capability(String metadataBody, String id) throws Exception {
        for (JsonNode capability : objectMapper.readTree(metadataBody).get("capabilities")) {
            if (capability.get("id").asText().equals(id)) {
                return capability;
            }
        }
        throw new AssertionError("No capability " + id + " in " + metadataBody);
    }
}
