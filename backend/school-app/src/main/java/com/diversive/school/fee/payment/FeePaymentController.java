package com.diversive.school.fee.payment;

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

/** Recording a payment (UC-04-05, planning contract UC-04-05-PC-1). */
@RestController
public class FeePaymentController {

    public static final String CAPABILITY = "fee.payment.record";

    private final FeePaymentService payments;

    public FeePaymentController(FeePaymentService payments) {
        this.payments = payments;
    }

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = false,
            blastRadius = BlastRadius.SINGLE,
            reverses = "fee.payment.correction.raise",
            description = """
                    Records money a family has paid against one fee invoice, in cash at the office or
                    through a bank challan, and issues the numbered receipt. Use this when a payment has
                    actually been received.
                    Not for online payments, which record themselves.
                    Not for fixing a payment that was recorded wrongly - use fee.payment.correction.raise.
                    """)
    @AgentParam(name = "invoice_id", meaning = "The invoice the money is for", resolver = "invoice", label = "invoice_label")
    @AgentParam(name = "route", meaning = "How the money arrived")
    @AgentParam(name = "amount_received",
            meaning = "Exactly the amount received, never rounded and never assumed to be the balance")
    @AgentParam(name = "payment_date", meaning = "The day the money was received, not the day it is entered")
    @AgentParam(name = "bank_stamp_date", meaning = "The date stamped on the bank's copy of a challan")
    @AgentParam(name = "remarks", meaning = "A short note printed on the receipt")
    @AgentPrecondition(id = "invoice_is_open",
            text = "The invoice must be issued and not yet fully paid",
            hint = "This invoice has nothing left to pay")
    @AgentPrecondition(id = "amount_within_balance",
            text = "The amount must not be more than is outstanding on the invoice",
            hint = "That is more than is outstanding on this invoice")
    @AgentEffect(
            creates = "one payment and one numbered receipt",
            notifies = "the guardian, who is sent the receipt",
            confirmationTemplate = "Record {amount_received} received {route} on {payment_date} against {invoice_label}.",
            pendingTemplate = "Record the payment against the invoice.",
            replyTemplate = "Recorded {amount_received} against {invoice_label}. Receipt {receipt_number}.",
            facts = {"receipt_number"})
    @PostMapping("/api/v1/fee-payments")
    public FeePaymentResponse record(@Valid @RequestBody FeePaymentRequest request, UserContext user) {
        return payments.record(request, user);
    }
}
