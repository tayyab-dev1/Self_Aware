-- Execute (Phase 3): the agent gateway's audit trail, and what the executed fee capabilities write.

-- ---------------------------------------------------------------------------
-- The audit trail. One row per thing execute did with one plan step. Append-only:
-- a trigger refuses UPDATE, DELETE and TRUNCATE, even for this table's owner.
-- ---------------------------------------------------------------------------
CREATE TABLE agent_audit (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    idempotency_key     text        NOT NULL,   -- session_id:plan_id:step
    session_id          text        NOT NULL,
    plan_id             text        NOT NULL,
    step                integer     NOT NULL CHECK (step > 0),
    capability_id       text        NOT NULL,
    capability_version  text        NOT NULL,
    writes              boolean     NOT NULL,
    user_id             text        NOT NULL,
    sentence            text        NOT NULL,   -- what the user typed, as the AI layer received it
    kind                text        NOT NULL CHECK (kind IN ('STARTED', 'SUCCEEDED', 'REPLAYED', 'REFUSED', 'FAILED')),
    params              jsonb       NOT NULL,   -- the step's values: ids, never names
    labels              jsonb       NOT NULL,   -- what those ids were shown to the user as
    confirmed_count     bigint,                 -- what the confirmation said the step would touch
    actual_count        bigint,                 -- what it touched
    error_code          text,
    result              jsonb                   -- a succeeded write's result: what a replay answers with
);

CREATE INDEX agent_audit_plan_idx ON agent_audit (session_id, plan_id, step);

-- A write succeeds at most once per idempotency key. A concurrent duplicate fails here,
-- its transaction rolls back, and the retry finds this row and replays it.
CREATE UNIQUE INDEX agent_audit_one_success_per_write
    ON agent_audit (idempotency_key) WHERE kind = 'SUCCEEDED' AND writes;

CREATE FUNCTION agent_audit_refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent_audit is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER agent_audit_append_only
    BEFORE UPDATE OR DELETE ON agent_audit
    FOR EACH ROW EXECUTE FUNCTION agent_audit_refuse_change();

CREATE TRIGGER agent_audit_no_truncate
    BEFORE TRUNCATE ON agent_audit
    FOR EACH STATEMENT EXECUTE FUNCTION agent_audit_refuse_change();

-- ---------------------------------------------------------------------------
-- Fee reminders (UC-04-07): one log entry per guardian reached, with the amount
-- outstanding at the moment of sending (BR-9). Delivery starts Queued and is never
-- reported optimistically (BR-8); the POC has no messaging provider behind it.
-- ---------------------------------------------------------------------------
CREATE TABLE fee_reminders (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id           bigint        NOT NULL REFERENCES branches (id),
    section_id          bigint        NOT NULL REFERENCES sections (id),
    guardian_id         bigint        NOT NULL REFERENCES guardians (id),
    channel             text          NOT NULL CHECK (channel IN ('WhatsApp', 'SMS', 'Email')),
    contact             text          NOT NULL,
    students            integer       NOT NULL CHECK (students > 0),
    amount_outstanding  numeric(12,2) NOT NULL CHECK (amount_outstanding > 0),
    oldest_due_on       date          NOT NULL,
    delivery_status     text          NOT NULL DEFAULT 'Queued'
                                      CHECK (delivery_status IN ('Queued', 'Sent', 'Delivered', 'Failed')),
    sent_by             bigint        NOT NULL REFERENCES app_users (id),
    created_at          timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX fee_reminders_guardian_idx ON fee_reminders (guardian_id);

-- ---------------------------------------------------------------------------
-- Recording a payment (UC-04-05) also keeps the bank's stamp date and a remark
-- printed on the receipt.
-- ---------------------------------------------------------------------------
ALTER TABLE fee_payments
    ADD COLUMN bank_stamp_date date,
    ADD COLUMN remarks         text CHECK (char_length(remarks) <= 250);
