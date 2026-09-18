package com.diversive.school.fee.cancellation;

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
 * Cancelling a wrong unpaid charge (UC-04-08, planning contract UC-04-08-PC-1).
 *
 * <p>Metadata only in the POC: one of the four confusable fee corrections, there so retrieval has to
 * tell them apart. Its handler is never implemented, so preflight refuses it with {@code NOT_IMPLEMENTED}
 * and its precondition checks need no beans.
 */
@RestController
public class FeeCancellationController {

    public static final String CAPABILITY = "fee.cancellation.raise";

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.SINGLE,
            description = """
                    Proposes cancelling a fee charge that should never have been raised and against
                    which nothing has been paid, such as an invoice billed to the wrong student or a
                    duplicate invoice. It cancels nothing on its own: a School Admin must approve it.
                    Not for a wrong charge the family has already paid - use fee.credit.raise.
                    Not for a correct debt the school will never collect - use fee.writeoff.propose.
                    Not for removing only a late fee - use fee.latefee.waive.
                    """,
            disambiguateFrom = {"fee.credit.raise", "fee.writeoff.propose", "fee.latefee.waive"})
    @AgentNotImplemented
    @AgentParam(name = "invoice_id", meaning = "The invoice raised in error", resolver = "invoice", label = "invoice_label")
    @AgentParam(name = "reason", meaning = "Why the charge should not have been raised, as the user stated it")
    @AgentParam(name = "description", meaning = "What went wrong, in the user's own words, 20 to 500 characters")
    @AgentPrecondition(id = "invoice_has_no_payment",
            text = "No money may have been received against the invoice",
            hint = "Money has already been received against this invoice, so it cannot be cancelled")
    @AgentPrecondition(id = "invoice_not_settled",
            text = "The invoice must not already be cancelled, credited or written off",
            hint = "This invoice has already been cancelled, credited or written off")
    @AgentEffect(
            creates = "one cancellation proposal awaiting approval",
            notifies = "the School Admin who approves cancellations",
            confirmationTemplate = "Propose cancelling {invoice_label} because: {reason}. "
                    + "Nothing is cancelled until a School Admin approves.",
            pendingTemplate = "Propose cancelling the invoice.",
            replyTemplate = "Cancellation {cancellation_id} proposed for {invoice_label}. It now waits for approval.",
            facts = {"cancellation_id"})
    @PostMapping("/api/v1/fee-cancellations")
    public void raise(@Valid @RequestBody FeeCancellationRequest request) {
        throw PendingImplementations.handlerNotImplemented(CAPABILITY);
    }
}
