package com.diversive.school.fee.overdue;

import com.diversive.agent.spi.UserContext;
import com.diversive.school.fee.overdue.FeeOverdueQuery.AgeBand;
import com.diversive.school.fee.overdue.FeeOverdueResponse.OverdueStudent;
import com.diversive.school.platform.agent.SchoolScope;
import com.diversive.school.platform.format.SchoolFormats;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The overdue list (UC-04-06): students of the user's branch with invoices issued, not fully paid, and past their
 * due date, for the whole school, a class, a section or one student. It only lists; it contacts nobody.
 */
@Service
public class FeeOverdueService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public FeeOverdueService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FeeOverdueResponse list(FeeOverdueQuery query, UserContext user) {
        long branchId = SchoolScope.branchId(user);
        LocalDate today = LocalDate.now(clock);
        int[] band = days(query.ageBand());

        List<OverdueStudent> rows = jdbc.sql("""
                        SELECT st.id AS student_id, st.full_name, st.admission_no, c.name || ' ' || s.name AS section,
                               g.full_name AS guardian_name, sum(b.outstanding_amount) AS outstanding, min(fi.due_on) AS oldest_due_on
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        JOIN students st            ON st.id = fi.student_id
                        JOIN guardians g            ON g.id = st.guardian_id
                        JOIN sections s             ON s.id = fi.section_id
                        JOIN classes c              ON c.id = s.class_id
                        WHERE fi.branch_id = :branchId
                          AND fi.status = 'Issued'
                          AND b.outstanding_amount > 0
                          AND fi.due_on < :today
                          AND (CAST(:classId AS bigint) IS NULL OR s.class_id = :classId)
                          AND (CAST(:sectionId AS bigint) IS NULL OR fi.section_id = :sectionId)
                          AND (CAST(:studentId AS bigint) IS NULL OR fi.student_id = :studentId)
                          AND :today - fi.due_on BETWEEN :fromDays AND :toDays
                        GROUP BY st.id, st.full_name, st.admission_no, c.name, s.name, g.full_name, c.display_order
                        HAVING (CAST(:minimumAmount AS numeric) IS NULL OR sum(b.outstanding_amount) >= :minimumAmount)
                        ORDER BY c.display_order, s.name, st.full_name""")
                .param("branchId", branchId)
                .param("today", today)
                .param("classId", new SqlParameterValue(Types.BIGINT, query.classId()))
                .param("sectionId", new SqlParameterValue(Types.BIGINT, query.sectionId()))
                .param("studentId", new SqlParameterValue(Types.BIGINT, query.studentId()))
                .param("fromDays", band[0])
                .param("toDays", band[1])
                .param("minimumAmount", new SqlParameterValue(Types.NUMERIC, query.minimumAmount()))
                .query((row, rowNumber) -> {
                    LocalDate oldest = row.getDate("oldest_due_on").toLocalDate();
                    return new OverdueStudent(row.getLong("student_id"), row.getString("full_name"), row.getString("admission_no"),
                            row.getString("section"), row.getString("guardian_name"), row.getBigDecimal("outstanding"), oldest,
                            today.toEpochDay() - oldest.toEpochDay());
                })
                .list();

        BigDecimal total = rows.stream().map(OverdueStudent::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FeeOverdueResponse(rows.size(), SchoolFormats.count(rows.size(), "student", "students"),
                scopeName(query, branchId), total, rows);
    }

    /** The age band as a range of days overdue; no band means any overdue debt. */
    private static int[] days(AgeBand band) {
        if (band == null) {
            return new int[] {1, Integer.MAX_VALUE};
        }
        return switch (band) {
            case UP_TO_30_DAYS -> new int[] {1, 30};
            case UP_TO_60_DAYS -> new int[] {31, 60};
            case UP_TO_90_DAYS -> new int[] {61, 90};
            case OVER_90_DAYS -> new int[] {91, Integer.MAX_VALUE};
        };
    }

    private String scopeName(FeeOverdueQuery query, long branchId) {
        return switch (query.scope()) {
            case SCHOOL_WIDE -> "the whole school";
            case CLASS -> name("SELECT c.name FROM classes c JOIN academic_sessions a ON a.id = c.session_id "
                    + "WHERE c.id = :id AND a.branch_id = :branchId", query.classId(), branchId);
            case SECTION -> name("SELECT c.name || ' ' || s.name FROM sections s JOIN classes c ON c.id = s.class_id "
                    + "JOIN academic_sessions a ON a.id = c.session_id WHERE s.id = :id AND a.branch_id = :branchId",
                    query.sectionId(), branchId);
            case STUDENT -> name("SELECT full_name FROM students WHERE id = :id AND branch_id = :branchId",
                    query.studentId(), branchId);
        };
    }

    private String name(String sql, Long id, long branchId) {
        return jdbc.sql(sql).param("id", id).param("branchId", branchId).query(String.class).optional().orElse("that scope");
    }
}
