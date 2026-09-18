package com.diversive.agent.fixtures;

import com.diversive.agent.spi.AuditEvent;
import com.diversive.agent.spi.AuditTrail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An audit trail in memory, for tests that run without a database (and so without rollbacks). */
public final class MemoryAuditTrail implements AuditTrail {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void append(AuditEvent event) {
        events.add(event);
    }

    @Override
    public Optional<String> succeededWrite(String idempotencyKey) {
        return events.stream()
                .filter(event -> event.kind() == AuditEvent.Kind.SUCCEEDED && event.writes()
                        && event.idempotencyKey().equals(idempotencyKey))
                .map(AuditEvent::result)
                .findFirst();
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public List<AuditEvent.Kind> kinds() {
        return events.stream().map(AuditEvent::kind).toList();
    }
}
