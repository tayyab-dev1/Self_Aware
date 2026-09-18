-- The capability index: one row per capability, description only.
-- This is the AI layer's only table. It never holds business data (CLAUDE.md invariant 1).
--
-- Filled by metadata sync in Phase 4. Hybrid retrieval reads both columns:
--   embedding  dense search (pgvector), BGE-M3 produces 1024 dimensions
--   tsv        lexical search (Postgres full-text), generated from content

CREATE TABLE capability_index (
    capability_id text PRIMARY KEY,
    content       text    NOT NULL,
    module        text    NOT NULL,
    read_only     boolean NOT NULL,
    embedding     vector(1024),
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);

CREATE INDEX capability_index_tsv_idx ON capability_index USING gin (tsv);
-- No vector index: an exact scan is faster and more accurate at this size.
