package com.diversive.agent.execute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads what a handler returned by the names the endpoint would answer with, keeping each value's Java type,
 * so a reply formats money as money. Also turns a value into plain text for the audit trail.
 */
final class ResponseReader {

    private final ObjectMapper objectMapper;

    ResponseReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Every property of a handler's response by JSON name, typed. A recorded response (a map) is read as it is. */
    Map<String, Object> properties(Object response) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (response == null) {
            return values;
        }
        if (response instanceof Map<?, ?> recorded) {
            recorded.forEach((name, value) -> values.put(String.valueOf(name), value));
            return values;
        }
        BeanDescription description = objectMapper.getSerializationConfig()
                .introspect(objectMapper.constructType(response.getClass()));
        for (BeanPropertyDefinition property : description.findProperties()) {
            AnnotatedMember accessor = property.getAccessor();
            if (accessor != null) {
                accessor.fixAccess(true);
                values.put(property.getName(), accessor.getValue(response));
            }
        }
        return values;
    }

    /** A value as the plan or the endpoint would write it: "whatsapp", "2026-09-14", "5000.00". */
    String text(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.startsWith("\"") ? objectMapper.readValue(json, String.class) : json;
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot record a step result", e);
        }
    }

    ExecutedStep recordedStep(String json) {
        try {
            return objectMapper.readValue(json, ExecutedStep.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read a recorded step result", e);
        }
    }
}
