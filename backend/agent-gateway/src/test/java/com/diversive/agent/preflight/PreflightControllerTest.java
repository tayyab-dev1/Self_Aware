package com.diversive.agent.preflight;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.spi.UserContextResolver;
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

/** The wire format of {@code POST /agent/preflight}: snake_case both ways, and each refusal's status and body. */
@WebMvcTest(controllers = PreflightController.class,
        properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
@Import(PreflightControllerTest.Beans.class)
class PreflightControllerTest {

    @TestConfiguration
    static class Beans {

        @Bean
        PreflightService preflightService() {
            return PreflightTestSupport.service();
        }

        /** The test signs in as the editor with a header; no header means no user. */
        @Bean
        UserContextResolver userContextResolver() {
            return request -> Optional.ofNullable(request.getHeader("X-Test-User")).map(ignored -> PreflightFixtures.EDITOR);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static String shareBody(String folderStep) {
        String version = PreflightTestSupport.REGISTRY.find("notes.folder.share").orElseThrow().metadata().version();
        return """
                {"plan": {"plan_id": "plan-1", "session_id": "session-1", "steps": [
                  {"step": 1, "capability_id": "notes.folder.share", "capability_version": "%s",
                   "params": {"folder_id": %s, "channel": {"value": "sms"}}}]}}
                """.formatted(version, folderStep);
    }

    private ResultActions preflight(String body) throws Exception {
        return mockMvc.perform(post("/agent/preflight").header("X-Test-User", "editor")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void answersWithTheConfirmationAndATokenInSnakeCase() throws Exception {
        preflight(shareBody("{\"raw\": \"recipes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value("plan-1"))
                .andExpect(jsonPath("$.requires_confirmation").value(true))
                .andExpect(jsonPath("$.confirmation").value("Share 4 notes in Recipes by sms. This cannot be undone."))
                .andExpect(jsonPath("$.warnings", contains("This cannot be undone.")))
                .andExpect(jsonPath("$.steps[0].capability_id").value("notes.folder.share"))
                .andExpect(jsonPath("$.steps[0].resolved[0].param").value("folder_id"))
                .andExpect(jsonPath("$.steps[0].resolved[0].id").value("7"))
                .andExpect(jsonPath("$.steps[0].resolved[0].label").value("Recipes"))
                .andExpect(jsonPath("$.steps[0].count").value(4))
                .andExpect(jsonPath("$.token", matchesPattern("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
                .andExpect(jsonPath("$.expires_at").value("2026-09-14T09:05:00Z"));
    }

    @Test
    void refusesARequestWithoutAUser() throws Exception {
        mockMvc.perform(post("/agent/preflight").contentType(MediaType.APPLICATION_JSON).content(shareBody("{\"raw\": \"recipes\"}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anAmbiguousNameIs422WithCandidates() throws Exception {
        // "list" matches three notes; the archived one is not offered for archiving.
        String version = PreflightTestSupport.REGISTRY.find("notes.note.archive").orElseThrow().metadata().version();
        preflight("""
                {"plan": {"plan_id": "plan-1", "session_id": "session-1", "steps": [
                  {"step": 1, "capability_id": "notes.note.archive", "capability_version": "%s",
                   "params": {"note_id": {"raw": "list"}, "reason": {"value": "done"}}}]}}
                """.formatted(version))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_ENTITY"))
                .andExpect(jsonPath("$.step").value(1))
                .andExpect(jsonPath("$.param").value("note_id"))
                .andExpect(jsonPath("$.candidates[*].label", contains("Shopping list", "Packing list")))
                .andExpect(jsonPath("$.candidates[0].id").value("11"))
                .andExpect(jsonPath("$.hint").doesNotExist());
    }

    @Test
    void aFailingPreconditionIs422WithItsHint() throws Exception {
        preflight(shareBody("{\"raw\": \"receipts\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.precondition").value("folder_not_empty"))
                .andExpect(jsonPath("$.hint").value("There is nothing in this folder to share"));
    }

    @Test
    void aFieldTheContractDoesNotDefineIsAnInvalidPlan() throws Exception {
        preflight(shareBody("{\"raw\": \"recipes\", \"guess_id\": \"7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"))
                .andExpect(jsonPath("$.message", containsString("'guess_id'")));
    }

    @Test
    void aBodyThatIsNotJsonIsAnInvalidPlan() throws Exception {
        preflight("{\"plan\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN"));
    }

    @Test
    void aCapabilityThatCannotRunIs501() throws Exception {
        String version = PreflightTestSupport.REGISTRY.find("notes.note.restore").orElseThrow().metadata().version();
        preflight("""
                {"plan": {"plan_id": "plan-1", "session_id": "session-1", "steps": [
                  {"step": 1, "capability_id": "notes.note.restore", "capability_version": "%s",
                   "params": {"note_id": {"raw": "shopping"}}}]}}
                """.formatted(version))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }

    @Test
    void anOlderVersionIs409() throws Exception {
        preflight(shareBody("{\"raw\": \"recipes\"}").replaceFirst("\"capability_version\": \"[0-9a-f]+\"",
                "\"capability_version\": \"" + "0".repeat(64) + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }
}
