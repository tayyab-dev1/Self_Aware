package com.diversive.agent.session;

import com.diversive.agent.error.AgentUnauthenticatedException;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.UserContext;
import com.diversive.agent.spi.UserContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /agent/session/capabilities}: which capabilities the calling user may use. */
@RestController
@RequestMapping("/agent/session")
public class AgentSessionController {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionController.class);

    private final CapabilityRegistry registry;
    private final UserContextResolver userContextResolver;
    private final CapabilityPolicy policy;

    public AgentSessionController(CapabilityRegistry registry, UserContextResolver userContextResolver,
                                  CapabilityPolicy policy) {
        this.registry = registry;
        this.userContextResolver = userContextResolver;
        this.policy = policy;
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionCapabilitiesResponse capabilities(HttpServletRequest request) {
        UserContext user = userContextResolver.resolve(request).orElseThrow(AgentUnauthenticatedException::new);
        List<String> permitted = registry.ids().stream()
                .filter(id -> policy.permits(user, id))
                .toList();
        log.debug("User {} may use {} of {} capabilities", user.userId(), permitted.size(), registry.size());
        return new SessionCapabilitiesResponse(user.userId(), permitted);
    }
}
