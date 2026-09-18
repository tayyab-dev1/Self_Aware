package com.diversive.agent.registry;

import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.metadata.ParamMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rules that need the whole registry or the application's beans. Four of them are the build-time
 * assertions in CLAUDE.md; the fifth (no near-duplicate descriptions) needs the retrieval embedding
 * model and runs in the AI layer's build checks.
 *
 * <ol>
 *   <li>Every {@code @AgentPrecondition} id resolves to a {@code PreconditionCheck} bean.</li>
 *   <li>{@code disambiguateFrom} is symmetric: if A names B, B names A.</li>
 *   <li>Every template placeholder is a parameter, a resolved label, {@code count}, or a declared fact.</li>
 *   <li>Every capability above {@code SINGLE} has an {@code AffectedCount} bean.</li>
 * </ol>
 *
 * <p>Preflight adds two more of the same kind: every resolver a parameter names has an
 * {@code EntityResolver} bean, and a confirmation can always be filled before the capability runs.
 * Rules about beans apply only to capabilities that can run; a capability marked
 * {@code @AgentNotImplemented} is refused by preflight, so nothing of it runs unchecked.
 */
final class RegistryRules {

    static final String COUNT_PLACEHOLDER = "count";

    private RegistryRules() {
    }

    static List<String> check(List<RegisteredCapability> capabilities,
                              List<String> preconditionCheckIds,
                              List<String> affectedCountIds,
                              List<String> entityResolverTypes) {
        List<String> problems = new ArrayList<>();
        Map<String, RegisteredCapability> byId = uniqueById(capabilities, problems);
        Set<String> checks = distinctBeanIds(preconditionCheckIds, "PreconditionCheck", problems);
        Set<String> counts = distinctBeanIds(affectedCountIds, "AffectedCount", problems);
        Set<String> resolvers = distinctBeanIds(entityResolverTypes, "EntityResolver", problems);

        for (RegisteredCapability capability : byId.values()) {
            CapabilityMetadata entry = capability.metadata();
            siblingsAreSymmetric(entry, byId, problems);
            placeholdersAreProducible(entry, problems);
            confirmationsNeverHaveGaps(entry, problems);
            if (capability.implemented()) {
                preconditionsHaveChecks(entry, checks, problems);
                wideCapabilitiesHaveCounts(entry, counts, problems);
                resolversHaveBeans(entry, resolvers, problems);
                confirmationFactsHaveACount(entry, counts, problems);
                handlerReturnsWhatExecuteReads(capability, counts, problems);
            }
        }
        for (String countId : counts) {
            if (!byId.containsKey(countId)) {
                problems.add("AffectedCount bean for '" + countId + "' names no registered capability");
            }
        }
        Set<String> namedResolvers = new TreeSet<>();
        byId.values().forEach(capability -> capability.metadata().params().stream()
                .map(ParamMetadata::resolver)
                .filter(resolver -> resolver != null)
                .forEach(namedResolvers::add));
        resolvers.stream()
                .filter(type -> !namedResolvers.contains(type))
                .forEach(type -> problems.add("EntityResolver bean for '" + type + "' is named by no parameter"));
        return problems;
    }

    /** Assertion 1. */
    private static void preconditionsHaveChecks(CapabilityMetadata entry, Set<String> checks, List<String> problems) {
        entry.preconditions().stream()
                .filter(precondition -> !checks.contains(precondition.id()))
                .forEach(precondition -> problems.add(entry.id() + ": precondition '" + precondition.id()
                        + "' has no PreconditionCheck bean"));
    }

    /** Assertion 2. */
    private static void siblingsAreSymmetric(CapabilityMetadata entry, Map<String, RegisteredCapability> byId,
                                             List<String> problems) {
        for (String siblingId : entry.disambiguateFrom()) {
            RegisteredCapability sibling = byId.get(siblingId);
            if (sibling == null) {
                problems.add(entry.id() + ": disambiguateFrom names '" + siblingId
                        + "', which is not a registered capability");
            } else if (!sibling.metadata().disambiguateFrom().contains(entry.id())) {
                problems.add(entry.id() + ": disambiguateFrom names '" + siblingId + "', but '" + siblingId
                        + "' does not name '" + entry.id() + "' back (disambiguateFrom must be symmetric)");
            }
        }
    }

    /** Assertion 3. */
    private static void placeholdersAreProducible(CapabilityMetadata entry, List<String> problems) {
        Set<String> producible = new LinkedHashSet<>();
        producible.add(COUNT_PLACEHOLDER);
        for (ParamMetadata param : entry.params()) {
            producible.add(param.name());
            if (param.label() != null) {
                producible.add(param.label());
            }
        }
        producible.addAll(entry.effect().facts());

        Map<String, String> templates = new HashMap<>();
        templates.put("confirmation template", entry.effect().confirmationTemplate());
        templates.put("reply template", entry.effect().replyTemplate());
        templates.forEach((name, template) -> {
            if (template == null) {
                return;
            }
            Set<String> unknown = new TreeSet<>(TemplatePlaceholders.names(template));
            unknown.removeAll(producible);
            unknown.forEach(placeholder -> problems.add(entry.id() + ": " + name + " uses {" + placeholder
                    + "}, which is not a parameter, a resolved label, count, or a declared fact"));
        });
    }

    /** Assertion 4. */
    private static void wideCapabilitiesHaveCounts(CapabilityMetadata entry, Set<String> counts, List<String> problems) {
        if (entry.blastRadius().compareTo(BlastRadius.SINGLE) > 0 && !counts.contains(entry.id())) {
            problems.add(entry.id() + ": blast radius " + entry.blastRadius().wireValue()
                    + " is above single, but there is no AffectedCount bean for it");
        }
    }

    /** Every entity type a parameter resolves has a bean to resolve it. */
    private static void resolversHaveBeans(CapabilityMetadata entry, Set<String> resolvers, List<String> problems) {
        entry.params().stream()
                .filter(param -> param.resolver() != null && !resolvers.contains(param.resolver()))
                .forEach(param -> problems.add(entry.id() + ": param '" + param.name() + "' resolves '"
                        + param.resolver() + "', but there is no EntityResolver bean for it"));
    }

    /**
     * A confirmation is shown before anything runs, so each placeholder must always have a value then:
     * a parameter that is required or has a default, or the label of one.
     */
    private static void confirmationsNeverHaveGaps(CapabilityMetadata entry, List<String> problems) {
        String template = entry.effect().confirmationTemplate();
        if (template == null) {
            return;
        }
        Set<String> placeholders = TemplatePlaceholders.names(template);
        for (ParamMetadata param : entry.params()) {
            boolean alwaysPresent = param.required() || param.defaultValue() != null;
            if (alwaysPresent) {
                continue;
            }
            if (placeholders.contains(param.name())) {
                problems.add(entry.id() + ": confirmation template uses {" + param.name()
                        + "}, which is optional, so the confirmation could have a gap");
            }
            if (param.label() != null && placeholders.contains(param.label())) {
                problems.add(entry.id() + ": confirmation template uses {" + param.label()
                        + "}, the label of optional param '" + param.name() + "', so the confirmation could have a gap");
            }
        }
    }

    /** Before the capability runs, only its count can supply facts, so a confirmation using one needs a count. */
    private static void confirmationFactsHaveACount(CapabilityMetadata entry, Set<String> counts, List<String> problems) {
        String template = entry.effect().confirmationTemplate();
        if (template == null || counts.contains(entry.id())) {
            return;
        }
        Set<String> facts = new TreeSet<>(TemplatePlaceholders.names(template));
        facts.retainAll(entry.effect().facts());
        facts.forEach(fact -> problems.add(entry.id() + ": confirmation template uses fact {" + fact
                + "}, which only an AffectedCount bean can supply before it runs, and there is none"));
    }

    /**
     * Execute reads the facts, and the count it verifies, from what the handler returns: every declared fact is
     * a property of it, and so is {@code count} when the reply uses it or the capability has a count to verify.
     */
    private static void handlerReturnsWhatExecuteReads(RegisteredCapability capability, Set<String> counts,
                                                       List<String> problems) {
        CapabilityMetadata entry = capability.metadata();
        String returned = capability.handlerMethod().getReturnType().getSimpleName();
        for (String fact : entry.effect().facts()) {
            if (!capability.responseProperties().contains(fact)) {
                problems.add(entry.id() + ": fact '" + fact + "' is not a property of what its handler returns (" + returned + ")");
            }
        }
        boolean replyUsesCount = TemplatePlaceholders.names(entry.effect().replyTemplate()).contains(COUNT_PLACEHOLDER);
        if ((replyUsesCount || counts.contains(entry.id())) && !capability.responseProperties().contains(COUNT_PLACEHOLDER)) {
            problems.add(entry.id() + ": what its handler returns (" + returned + ") needs a 'count' property, because "
                    + (replyUsesCount ? "the reply template uses {count}" : "execute checks it against the confirmed count"));
        }
    }

    private static Map<String, RegisteredCapability> uniqueById(List<RegisteredCapability> capabilities,
                                                                List<String> problems) {
        Map<String, RegisteredCapability> byId = new HashMap<>();
        for (RegisteredCapability capability : capabilities) {
            RegisteredCapability previous = byId.putIfAbsent(capability.id(), capability);
            if (previous != null) {
                problems.add(capability.id() + ": declared twice, on " + describe(previous) + " and " + describe(capability));
            }
        }
        return byId;
    }

    private static Set<String> distinctBeanIds(List<String> ids, String kind, List<String> problems) {
        Set<String> distinct = new TreeSet<>();
        for (String id : ids) {
            if (!distinct.add(id)) {
                problems.add("two " + kind + " beans claim the id '" + id + "'");
            }
        }
        return distinct;
    }

    private static String describe(RegisteredCapability capability) {
        return capability.handlerType().getSimpleName() + "." + capability.handlerMethod().getName();
    }
}
