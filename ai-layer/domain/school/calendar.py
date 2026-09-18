"""The school's own clock: what "today" means for a school in Pakistan."""

from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

SCHOOL_TIME_ZONE = "Asia/Karachi"


def school_today() -> date:
    return datetime.now(ZoneInfo(SCHOOL_TIME_ZONE)).date()
