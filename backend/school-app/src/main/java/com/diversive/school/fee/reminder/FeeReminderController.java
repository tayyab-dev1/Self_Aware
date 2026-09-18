package com.diversive.school.fee.reminder;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.AgentPrecondition;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.spi.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Fee reminders (UC-04-07, planning contract UC-04-07-PC-1). */
@RestController
public class FeeReminderController {

    public static final String CAPABILITY = "fee.reminder.send";

    private final FeeReminderService reminders;

    public FeeReminderController(FeeReminderService reminders) {
        this.reminders = reminders;
    }

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.GROUP,
            description = """
                    Sends a fee reminder by WhatsApp, SMS or email to the guardians of every student in
                    a section who has overdue fees, each message carrying that family's own amount and
                    due date. Use this for chasing overdue payments.
                    Not for seeing who owes money without contacting anyone - use fee.overdue.list.
                    """,
            disambiguateFrom = {"fee.overdue.list"})
    @AgentParam(name = "section_id", meaning = "The section whose families with overdue fees are reminded, e.g. Class 5 Blue",
            resolver = "section", label = "section_name")
    @AgentParam(name = "channel", meaning = "How the reminder is delivered", defaultValue = "whatsapp")
    @AgentPrecondition(id = "section_has_defaulters",
            text = "The section must have at least one unpaid invoice past its due date",
            hint = "Nobody in this section has overdue fees right now")
    @AgentPrecondition(id = "channel_reaches_defaulters",
            text = "At least one guardian with overdue fees in the section must have a contact for the chosen channel",
            hint = "No guardian with overdue fees in this section can be reached by that channel")
    @AgentEffect(
            creates = "one reminder log entry per guardian reached",
            notifies = "the guardians of students with overdue fees in the section",
            confirmationTemplate = "Send a fee reminder to {guardians} in {section_name} by {channel}, "
                    + "covering {total_outstanding} outstanding.",
            pendingTemplate = "Send a fee reminder to the families with overdue fees.",
            replyTemplate = "Sent a fee reminder to {guardians} in {section_name} by {channel}.",
            facts = {"guardians", "total_outstanding"})
    @PostMapping("/api/v1/fee-reminders")
    public FeeReminderResponse send(@Valid @RequestBody FeeReminderRequest request, UserContext user) {
        return reminders.send(request, user);
    }
}
