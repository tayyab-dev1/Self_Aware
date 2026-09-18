package com.diversive.school.fee.payment;

import com.diversive.agent.spi.UserContext;
import com.diversive.school.fee.invoice.InvoiceBalances;
import com.diversive.school.fee.invoice.InvoiceBalances.InvoiceBalance;
import com.diversive.school.platform.agent.SchoolScope;
import java.sql.Types;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records money received against one invoice and issues the numbered receipt (UC-04-05), together or not at all
 * (BR-9). It locks the invoice first and reads its balance through {@link InvoiceBalances}, the same reads the
 * {@code invoice_is_open} and {@code amount_within_balance} checks use, so a payment called directly obeys the
 * same rules as one that went through preflight.
 */
@Service
public class FeePaymentService {

    private final JdbcClient jdbc;
    private final InvoiceBalances balances;

    public FeePaymentService(JdbcClient jdbc, InvoiceBalances balances) {
        this.jdbc = jdbc;
        this.balances = balances;
    }

    @Transactional
    public FeePaymentResponse record(FeePaymentRequest request, UserContext user) {
        long branchId = SchoolScope.branchId(user);
        Invoice invoice = jdbc.sql("""
                        SELECT fi.id, b.code AS branch_code, s.name AS session_name
                        FROM fee_invoices fi
                        JOIN branches b          ON b.id = fi.branch_id
                        JOIN academic_sessions s ON s.id = fi.session_id
                        WHERE fi.id = :invoiceId
                          AND fi.branch_id = :branchId
                        FOR UPDATE OF fi""")
                .param("invoiceId", request.invoiceId())
                .param("branchId", branchId)
                .query((row, rowNumber) -> new Invoice(row.getLong("id"), row.getString("branch_code"), row.getString("session_name")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice"));

        InvoiceBalance before = balances.find(invoice.id(), branchId).orElseThrow();
        if (!before.isOpen()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This invoice has nothing left to pay");
        }
        if (request.amountReceived().compareTo(before.outstanding()) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "That is more than is outstanding on this invoice");
        }

        String receiptNumber = nextReceiptNumber(invoice);
        long paymentId = jdbc.sql("""
                        INSERT INTO fee_payments (invoice_id, receipt_no, amount, method, paid_on, recorded_by, bank_stamp_date, remarks)
                        VALUES (:invoiceId, :receiptNo, :amount, :method, :paidOn, :recordedBy, :bankStampDate, :remarks)
                        RETURNING id""")
                .param("invoiceId", invoice.id())
                .param("receiptNo", receiptNumber)
                .param("amount", request.amountReceived())
                .param("method", switch (request.route()) {
                    case CASH -> "Cash";
                    case BANK_CHALLAN -> "BankChallan";
                })
                .param("paidOn", request.paymentDate())
                .param("recordedBy", Long.parseLong(user.userId()))
                .param("bankStampDate", new SqlParameterValue(Types.DATE, request.bankStampDate()))
                .param("remarks", new SqlParameterValue(Types.VARCHAR, request.remarks()))
                .query(Long.class)
                .single();

        InvoiceBalance after = balances.find(invoice.id(), branchId).orElseThrow();
        return new FeePaymentResponse(paymentId, receiptNumber, request.amountReceived(),
                after.outstanding().signum() == 0 ? "Paid" : "Partially paid", after.outstanding());
    }

    /**
     * The next receipt number in this branch and session, e.g. RCT/LHR/26-27/000047. Gapless: a payment that fails
     * rolls back with its number. Receipt numbers are unique, so two payments at the same moment cannot share one:
     * the second fails, and execute tries it again.
     */
    private String nextReceiptNumber(Invoice invoice) {
        String prefix = "RCT/" + invoice.branchCode() + "/" + invoice.sessionName().substring(2) + "/";
        int next = jdbc.sql("""
                        SELECT COALESCE(max(CAST(substring(receipt_no FROM '([0-9]+)$') AS integer)), 0) + 1
                        FROM fee_payments
                        WHERE receipt_no LIKE :prefix""")
                .param("prefix", prefix + "%")
                .query(Integer.class)
                .single();
        return prefix + String.format("%06d", next);
    }

    private record Invoice(long id, String branchCode, String sessionName) {
    }
}
