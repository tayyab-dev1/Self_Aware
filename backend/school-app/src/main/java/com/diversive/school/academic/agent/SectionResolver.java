package com.diversive.school.academic.agent;

import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.platform.agent.NameSearch;
import com.diversive.school.platform.agent.SchoolScope;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "class 5 blue" to the section Class 5 Blue. People name a section by its class and its own name
 * together, so both are searched as one label. Words that only say "section" ("blue section", "class 5
 * ka blue section") are dropped, since no section's name contains them. Only sections of the open session
 * in the user's branch exist for this query.
 */
@Component
public class SectionResolver implements EntityResolver {

    private static final String SQL = """
            SELECT s.id, c.name || ' ' || s.name AS label, count(e.id) AS students
            FROM sections s
            JOIN classes c            ON c.id = s.class_id
            JOIN academic_sessions a  ON a.id = c.session_id
            LEFT JOIN enrolments e    ON e.section_id = s.id AND e.session_id = a.id
            WHERE a.branch_id = :branchId
              AND a.is_open
              AND c.active
              AND s.active
              AND %s @> string_to_array(:words, ' ')
            GROUP BY s.id, c.name, s.name, c.display_order
            ORDER BY c.display_order, s.name
            LIMIT 50""".formatted(NameSearch.wordsOf("c.name || ' ' || s.name"));

    private static final Set<String> FILLER = Set.of("section", "sections", "the", "of", "ka", "ki", "ke");

    private final JdbcClient jdbc;

    public SectionResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String lookup() {
        return "the class and section together, e.g. class 5 blue, or only the section, e.g. blue";
    }

    @Override
    public String type() {
        return "section";
    }

    @Override
    public List<EntityMatch> resolve(String raw, UserContext user) {
        List<String> words = NameSearch.words(raw).stream().filter(word -> !FILLER.contains(word)).toList();
        if (words.isEmpty()) {
            return List.of();
        }
        List<EntityMatch> matches = jdbc.sql(SQL)
                .param("branchId", SchoolScope.branchId(user))
                .param("words", NameSearch.sqlWords(words))
                .query((row, rowNumber) -> new EntityMatch(String.valueOf(row.getLong("id")), row.getString("label"),
                        students(row.getLong("students"))))
                .list();
        return NameSearch.preferExact(NameSearch.sqlWords(words), matches);
    }

    private static String students(long count) {
        return count == 1 ? "1 student" : count + " students";
    }
}
