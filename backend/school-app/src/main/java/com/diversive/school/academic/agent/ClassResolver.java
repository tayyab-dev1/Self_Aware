package com.diversive.school.academic.agent;

import com.diversive.agent.spi.EntityMatch;
import com.diversive.agent.spi.EntityResolver;
import com.diversive.agent.spi.UserContext;
import com.diversive.school.platform.agent.NameSearch;
import com.diversive.school.platform.agent.SchoolScope;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** "class 5" to the class Class 5, among the open session's classes in the user's branch. */
@Component
public class ClassResolver implements EntityResolver {

    private static final String SQL = """
            SELECT c.id, c.name AS label, count(s.id) AS sections
            FROM classes c
            JOIN academic_sessions a ON a.id = c.session_id
            LEFT JOIN sections s     ON s.class_id = c.id AND s.active
            WHERE a.branch_id = :branchId
              AND a.is_open
              AND c.active
              AND %s @> string_to_array(:words, ' ')
            GROUP BY c.id, c.name, c.display_order
            ORDER BY c.display_order, c.name
            LIMIT 50""".formatted(NameSearch.wordsOf("c.name"));

    private final JdbcClient jdbc;

    public ClassResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String lookup() {
        return "the class's name, e.g. class 5";
    }

    @Override
    public String type() {
        return "class";
    }

    @Override
    public List<EntityMatch> resolve(String raw, UserContext user) {
        List<String> words = NameSearch.words(raw);
        if (words.isEmpty()) {
            return List.of();
        }
        List<EntityMatch> matches = jdbc.sql(SQL)
                .param("branchId", SchoolScope.branchId(user))
                .param("words", NameSearch.sqlWords(words))
                .query((row, rowNumber) -> new EntityMatch(String.valueOf(row.getLong("id")), row.getString("label"),
                        sections(row.getLong("sections"))))
                .list();
        return NameSearch.preferExact(raw, matches);
    }

    private static String sections(long count) {
        return count == 1 ? "1 section" : count + " sections";
    }
}
