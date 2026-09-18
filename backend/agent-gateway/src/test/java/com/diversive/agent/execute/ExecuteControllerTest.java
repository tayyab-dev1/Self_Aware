package com.diversive.agent.execute;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.fixtures.NotesStore;
import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.preflight.PreflightController;
import com.diversive.agent.preflight.PreflightService;
import com.diversive.agent.preflight.PreflightTestSupport;
import com.diversive.agent.spi.UserContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The wire format of {@code POST /agent/execute}: a plan-wide refusal is an error body; once accepted, it is 200 step by step. */
@WebMvcTest(controllers = {PreflightController.class, ExecuteController.class},
        properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
@Import(ExecuteControllerTest.Beans.class)
class ExecuteControllerTest {

    @TestConfiguration
    static class Beans {

        private final ExecuteTestSupport world = new ExecuteTestSupport();

        @Bean
        PreflightService preflightService() {
            return world.preflight;
        }

        @Bean
        ExecuteService executeService() {
            return world.execute;
        }

        @Bean
        NotesStore notesStore() {
            return world.store;
        }

        @Bean
        UserContextResolver userContextResolver() {
            return request -> Optional.ofNullable(request.getHeader("X-Test-User")).map(ignored -> PreflightFixtures.EDITOR);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotesStore store;

    private static String plan(String planId) {
        String version = PreflightTestSupport.REGISTRY.find("notes.folder.share").orElseThrow().metadata().version();
        return """
                {"plan_id": "%s", "session_id": "session-1", "steps": [
                  {"step": 1, "capability_id": "notes.folder.share", "capability_version": "%s",
                   "params": {"folder_id": {"raw": "recipes"}, "channel": {"value": "sms"}}}]}
                """.formatted(planId, version);
    }

    private ResultActions send(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header("X-Test-User", "editor").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String token(String plan) throws Exception {
        String body = send("/agent/preflight", "{\"plan\": " + plan + "}").andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private static String executeBody(String plan, String token) {
        return "{\"plan\": " + plan + ", \"token\": \"" + token + "\", \"sentence\": \"share my recipes by sms\"}";
    }

    @Test
    void answersStepByStepInSnakeCase() throws Exception {
        String plan = plan("plan-wire");

        send("/agent/execute", executeBody(plan, token(plan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value("plan-wire"))
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.steps[0].capability_id").value("notes.folder.share"))
                .andExpect(jsonPath("$.steps[0].status").value("succeeded"))
                .andExpect(jsonPath("$.steps[0].reply").value("Shared 4 notes from Recipes, 2048 bytes in all."))
                .andExpect(jsonPath("$.steps[0].count").value(4))
                .andExpect(jsonPath("$.steps[0].data.shared_bytes").value(2048))
                .andExpect(jsonPath("$.steps[0].error").doesNotExist());
    }

    @Test
    void aStepRefusedAtExecuteIsStill200WithItsError() throws Exception {
        String plan = plan("plan-moved");
        String token = token(plan);
        store.notesInFolder.put(7L, 6L);

        send("/agent/execute", executeBody(plan, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("failed"))
                .andExpect(jsonPath("$.steps[0].status").value("failed"))
                .andExpect(jsonPath("$.steps[0].error.code").value("COUNT_CHANGED"))
                .andExpect(jsonPath("$.steps[0].error.confirmed_count").value(4))
                .andExpect(jsonPath("$.steps[0].error.current_count").value(6));
        store.notesInFolder.put(7L, 4L);
    }

    @Test
    void aTokenThatDoesNotMatchThePlanIsRefusedAsAWhole() throws Exception {
        String token = token(plan("plan-confirmed"));

        send("/agent/execute", executeBody(plan("plan-other"), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void refusesARequestWithoutAUserOrWithAnUnknownField() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/agent/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(plan("plan-anon"), "x.y")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        send("/agent/execute", executeBody(plan("plan-extra"), "x.y").replace("}}]}", "}}]}, \"force\": true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"));
    }
}
