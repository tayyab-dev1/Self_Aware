package com.diversive.school.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.school.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phase 0: migrations run clean, pgvector works, and the seed has the shape the
 * later phases rely on. The numbers mirror the header of V5__seed_demo_school.sql.
 */
class DatabaseMigrationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyMigrationIsAppliedWithoutFailure() {
        List<String> versions = jdbc.sql("""
                        SELECT version FROM flyway_schema_history
                        WHERE version IS NOT NULL AND success
                        ORDER BY installed_rank""")
                .query(String.class)
                .list();
        long failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success")
                .query(Long.class)
                .single();

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(failed).isZero();
    }

    @Test
    void pgvectorIsInstalledAndUsable() {
        assertThat(jdbc.sql("SELECT '[1,2,3]'::vector::text").query(String.class).single())
                .isEqualTo("[1,2,3]");
    }

    @Test
    void seedHasTheSizeThePlanAsksFor() {
        assertThat(count("branches")).isEqualTo(1);
        assertThat(count("app_users")).isEqualTo(1);
        assertThat(count("academic_sessions WHERE is_open")).isEqualTo(1);
        assertThat(count("classes")).isEqualTo(2);
        assertThat(count("sections")).isEqualTo(3);
        assertThat(count("guardians")).isEqualTo(27);
        assertThat(count("students")).isEqualTo(30);
        assertThat(count("enrolments")).isEqualTo(30);
        assertThat(count("fee_invoices")).isEqualTo(60);
        assertThat(count("fee_payments")).isEqualTo(46);
    }

    @Test
    void invoicesHaveARealisticPaidAndUnpaidMix() {
        Map<String, Long> bySettlement = jdbc.sql("""
                        SELECT settlement, count(*) AS invoices
                        FROM fee_invoice_balances
                        GROUP BY settlement""")
                .query((rs, row) -> Map.entry(rs.getString("settlement"), rs.getLong("invoices")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(bySettlement).containsExactlyInAnyOrderEntriesOf(
                Map.of("Paid", 43L, "PartiallyPaid", 3L, "Unpaid", 14L));
    }

    @Test
    void class5BlueOwesFromFewerGuardiansThanStudentsAndFewerStillOnWhatsApp() {
        SectionDebt debt = debtOf("Class 5", "Blue");

        assertThat(debt.students()).isEqualTo(8);
        assertThat(debt.guardians()).isEqualTo(7);
        assertThat(debt.whatsappGuardians()).isEqualTo(5);
        assertThat(debt.outstanding()).isEqualByComparingTo("84500");
    }

    @Test
    void class5GreenOwesAndClass6BlueOwesNothing() {
        SectionDebt green = debtOf("Class 5", "Green");
        SectionDebt class6Blue = debtOf("Class 6", "Blue");

        assertThat(green.students()).isEqualTo(4);
        assertThat(green.guardians()).isEqualTo(4);
        assertThat(green.outstanding()).isEqualByComparingTo("32000");
        assertThat(class6Blue.students()).isZero();
        assertThat(class6Blue.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    void someNamesAreDeliberatelyAmbiguous() {
        long sectionsMatchingClass5 = jdbc.sql("""
                        SELECT count(*) FROM sections s JOIN classes c ON c.id = s.class_id
                        WHERE (c.name || ' ' || s.name) ILIKE '%class 5%'""")
                .query(Long.class)
                .single();
        long sectionsNamedBlue = count("sections WHERE name = 'Blue'");
        long studentsNamedAhmed = count("students WHERE full_name ILIKE 'ahmed %'");

        assertThat(sectionsMatchingClass5).isEqualTo(2);
        assertThat(sectionsNamedBlue).isEqualTo(2);
        assertThat(studentsNamedAhmed).isEqualTo(2);
    }

    private long count(String tableAndFilter) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndFilter).query(Long.class).single();
    }

    private SectionDebt debtOf(String className, String sectionName) {
        return jdbc.sql("""
                        SELECT count(DISTINCT fi.student_id)                                             AS students,
                               count(DISTINCT st.guardian_id)                                            AS guardians,
                               count(DISTINCT st.guardian_id) FILTER (WHERE g.whatsapp_number IS NOT NULL) AS whatsapp_guardians,
                               COALESCE(sum(b.outstanding_amount), 0)                                    AS outstanding
                        FROM fee_invoices fi
                        JOIN fee_invoice_balances b ON b.invoice_id = fi.id
                        JOIN students st            ON st.id = fi.student_id
                        JOIN guardians g            ON g.id = st.guardian_id
                        JOIN sections s             ON s.id = fi.section_id
                        JOIN classes c              ON c.id = s.class_id
                        WHERE fi.status = 'Issued'
                          AND b.outstanding_amount > 0
                          AND c.name = :className
                          AND s.name = :sectionName""")
                .param("className", className)
                .param("sectionName", sectionName)
                .query(SectionDebt.class)
                .single();
    }

    record SectionDebt(long students, long guardians, long whatsappGuardians, BigDecimal outstanding) {
    }
}
