package com.diversive.school.fee.payment;

import com.diversive.school.platform.agent.DisplayName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Money received against one invoice (UC-04-05). Online payments record themselves, so they are not a route here. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeePaymentRequest(
        @NotNull Long invoiceId,
        @NotNull Route route,
        @NotNull @Positive BigDecimal amountReceived,
        @NotNull @PastOrPresent LocalDate paymentDate,
        @PastOrPresent LocalDate bankStampDate,
        @Size(max = 250) String remarks) {

    public enum Route implements DisplayName {
        CASH("cash", "in cash"), BANK_CHALLAN("bank_challan", "by bank challan");

        private final String wireValue;
        private final String displayName;

        Route(String wireValue, String displayName) {
            this.wireValue = wireValue;
            this.displayName = displayName;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        /** Reads after "received": "received in cash". */
        @Override
        public String displayName() {
            return displayName;
        }
    }
}
