package com.diversive.agent.config;

import com.diversive.agent.error.AgentErrorCodes;
import com.diversive.agent.error.AgentErrorResponse;
import com.diversive.agent.spi.UserContext;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * When the host application publishes OpenAPI with springdoc, documents every error response of the plan
 * endpoints with the codes it carries. The AI layer branches on those codes, so the contract should show them,
 * not only the statuses springdoc can infer. A handler's {@link UserContext} parameter is left out of the docs:
 * it comes from sign-in, not from the request.
 */
@AutoConfiguration(after = AgentGatewayAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(GlobalOpenApiCustomizer.class)
public class AgentGatewayOpenApiConfiguration {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(UserContext.class);
    }

    /** The codes each plan endpoint can answer with as an error response. */
    static final Map<String, List<String>> ERROR_CODES = Map.of(
            "/agent/preflight", List.of(
                    AgentErrorCodes.INVALID_PLAN, AgentErrorCodes.UNAUTHENTICATED, AgentErrorCodes.STALE_VERSION,
                    AgentErrorCodes.NOT_PERMITTED, AgentErrorCodes.NOT_IMPLEMENTED, AgentErrorCodes.NOT_FOUND,
                    AgentErrorCodes.AMBIGUOUS_ENTITY, AgentErrorCodes.PRECONDITION_FAILED),
            "/agent/execute", List.of(
                    AgentErrorCodes.INVALID_PLAN, AgentErrorCodes.UNAUTHENTICATED, AgentErrorCodes.TOKEN_INVALID,
                    AgentErrorCodes.TOKEN_EXPIRED, AgentErrorCodes.STALE_VERSION, AgentErrorCodes.NOT_PERMITTED,
                    AgentErrorCodes.NOT_IMPLEMENTED));

    @Bean
    GlobalOpenApiCustomizer agentGatewayErrorResponses() {
        return openApi -> ERROR_CODES.forEach((pathName, codes) -> {
            PathItem path = openApi.getPaths() == null ? null : openApi.getPaths().get(pathName);
            Operation post = path == null ? null : path.getPost();
            if (post == null) {
                return;
            }
            ApiResponses responses = post.getResponses() == null ? new ApiResponses() : post.getResponses();
            Map<Integer, List<String>> codesByStatus = codes.stream().collect(Collectors.groupingBy(
                    code -> AgentErrorCodes.status(code).value(), TreeMap::new, Collectors.toList()));
            codesByStatus.forEach((status, statusCodes) -> responses.addApiResponse(String.valueOf(status), new ApiResponse()
                    .description(String.join(", ", statusCodes))
                    .content(new Content().addMediaType("application/json", new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/" + AgentErrorResponse.class.getSimpleName()))))));
            post.setResponses(responses);
        });
    }
}
