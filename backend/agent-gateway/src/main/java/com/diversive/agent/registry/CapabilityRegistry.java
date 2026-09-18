package com.diversive.agent.registry;

import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.metadata.CapabilityVersion;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every registered capability, validated and versioned, sorted by id. Immutable: built once at
 * startup by {@link CapabilityRegistryBuilder}.
 */
public final class CapabilityRegistry {

    private final Map<String, RegisteredCapability> byId;

    CapabilityRegistry(Collection<RegisteredCapability> capabilities) {
        Map<String, RegisteredCapability> sorted = new TreeMap<>();
        capabilities.forEach(capability -> sorted.put(capability.id(), capability));
        this.byId = Collections.unmodifiableMap(sorted);
    }

    public List<RegisteredCapability> all() {
        return List.copyOf(byId.values());
    }

    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    public Optional<RegisteredCapability> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<CapabilityMetadata> metadata() {
        return byId.values().stream().map(RegisteredCapability::metadata).toList();
    }

    public List<CapabilityVersion> versions() {
        return byId.values().stream()
                .map(capability -> new CapabilityVersion(capability.id(), capability.metadata().version()))
                .toList();
    }

    public int size() {
        return byId.size();
    }
}
