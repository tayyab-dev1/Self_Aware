package com.diversive.agent.session;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.fixtures.NotesCapabilities;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.spi.UserContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentSessionController.class)
@Import(AgentSessionControllerTest.Beans.class)
class AgentSessionControllerTest {

    /** A reader may only find; an editor may do anything. The user comes from a test header. */
    @TestConfiguration
    static class Beans {

        @Bean
        CapabilityRegistry capabilityRegistry(ObjectMapper objectMapper) {
            return new CapabilityRegistryBuilder(objectMapper).build(
                    List.of(ShareAndFindHandlers.class, ArchiveHandler.class),
                    NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS,
                    NotesCapabilities.RESOLVER_TYPES);
        }

        @Bean
        UserContextResolver userContextResolver() {
            return request -> Optional.ofNullable(request.getHeader("X-Test-User"))
                    .map(role -> new UserContext("user-" + role, Set.of(role), Map.of("folder", "7")));
        }

        @Bean
        CapabilityPolicy capabilityPolicy() {
            return (user, capabilityId) -> user.hasRole("editor") || capabilityId.endsWith(".find");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void refusesARequestWithoutAUser() throws Exception {
        mockMvc.perform(get("/agent/session/capabilities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void listsOnlyWhatThePolicyPermits() throws Exception {
        mockMvc.perform(get("/agent/session/capabilities").header("X-Test-User", "reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("user-reader"))
                .andExpect(jsonPath("$.capability_ids", contains("notes.note.find")));

        mockMvc.perform(get("/agent/session/capabilities").header("X-Test-User", "editor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capability_ids", contains("notes.folder.share", "notes.note.archive", "notes.note.find")));
    }
}
