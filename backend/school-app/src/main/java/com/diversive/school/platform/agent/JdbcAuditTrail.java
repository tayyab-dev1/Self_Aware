package com.diversive.school.platform.agent;

import com.diversive.agent.spi.AuditEvent;
import com.diversive.agent.spi.AuditTrail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The agent gateway's audit trail, in the {@code agent_audit} table (V6). Only ever inserts: the table's
 * trigger refuses any change or removal. Runs in whatever transaction the gateway is in.
 */
@Repository
public class JdbcAuditTrail implements AuditTrail {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditTrail(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql("""
                        INSERT INTO agent_audit (idempotency_key, session_id, plan_id, step, capability_id, capability_version,
                                                 writes, user_id, sentence, kind, params, labels, confirmed_count,
                                                 actual_count, error_code, result)
                        VALUES (:key, :sessionId, :planId, :step, :capabilityId, :capabilityVersion,
                                :writes, :userId, :sentence, :kind, CAST(:params AS jsonb), CAST(:labels AS jsonb), :confirmedCount,
                                :actualCount, :errorCode, CAST(:result AS jsonb))""")
                .param("key", event.idempotencyKey())
                .param("sessionId", event.sessionId())
                .param("planId", event.planId())
                .param("step", event.step())
                .param("capabilityId", event.capabilityId())
                .param("capabilityVersion", event.capabilityVersion())
                .param("writes", event.writes())
                .param("userId", event.userId())
                .param("sentence", event.sentence())
                .param("kind", event.kind().name())
                .param("params", json(event.params()))
                .param("labels", json(event.labels()))
                .param("confirmedCount", new SqlParameterValue(Types.BIGINT, event.confirmedCount()))
                .param("actualCount", new SqlParameterValue(Types.BIGINT, event.count()))
                .param("errorCode", new SqlParameterValue(Types.VARCHAR, event.errorCode()))
                .param("result", new SqlParameterValue(Types.VARCHAR, event.result()))
                .update();
    }

    @Override
    public Optional<String> succeededWrite(String idempotencyKey) {
        return jdbc.sql("""
                        SELECT result::text
                        FROM agent_audit
                        WHERE idempotency_key = :key
                          AND kind = 'SUCCEEDED'
                          AND writes""")
                .param("key", idempotencyKey)
                .query(String.class)
                .optional();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot record audit values", e);
        }
    }
}
