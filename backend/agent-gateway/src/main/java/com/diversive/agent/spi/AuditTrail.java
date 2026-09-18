package com.diversive.agent.spi;

import java.util.Optional;

/**
 * Where execute records what it did. Supplied by the host application, which owns the table. Append-only:
 * events are added, never changed or removed.
 *
 * <p>Both methods run inside the caller's transaction, so a step's {@code STARTED} and {@code SUCCEEDED}
 * events commit or roll back together with what the step wrote. A refusal or failure is recorded afterwards,
 * in a transaction of its own, so it stays even though the step's writes were rolled back.
 */
public interface AuditTrail {

    /** Adds one event. */
    void append(AuditEvent event);

    /**
     * The recorded result of a write that already succeeded under this idempotency key, so execute can answer
     * with it instead of running the write twice. The host enforces at most one success per key for writes.
     *
     * @return the step result as JSON, exactly as it was appended
     */
    Optional<String> succeededWrite(String idempotencyKey);
}
