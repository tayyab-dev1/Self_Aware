package com.diversive.agent.registry;

import com.diversive.agent.metadata.CapabilityMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Version = SHA-256 of the entry's canonical JSON, every field except the version itself.
 *
 * <p>Canonical means keys sorted at every level and no whitespace, with a mapper owned here rather
 * than the application's, so the same entry hashes the same everywhere. Changing one character of
 * one description changes that entry's version and no other.
 */
final class CapabilityVersioner {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final TypeReference<Map<String, Object>> AS_MAP = new TypeReference<>() {
    };

    private CapabilityVersioner() {
    }

    static String version(CapabilityMetadata entry) {
        Map<String, Object> fields = CANONICAL.convertValue(entry, AS_MAP);
        fields.remove("version");
        try {
            return sha256(CANONICAL.writeValueAsBytes(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise capability " + entry.id(), e);
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
