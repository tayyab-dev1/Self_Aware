package com.diversive.agent.step;

import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.RegisteredCapability;
import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.UserContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a plan against the registry, before any data is touched. In this order, across all steps, so the
 * most basic problem is the one reported:
 *
 * <ol>
 *   <li>the plan's shape, and every capability id is registered ({@code INVALID_PLAN})</li>
 *   <li>every step was planned with the current version ({@code STALE_VERSION}), checked before
 *       parameters because an old plan's parameters may no longer fit</li>
 *   <li>the user may use every capability ({@code NOT_PERMITTED})</li>
 *   <li>every capability can run ({@code NOT_IMPLEMENTED}), so nothing is resolved or checked for one
 *       that cannot</li>
 *   <li>every parameter fits its declaration and the record's constraints ({@code INVALID_PLAN})</li>
 * </ol>
 */
public final class PlanReader {

    /** A name longer than this is not a name; it is refused before any query runs. */
    public static final int MAX_RAW_LENGTH = 200;

    /**
     * Plan and session ids are plain ids. They go into log lines and make up the idempotency key
     * {@code session_id:plan_id:step}, so they cannot contain a colon.
     */
    public static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final CapabilityRegistry registry;
    private final CapabilityPolicy policy;
    private final ParamBinder binder;
    private final int maxSteps;

    public PlanReader(CapabilityRegistry registry, CapabilityPolicy policy, ParamBinder binder, int maxSteps) {
        this.registry = registry;
        this.policy = policy;
        this.binder = binder;
        this.maxSteps = maxSteps;
    }

    public List<PreparedStep> read(Plan plan, UserContext user) {
        List<RegisteredCapability> capabilities = capabilities(plan);
        List<PlanStep> planned = plan.steps();

        for (int i = 0; i < planned.size(); i++) {
            if (!planned.get(i).capabilityVersion().equals(capabilities.get(i).metadata().version())) {
                throw StepRejections.staleVersion(i + 1, capabilities.get(i).id());
            }
        }
        for (int i = 0; i < planned.size(); i++) {
            if (!policy.permits(user, capabilities.get(i).id())) {
                throw StepRejections.notPermitted(i + 1, capabilities.get(i).id());
            }
        }
        for (int i = 0; i < planned.size(); i++) {
            if (!capabilities.get(i).implemented()) {
                throw StepRejections.notImplemented(i + 1, capabilities.get(i).id());
            }
        }

        List<PreparedStep> steps = new ArrayList<>();
        for (int i = 0; i < planned.size(); i++) {
            steps.add(prepare(i + 1, planned.get(i), capabilities.get(i), capabilities));
        }
        return steps;
    }

    private List<RegisteredCapability> capabilities(Plan plan) {
        if (plan == null) {
            throw StepRejections.invalidPlan("The request has no plan");
        }
        if (isBlank(plan.planId()) || isBlank(plan.sessionId())) {
            throw StepRejections.invalidPlan("The plan needs a plan_id and a session_id");
        }
        if (!PLAIN_ID.matcher(plan.planId()).matches() || !PLAIN_ID.matcher(plan.sessionId()).matches()) {
            throw StepRejections.invalidPlan("plan_id and session_id may only use letters, digits, '.', '_' and '-', "
                    + "at most 100 of them");
        }
        if (plan.steps() == null || plan.steps().isEmpty()) {
            throw StepRejections.invalidPlan("The plan has no steps");
        }
        if (plan.steps().size() > maxSteps) {
            throw StepRejections.invalidPlan("The plan has " + plan.steps().size() + " steps; at most " + maxSteps + " are allowed");
        }
        List<RegisteredCapability> capabilities = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            int number = i + 1;
            if (step == null) {
                throw StepRejections.invalidPlan("Step " + number + " is empty");
            }
            if (!Integer.valueOf(number).equals(step.step())) {
                throw StepRejections.invalidPlan("Steps must be numbered 1, 2, 3 in order; the step at position " + number
                        + " is numbered " + step.step());
            }
            if (isBlank(step.capabilityId()) || isBlank(step.capabilityVersion()) || step.params() == null) {
                throw StepRejections.invalidStep(number, "a step needs capability_id, capability_version and params "
                        + "(send {} when there are none)");
            }
            capabilities.add(registry.find(step.capabilityId()).orElseThrow(() ->
                    StepRejections.invalidStep(number, step.capabilityId() + " is not a registered capability")));
        }
        return capabilities;
    }

    private PreparedStep prepare(int number, PlanStep planned, RegisteredCapability capability,
                                 List<RegisteredCapability> capabilities) {
        PreparedStep step = new PreparedStep(number, capability);
        Set<String> declared = capability.metadata().params().stream().map(ParamMetadata::name)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> unknown = new TreeSet<>(planned.params().keySet());
        unknown.removeAll(declared);
        if (!unknown.isEmpty()) {
            throw StepRejections.invalidParam(number, unknown.iterator().next(),
                    capability.id() + " takes no such parameter; it takes " + (declared.isEmpty() ? "none" : declared));
        }

        for (ParamMetadata param : capability.metadata().params()) {
            ParamValue given = planned.params().get(param.name());
            if (given == null) {
                if (param.defaultValue() != null) {
                    step.value(param.name(), binder.bindDefault(capability, param));
                } else if (param.required()) {
                    throw StepRejections.invalidParam(number, param.name(), "it is required, and the plan does not give it");
                }
                continue;
            }
            switch (given.form()) {
                case VALUE -> bindValue(step, param, given);
                case NAME -> {
                    if (param.resolver() == null) {
                        throw StepRejections.invalidParam(number, param.name(), "it takes a value as given, not raw words to look up");
                    }
                    if (given.raw().length() > MAX_RAW_LENGTH) {
                        throw StepRejections.invalidParam(number, param.name(),
                                "the words to look up are longer than " + MAX_RAW_LENGTH + " characters");
                    }
                    step.name(param.name(), given);
                }
                case EARLIER_STEP -> {
                    checkEarlierStep(number, param, given, capabilities);
                    step.fromEarlierStep(param.name(), given);
                }
                case MALFORMED -> throw StepRejections.invalidParam(number, param.name(),
                        "give exactly one of: value; raw (with an optional chosen_id); from_step with field");
            }
        }

        if (step.pending() && step.writes() && capability.metadata().effect().pendingTemplate() == null) {
            throw StepRejections.invalidStep(number, "it takes a value from an earlier step, but " + capability.id()
                    + " has no pending template to confirm it with");
        }
        return step;
    }

    private void bindValue(PreparedStep step, ParamMetadata param, ParamValue given) {
        if (param.resolver() != null) {
            throw StepRejections.invalidParam(step.number(), param.name(),
                    "it is looked up by name: give the user's words as raw, not a value");
        }
        StringBuilder problem = new StringBuilder();
        Optional<Object> typed = binder.bindValue(step.capability(), param, given.value(), problem);
        if (typed.isEmpty()) {
            throw StepRejections.invalidParam(step.number(), param.name(), problem.toString());
        }
        step.value(param.name(), typed.get());
    }

    private static void checkEarlierStep(int number, ParamMetadata param, ParamValue given,
                                         List<RegisteredCapability> capabilities) {
        int from = given.fromStep();
        if (from < 1 || from >= number) {
            throw StepRejections.invalidParam(number, param.name(), "from_step must name an earlier step, not " + from);
        }
        RegisteredCapability source = capabilities.get(from - 1);
        if (!source.metadata().effect().facts().contains(given.field())) {
            throw StepRejections.invalidParam(number, param.name(), "step " + from + " (" + source.id()
                    + ") publishes no fact '" + given.field() + "'; it publishes " + source.metadata().effect().facts());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
