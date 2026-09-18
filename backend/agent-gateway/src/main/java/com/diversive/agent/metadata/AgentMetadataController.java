package com.diversive.agent.metadata;

import com.diversive.agent.registry.CapabilityRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the AI layer is allowed to plan with.
 *
 * <ul>
 *   <li>{@code GET /agent/metadata}: every capability with full detail, sorted by id.</li>
 *   <li>{@code GET /agent/metadata/versions}: ids and versions only, for cheap change polling.</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/metadata")
public class AgentMetadataController {

    private final CapabilityRegistry registry;

    public AgentMetadataController(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentMetadataResponse metadata() {
        return new AgentMetadataResponse(registry.metadata());
    }

    @GetMapping(value = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public CapabilityVersionsResponse versions() {
        return new CapabilityVersionsResponse(registry.versions());
    }
}
