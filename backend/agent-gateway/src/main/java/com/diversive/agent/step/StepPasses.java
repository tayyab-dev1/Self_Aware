package com.diversive.agent.step;

import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.metadata.PreconditionMetadata;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.registry.TemplatePlaceholders;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.Lookup;
import com.diversive.agent.spi.LookupField;
import com.diversive.agent.spi.TemplateFormatter;
import com.diversive.agent.spi.UserContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The passes preflight and execute both run on prepared steps: resolve names, validate the whole request,
 * check preconditions, count, and fill templates. Preflight runs them to show the user; execute runs them
 * again, because a person sat between the two and the data may have moved (invariants 4 and 6).
 */
public final class StepPasses {

    private final CapabilityBeans beans;
    private final ParamBinder binder;
    private final TemplateFormatter formatter;

    public StepPasses(CapabilityBeans beans, ParamBinder binder, TemplateFormatter formatter) {
        this.beans = beans;
        this.binder = binder;
        this.formatter = formatter;
    }

    // --- Resolve -----------------------------------------------------------------------------------------

    /**
     * Preflight: every name to one record the user may see. A name that matches nothing stops at once; an
     * ambiguity waits, in case a later name matches nothing.
     *
     * <p>When a name matches several records, only the ones this step could act on are offered: each is tried
     * against the step's preconditions in declared order, as if chosen, and the matches that get furthest are
     * kept. "Ahmed Raza's invoice" for a payment offers the open invoice, not the one already paid. Exactly one
     * kept is simply the record: the confirmation names it before anything runs, or, when it still breaks a
     * later rule (an amount above its balance), the check pass refuses with that rule's hint instead of a
     * question that has no good answer. The filter needs the step's other values, so it applies when this is
     * the step's only name still unresolved and nothing waits on an earlier step; otherwise every match is
     * offered.
     */
    public void resolve(List<PreparedStep> steps, UserContext user) {
        List<Ambiguity> ambiguities = new ArrayList<>();
        for (PreparedStep step : steps) {
            for (Map.Entry<String, ParamValue> name : step.names().entrySet()) {
                ParamMetadata param = step.param(name.getKey());
                ParamValue given = name.getValue();
                String raw = given.raw().strip();
                Lookup lookup = lookup(step, param, given);

                List<EntityMatch> matches = search(param, lookup, user);
                if (given.chosenId() != null) {
                    matches = matches.stream().filter(match -> match.id().equals(given.chosenId())).toList();
                    if (matches.isEmpty()) {
                        throw StepRejections.chosenNotAMatch(step.number(), param, raw);
                    }
                }
                if (matches.isEmpty()) {
                    throw StepRejections.notFound(step.number(), param, raw);
                }
                if (matches.size() > 1) {
                    ambiguities.add(new Ambiguity(step, param, raw, matches));
                    continue;
                }
                EntityMatch match = matches.getFirst();
                step.resolved(param.name(), match, binder.bindId(step.capability(), param, match.id()));
            }
        }

        RuntimeException firstAmbiguity = null;
        for (Ambiguity ambiguity : ambiguities) {
            PreparedStep step = ambiguity.step();
            boolean onlyOneOpen = ambiguities.stream().filter(other -> other.step() == step).count() == 1;
            List<EntityMatch> offered = onlyOneOpen && !step.pending()
                    ? usable(step, ambiguity.param(), ambiguity.matches(), user)
                    : ambiguity.matches();
            if (offered.size() == 1) {
                EntityMatch match = offered.getFirst();
                step.resolved(ambiguity.param().name(), match,
                        binder.bindId(step.capability(), ambiguity.param(), match.id()));
                continue;
            }
            if (firstAmbiguity == null) {
                firstAmbiguity = StepRejections.ambiguous(step.number(), ambiguity.param(), ambiguity.raw(), offered);
            }
        }
        if (firstAmbiguity != null) {
            throw firstAmbiguity;
        }
    }

    /**
     * The matches that get furthest through the step's preconditions, in declared order, as if each had been
     * chosen: all of those that pass every one, or else those that fail latest. Never empty.
     */
    private List<EntityMatch> usable(PreparedStep step, ParamMetadata param, List<EntityMatch> matches, UserContext user) {
        List<PreconditionMetadata> preconditions = step.metadata().preconditions();
        if (preconditions.isEmpty()) {
            return matches;
        }
        Map<EntityMatch, Integer> passed = new LinkedHashMap<>();
        for (EntityMatch match : matches) {
            Map<String, Object> values = new LinkedHashMap<>(step.values());
            values.put(param.name(), binder.bindId(step.capability(), param, match.id()));
            int holding = 0;
            while (holding < preconditions.size() && beans.check(preconditions.get(holding).id()).holds(values, user)) {
                holding++;
            }
            passed.put(match, holding);
        }
        int furthest = Collections.max(passed.values());
        return matches.stream().filter(match -> passed.get(match) == furthest).toList();
    }

    private record Ambiguity(PreparedStep step, ParamMetadata param, String raw, List<EntityMatch> matches) {
    }

    /**
     * Execute: each name must still find the record the user confirmed, searched the same way and inside the
     * same scope. When the confirmed id is no longer among the matches, the record left the user's scope, or
     * changed name, and the step is refused with {@code OUT_OF_SCOPE}. The label is read fresh.
     *
     * @param confirmedIds the id each name resolved to at preflight, from the token
     */
    public void resolveConfirmed(PreparedStep step, Map<String, String> confirmedIds, UserContext user) {
        for (Map.Entry<String, ParamValue> name : step.names().entrySet()) {
            ParamMetadata param = step.param(name.getKey());
            String confirmedId = confirmedIds.get(param.name());
            EntityMatch match = confirmedId == null ? null
                    : search(param, lookup(step, param, name.getValue()), user).stream()
                    .filter(candidate -> candidate.id().equals(confirmedId))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                throw StepRejections.outOfScope(step.number(), param);
            }
            step.resolved(param.name(), match, binder.bindId(step.capability(), param, match.id()));
        }
    }

    /** Execute: a value an earlier step published, as this step's field type. */
    public void bindEarlierValue(PreparedStep step, String param, Object value) {
        step.earlierStepValue(param, binder.bindId(step.capability(), step.param(param), value));
    }

    private List<EntityMatch> search(ParamMetadata param, Lookup lookup, UserContext user) {
        Map<String, EntityMatch> byId = new LinkedHashMap<>();
        beans.resolver(param.resolver()).resolve(lookup, user).forEach(match -> byId.putIfAbsent(match.id(), match));
        return List.copyOf(byId.values());
    }

    /**
     * The words and the parts the plan filled, checked against what the resolver declares: an unknown
     * part, or one with nothing in it, is the plan's mistake and is refused like any other bad field.
     * A lookup with parts must fill at least one that alone names a record.
     */
    private static Lookup lookup(PreparedStep step, ParamMetadata param, ParamValue given) {
        Map<String, String> parts = given.lookup();
        if (parts == null || parts.isEmpty()) {
            return Lookup.of(given.raw().strip());
        }
        Map<String, String> kept = new LinkedHashMap<>();
        boolean identified = false;
        for (Map.Entry<String, String> part : parts.entrySet()) {
            LookupField declared = param.lookupField(part.getKey());
            if (declared == null) {
                throw StepRejections.unknownLookupPart(step.number(), param, part.getKey(), param.lookupFields());
            }
            if (part.getValue() == null || part.getValue().isBlank()) {
                throw StepRejections.blankLookupPart(step.number(), param, part.getKey());
            }
            kept.put(part.getKey(), part.getValue().strip());
            identified |= declared.identifies();
        }
        if (!identified) {
            throw StepRejections.lookupNamesNothing(step.number(), param, param.lookupFields());
        }
        return new Lookup(given.raw().strip(), kept);
    }

    // --- Validate the whole request --------------------------------------------------------------------------

    /**
     * The request record the handler will receive, checked as a whole, so a rule across fields ("a class list
     * needs a class") refuses the plan with {@code INVALID_PLAN} before anyone confirms it. Null when the
     * capability takes no input.
     */
    public Object request(PreparedStep step) {
        Object request = binder.buildRequest(step.capability(), step.values());
        List<String> violations = binder.violations(step.capability(), request);
        if (!violations.isEmpty()) {
            throw StepRejections.invalidStep(step.number(), String.join("; ", violations));
        }
        return request;
    }

    // --- Check and count -----------------------------------------------------------------------------------------

    /** Every precondition, in declared order, now that ids exist (invariant 5). The first to fail refuses the step. */
    public void check(PreparedStep step, UserContext user) {
        for (PreconditionMetadata precondition : step.metadata().preconditions()) {
            if (!beans.check(precondition.id()).holds(step.values(), user)) {
                throw StepRejections.preconditionFailed(step.number(), precondition);
            }
        }
    }

    /** What the write touches: the host's count, or 1. A count publishing an undeclared fact is a bug. */
    public CountResult count(PreparedStep step, UserContext user) {
        CountResult result = beans.count(step.metadata().id())
                .map(counter -> counter.count(step.values(), user))
                .orElseGet(() -> new CountResult(1, null, Map.of()));
        Set<String> undeclared = new TreeSet<>(result.facts().keySet());
        undeclared.removeAll(step.metadata().effect().facts());
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(step.metadata().id() + ": its AffectedCount published facts "
                    + undeclared + " that @AgentEffect(facts) does not declare");
        }
        step.counted(result);
        return result;
    }

    /** Whether the host counts this capability itself; otherwise a write counts as 1. */
    public boolean hasCount(PreparedStep step) {
        return beans.count(step.metadata().id()).isPresent();
    }

    // --- Templates ---------------------------------------------------------------------------------------------

    /** Fills a template with the host's wording. A placeholder without a value is a bug, never a gap on screen. */
    public String render(PreparedStep step, String template, Map<String, Object> facts) {
        return TemplatePlaceholders.fill(template, placeholder -> {
            Object value = facts.get(placeholder);
            if (value == null) {
                throw new IllegalStateException(step.metadata().id() + ": the template needs {" + placeholder
                        + "}, but nothing supplied it");
            }
            return formatter.format(placeholder, value);
        });
    }
}
