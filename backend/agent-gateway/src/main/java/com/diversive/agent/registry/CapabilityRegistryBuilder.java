package com.diversive.agent.registry;

import com.diversive.agent.metadata.CapabilityMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the registry: scan the annotated handlers, apply every rule, then version each entry.
 * Any problem stops the build with all of them listed, so the application never starts on
 * metadata that breaks a rule.
 */
public final class CapabilityRegistryBuilder {

    private final CapabilityScanner scanner;

    /** @param objectMapper the application's mapper, so parameter names match what endpoints accept */
    public CapabilityRegistryBuilder(ObjectMapper objectMapper) {
        this.scanner = new CapabilityScanner(objectMapper);
    }

    /**
     * @param handlerTypes          classes that may carry {@code @AgentCapability} methods
     * @param preconditionCheckIds  the id of every {@code PreconditionCheck} bean, duplicates included
     * @param affectedCountIds      the capability id of every {@code AffectedCount} bean, duplicates included
     * @param entityResolverTypes   the type of every {@code EntityResolver} bean, duplicates included
     * @throws CapabilityRegistryException listing every broken rule
     */
    public CapabilityRegistry build(Collection<Class<?>> handlerTypes,
                                    Collection<String> preconditionCheckIds,
                                    Collection<String> affectedCountIds,
                                    Collection<String> entityResolverTypes) {
        return build(handlerTypes, preconditionCheckIds, affectedCountIds, entityResolverTypes, Map.of());
    }

    /**
     * As above, and each parameter a resolver looks up carries that resolver's {@code lookup()} text, so
     * a changed description of what a resolver searches by changes the version of every capability using it.
     *
     * @param lookups what each resolver type searches by, from {@code EntityResolver#lookup()}; types that
     *                say nothing are left out
     */
    public CapabilityRegistry build(Collection<Class<?>> handlerTypes,
                                    Collection<String> preconditionCheckIds,
                                    Collection<String> affectedCountIds,
                                    Collection<String> entityResolverTypes,
                                    Map<String, String> lookups) {
        List<String> problems = new ArrayList<>();
        List<RegisteredCapability> scanned = scan(handlerTypes, problems);
        problems.addAll(RegistryRules.check(scanned, List.copyOf(preconditionCheckIds), List.copyOf(affectedCountIds),
                List.copyOf(entityResolverTypes)));
        if (!problems.isEmpty()) {
            throw new CapabilityRegistryException(problems);
        }
        return new CapabilityRegistry(scanned.stream()
                .map(capability -> {
                    CapabilityMetadata described = withLookups(capability.metadata(), lookups);
                    return capability.withMetadata(described.withVersion(CapabilityVersioner.version(described)));
                })
                .toList());
    }

    private static CapabilityMetadata withLookups(CapabilityMetadata metadata, Map<String, String> lookups) {
        return metadata.withParams(metadata.params().stream()
                .map(param -> param.resolver() == null ? param : param.withLookup(lookups.get(param.resolver())))
                .toList());
    }

    /**
     * The declared capabilities without the rules that need beans, for checks that run without an
     * application context, such as comparing ids with the planning contracts.
     *
     * @throws CapabilityRegistryException when an annotation breaks a rule on its own
     */
    public List<CapabilityMetadata> declared(Collection<Class<?>> handlerTypes) {
        List<String> problems = new ArrayList<>();
        List<RegisteredCapability> scanned = scan(handlerTypes, problems);
        if (!problems.isEmpty()) {
            throw new CapabilityRegistryException(problems);
        }
        return scanned.stream()
                .map(capability -> capability.metadata().withVersion(CapabilityVersioner.version(capability.metadata())))
                .sorted(Comparator.comparing(CapabilityMetadata::id))
                .toList();
    }

    private List<RegisteredCapability> scan(Collection<Class<?>> handlerTypes, List<String> problems) {
        List<RegisteredCapability> scanned = new ArrayList<>();
        handlerTypes.stream()
                .distinct()
                .sorted(Comparator.comparing(Class::getName))
                .forEach(type -> scanner.scan(type, scanned, problems));
        return scanned;
    }
}
