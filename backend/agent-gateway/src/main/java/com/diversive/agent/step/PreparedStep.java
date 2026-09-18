package com.diversive.agent.step;

import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.registry.RegisteredCapability;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.EntityMatch;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One plan step as preflight or execute works through it, filled in pass by pass. Internal to the
 * gateway: it never leaves the backend.
 */
public final class PreparedStep {

    private final int number;
    private final RegisteredCapability capability;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, ParamValue> names = new LinkedHashMap<>();
    private final Map<String, EntityMatch> resolved = new LinkedHashMap<>();
    private final Map<String, ParamValue> fromEarlierSteps = new LinkedHashMap<>();
    private CountResult count;
    private String line;

    PreparedStep(int number, RegisteredCapability capability) {
        this.number = number;
        this.capability = capability;
    }

    public int number() {
        return number;
    }

    public RegisteredCapability capability() {
        return capability;
    }

    public CapabilityMetadata metadata() {
        return capability.metadata();
    }

    public boolean writes() {
        return !metadata().readOnly();
    }

    /** It takes a value from an earlier step, so at preflight its checks and count wait until that step has run. */
    public boolean pending() {
        return !fromEarlierSteps.isEmpty();
    }

    public ParamMetadata param(String name) {
        return metadata().params().stream()
                .filter(param -> param.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(metadata().id() + " has no param " + name));
    }

    /** Parameter values by JSON name, typed, entity names already ids: what checks, counts and handlers receive. */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    void value(String param, Object typed) {
        values.put(param, typed);
    }

    /** The parameters given as names to look up. */
    public Map<String, ParamValue> names() {
        return Collections.unmodifiableMap(names);
    }

    void name(String param, ParamValue given) {
        names.put(param, given);
    }

    /** The parameters that take a fact from an earlier step, with where to find it. */
    public Map<String, ParamValue> fromEarlierSteps() {
        return Collections.unmodifiableMap(fromEarlierSteps);
    }

    void fromEarlierStep(String param, ParamValue given) {
        fromEarlierSteps.put(param, given);
    }

    /** Fills in a value an earlier step has now published, at execute. */
    public void earlierStepValue(String param, Object typed) {
        values.put(param, typed);
    }

    public void resolved(String param, EntityMatch match, Object typedId) {
        resolved.put(param, match);
        values.put(param, typedId);
    }

    public List<ResolvedEntityView> resolvedEntities() {
        return resolved.entrySet().stream()
                .map(entry -> new ResolvedEntityView(entry.getKey(), entry.getValue().id(), entry.getValue().label()))
                .toList();
    }

    public Map<String, String> resolvedIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        resolved.forEach((param, match) -> ids.put(param, match.id()));
        return ids;
    }

    /** What the resolved records read as, by their label keys, e.g. {@code section_name -> Class 5 Blue}. */
    public Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        resolved.forEach((param, match) -> labels.put(param(param).label(), match.label()));
        return labels;
    }

    /** What the confirmation template may use: values, resolved labels, the count and its facts. */
    public Map<String, Object> facts() {
        Map<String, Object> facts = new LinkedHashMap<>(values);
        facts.putAll(labels());
        if (count != null) {
            facts.putAll(count.facts());
            facts.put("count", count.count());
        }
        return facts;
    }

    public CountResult count() {
        return count;
    }

    public void counted(CountResult result) {
        this.count = result;
    }

    public String line() {
        return line;
    }

    public void line(String confirmationLine) {
        this.line = confirmationLine;
    }

    /** A resolved record as it is shown: the parameter, the record's id and its label. */
    public record ResolvedEntityView(String param, String id, String label) {
    }
}
