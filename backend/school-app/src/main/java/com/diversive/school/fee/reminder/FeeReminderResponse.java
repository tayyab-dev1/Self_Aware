package com.diversive.school.fee.reminder;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;

/**
 * What a reminder send did.
 *
 * @param count            reminders logged, one per guardian reached; execute checks it against the confirmed count
 * @param guardians        the same number in words, e.g. "5 guardians"
 * @param totalOutstanding what the families reached owe on their overdue invoices
 * @param notReached       guardians with overdue fees in the section who have no contact for the channel
 * @param sent             each reminder logged, Queued until a provider says otherwise (BR-8)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeReminderResponse(
        long count,
        String guardians,
        BigDecimal totalOutstanding,
        int notReached,
        List<SentReminder> sent) {

    public FeeReminderResponse {
        sent = List.copyOf(sent);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SentReminder(long reminderId, long guardianId, int students, BigDecimal amountOutstanding,
                               String deliveryStatus) {
    }
}
