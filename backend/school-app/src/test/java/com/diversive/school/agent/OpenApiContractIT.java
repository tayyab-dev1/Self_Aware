package com.diversive.school.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.diversive.school.support.CommittedJson;
import com.diversive.school.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed gateway contract ({@code self_aware/openapi/agent-gateway.json}) must match what the
 * backend actually serves. The AI layer generates its Pydantic models from that file, so drift
 * here means the two services disagree about request and response shapes.
 */
class OpenApiContractIT extends PostgresIntegrationTest {

    private static final Path CONTRACT = Path.of(
            System.getProperty("contract.path", "../../openapi/agent-gateway.json"));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void committedContractMatchesTheServedSpec() throws Exception {
        String served = mockMvc.perform(get("/v3/api-docs/agent-gateway"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        CommittedJson.assertMatchesOrUpdate(CONTRACT, served);
    }
}
