package com.diversive.school.fee.writeoff;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Locale;

/** Propose writing off a correct debt that will never be collected (UC-04-09). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeWriteoffRequest(
        @NotNull Long invoiceId,
        @NotNull @Positive BigDecimal amount,
        @NotNull Reason reason,
        @NotBlank @Size(min = 20, max = 1000) String recoveryAttempted) {

    public enum Reason {
        FAMILY_UNTRACEABLE, STUDENT_LEFT_WITHOUT_SETTLING, FAMILY_UNABLE_TO_PAY, COST_OF_RECOVERY_EXCEEDS_DEBT,
        LEGAL_OR_HUMANITARIAN_DECISION, OTHER;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
