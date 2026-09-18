package com.diversive.school.fee.latefee;

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
 * Waiving a late fee (UC-04-15, planning contract UC-04-15-PC-4). The contract's path carries the
 * late fee id; the POC takes it in the body, like every other capability.
 *
 * <p>Metadata only in the POC: one of the four confusable fee corrections. Its handler is never implemented,
 * so preflight refuses it with {@code NOT_IMPLEMENTED} and its precondition checks need no beans.
 */
@RestController
public class LateFeeWaiverController {

    public static final String CAPABILITY = "fee.latefee.waive";

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.SINGLE,
            description = """
                    Waives a late fee that has already been charged to a family, removing the fine from
                    their invoice while both the fine and its waiver stay on record. One fine at a time,
                    each with its own reason.
                    Not for cancelling or crediting the fee charge itself - use fee.cancellation.raise or fee.credit.raise.
                    Not for writing off an unpaid debt - use fee.writeoff.propose.
                    """,
            disambiguateFrom = {"fee.cancellation.raise", "fee.credit.raise", "fee.writeoff.propose"})
    @AgentNotImplemented
    @AgentParam(name = "late_fee_id",
            meaning = "The late fee to waive, found from the student, the invoice, or the amount and date",
            resolver = "late_fee", label = "late_fee_label")
    @AgentParam(name = "waiver_reason", meaning = "Why the fine is waived, in the user's own words")
    @AgentPrecondition(id = "late_fee_unpaid",
            text = "The late fee must not have been paid",
            hint = "This late fee has already been paid, so it cannot be waived")
    @AgentEffect(
            confirmationTemplate = "Waive {late_fee_label} because: {waiver_reason}. "
                    + "The fine and its waiver both stay on record.",
            pendingTemplate = "Waive the late fee.",
            replyTemplate = "Waived {late_fee_label}.")
    @PostMapping("/api/v1/late-fee-waivers")
    public void waive(@Valid @RequestBody LateFeeWaiverRequest request) {
        throw PendingImplementations.handlerNotImplemented(CAPABILITY);
    }
}
