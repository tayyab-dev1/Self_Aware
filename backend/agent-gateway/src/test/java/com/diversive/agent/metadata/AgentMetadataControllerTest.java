package com.diversive.agent.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diversive.agent.fixtures.NotesCapabilities;
import com.diversive.agent.fixtures.NotesCapabilities.ArchiveHandler;
import com.diversive.agent.fixtures.NotesCapabilities.ShareAndFindHandlers;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentMetadataController.class)
@Import(AgentMetadataControllerTest.FixtureRegistry.class)
class AgentMetadataControllerTest {

    @TestConfiguration
    static class FixtureRegistry {

        @Bean
        CapabilityRegistry capabilityRegistry(ObjectMapper objectMapper) {
            return new CapabilityRegistryBuilder(objectMapper).build(
                    List.of(ShareAndFindHandlers.class, ArchiveHandler.class),
                    NotesCapabilities.CHECK_IDS, NotesCapabilities.COUNT_IDS,
                    NotesCapabilities.RESOLVER_TYPES);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapabilityRegistry registry;

    @Test
    void servesEveryCapabilityWithFullDetailInSnakeCase() throws Exception {
        mockMvc.perform(get("/agent/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities[*].id", contains("notes.folder.share", "notes.note.archive", "notes.note.find")))
                .andExpect(jsonPath("$.capabilities[0].version", matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.capabilities[0].read_only").value(false))
                .andExpect(jsonPath("$.capabilities[0].blast_radius").value("group"))
                .andExpect(jsonPath("$.capabilities[0].disambiguate_from", contains("notes.note.archive")))
                .andExpect(jsonPath("$.capabilities[0].params", hasSize(3)))
                .andExpect(jsonPath("$.capabilities[0].params[0].name").value("folder_id"))
                .andExpect(jsonPath("$.capabilities[0].params[0].type").value("integer"))
                .andExpect(jsonPath("$.capabilities[0].params[0].resolver").value("folder"))
                .andExpect(jsonPath("$.capabilities[0].params[1].allowed", contains("email", "sms")))
                .andExpect(jsonPath("$.capabilities[0].params[1].default_value").value("email"))
                .andExpect(jsonPath("$.capabilities[0].preconditions[0].hint").value("There is nothing in this folder to share"))
                .andExpect(jsonPath("$.capabilities[0].effect.confirmation_template")
                        .value("Share {count} notes in {folder_name} by {channel}."))
                .andExpect(jsonPath("$.capabilities[0].effect.facts", contains("shared_bytes")))
                .andExpect(jsonPath("$.capabilities[0].reverses").doesNotExist())
                .andExpect(jsonPath("$.capabilities[1].reverses").value("notes.note.restore"))
                .andExpect(jsonPath("$.capabilities[2].effect.confirmation_template").doesNotExist());
    }

    @Test
    void neverPublishesWhereTheHandlerLives() throws Exception {
        String body = mockMvc.perform(get("/agent/metadata")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("/fixture").doesNotContain("Handler").doesNotContain("handler");
    }

    @Test
    void versionsListsIdsAndVersionsOnly() throws Exception {
        mockMvc.perform(get("/agent/metadata/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)))
                .andExpect(jsonPath("$.versions[0].id").value("notes.folder.share"))
                .andExpect(jsonPath("$.versions[0].version").value(registry.versions().get(0).version()))
                .andExpect(jsonPath("$.versions[0].description").doesNotExist());
    }
}
