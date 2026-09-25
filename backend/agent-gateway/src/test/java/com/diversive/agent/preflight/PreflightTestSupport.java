package com.diversive.agent.preflight;

import com.diversive.agent.fixtures.PreflightFixtures;
import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.registry.CapabilityRegistry;
import com.diversive.agent.registry.CapabilityRegistryBuilder;
import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.step.CapabilityBeans;
import com.diversive.agent.step.ParamBinder;
import com.diversive.agent.step.PlainTemplateFormatter;
import com.diversive.agent.step.PlanReader;
import com.diversive.agent.step.StepPasses;
import com.diversive.agent.token.PreflightTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionOperations;

/** Builds a preflight over the notes fixtures, and plans against it, for the preflight tests. */
public final class PreflightTestSupport {

    public static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"), ZoneOffset.UTC);
    public static final byte[] SECRET = "test-secret-that-is-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    public static final CapabilityRegistry REGISTRY = new CapabilityRegistryBuilder(MAPPER).build(
            PreflightFixtures.handlers(), PreflightFixtures.checkIds(), PreflightFixtures.countIds(),
            PreflightFixtures.resolverTypes());

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private PreflightTestSupport() {
    }

    public static PreflightTokens tokens() {
        return new PreflightTokens(SECRET, Duration.ofMinutes(5), CLOCK);
    }

    public static PreflightService service() {
        return service(PreflightFixtures.checks(), PreflightFixtures.counts(), PreflightFixtures.resolvers());
    }

    public static PreflightService service(List<PreconditionCheck> checks, List<AffectedCount> counts,
                                           List<EntityResolver> resolvers) {
        return new PreflightService(reader(), passes(checks, counts, resolvers), tokens(),
                TransactionOperations.withoutTransaction());
    }

    public static PlanReader reader() {
        return reader(REGISTRY);
    }

    public static PlanReader reader(CapabilityRegistry registry) {
        return new PlanReader(registry, PreflightFixtures.POLICY, binder(), 3);
    }

    public static StepPasses passes(List<PreconditionCheck> checks, List<AffectedCount> counts, List<EntityResolver> resolvers) {
        return new StepPasses(CapabilityBeans.of(checks, counts, resolvers), binder(), new PlainTemplateFormatter(MAPPER));
    }

    private static ParamBinder binder() {
        return new ParamBinder(MAPPER, VALIDATOR);
    }

    public static Plan plan(PlanStep... steps) {
        return new Plan("plan-1", "session-1", List.of(steps));
    }

    /** A step planned with the capability's current version. */
    public static PlanStep step(int number, String capabilityId, Object... nameThenValue) {
        return new PlanStep(number, capabilityId, REGISTRY.find(capabilityId).orElseThrow().metadata().version(),
                params(nameThenValue));
    }

    public static Map<String, ParamValue> params(Object... nameThenValue) {
        Map<String, ParamValue> params = new LinkedHashMap<>();
        for (int i = 0; i < nameThenValue.length; i += 2) {
            params.put((String) nameThenValue[i], (ParamValue) nameThenValue[i + 1]);
        }
        return params;
    }

    public static ParamValue value(Object value) {
        return new ParamValue(value, null, null, null, null, null);
    }

    public static ParamValue raw(String words) {
        return new ParamValue(null, words, null, null, null, null);
    }

    public static ParamValue chosen(String words, String id) {
        return new ParamValue(null, words, null, id, null, null);
    }

    public static ParamValue fromStep(int step, String field) {
        return new ParamValue(null, null, null, null, step, field);
    }
}
