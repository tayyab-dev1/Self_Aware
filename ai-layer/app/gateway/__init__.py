"""The only way into the backend: the agent gateway (``/agent/*``).

``models.py`` is generated from the backend's OpenAPI spec. ``client.py`` is the hand-written
HTTP client around it. Plans name capability ids, never URLs (CLAUDE.md invariant 2).
"""
