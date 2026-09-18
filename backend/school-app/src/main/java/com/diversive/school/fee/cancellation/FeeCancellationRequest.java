package com.diversive.school.fee.cancellation;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** Propose cancelling one unpaid invoice raised in error (UC-04-08). The POC has no issue batches. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeCancellationRequest(
        @NotNull Long invoiceId,
        @NotNull Reason reason,
        @NotBlank @Size(min = 20, max = 500) String description) {

    public enum Reason {
        BILLED_TO_WRONG_STUDENT, WRONG_AMOUNT, WRONG_BILLING_PERIOD, DUPLICATE_INVOICE, STUDENT_HAD_LEFT,
        STRUCTURE_APPLIED_IN_ERROR, OTHER;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
