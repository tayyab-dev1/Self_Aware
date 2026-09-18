package com.diversive.school.fee.reminder;

import com.diversive.school.fee.reminder.FeeReminderRequest.Channel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who a section's fee reminder reaches. The confirmation's count and the send itself (Phase 3) both call
 * {@link #recipients}: the predicate is written once, so the confirmation cannot say 7 while the send
 * reaches 5 (invariant 8).
 */
@Repository
public class FeeReminderRepository {

    /** An overdue invoice: issued, not fully paid, and past its due date. Every query here uses exactly this. */
    private static final String OVERDUE_IN_SECTION = """
            fi.branch_id = :branchId
              AND fi.section_id = :sectionId
              AND fi.status = 'Issued'
              AND b.outstanding_amount > 0
              AND fi.due_on < :today""";

    private final JdbcClient jdbc;

    public FeeReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether anyone in the section has an overdue invoice, whatever their contacts. */
    public boolean sectionHasOverdueInvoices(long sectionId, long branchId, LocalDate today) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM fee_invoices fi
                            JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                            WHERE %s)""".formatted(OVERDUE_IN_SECTION))
                .param("branchId", branchId)
                .param("sectionId", sectionId)
                .param("today", today)
                .query(Boolean.class)
                .single();
    }

    /**
     * One recipient per guardian with an overdue invoice in the section who has a contact for the channel,
     * with what their family owes there. Twins share a guardian, so they get one reminder.
     */
    public List<ReminderRecipient> recipients(long sectionId, Channel channel, long branchId, LocalDate today) {
        String contact = contactColumn(channel);
        return jdbc.sql("""
                        SELECT g.id                          AS guardian_id,
                               g.full_name                   AS guardian_name,
                               %1$s                          AS contact,
                               count(DISTINCT fi.student_id) AS students,
                               sum(b.outstanding_amount)     AS outstanding,
                               min(fi.due_on)                AS oldest_due_on
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        JOIN students st            ON st.id = fi.student_id
                        JOIN guardians g            ON g.id = st.guardian_id
                        WHERE %2$s
                          AND %1$s IS NOT NULL
                        GROUP BY g.id, g.full_name, %1$s
                        ORDER BY g.full_name, g.id""".formatted(contact, OVERDUE_IN_SECTION))
                .param("branchId", branchId)
                .param("sectionId", sectionId)
                .param("today", today)
                .query((row, rowNumber) -> new ReminderRecipient(row.getLong("guardian_id"), row.getString("guardian_name"),
                        row.getString("contact"), row.getInt("students"), row.getBigDecimal("outstanding"),
                        row.getDate("oldest_due_on").toLocalDate()))
                .list();
    }

    /** How many guardians have an overdue invoice in the section, whether or not the channel reaches them. */
    public int guardiansWithOverdueFees(long sectionId, long branchId, LocalDate today) {
        return jdbc.sql("""
                        SELECT count(DISTINCT st.guardian_id)
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        JOIN students st            ON st.id = fi.student_id
                        WHERE %s""".formatted(OVERDUE_IN_SECTION))
                .param("branchId", branchId)
                .param("sectionId", sectionId)
                .param("today", today)
                .query(Integer.class)
                .single();
    }

    /** Logs one reminder, Queued, with what the family owed when it was sent (BR-9). */
    public long logReminder(long branchId, long sectionId, ReminderRecipient recipient, Channel channel, long sentBy) {
        return jdbc.sql("""
                        INSERT INTO fee_reminders (branch_id, section_id, guardian_id, channel, contact, students,
                                                   amount_outstanding, oldest_due_on, sent_by)
                        VALUES (:branchId, :sectionId, :guardianId, :channel, :contact, :students,
                                :amountOutstanding, :oldestDueOn, :sentBy)
                        RETURNING id""")
                .param("branchId", branchId)
                .param("sectionId", sectionId)
                .param("guardianId", recipient.guardianId())
                .param("channel", channelName(channel))
                .param("contact", recipient.contact())
                .param("students", recipient.students())
                .param("amountOutstanding", recipient.outstanding())
                .param("oldestDueOn", recipient.oldestDueOn())
                .param("sentBy", sentBy)
                .query(Long.class)
                .single();
    }

    private static String channelName(Channel channel) {
        return switch (channel) {
            case WHATSAPP -> "WhatsApp";
            case SMS -> "SMS";
            case EMAIL -> "Email";
        };
    }

    /** The column is chosen from the enum, never from anything a user typed. */
    private static String contactColumn(Channel channel) {
        return switch (channel) {
            case WHATSAPP -> "g.whatsapp_number";
            case SMS -> "g.mobile_number";
            case EMAIL -> "g.email";
        };
    }

    /**
     * @param contact     the number or address for the channel; never logged
     * @param students    how many of the guardian's children in the section owe
     * @param outstanding what the family owes on those overdue invoices
     */
    public record ReminderRecipient(long guardianId, String guardianName, String contact, int students,
                                    BigDecimal outstanding, LocalDate oldestDueOn) {

        @Override
        public String toString() {
            return "ReminderRecipient[guardianId=" + guardianId + ", students=" + students + "]";
        }
    }
}
