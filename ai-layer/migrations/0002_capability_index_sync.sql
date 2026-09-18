-- What metadata sync needs to keep the index current, and what retrieval needs for siblings.
--
--   version            the capability's metadata version when this row was written; a different
--                      version from GET /agent/metadata/versions means re-embed
--   disambiguate_from  declared siblings, pulled into the candidates whenever this one is
--   embedding_model    which model and revision produced the embedding; a different one means
--                      re-embed, because vectors from two models cannot be compared
--   synced_at          when sync last wrote the row

ALTER TABLE capability_index
    ADD COLUMN version           text        NOT NULL DEFAULT '',
    ADD COLUMN disambiguate_from text[]      NOT NULL DEFAULT '{}',
    ADD COLUMN embedding_model   text        NOT NULL DEFAULT '',
    ADD COLUMN synced_at         timestamptz NOT NULL DEFAULT now();
