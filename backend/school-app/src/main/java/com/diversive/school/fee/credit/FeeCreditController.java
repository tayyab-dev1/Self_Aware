package com.diversive.school.fee.credit;

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
 * Crediting a wrong charge already paid (UC-04-10, planning contract UC-04-10-PC-1).
 *
 * <p>Metadata only in the POC: one of the four confusable fee corrections. Its handler is never implemented,
 * so preflight refuses it with {@code NOT_IMPLEMENTED} and its precondition checks need no beans.
 */
@RestController
public class FeeCreditController {

    public static final String CAPABILITY = "fee.credit.raise";

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.SINGLE,
            description = """
                    Proposes crediting a fee charge that was wrong but has already been paid, holding
                    the money on the family's account so it reduces their next bill rather than being
                    handed back. It credits nothing on its own: a School Admin must approve it.
                    Not for a wrong charge nobody has paid yet - use fee.cancellation.raise.
                    Not for a correct debt the school will never collect - use fee.writeoff.propose.
                    Not for removing only a late fee - use fee.latefee.waive.
                    """,
            disambiguateFrom = {"fee.cancellation.raise", "fee.writeoff.propose", "fee.latefee.waive"})
    @AgentNotImplemented
    @AgentParam(name = "invoice_id", meaning = "The paid invoice that carried the wrong charge",
            resolver = "invoice", label = "invoice_label")
    @AgentParam(name = "amount",
            meaning = "How much to credit, all of what was paid or part of it, exactly as the user stated")
    @AgentParam(name = "reason", meaning = "Why the charge was wrong, as the user stated it")
    @AgentParam(name = "description", meaning = "What went wrong, in the user's own words, 20 to 500 characters")
    @AgentPrecondition(id = "invoice_has_payment",
            text = "Money must have been received against the invoice",
            hint = "Nothing has been paid against this invoice yet, so there is nothing to credit")
    @AgentPrecondition(id = "amount_within_received",
            text = "The amount must not be more than was received against the invoice",
            hint = "That is more than was paid against this invoice")
    @AgentPrecondition(id = "invoice_not_settled",
            text = "The invoice must not already be cancelled, credited or written off",
            hint = "This invoice has already been cancelled, credited or written off")
    @AgentEffect(
            creates = "one credit proposal awaiting approval",
            notifies = "the School Admin who approves credits",
            confirmationTemplate = "Propose crediting {amount} on {invoice_label} to the family account because: "
                    + "{reason}. Nothing is credited until a School Admin approves.",
            pendingTemplate = "Propose crediting the charge to the family account.",
            replyTemplate = "Credit {credit_id} proposed for {amount} on {invoice_label}. It now waits for approval.",
            facts = {"credit_id"})
    @PostMapping("/api/v1/fee-credits")
    public void raise(@Valid @RequestBody FeeCreditRequest request) {
        throw PendingImplementations.handlerNotImplemented(CAPABILITY);
    }
}
