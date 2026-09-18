"""``GET /``: a small page for trying ``POST /chat`` by hand.

A developer tool for the POC, not the product's interface. It is one static file, served from the
same origin as ``/chat`` so no cross-origin setup is needed, and the page never talks to anything
else. The user's token is typed into the page and kept in that browser tab. Turn it off with
``AI_LAYER_CHAT_PAGE_ENABLED=false``.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

PAGE = Path(__file__).resolve().parents[1] / "web" / "chat.html"

router = APIRouter(include_in_schema=False)

_HEADERS = {
    # Everything is inline and same-origin; nothing may be loaded from or sent anywhere else.
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
        "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; "
        "frame-ancestors 'none'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}


@router.get("/", response_class=HTMLResponse)
async def chat_page() -> HTMLResponse:
    return HTMLResponse(PAGE.read_text(encoding="utf-8"), headers=_HEADERS)
