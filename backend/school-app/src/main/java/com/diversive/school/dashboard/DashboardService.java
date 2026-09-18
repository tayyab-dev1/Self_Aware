package com.diversive.school.dashboard;

import com.diversive.agent.spi.UserContext;
import com.diversive.school.platform.agent.SchoolScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The main dashboard's fee figures (UC-01-07) for the user's branch: today's and this month's collection, and what
 * is outstanding. The POC has one role, so everyone sees the Accounts figures.
 */
@Service
public class DashboardService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public DashboardService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse read(UserContext user) {
        long branchId = SchoolScope.branchId(user);
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        return jdbc.sql("""
                        SELECT br.name AS branch_name,
                               (SELECT COALESCE(sum(p.amount), 0) FROM fee_payments p JOIN fee_invoices fi ON fi.id = p.invoice_id
                                WHERE fi.branch_id = br.id AND p.paid_on = :today)                        AS collected_today,
                               (SELECT COALESCE(sum(p.amount), 0) FROM fee_payments p JOIN fee_invoices fi ON fi.id = p.invoice_id
                                WHERE fi.branch_id = br.id AND p.paid_on BETWEEN :monthStart AND :today)  AS collected_this_month,
                               (SELECT COALESCE(sum(b.outstanding_amount), 0) FROM fee_invoices fi
                                JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                                WHERE fi.branch_id = br.id AND fi.status = 'Issued')                     AS total_outstanding
                        FROM branches br
                        WHERE br.id = :branchId""")
                .param("branchId", branchId)
                .param("today", today)
                .param("monthStart", today.withDayOfMonth(1))
                .query((row, rowNumber) -> new DashboardResponse(row.getString("branch_name"), now,
                        row.getBigDecimal("collected_today"), row.getBigDecimal("collected_this_month"),
                        row.getBigDecimal("total_outstanding")))
                .single();
    }
}
