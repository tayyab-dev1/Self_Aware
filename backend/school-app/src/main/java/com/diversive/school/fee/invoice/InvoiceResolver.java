package com.diversive.school.fee.invoice;

import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.platform.agent.NameSearch;
import com.diversive.school.platform.agent.SchoolScope;
import com.diversive.school.platform.format.SchoolFormats;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * An invoice, by its number ("INV/LHR/26-27/000031") or by the student and month ("Ahmed Raza's September
 * invoice"), among the user's branch's invoices. People also add the class and section ("Ahmed Raza Class 5
 * Blue fees"), so the words may come from the student's name or the invoice's class and section, but at least
 * one must be from the name: "class 5 blue fees" alone names no student. Every invoice is a candidate, paid or not; the context
 * says what is still owed, so the user can choose, and preconditions decide what may be done with it.
 */
@Component
public class InvoiceResolver implements EntityResolver {

    private static final String SQL = """
            SELECT fi.id, fi.invoice_no, fi.billing_period, fi.status, st.full_name, b.outstanding_amount, b.settlement
            FROM fee_invoices fi
            JOIN students st              ON st.id = fi.student_id
            JOIN fee_invoice_balances b   ON b.invoice_id = fi.id
            JOIN sections sec             ON sec.id = fi.section_id
            JOIN classes c                ON c.id = sec.class_id
            WHERE fi.branch_id = :branchId
              AND (lower(fi.invoice_no) = lower(:raw)
                   OR (:nameWords <> ''
                       AND %s @> string_to_array(:nameWords, ' ')
                       AND %s && string_to_array(:nameWords, ' ')
                       AND (:month = 0 OR extract(month FROM fi.billing_period) = :month)
                       AND (:year = 0 OR extract(year FROM fi.billing_period) = :year)))
            ORDER BY fi.billing_period, st.full_name, fi.invoice_no
            LIMIT 50""".formatted(NameSearch.wordsOf("st.full_name || ' ' || c.name || ' ' || sec.name"),
            NameSearch.wordsOf("st.full_name"));

    private final JdbcClient jdbc;

    public InvoiceResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String lookup() {
        return "the student's name, optionally with the month or year billed and the class and section, e.g. Ahmed Raza September or Ahmed Raza class 5 blue; or the invoice number, e.g. INV/LHR/26-27/000031";
    }

    @Override
    public String type() {
        return "invoice";
    }

    @Override
    public List<EntityMatch> resolve(String raw, UserContext user) {
        InvoicePhrase phrase = InvoicePhrase.parse(raw);
        return jdbc.sql(SQL)
                .param("branchId", SchoolScope.branchId(user))
                .param("raw", raw.strip())
                .param("nameWords", NameSearch.sqlWords(phrase.nameWords()))
                .param("month", phrase.month())
                .param("year", phrase.year())
                .query((row, rowNumber) -> match(row))
                .list();
    }

    /** "Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031", with what is still owed on it. */
    private static EntityMatch match(ResultSet row) throws SQLException {
        String label = row.getString("full_name") + "'s " + SchoolFormats.month(row.getDate("billing_period").toLocalDate())
                + " invoice " + row.getString("invoice_no");
        return new EntityMatch(String.valueOf(row.getLong("id")), label,
                context(row.getString("status"), row.getString("settlement"), row.getBigDecimal("outstanding_amount")));
    }

    private static String context(String status, String settlement, BigDecimal outstanding) {
        return switch (status) {
            case "Cancelled" -> "cancelled";
            case "WrittenOff" -> "written off";
            default -> "Paid".equals(settlement) ? "paid in full" : SchoolFormats.money(outstanding) + " outstanding";
        };
    }
}
