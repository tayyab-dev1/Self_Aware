"""FastAPI dependencies: how a route gets hold of shared resources."""

from __future__ import annotations

from typing import Annotated, cast

from fastapi import Depends, Request

from app.core.settings import Settings
from app.resources import AppResources


def get_resources(request: Request) -> AppResources:
    return cast(AppResources, request.app.state.resources)


def get_app_settings(request: Request) -> Settings:
    """The settings this app was created with (tests pass their own)."""
    return cast(Settings, request.app.state.settings)


Resources = Annotated[AppResources, Depends(get_resources)]
AppSettings = Annotated[Settings, Depends(get_app_settings)]
