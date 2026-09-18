package com.diversive.school.fee.reminder;

import com.diversive.agent.spi.UserContext;
import com.diversive.school.fee.reminder.FeeReminderRepository.ReminderRecipient;
import com.diversive.school.fee.reminder.FeeReminderResponse.SentReminder;
import com.diversive.school.platform.agent.SchoolScope;
import com.diversive.school.platform.format.SchoolFormats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a section's fee reminders (UC-04-07): one reminder log entry per guardian reached, each carrying what that
 * family owes. It asks {@link FeeReminderRepository#recipients} who to reach, the very method the confirmation's
 * count used (invariant 8). Nothing about the invoices changes (BR-10).
 */
@Service
public class FeeReminderService {

    private final FeeReminderRepository reminders;
    private final Clock clock;

    public FeeReminderService(FeeReminderRepository reminders, Clock clock) {
        this.reminders = reminders;
        this.clock = clock;
    }

    @Transactional
    public FeeReminderResponse send(FeeReminderRequest request, UserContext user) {
        long branchId = SchoolScope.branchId(user);
        LocalDate today = LocalDate.now(clock);
        List<ReminderRecipient> recipients = reminders.recipients(request.sectionId(), request.channel(), branchId, today);

        List<SentReminder> sent = new ArrayList<>();
        BigDecimal outstanding = BigDecimal.ZERO;
        for (ReminderRecipient recipient : recipients) {
            long reminderId = reminders.logReminder(branchId, request.sectionId(), recipient, request.channel(),
                    Long.parseLong(user.userId()));
            sent.add(new SentReminder(reminderId, recipient.guardianId(), recipient.students(), recipient.outstanding(), "Queued"));
            outstanding = outstanding.add(recipient.outstanding());
        }
        int notReached = reminders.guardiansWithOverdueFees(request.sectionId(), branchId, today) - recipients.size();
        return new FeeReminderResponse(sent.size(), SchoolFormats.count(sent.size(), "guardian", "guardians"), outstanding,
                notReached, sent);
    }
}
