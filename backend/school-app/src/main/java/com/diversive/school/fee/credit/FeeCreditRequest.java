package com.diversive.school.fee.credit;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Locale;

/** Propose crediting a wrong charge that has already been paid (UC-04-10). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeCreditRequest(
        @NotNull Long invoiceId,
        @NotNull @Positive BigDecimal amount,
        @NotNull Reason reason,
        @NotBlank @Size(min = 20, max = 500) String description) {

    public enum Reason {
        WRONG_AMOUNT_CHARGED, CHARGED_TO_WRONG_STUDENT, DUPLICATE_CHARGE, CONCESSION_NOT_APPLIED,
        CHARGE_SHOULD_NOT_HAVE_BEEN_RAISED, OTHER;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
