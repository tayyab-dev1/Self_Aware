"""Regenerate the retrieval eval's contract-derived files from planning-contracts.

    uv run python scripts/extract_eval_corpus.py [path/to/routing-index.json]

Writes two files under eval/retrieval/data/ (both committed, so the eval runs without the
planning-contracts checkout):

  contract_sentences.jsonl  every example sentence the contracts route to a capability the POC
                            publishes: "routesHere" examples, and "routesElsewhere" requests whose
                            "goesTo" names a POC capability (the near-misses written to catch
                            confusion). Labelled by the contracts, not by us.
  distractors.jsonl         every other intent's one-line summary, to stand in for the capabilities
                            a full product would index (the stress index).

and one under eval/measure/data/:

  refusal_sentences.jsonl   requests the POC must refuse, labelled by the contracts as routing to a
                            capability the POC does not publish: one for every other fee intent (the
                            hardest to refuse) and one from each other module, alternating English
                            and Roman Urdu.

A sentence the contracts send to two different POC capabilities is dropped and reported.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import structlog

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.capabilities.snapshot import load_snapshot  # noqa: E402

DEFAULT_ROUTING_INDEX = ROOT.parents[1] / "planning-contracts" / "dist" / "routing-index.json"
DATA_DIR = ROOT / "eval" / "retrieval" / "data"
REFUSAL_FILE = ROOT / "eval" / "measure" / "data" / "refusal_sentences.jsonl"

log = structlog.get_logger("extract_eval_corpus")


def _text_and_lang(example: str | dict[str, Any]) -> tuple[str, str, str | None]:
    if isinstance(example, str):
        return example, "en", None
    return example["text"], example.get("lang", "en"), example.get("gloss")


def _key(text: str) -> str:
    return " ".join(text.lower().split())


def main(routing_index: Path) -> int:
    poc_ids = {capability.id for capability in load_snapshot().capabilities}
    intents = json.loads(routing_index.read_text())["intents"]

    sentences: dict[str, dict[str, Any]] = {}
    conflicts: set[str] = set()

    def add(text: str, lang: str, gloss: str | None, expected: str, origin: str) -> None:
        key = _key(text)
        if key in conflicts:
            return
        existing = sentences.get(key)
        if existing is not None:
            if existing["expected"] != expected:
                conflicts.add(key)
                del sentences[key]
            return
        entry = {"text": text, "lang": lang, "expected": expected, "source": "contract"}
        if gloss:
            entry["gloss"] = gloss
        entry["origin"] = origin
        sentences[key] = entry

    distractors = []
    for intent in intents:
        mapped = sorted(set(intent.get("capabilities") or []) & poc_ids)
        if len(mapped) == 1:
            for example in intent.get("routesHere") or []:
                text, lang, gloss = _text_and_lang(example)
                add(text, lang, gloss, mapped[0], f"{intent['intent']} routesHere")
        elif not mapped and intent.get("summary"):
            distractors.append(
                {"id": intent["intent"], "module": intent["module"], "content": intent["summary"]}
            )
        for near_miss in intent.get("routesElsewhere") or []:
            goes_to = near_miss["goesTo"].split()[0]
            if goes_to in poc_ids:
                text, lang, gloss = _text_and_lang(near_miss["request"])
                add(text, lang, gloss, goes_to, f"{intent['intent']} routesElsewhere")

    refusals = _refusal_sentences(intents, poc_ids, set(sentences))
    REFUSAL_FILE.parent.mkdir(parents=True, exist_ok=True)
    _write(REFUSAL_FILE, refusals)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    rows = sorted(sentences.values(), key=lambda entry: (entry["expected"], entry["text"]))
    _write(DATA_DIR / "contract_sentences.jsonl", rows)
    _write(DATA_DIR / "distractors.jsonl", sorted(distractors, key=lambda entry: entry["id"]))
    log.info(
        "eval_corpus_extracted",
        sentences=len(rows),
        distractors=len(distractors),
        dropped_conflicting=sorted(conflicts),
        refusal_sentences=len(refusals),
    )
    return 0


# A contract label the POC could reasonably answer anyway: "43 defaulters kaun hain?" routes to the
# dashboard's drill-through, but fee.overdue.list lists exactly those families. Left out, so the
# refusal set only holds requests nothing in the POC does.
DEBATABLE_REFUSALS = frozenset({"dashboard.figure.drillthrough"})


def _refusal_sentences(
    intents: list[dict[str, Any]], poc_ids: set[str], positive_keys: set[str]
) -> list[dict[str, Any]]:
    elsewhere = sorted(
        (
            i
            for i in intents
            if i.get("intent")
            and i["intent"] not in DEBATABLE_REFUSALS
            and not set(i.get("capabilities") or []) & poc_ids
        ),
        key=lambda i: i["intent"],
    )
    fee = [i for i in elsewhere if i["module"] == "Fee"]
    first_per_module: dict[str, dict[str, Any]] = {}
    for intent in elsewhere:
        if intent["module"] != "Fee":
            first_per_module.setdefault(intent["module"], intent)
    chosen = fee + [first_per_module[module] for module in sorted(first_per_module)]

    rows = []
    for position, intent in enumerate(chosen):
        wanted = "ur-Latn" if position % 2 else "en"
        examples = [_text_and_lang(example) for example in intent.get("routesHere") or []]
        examples = [e for e in examples if _key(e[0]) not in positive_keys]
        pick = next((e for e in examples if e[1] == wanted), examples[0] if examples else None)
        if pick is None:
            continue
        text, lang, gloss = pick
        row = {"text": text, "lang": lang, "source": "contract", "origin": intent["intent"]}
        if gloss:
            row["gloss"] = gloss
        rows.append(row)
    return rows


def _write(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ROUTING_INDEX))
