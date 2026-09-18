package com.diversive.school.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capability ids come from the planning contracts, never invented (README decision 10). Runs without
 * Docker: it reads the annotations straight from the classes.
 */
class CapabilityIdsMatchPlanningContractsTest {

    private static final Path CAPABILITY_INDEX =
            Path.of("..", "..", "..", "planning-contracts", "dist", "capability-index.json");

    @Test
    void everyCapabilityIsAnOperationInThePlanningContractsWithTheSameKind() throws IOException {
        Map<String, String> kindById = contractKinds();
        List<CapabilityMetadata> declared = declaredCapabilities();

        assertThat(declared).isNotEmpty();
        for (CapabilityMetadata capability : declared) {
            assertThat(kindById)
                    .as("%s is not an operation in planning-contracts/dist/capability-index.json", capability.id())
                    .containsKey(capability.id());
            assertThat(capability.readOnly())
                    .as("%s: readOnly must agree with the planning contract's kind '%s'",
                            capability.id(), kindById.get(capability.id()))
                    .isEqualTo(kindById.get(capability.id()).equals("read"));
            if (capability.reverses() != null) {
                assertThat(kindById)
                        .as("%s reverses %s, which is not in the planning contracts", capability.id(), capability.reverses())
                        .containsKey(capability.reverses());
            }
        }
    }

    static List<CapabilityMetadata> declaredCapabilities() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> handlerTypes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.diversive.school")) {
            handlerTypes.add(ClassUtils.resolveClassName(Objects.requireNonNull(definition.getBeanClassName()), null));
        }
        return new CapabilityRegistryBuilder(JsonMapper.builder().build()).declared(handlerTypes);
    }

    private static Map<String, String> contractKinds() throws IOException {
        JsonNode index = JsonMapper.builder().build().readTree(CAPABILITY_INDEX.toFile());
        Map<String, String> kinds = new HashMap<>();
        index.get("capabilities").forEach(entry -> kinds.put(entry.get("capability").asText(), entry.get("kind").asText()));
        return kinds;
    }
}
