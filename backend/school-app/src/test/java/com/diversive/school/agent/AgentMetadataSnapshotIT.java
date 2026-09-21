package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.diversive.agent.spi.EntityResolver;
import com.diversive.school.support.CommittedJson;
import com.diversive.school.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed snapshot of {@code GET /agent/metadata} ({@code self_aware/snapshots/agent-metadata.json})
 * must match what the backend serves.
 *
 * <p>The AI layer's build checks read this snapshot. The fifth build-time assertion, no
 * near-duplicate descriptions, needs the embedding model, so it runs there. Because this test fails
 * on a stale snapshot, nobody can change a description without that check seeing the new text.
 */
class AgentMetadataSnapshotIT extends PostgresIntegrationTest {

    private static final Path SNAPSHOT = Path.of(
            System.getProperty("snapshot.path", "../../snapshots/agent-metadata.json"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<EntityResolver> resolvers;

    @Test
    void committedSnapshotMatchesTheServedMetadata() throws Exception {
        String served = mockMvc.perform(get("/agent/metadata"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        CommittedJson.assertMatchesOrUpdate(SNAPSHOT, served);
    }

    /** The planner learns from it which of the user's words to pass, so every school resolver says it. */
    @Test
    void everyResolverSaysWhatItSearchesBy() {
        assertThat(resolvers).isNotEmpty().allSatisfy(resolver ->
                assertThat(resolver.lookup()).as(resolver.type()).isNotBlank());
    }
}
