package com.diversive.school.fee.payment;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

/**
 * The payment as recorded, with its receipt (UC-04-05).
 *
 * @param receiptNumber      gapless per branch and academic session, never reused (BR-7)
 * @param invoiceStatus      "Paid", or "Partially paid" while a balance remains (BR-5)
 * @param outstandingBalance what is still owed on the invoice after this payment
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeePaymentResponse(
        long paymentId,
        String receiptNumber,
        BigDecimal amountReceived,
        String invoiceStatus,
        BigDecimal outstandingBalance) {
}
