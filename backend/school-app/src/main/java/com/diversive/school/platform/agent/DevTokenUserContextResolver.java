package com.diversive.school.platform.agent;

import com.diversive.agent.spi.UserContext;
import com.diversive.agent.spi.UserContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who a gateway request is for, in the POC: {@code Authorization: Bearer <dev token>} maps to the
 * configured user, whose role and branch come from {@code app_users}. The token is compared in
 * constant time and never logged. Replaced by the school's real sign-in later.
 */
@Component
public class DevTokenUserContextResolver implements UserContextResolver {

    private static final String BEARER = "Bearer ";

    private final JdbcClient jdbc;
    private final DevUserProperties properties;

    public DevTokenUserContextResolver(JdbcClient jdbc, DevUserProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public Optional<UserContext> resolve(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!properties.enabled() || header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        byte[] presented = header.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, expected)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT id, role, branch_id
                        FROM app_users
                        WHERE username = :username AND active""")
                .param("username", properties.username())
                .query((row, rowNumber) -> new UserContext(
                        String.valueOf(row.getLong("id")),
                        Set.of(row.getString("role")),
                        Map.of(SchoolScope.BRANCH_ID, String.valueOf(row.getLong("branch_id")))))
                .optional();
    }
}
