package com.diversive.agent.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * A plan's fingerprint: SHA-256 of its canonical JSON, the same way capability versions are made.
 *
 * <p>Canonical means snake_case keys sorted at every level, no whitespace and no absent fields, so
 * anyone can recompute it: in Python, {@code json.dumps(plan, sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)}. Changing any character of the plan changes the hash.
 */
public final class PlanHasher {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private PlanHasher() {
    }

    public static String hash(Plan plan) {
        try {
            // Through a map first, so keys nested inside parameter values are sorted too.
            Object tree = CANONICAL.convertValue(plan, Map.class);
            return sha256(CANONICAL.writeValueAsBytes(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise plan " + plan.planId(), e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
