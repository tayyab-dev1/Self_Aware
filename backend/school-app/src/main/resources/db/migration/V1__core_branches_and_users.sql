-- Core: the branch (campus) every record belongs to, and the staff who sign in.
-- Scope checks in later phases filter on branch_id inside the SQL WHERE clause.

CREATE TABLE branches (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        text        NOT NULL UNIQUE,   -- short code used in document numbers, e.g. LHR
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username    text        NOT NULL UNIQUE,
    full_name   text        NOT NULL,
    role        text        NOT NULL,          -- the POC has one role; permissions arrive in later phases
    branch_id   bigint      NOT NULL REFERENCES branches (id),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);
