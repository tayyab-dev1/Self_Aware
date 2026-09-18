"""The capability index: the AI layer's only database access (CLAUDE.md invariant 1).

``database.py`` owns the connection pool, ``migrations.py`` applies the plain-SQL files in
``ai-layer/migrations/``, and ``python -m app.index`` runs those migrations from the shell.
Phase 4 adds hybrid search over this index.
"""
