package com.diversive.agent.step;

import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.metadata.ParamType;
import com.diversive.agent.registry.RegisteredCapability;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a plan's values into the Java types of the capability's request record, with the same
 * Jackson the endpoint uses and the record's own validation constraints. So a value preflight
 * accepts is one the endpoint accepts, and checks, counts and handlers receive typed values.
 */
public final class ParamBinder {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    /** @param validator runs the request record's constraints; null skips them */
    public ParamBinder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * A value the plan gave, as the field's type.
     *
     * @return the typed value, or empty with the problem written to {@code problem}
     */
    public Optional<Object> bindValue(RegisteredCapability capability, ParamMetadata param, Object value, StringBuilder problem) {
        if (!hasJsonShape(param, value)) {
            problem.append("must be ").append(describe(param));
            return Optional.empty();
        }
        if (!param.allowed().isEmpty() && !allowed(param, value)) {
            problem.append("must be ").append(param.multiple() ? "a list of " : "one of ")
                    .append(String.join(", ", param.allowed()));
            return Optional.empty();
        }
        RecordComponent field = field(capability, param);
        Object typed;
        try {
            typed = objectMapper.convertValue(value, javaType(field));
        } catch (IllegalArgumentException e) {
            problem.append("must be ").append(describe(param));
            return Optional.empty();
        }
        if (validator != null) {
            Set<? extends ConstraintViolation<?>> violations = validateField(capability, field, typed);
            if (!violations.isEmpty()) {
                problem.append(violations.stream().map(ConstraintViolation::getMessage).sorted()
                        .collect(Collectors.joining("; ")));
                return Optional.empty();
            }
        }
        return Optional.of(typed);
    }

    /** The declared default, as the field's type. A default that does not convert is a declaration bug. */
    public Object bindDefault(RegisteredCapability capability, ParamMetadata param) {
        return convertOwnValue(capability, param, param.defaultValue(), "default value");
    }

    /** A resolver's id, or a value an earlier step published, as the field's type. */
    public Object bindId(RegisteredCapability capability, ParamMetadata param, Object id) {
        return convertOwnValue(capability, param, id, "value");
    }

    /**
     * The request record the handler receives, built from typed values by JSON name. Null when the
     * capability takes no input.
     */
    public Object buildRequest(RegisteredCapability capability, Map<String, Object> values) {
        if (capability.requestType() == null) {
            return null;
        }
        Map<String, Object> known = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (capability.requestFields().containsKey(name)) {
                known.put(name, value);
            }
        });
        return objectMapper.convertValue(known, capability.requestType());
    }

    /**
     * What the whole record breaks, for rules across fields such as "a class list needs a class".
     * Empty when it is valid, or when there is no validator.
     */
    public List<String> violations(RegisteredCapability capability, Object request) {
        if (validator == null || request == null) {
            return List.of();
        }
        return validator.validate(request).stream()
                .map(violation -> describeViolation(capability, violation))
                .sorted()
                .toList();
    }

    private static String describeViolation(RegisteredCapability capability, ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return capability.requestFields().entrySet().stream()
                .filter(field -> field.getValue().getName().equals(path))
                .map(field -> "'" + field.getKey() + "' " + violation.getMessage())
                .findFirst()
                .orElse(violation.getMessage());
    }

    private Object convertOwnValue(RegisteredCapability capability, ParamMetadata param, Object value, String what) {
        try {
            return objectMapper.convertValue(value, javaType(field(capability, param)));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(capability.id() + ": " + what + " '" + value + "' of param '" + param.name()
                    + "' is not " + describe(param), e);
        }
    }

    private static RecordComponent field(RegisteredCapability capability, ParamMetadata param) {
        return capability.requestField(param.name()).orElseThrow(() -> new IllegalStateException(
                capability.id() + ": param '" + param.name() + "' has no request field"));
    }

    private JavaType javaType(RecordComponent field) {
        return objectMapper.constructType(field.getGenericType());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Set<? extends ConstraintViolation<?>> validateField(RegisteredCapability capability, RecordComponent field,
                                                                Object typed) {
        return validator.validateValue((Class) capability.requestType(), field.getName(), typed);
    }

    /** Strict JSON shape first, because Jackson alone would quietly turn 12.5 into the integer 12. */
    private static boolean hasJsonShape(ParamMetadata param, Object value) {
        if (param.multiple()) {
            return value instanceof List<?> list && list.stream().allMatch(item -> hasScalarShape(param.type(), item));
        }
        return hasScalarShape(param.type(), value);
    }

    private static boolean hasScalarShape(ParamType type, Object value) {
        return switch (type) {
            case STRING, DATE -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long || value instanceof BigInteger;
            case DECIMAL -> value instanceof Integer || value instanceof Long || value instanceof BigInteger
                    || value instanceof Double || value instanceof BigDecimal
                    || (value instanceof String text && isDecimal(text));
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    private static boolean isDecimal(String text) {
        try {
            new BigDecimal(text.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean allowed(ParamMetadata param, Object value) {
        if (value instanceof List<?> list) {
            return list.stream().allMatch(param.allowed()::contains);
        }
        return param.allowed().contains(value);
    }

    private static String describe(ParamMetadata param) {
        String single = switch (param.type()) {
            case STRING -> "text";
            case INTEGER -> "a whole number";
            case DECIMAL -> "a number (a JSON number, or a decimal string such as \"5000.50\")";
            case BOOLEAN -> "true or false";
            case DATE -> "a date written as YYYY-MM-DD";
        };
        return param.multiple() ? "a list, each item " + single : single;
    }
}
