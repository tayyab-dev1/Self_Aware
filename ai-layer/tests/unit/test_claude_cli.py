from __future__ import annotations

import dataclasses
import json
import stat
import sys
from pathlib import Path

import pytest

from app.core.settings import Settings
from app.llm.claude_cli import ClaudeCliModel, find_desktop_app_cli
from app.llm.runner import DECOMPOSE_MODEL, ModelOutputError, ModelRequest, ModelUnavailableError

SCHEMA = {"type": "object", "properties": {"words": {"type": "integer"}}}
REQUEST = ModelRequest(
    model=DECOMPOSE_MODEL,
    system="Count the words.",
    prompt="Ahmed Raza ki fees",
    schema=SCHEMA,
    purpose="probe",
)


def fake_cli(tmp_path: Path, body: str) -> Path:
    """A stand-in `claude` binary: a Python script that records how it was called."""
    script = tmp_path / "claude"
    script.write_text(
        f"#!{sys.executable}\n"
        "import json, os, sys, time\n"
        "record = {'argv': sys.argv[1:], 'stdin': sys.stdin.read(), 'cwd': os.getcwd(),"
        " 'thinking_budget': os.environ.get('MAX_THINKING_TOKENS')}\n"
        f"open({str(tmp_path / 'call.json')!r}, 'w').write(json.dumps(record))\n" + body
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    return script


def envelope(**fields: object) -> str:
    body = {"type": "result", "subtype": "success", "is_error": False, "duration_ms": 12, **fields}
    return f"print(json.dumps({body!r}))\n"


async def test_the_prompt_goes_in_on_stdin_and_the_structured_output_comes_back(
    tmp_path: Path,
) -> None:
    cli = fake_cli(tmp_path, envelope(structured_output={"words": 4}))

    output = await ClaudeCliModel(cli, timeout_seconds=10).generate(REQUEST)

    call = json.loads((tmp_path / "call.json").read_text())
    assert output == {"words": 4}
    assert call["stdin"] == "Ahmed Raza ki fees"
    assert all("Ahmed" not in argument for argument in call["argv"])
    assert call["cwd"] != str(Path.cwd())


def test_the_call_is_headless_pinned_and_has_no_tools() -> None:
    arguments = ClaudeCliModel(Path("/bin/claude"), timeout_seconds=1).arguments(REQUEST)

    def after(flag: str) -> str:
        return arguments[arguments.index(flag) + 1]

    assert "--print" in arguments and "--no-session-persistence" in arguments
    assert after("--model") == DECOMPOSE_MODEL
    assert after("--output-format") == "json"
    assert json.loads(after("--json-schema")) == SCHEMA
    assert after("--system-prompt") == "Count the words."
    assert after("--tools") == ""
    assert after("--setting-sources") == ""
    assert "--strict-mcp-config" in arguments
    assert "--bare" not in arguments  # it would ignore the subscription sign-in


async def test_a_call_without_thinking_gets_no_thinking_budget_and_others_are_untouched(
    tmp_path: Path,
) -> None:
    cli = fake_cli(tmp_path, envelope(structured_output={"words": 4}))
    model = ClaudeCliModel(cli, timeout_seconds=10)

    await model.generate(REQUEST)
    default = json.loads((tmp_path / "call.json").read_text())
    await model.generate(dataclasses.replace(REQUEST, thinking=False))
    without = json.loads((tmp_path / "call.json").read_text())

    assert default["thinking_budget"] is None
    assert without["thinking_budget"] == "0"


async def test_an_error_result_is_a_model_unavailable_error(tmp_path: Path) -> None:
    cli = fake_cli(tmp_path, envelope(is_error=True, subtype="error_during_execution"))

    with pytest.raises(ModelUnavailableError, match="error_during_execution"):
        await ClaudeCliModel(cli, timeout_seconds=10).generate(REQUEST)


async def test_an_api_error_says_what_went_wrong_such_as_an_expired_sign_in(tmp_path: Path) -> None:
    message = "Failed to authenticate: OAuth session expired and could not be refreshed"
    cli = fake_cli(tmp_path, envelope(is_error=True, terminal_reason="api_error", result=message))

    with pytest.raises(ModelUnavailableError, match="OAuth session expired"):
        await ClaudeCliModel(cli, timeout_seconds=10).generate(REQUEST)


async def test_an_answer_without_structured_output_is_a_model_output_error(tmp_path: Path) -> None:
    cli = fake_cli(tmp_path, envelope(result="four"))

    with pytest.raises(ModelOutputError, match="no structured output"):
        await ClaudeCliModel(cli, timeout_seconds=10).generate(REQUEST)


async def test_output_that_is_not_json_never_echoes_stderr(tmp_path: Path) -> None:
    cli = fake_cli(tmp_path, "sys.stderr.write('Ahmed Raza ki fees'); sys.exit(3)\n")

    with pytest.raises(ModelUnavailableError) as raised:
        await ClaudeCliModel(cli, timeout_seconds=10).generate(REQUEST)

    assert "exited with 3" in str(raised.value)
    assert "Ahmed" not in str(raised.value)


async def test_a_slow_call_is_killed_at_the_timeout(tmp_path: Path) -> None:
    cli = fake_cli(tmp_path, "time.sleep(30)\n")

    with pytest.raises(ModelUnavailableError, match="timed out"):
        await ClaudeCliModel(cli, timeout_seconds=0.5).generate(REQUEST)


def test_the_newest_desktop_app_cli_is_found(tmp_path: Path) -> None:
    for version in ("2.1.9", "2.1.266", "2.1.30"):
        binary = tmp_path / "claude-code" / version / "claude.app" / "Contents" / "MacOS" / "claude"
        binary.parent.mkdir(parents=True)
        binary.write_text("")

    found = find_desktop_app_cli(tmp_path)

    assert found is not None and "2.1.266" in str(found)
    assert find_desktop_app_cli(tmp_path / "nowhere") is None


def test_a_missing_cli_is_reported_with_the_setting_to_fix_it(tmp_path: Path) -> None:
    settings = Settings(claude_cli_path=str(tmp_path / "no-such-claude"))

    with pytest.raises(ModelUnavailableError, match="AI_LAYER_CLAUDE_CLI_PATH"):
        ClaudeCliModel.from_settings(settings)
