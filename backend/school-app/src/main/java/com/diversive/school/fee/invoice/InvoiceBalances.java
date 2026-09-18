package com.diversive.school.fee.invoice;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What is billed, paid and still owed on one invoice of the user's branch, read from the one place it is
 * computed ({@code fee_invoice_balances}). The invoice checks use it now; recording a payment re-reads it
 * inside its transaction (Phase 3).
 */
@Repository
public class InvoiceBalances {

    private final JdbcClient jdbc;

    public InvoiceBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InvoiceBalance> find(long invoiceId, long branchId) {
        return jdbc.sql("""
                        SELECT fi.status, b.billed_amount, b.paid_amount, b.outstanding_amount
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        WHERE fi.id = :invoiceId
                          AND fi.branch_id = :branchId""")
                .param("invoiceId", invoiceId)
                .param("branchId", branchId)
                .query((row, rowNumber) -> new InvoiceBalance(row.getString("status"), row.getBigDecimal("billed_amount"),
                        row.getBigDecimal("paid_amount"), row.getBigDecimal("outstanding_amount")))
                .optional();
    }

    /** @param status the invoice's lifecycle: Issued, Cancelled or WrittenOff */
    public record InvoiceBalance(String status, BigDecimal billed, BigDecimal paid, BigDecimal outstanding) {

        /** Issued, and not yet fully paid. */
        public boolean isOpen() {
            return "Issued".equals(status) && outstanding.signum() > 0;
        }
    }
}
