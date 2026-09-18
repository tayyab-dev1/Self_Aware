package com.diversive.school.student.agent;

import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.platform.agent.NameSearch;
import com.diversive.school.platform.agent.SchoolScope;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "ahmed raza", or an admission number such as "2026-0501", to a student of the user's branch. Two
 * students with the same name are told apart by their section and admission number.
 */
@Component
public class StudentResolver implements EntityResolver {

    private static final String SQL = """
            SELECT st.id, st.full_name, st.admission_no, placement.section
            FROM students st
            LEFT JOIN LATERAL (
                SELECT c.name || ' ' || sec.name AS section
                FROM enrolments e
                JOIN academic_sessions a ON a.id = e.session_id AND a.is_open
                JOIN sections sec        ON sec.id = e.section_id
                JOIN classes c           ON c.id = sec.class_id
                WHERE e.student_id = st.id
            ) placement ON true
            WHERE st.branch_id = :branchId
              AND (lower(st.admission_no) = lower(:raw)
                   OR %s @> string_to_array(:words, ' '))
            ORDER BY st.full_name, st.admission_no
            LIMIT 50""".formatted(NameSearch.wordsOf("st.full_name"));

    private final JdbcClient jdbc;

    public StudentResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "student";
    }

    @Override
    public List<EntityMatch> resolve(String raw, UserContext user) {
        List<String> words = NameSearch.words(raw);
        if (words.isEmpty()) {
            return List.of();
        }
        List<EntityMatch> matches = jdbc.sql(SQL)
                .param("branchId", SchoolScope.branchId(user))
                .param("raw", raw.strip())
                .param("words", NameSearch.sqlWords(words))
                .query((row, rowNumber) -> new EntityMatch(String.valueOf(row.getLong("id")), row.getString("full_name"),
                        context(row.getString("section"), row.getString("admission_no"))))
                .list();
        return NameSearch.preferExact(raw, matches);
    }

    private static String context(String section, String admissionNo) {
        return (section == null ? "" : section + ", ") + "admission no. " + admissionNo;
    }
}
