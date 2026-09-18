package com.diversive.school.platform.agent;

import com.diversive.agent.step.PlainTemplateFormatter;
import com.diversive.agent.spi.TemplateFormatter;
import com.diversive.school.platform.format.SchoolFormats;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * How values read in the school's confirmations. Every decimal a fee capability confirms is money, so it
 * reads as rupees; dates read in words; enums use their display name. Anything else is plain text.
 */
@Component
public class SchoolTemplateFormatter implements TemplateFormatter {

    private final PlainTemplateFormatter plain;

    public SchoolTemplateFormatter(ObjectMapper objectMapper) {
        this.plain = new PlainTemplateFormatter(objectMapper);
    }

    @Override
    public String format(String key, Object value) {
        return switch (value) {
            case DisplayName named -> named.displayName();
            case BigDecimal amount -> SchoolFormats.money(amount);
            case LocalDate date -> SchoolFormats.date(date);
            case OffsetDateTime dateTime -> SchoolFormats.dateTime(dateTime);
            case null, default -> plain.format(key, value);
        };
    }
}
