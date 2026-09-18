"""Model calls through the Claude Code CLI, on the owner's Claude subscription.

Each call is one headless ``claude -p`` subprocess: the pinned model, a replacement system prompt,
the schema as structured output, no tools, no MCP servers, no settings files, no saved session,
and a private empty working directory so no CLAUDE.md is picked up. The prompt goes in on stdin,
never on the command line, where other processes could read the user's sentence.

``--bare`` is deliberately not used: it only accepts an API key, not the subscription's sign-in.

The CLI thinks before answering by default. A request with ``thinking=False`` sets
``MAX_THINKING_TOKENS=0`` for that one subprocess: decompose took 4-40 s with thinking and a steady
2 s at the API without it.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any, Self

import structlog

from app.core.settings import Settings
from app.llm.runner import ModelOutputError, ModelRequest, ModelUnavailableError

log = structlog.get_logger(__name__)

DESKTOP_APP_CLI_GLOB = "claude-code/*/claude.app/Contents/MacOS/claude"
DESKTOP_APP_SUPPORT = Path.home() / "Library" / "Application Support" / "Claude"


def find_desktop_app_cli(support_dir: Path = DESKTOP_APP_SUPPORT) -> Path | None:
    """The newest CLI bundled with the Claude desktop app, by version number, if any."""

    def version(path: Path) -> tuple[int, ...]:
        name = path.parents[3].name
        return tuple(int(part) if part.isdigit() else -1 for part in name.split("."))

    candidates = [path for path in support_dir.glob(DESKTOP_APP_CLI_GLOB) if path.is_file()]
    return max(candidates, key=version) if candidates else None


class ClaudeCliModel:
    def __init__(self, cli: Path, *, timeout_seconds: float) -> None:
        self._cli = cli
        self._timeout = timeout_seconds

    @classmethod
    def from_settings(cls, settings: Settings) -> Self:
        cli = Path(settings.claude_cli_path) if settings.claude_cli_path else find_desktop_app_cli()
        if cli is None or not cli.is_file():
            raise ModelUnavailableError(
                "No Claude CLI found. Install the Claude desktop app, or set "
                "AI_LAYER_CLAUDE_CLI_PATH to the `claude` binary."
            )
        return cls(cli, timeout_seconds=settings.model_timeout_seconds)

    @property
    def cli(self) -> Path:
        return self._cli

    def arguments(self, request: ModelRequest) -> list[str]:
        return [
            str(self._cli),
            "--print",
            "--model",
            request.model,
            "--output-format",
            "json",
            "--json-schema",
            json.dumps(request.schema, separators=(",", ":")),
            "--system-prompt",
            request.system,
            "--tools",
            "",
            "--strict-mcp-config",
            "--setting-sources",
            "",
            "--no-session-persistence",
        ]

    def environment(self, request: ModelRequest) -> dict[str, str] | None:
        """The subprocess environment: inherited, plus no thinking budget when none is wanted."""
        return None if request.thinking else {**os.environ, "MAX_THINKING_TOKENS": "0"}

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        with tempfile.TemporaryDirectory(prefix="ai-layer-model-") as workdir:
            try:
                process = await asyncio.create_subprocess_exec(
                    *self.arguments(request),
                    cwd=workdir,
                    env=self.environment(request),
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as exc:
                raise ModelUnavailableError(f"Could not start the Claude CLI ({exc})") from exc
            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(request.prompt.encode()), timeout=self._timeout
                )
            except TimeoutError as exc:
                with contextlib.suppress(ProcessLookupError):
                    process.kill()
                await process.wait()
                raise ModelUnavailableError(
                    f"The {request.purpose} call timed out after {self._timeout:g}s"
                ) from exc
            except asyncio.CancelledError:
                with contextlib.suppress(ProcessLookupError):
                    process.kill()
                raise
        return _structured_output(request, process.returncode, stdout, stderr)


def _structured_output(
    request: ModelRequest, returncode: int | None, stdout: bytes, stderr: bytes
) -> dict[str, Any]:
    try:
        envelope = json.loads(stdout)
    except ValueError:
        envelope = None
    if not isinstance(envelope, dict):
        # stderr can echo the prompt, so only its size is reported.
        raise ModelUnavailableError(
            f"The Claude CLI exited with {returncode} and no JSON result "
            f"({len(stderr)} bytes on stderr)"
        )
    usage: dict[str, Any] = envelope["usage"] if isinstance(envelope.get("usage"), dict) else {}
    log.info(
        "model_call",
        purpose=request.purpose,
        model=request.model,
        duration_ms=envelope.get("duration_ms"),
        input_tokens=usage.get("input_tokens"),
        output_tokens=usage.get("output_tokens"),
        outcome=envelope.get("subtype"),
    )
    if envelope.get("is_error") or returncode != 0:
        reason = f"{envelope.get('subtype')} (API status {envelope.get('api_error_status')})"
        if envelope.get("terminal_reason") == "api_error" and isinstance(
            envelope.get("result"), str
        ):
            # An API error's result is the CLI's own message (for example an expired sign-in),
            # never model text, so it is safe to repeat and it says what to fix.
            reason = envelope["result"][:200]
        raise ModelUnavailableError(f"The {request.purpose} call failed: {reason}")
    output = envelope.get("structured_output")
    if not isinstance(output, dict):
        raise ModelOutputError(f"The {request.purpose} call returned no structured output")
    return output
