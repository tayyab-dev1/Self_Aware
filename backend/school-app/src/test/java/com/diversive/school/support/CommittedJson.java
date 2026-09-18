package com.diversive.school.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A JSON file committed to the repository that must equal what the running backend serves: the
 * gateway's OpenAPI contract, and the capability metadata snapshot. The AI layer reads both.
 *
 * <p>Compared in canonical form (keys sorted, indented) so formatting never counts as drift. Run the
 * test with {@code -Dcontract.update=true} (what {@code make contracts} does) to rewrite the file.
 */
public final class CommittedJson {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private CommittedJson() {
    }

    public static void assertMatchesOrUpdate(Path file, String servedJson) throws IOException {
        String canonical = CANONICAL.writeValueAsString(CANONICAL.readValue(servedJson, Object.class)) + "\n";

        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, canonical, StandardCharsets.UTF_8);
            return;
        }

        assertThat(file)
                .as("%s is missing. Run `make contracts` to export it.", file)
                .isRegularFile();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("%s has drifted from the running backend. Run `make contracts` and commit the result.", file)
                .isEqualTo(canonical);
    }
}
