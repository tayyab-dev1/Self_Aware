package com.diversive.agent.step;

import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.PreconditionCheck;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The host application's checks, counts and resolvers, by the id they claim. The registry has already
 * proved every id a running capability declares is here, with no duplicates.
 */
public record CapabilityBeans(
        Map<String, PreconditionCheck> checks,
        Map<String, AffectedCount> counts,
        Map<String, EntityResolver> resolvers) {

    public CapabilityBeans {
        checks = Map.copyOf(checks);
        counts = Map.copyOf(counts);
        resolvers = Map.copyOf(resolvers);
    }

    public static CapabilityBeans of(Collection<PreconditionCheck> checks, Collection<AffectedCount> counts,
                                     Collection<EntityResolver> resolvers) {
        return new CapabilityBeans(
                byId(checks, PreconditionCheck::id),
                byId(counts, AffectedCount::capabilityId),
                byId(resolvers, EntityResolver::type));
    }

    public PreconditionCheck check(String id) {
        return required(checks, id, "PreconditionCheck");
    }

    public Optional<AffectedCount> count(String capabilityId) {
        return Optional.ofNullable(counts.get(capabilityId));
    }

    public EntityResolver resolver(String type) {
        return required(resolvers, type, "EntityResolver");
    }

    private static <T> Map<String, T> byId(Collection<T> beans, Function<T, String> id) {
        return beans.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    private static <T> T required(Map<String, T> beans, String id, String kind) {
        T bean = beans.get(id);
        if (bean == null) {
            throw new IllegalStateException("No " + kind + " bean for '" + id + "'; the registry should have refused to start");
        }
        return bean;
    }
}
