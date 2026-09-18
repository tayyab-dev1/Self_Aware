package com.diversive.agent.step;

import com.diversive.agent.spi.TemplateFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The gateway's default: values as their plain text. Enums read as their JSON value (the same word the
 * plan used), decimals without trailing zeros, lists joined with commas. A host application that wants
 * currencies, dates in words or friendlier names supplies its own {@link TemplateFormatter}, and may
 * delegate to this one for everything else.
 */
public class PlainTemplateFormatter implements TemplateFormatter {

    private final ObjectMapper objectMapper;

    public PlainTemplateFormatter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String format(String key, Object value) {
        return switch (value) {
            case null -> "";
            case String text -> text;
            case BigDecimal decimal -> decimal.stripTrailingZeros().toPlainString();
            case Enum<?> constant -> objectMapper.convertValue(constant, String.class);
            case Collection<?> values -> values.stream().map(item -> format(key, item)).collect(Collectors.joining(", "));
            default -> String.valueOf(value);
        };
    }
}
