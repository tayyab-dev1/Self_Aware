package com.diversive.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanHasherTest {

    private static Plan plan(Map<String, ParamValue> params) {
        return new Plan("plan-1", "session-1", List.of(new PlanStep(1, "fee.reminder.send", "v1", params)));
    }

    private static Map<String, ParamValue> params(String sectionWords, String channel) {
        Map<String, ParamValue> params = new LinkedHashMap<>();
        params.put("section_id", new ParamValue(null, sectionWords, null, null, null, null));
        params.put("channel", new ParamValue(channel, null, null, null, null, null));
        return params;
    }

    @Test
    void theHashIsSha256OfTheCanonicalJsonAnyoneCanRecompute() throws Exception {
        String canonical = "{\"plan_id\":\"plan-1\",\"session_id\":\"session-1\",\"steps\":[{\"capability_id\":\"fee.reminder.send\","
                + "\"capability_version\":\"v1\",\"params\":{\"channel\":{\"value\":\"whatsapp\"},"
                + "\"section_id\":{\"raw\":\"class 5 blue\"}},\"step\":1}]}";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(PlanHasher.hash(plan(params("class 5 blue", "whatsapp")))).isEqualTo(expected);
    }

    @Test
    void theOrderParametersArriveInDoesNotMatter() {
        Map<String, ParamValue> reversed = new LinkedHashMap<>();
        reversed.put("channel", new ParamValue("whatsapp", null, null, null, null, null));
        reversed.put("section_id", new ParamValue(null, "class 5 blue", null, null, null, null));

        assertThat(PlanHasher.hash(plan(reversed))).isEqualTo(PlanHasher.hash(plan(params("class 5 blue", "whatsapp"))));
    }

    @Test
    void changingOneCharacterChangesTheHash() {
        assertThat(PlanHasher.hash(plan(params("class 5 blue", "whatsapp"))))
                .isNotEqualTo(PlanHasher.hash(plan(params("class 6 blue", "whatsapp"))))
                .isNotEqualTo(PlanHasher.hash(plan(params("class 5 blue", "whatsApp"))));
    }
}
