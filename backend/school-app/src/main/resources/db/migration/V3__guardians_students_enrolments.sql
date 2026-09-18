-- Student records (module 3): guardians (one per family code), students, and the
-- enrolment that places a student in a section for a session.

CREATE TABLE guardians (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id        bigint NOT NULL REFERENCES branches (id),
    family_code      text   NOT NULL,          -- siblings share it, e.g. FAM-0101
    full_name        text   NOT NULL,
    relationship     text   NOT NULL CHECK (relationship IN ('Father', 'Mother', 'Guardian')),
    -- Contact channels. Any may be missing, so "who can be reached by WhatsApp"
    -- is a real question with a smaller answer than "who owes money".
    mobile_number    text,
    whatsapp_number  text,
    email            text,
    UNIQUE (branch_id, family_code)
);

CREATE TABLE students (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id      bigint NOT NULL REFERENCES branches (id),
    admission_no   text   NOT NULL,            -- e.g. 2026-0501
    full_name      text   NOT NULL,
    gender         text   NOT NULL CHECK (gender IN ('Male', 'Female')),
    date_of_birth  date   NOT NULL,
    guardian_id    bigint NOT NULL REFERENCES guardians (id),
    status         text   NOT NULL DEFAULT 'Enrolled' CHECK (status IN ('Enrolled', 'Withdrawn', 'Graduated')),
    UNIQUE (branch_id, admission_no)
);

CREATE INDEX students_guardian_idx ON students (guardian_id);

CREATE TABLE enrolments (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id   bigint NOT NULL REFERENCES students (id),
    section_id   bigint NOT NULL REFERENCES sections (id),
    session_id   bigint NOT NULL REFERENCES academic_sessions (id),  -- repeated here so one placement per session is a constraint
    enrolled_on  date   NOT NULL,
    UNIQUE (student_id, session_id)
);

CREATE INDEX enrolments_section_idx ON enrolments (section_id);
