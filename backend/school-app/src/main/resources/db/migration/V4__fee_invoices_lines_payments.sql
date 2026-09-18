-- Fee (module 4): an invoice per student per billing month, made of lines, paid by
-- payments. How much is owed is never typed: it is billed (sum of lines) less
-- paid (sum of payments), exposed once in the fee_invoice_balances view.

CREATE TABLE fee_invoices (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id       bigint      NOT NULL REFERENCES branches (id),
    session_id      bigint      NOT NULL REFERENCES academic_sessions (id),
    student_id      bigint      NOT NULL REFERENCES students (id),
    section_id      bigint      NOT NULL REFERENCES sections (id),  -- placement when issued; an invoice is a historical record
    invoice_no      text        NOT NULL UNIQUE,                    -- e.g. INV/LHR/26-27/000001
    billing_period  date        NOT NULL CHECK (extract(day FROM billing_period) = 1),  -- first day of the billed month
    issued_on       date        NOT NULL,
    due_on          date        NOT NULL,
    -- Lifecycle only. Whether it is paid comes from payments, never from this column.
    status          text        NOT NULL DEFAULT 'Issued' CHECK (status IN ('Issued', 'Cancelled', 'WrittenOff')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (due_on >= issued_on)
);

-- One live invoice per student per month; a cancelled one may be re-issued.
CREATE UNIQUE INDEX fee_invoices_one_live_per_student_period
    ON fee_invoices (student_id, billing_period) WHERE status <> 'Cancelled';
CREATE INDEX fee_invoices_section_idx ON fee_invoices (section_id);
CREATE INDEX fee_invoices_branch_due_idx ON fee_invoices (branch_id, due_on);

CREATE TABLE fee_invoice_lines (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id   bigint        NOT NULL REFERENCES fee_invoices (id),
    line_type    text          NOT NULL CHECK (line_type IN ('Tuition', 'Transport', 'LateFee', 'Concession')),
    description  text          NOT NULL,
    amount       numeric(12,2) NOT NULL,       -- negative only for a Concession
    CHECK ((line_type = 'Concession') = (amount < 0))
);

CREATE INDEX fee_invoice_lines_invoice_idx ON fee_invoice_lines (invoice_id);

CREATE TABLE fee_payments (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id   bigint        NOT NULL REFERENCES fee_invoices (id),
    receipt_no   text          NOT NULL UNIQUE,  -- e.g. RCT/LHR/26-27/000001
    amount       numeric(12,2) NOT NULL CHECK (amount > 0),
    method       text          NOT NULL CHECK (method IN ('Cash', 'BankChallan', 'OnlineGateway')),
    paid_on      date          NOT NULL,
    recorded_by  bigint        NOT NULL REFERENCES app_users (id),
    created_at   timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX fee_payments_invoice_idx ON fee_payments (invoice_id);

-- Billed, paid and outstanding per invoice, computed in exactly one place.
CREATE VIEW fee_invoice_balances AS
SELECT fi.id                                     AS invoice_id,
       billed.amount                             AS billed_amount,
       COALESCE(paid.amount, 0)                  AS paid_amount,
       billed.amount - COALESCE(paid.amount, 0)  AS outstanding_amount,
       CASE
           WHEN COALESCE(paid.amount, 0) = 0           THEN 'Unpaid'
           WHEN COALESCE(paid.amount, 0) < billed.amount THEN 'PartiallyPaid'
           ELSE 'Paid'
       END                                       AS settlement
FROM fee_invoices fi
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(l.amount), 0) AS amount
    FROM fee_invoice_lines l
    WHERE l.invoice_id = fi.id
) billed
LEFT JOIN LATERAL (
    SELECT SUM(p.amount) AS amount
    FROM fee_payments p
    WHERE p.invoice_id = fi.id
) paid ON true;
