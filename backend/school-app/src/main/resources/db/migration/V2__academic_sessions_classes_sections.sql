-- Academic structure (module 27): a branch runs academic sessions; a session runs
-- classes ("Class 5"); a class is split into sections ("Blue"). People say
-- "class 5 blue", which is a section of a class.

CREATE TABLE academic_sessions (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id   bigint  NOT NULL REFERENCES branches (id),
    name        text    NOT NULL,              -- e.g. 2026-27
    starts_on   date    NOT NULL,
    ends_on     date    NOT NULL,
    is_open     boolean NOT NULL DEFAULT false,
    UNIQUE (branch_id, name),
    CHECK (ends_on > starts_on)
);

-- At most one open session per branch.
CREATE UNIQUE INDEX academic_sessions_one_open_per_branch
    ON academic_sessions (branch_id) WHERE is_open;

CREATE TABLE classes (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id     bigint  NOT NULL REFERENCES academic_sessions (id),
    name           text    NOT NULL,           -- e.g. Class 5
    display_order  integer NOT NULL,
    active         boolean NOT NULL DEFAULT true,
    UNIQUE (session_id, name)
);

CREATE TABLE sections (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    class_id  bigint  NOT NULL REFERENCES classes (id),
    name      text    NOT NULL,                -- e.g. Blue
    capacity  integer NOT NULL CHECK (capacity > 0),
    active    boolean NOT NULL DEFAULT true,
    UNIQUE (class_id, name)
);
