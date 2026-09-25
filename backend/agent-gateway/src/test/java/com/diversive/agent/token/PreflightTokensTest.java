package com.diversive.agent.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.diversive.agent.plan.ParamValue;
import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanHasher;
import com.diversive.agent.plan.PlanStep;
import com.diversive.agent.spi.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A token vouches for exactly one plan, for one user, for five minutes, and cannot be altered. */
class PreflightTokensTest {

    private static final byte[] SECRET = "test-secret-that-is-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    private static final UserContext USER = new UserContext("user-7", Set.of("editor"), Map.of());
    private static final List<PreflightToken.Step> STEPS = List.of(new PreflightToken.Step(1, Map.of("section_id", "2"), 5L, false));

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-14T09:00:00Z"));
    private final PreflightTokens tokens = new PreflightTokens(SECRET, Duration.ofMinutes(5), clock);

    private static Plan plan(String sectionWords) {
        return new Plan("plan-1", "session-1", List.of(new PlanStep(1, "fee.reminder.send", "a".repeat(64), Map.of(
                "section_id", new ParamValue(null, sectionWords, null, null, null, null),
                "channel", new ParamValue("whatsapp", null, null, null, null, null)))));
    }

    @Test
    void aTokenVerifiesForThePlanAndUserItWasIssuedFor() throws Exception {
        Plan plan = plan("class 5 blue");
        PreflightTokens.Issued issued = tokens.issue(plan, USER, STEPS);

        PreflightToken payload = tokens.verify(issued.token(), plan, USER);

        assertThat(payload.planHash()).isEqualTo(PlanHasher.hash(plan));
        assertThat(payload.steps()).isEqualTo(STEPS);
        assertThat(issued.expiresAt()).isEqualTo("2026-09-14T09:05:00Z");
    }

    @Test
    void aTamperedPlanHashFailsVerification() {
        Plan confirmed = plan("class 5 blue");
        Plan edited = plan("class 5 green");
        String token = tokens.issue(confirmed, USER, STEPS).token();

        // Rewrite the hash inside the token so it matches the edited plan, keeping the original signature.
        String[] parts = token.split("\\.");
        ObjectNode payload = (ObjectNode) readJson(Base64.getUrlDecoder().decode(parts[0]));
        payload.put("plan_hash", PlanHasher.hash(edited));
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8))
                + "." + parts[1];

        TokenVerificationException failure = catchThrowableOfType(TokenVerificationException.class,
                () -> tokens.verify(tampered, edited, USER));

        assertThat(failure.reason()).isEqualTo(TokenVerificationException.Reason.BAD_SIGNATURE);
        assertThat(failure.code()).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aPlanChangedAfterConfirmationFailsVerification() {
        String token = tokens.issue(plan("class 5 blue"), USER, STEPS).token();

        TokenVerificationException failure = catchThrowableOfType(TokenVerificationException.class,
                () -> tokens.verify(token, plan("class 5 green"), USER));

        assertThat(failure.reason()).isEqualTo(TokenVerificationException.Reason.PLAN_MISMATCH);
        assertThat(failure.code()).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aTokenExpiresAfterFiveMinutes() throws Exception {
        Plan plan = plan("class 5 blue");
        String token = tokens.issue(plan, USER, STEPS).token();

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        tokens.verify(token, plan, USER);

        clock.advance(Duration.ofSeconds(1));
        TokenVerificationException failure = catchThrowableOfType(TokenVerificationException.class,
                () -> tokens.verify(token, plan, USER));

        assertThat(failure.reason()).isEqualTo(TokenVerificationException.Reason.EXPIRED);
        assertThat(failure.code()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void aTokenIssuedToAnotherUserFails() {
        Plan plan = plan("class 5 blue");
        String token = tokens.issue(plan, USER, STEPS).token();

        TokenVerificationException failure = catchThrowableOfType(TokenVerificationException.class,
                () -> tokens.verify(token, plan, new UserContext("user-8", Set.of("editor"), Map.of())));

        assertThat(failure.reason()).isEqualTo(TokenVerificationException.Reason.USER_MISMATCH);
    }

    @Test
    void aTokenFromAnotherKeyOrNotATokenAtAllFails() {
        Plan plan = plan("class 5 blue");
        PreflightTokens otherBackend = new PreflightTokens("another-secret-that-is-32-bytes!!".getBytes(StandardCharsets.UTF_8),
                Duration.ofMinutes(5), clock);
        String foreign = otherBackend.issue(plan, USER, STEPS).token();

        assertThat(catchThrowableOfType(TokenVerificationException.class, () -> tokens.verify(foreign, plan, USER)).reason())
                .isEqualTo(TokenVerificationException.Reason.BAD_SIGNATURE);
        for (String garbage : new String[] {null, "", "abc", "a.b.c", "!!!.???"}) {
            assertThat(catchThrowableOfType(TokenVerificationException.class, () -> tokens.verify(garbage, plan, USER)).reason())
                    .as(String.valueOf(garbage))
                    .isIn(TokenVerificationException.Reason.MALFORMED, TokenVerificationException.Reason.BAD_SIGNATURE);
        }
    }

    @Test
    void thePayloadCarriesIdsAndCountsButNoWordsOfThePlan() {
        String token = tokens.issue(plan("class 5 blue"), USER, STEPS).token();

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        assertThat(payload).contains("\"plan_hash\"", "\"resolved_ids\":{\"section_id\":\"2\"}", "\"count\":5")
                .doesNotContain("class 5 blue", "whatsapp");
    }

    @Test
    void aShortSecretIsRefusedAndABlankOneBecomesARandomKey() {
        assertThatThrownBy(() -> PreflightTokens.create("too-short", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");

        Plan plan = plan("class 5 blue");
        PreflightTokens first = PreflightTokens.create("", Duration.ofMinutes(5), clock);
        PreflightTokens second = PreflightTokens.create(" ", Duration.ofMinutes(5), clock);
        String token = first.issue(plan, USER, STEPS).token();

        assertThat(catchThrowableOfType(TokenVerificationException.class, () -> second.verify(token, plan, USER)).reason())
                .isEqualTo(TokenVerificationException.Reason.BAD_SIGNATURE);
    }

    private static JsonNode readJson(byte[] json) {
        try {
            return JsonMapper.builder().build().readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A clock the test moves forward by hand. */
    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
