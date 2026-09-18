package com.diversive.school.platform.openapi;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published OpenAPI description. The "agent-gateway" group (paths
 * {@code /agent/**}, see application.yml) is the contract the AI layer's
 * Pydantic models are generated from. It is committed at
 * {@code self_aware/openapi/agent-gateway.json} and checked by OpenApiContractIT.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static {
        // Publish enums as named schemas (BlastRadius, ParamType) instead of inline lists, so the
        // generated Python enums keep the Java names. springdoc's documented switch for this.
        ModelResolver.enumsAsRef = true;
    }

    @Bean
    OpenAPI schoolBackendOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("School backend: agent gateway")
                        .version("0.1.0")
                        .description("The only endpoints the AI layer calls. Plans name capability ids, never URLs."))
                // A fixed server keeps the exported contract identical on every machine.
                .servers(List.of(new Server().url("http://localhost:8080").description("Local development")));
    }
}
