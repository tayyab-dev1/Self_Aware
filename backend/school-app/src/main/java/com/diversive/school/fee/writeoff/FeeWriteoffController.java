package com.diversive.school.fee.writeoff;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentNotImplemented;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.AgentPrecondition;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.school.platform.agent.PendingImplementations;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Writing off an uncollectable debt (UC-04-09, planning contract UC-04-09-PC-1).
 *
 * <p>Metadata only in the POC: one of the four confusable fee corrections. Its handler is never implemented,
 * so preflight refuses it with {@code NOT_IMPLEMENTED} and its precondition checks need no beans.
 */
@RestController
public class FeeWriteoffController {

    public static final String CAPABILITY = "fee.writeoff.propose";

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.SINGLE,
            description = """
                    Proposes writing off a correctly charged fee debt that the school will never
                    collect, for example because the family cannot be traced or cannot pay, with an
                    account of what was done to recover it. It writes off nothing on its own: the
                    Principal must approve it.
                    Not for a charge raised in error - use fee.cancellation.raise, or fee.credit.raise if it was paid.
                    Not for removing only a late fee - use fee.latefee.waive.
                    """,
            disambiguateFrom = {"fee.cancellation.raise", "fee.credit.raise", "fee.latefee.waive"})
    @AgentNotImplemented
    @AgentParam(name = "invoice_id", meaning = "The invoice carrying the debt", resolver = "invoice", label = "invoice_label")
    @AgentParam(name = "amount",
            meaning = "How much to write off, the outstanding balance or part of it, exactly as the user stated")
    @AgentParam(name = "reason", meaning = "Why the debt cannot be collected, as the user stated it")
    @AgentParam(name = "recovery_attempted",
            meaning = "What was actually done to recover the money, in the user's own words, 20 to 1000 characters")
    @AgentPrecondition(id = "invoice_has_outstanding_balance",
            text = "The invoice must have an outstanding balance",
            hint = "Nothing is outstanding on this invoice")
    @AgentPrecondition(id = "amount_within_balance",
            text = "The amount must not be more than is outstanding on the invoice",
            hint = "That is more than is outstanding on this invoice")
    @AgentPrecondition(id = "invoice_not_settled",
            text = "The invoice must not already be cancelled, credited or written off",
            hint = "This invoice has already been cancelled, credited or written off")
    @AgentEffect(
            creates = "one write-off proposal awaiting the Principal's approval",
            notifies = "the Principal",
            confirmationTemplate = "Propose writing off {amount} on {invoice_label} because: {reason}. "
                    + "Nothing is written off until the Principal approves.",
            pendingTemplate = "Propose writing off the debt.",
            replyTemplate = "Write-off {writeoff_id} proposed for {amount} on {invoice_label}. It now waits for the Principal.",
            facts = {"writeoff_id"})
    @PostMapping("/api/v1/fee-writeoffs")
    public void propose(@Valid @RequestBody FeeWriteoffRequest request) {
        throw PendingImplementations.handlerNotImplemented(CAPABILITY);
    }
}
