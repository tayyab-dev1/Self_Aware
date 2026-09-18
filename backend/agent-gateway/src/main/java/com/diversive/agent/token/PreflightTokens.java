package com.diversive.agent.token;

import com.diversive.agent.plan.Plan;
import com.diversive.agent.plan.PlanHasher;
import com.diversive.agent.spi.UserContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs and verifies preflight tokens: {@code base64url(payload) + "." + base64url(HMAC-SHA256(payload))}.
 *
 * <p>Nothing is stored. Execute sends the plan and the token back; verifying checks, in order, that
 * the token is well formed, that the signature matches, that it has not expired, that it was issued to
 * this user, and that the plan's hash is the one it was issued for. The AI layer never opens a token.
 */
public final class PreflightTokens {

    /** HMAC-SHA256 keys shorter than its output add no strength, and suggest a placeholder. */
    public static final int MIN_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(PreflightTokens.class);
    private static final int FORMAT = 1;
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;

    public PreflightTokens(byte[] secret, Duration ttl, Clock clock) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("The preflight token secret must be at least " + MIN_SECRET_BYTES
                    + " bytes; it is " + secret.length);
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("The preflight token lifetime must be positive");
        }
        this.key = new SecretKeySpec(secret.clone(), HMAC);
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param configuredSecret the configured secret; blank means a random one, which is safe but lives
     *                         only as long as this process, so tokens die with a restart and are not
     *                         shared between instances
     */
    public static PreflightTokens create(String configuredSecret, Duration ttl, Clock clock) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("No preflight token secret is configured; using a random one. Tokens will not survive a restart "
                    + "or work across instances. Set agent.gateway.preflight.token-secret to fix this.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return new PreflightTokens(random, ttl, clock);
        }
        return new PreflightTokens(configuredSecret.getBytes(StandardCharsets.UTF_8), ttl, clock);
    }

    public Duration ttl() {
        return ttl;
    }

    /** Signs a token for this plan and user, valid for the configured lifetime from now. */
    public Issued issue(Plan plan, UserContext user, List<PreflightToken.Step> steps) {
        Instant expiresAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(ttl);
        PreflightToken payload = new PreflightToken(FORMAT, plan.planId(), PlanHasher.hash(plan), user.userId(),
                expiresAt.getEpochSecond(), steps);
        byte[] json;
        try {
            json = CANONICAL.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialise a preflight token", e);
        }
        return new Issued(ENCODER.encodeToString(json) + "." + ENCODER.encodeToString(sign(json)), expiresAt);
    }

    /**
     * The token's payload, when it vouches for exactly this plan and this user right now.
     *
     * @throws TokenVerificationException saying why it does not
     */
    public PreflightToken verify(String token, Plan plan, UserContext user) throws TokenVerificationException {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 2) {
            throw new TokenVerificationException(TokenVerificationException.Reason.MALFORMED, "The token is not in the expected form");
        }
        byte[] json;
        byte[] signature;
        try {
            json = DECODER.decode(parts[0]);
            signature = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new TokenVerificationException(TokenVerificationException.Reason.MALFORMED, "The token is not base64url");
        }
        if (!MessageDigest.isEqual(sign(json), signature)) {
            throw new TokenVerificationException(TokenVerificationException.Reason.BAD_SIGNATURE,
                    "The token's signature does not match: it was altered, or not issued by this backend");
        }

        PreflightToken payload;
        try {
            payload = CANONICAL.readValue(json, PreflightToken.class);
        } catch (IOException e) {
            throw new TokenVerificationException(TokenVerificationException.Reason.MALFORMED, "The token's payload cannot be read");
        }
        if (payload.format() != FORMAT) {
            throw new TokenVerificationException(TokenVerificationException.Reason.MALFORMED,
                    "The token has payload format " + payload.format() + "; this backend reads " + FORMAT);
        }
        if (!clock.instant().isBefore(Instant.ofEpochSecond(payload.expiresAt()))) {
            throw new TokenVerificationException(TokenVerificationException.Reason.EXPIRED,
                    "The token expired; run preflight again");
        }
        if (!payload.userId().equals(user.userId())) {
            throw new TokenVerificationException(TokenVerificationException.Reason.USER_MISMATCH,
                    "The token was issued to another user");
        }
        if (!MessageDigest.isEqual(payload.planHash().getBytes(StandardCharsets.US_ASCII),
                PlanHasher.hash(plan).getBytes(StandardCharsets.US_ASCII))) {
            throw new TokenVerificationException(TokenVerificationException.Reason.PLAN_MISMATCH,
                    "The plan is not the one the token was issued for");
        }
        return payload;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** A signed token and when it stops being accepted. */
    public record Issued(String token, Instant expiresAt) {
    }
}
