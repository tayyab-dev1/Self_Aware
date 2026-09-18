package com.diversive.school.fee.reminder;

import com.diversive.agent.spi.AffectedCount;
import com.diversive.agent.spi.AffectedCount.CountResult;
import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.fee.reminder.FeeReminderRepository.ReminderRecipient;
import com.diversive.school.fee.reminder.FeeReminderRequest.Channel;
import com.diversive.school.platform.agent.SchoolScope;
import com.diversive.school.platform.format.SchoolFormats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The checks and the count behind {@code fee.reminder.send}, next to the repository its handler will use.
 * The channel check and the count call the same {@link FeeReminderRepository#recipients} the send will.
 */
@Configuration(proxyBeanMethods = false)
class FeeReminderAgentBeans {

    @Bean
    PreconditionCheck sectionHasDefaulters(FeeReminderRepository reminders, Clock clock) {
        return PreconditionCheck.of("section_has_defaulters", (params, user) ->
                reminders.sectionHasOverdueInvoices(sectionId(params), SchoolScope.branchId(user), LocalDate.now(clock)));
    }

    @Bean
    PreconditionCheck channelReachesDefaulters(FeeReminderRepository reminders, Clock clock) {
        return PreconditionCheck.of("channel_reaches_defaulters", (params, user) ->
                !recipients(reminders, clock, params, user).isEmpty());
    }

    /**
     * One per guardian reached, and what their families owe, which the confirmation says out loud. The count is
     * also published as words ("1 guardian", "5 guardians"), because a template cannot choose a plural.
     */
    @Bean
    AffectedCount feeReminderSendCount(FeeReminderRepository reminders, Clock clock) {
        return AffectedCount.of(FeeReminderController.CAPABILITY, (params, user) -> {
            List<ReminderRecipient> recipients = recipients(reminders, clock, params, user);
            BigDecimal outstanding = recipients.stream().map(ReminderRecipient::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CountResult(recipients.size(), "guardians", Map.of(
                    "guardians", SchoolFormats.count(recipients.size(), "guardian", "guardians"),
                    "total_outstanding", outstanding));
        });
    }

    private static List<ReminderRecipient> recipients(FeeReminderRepository reminders, Clock clock,
                                                      Map<String, Object> params, UserContext user) {
        return reminders.recipients(sectionId(params), (Channel) params.get("channel"), SchoolScope.branchId(user),
                LocalDate.now(clock));
    }

    private static long sectionId(Map<String, Object> params) {
        return (Long) params.get("section_id");
    }
}
