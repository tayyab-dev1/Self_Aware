"""Embeddings of eval texts, cached on disk so a re-run does not re-embed 700 texts on a CPU.

The cache file is named after the pinned model revision, so a model change starts a new cache
rather than mixing vectors from two models. It lives in eval/.cache/ and is never committed.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Sequence
from pathlib import Path
from typing import Protocol

from app.embeddings.client import EXPECTED_MODEL_REVISION

CACHE_DIR = Path(__file__).resolve().parents[1] / ".cache"
# Saved after each chunk, so an interrupted first run keeps what it already paid for.
CHUNK = 64


class Embedder(Protocol):
    async def require_expected_model(self) -> None: ...

    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class CachingEmbedder:
    def __init__(self, embedder: Embedder, cache_dir: Path = CACHE_DIR) -> None:
        self._embedder = embedder
        self._path = cache_dir / f"embeddings-{EXPECTED_MODEL_REVISION[:12]}.json"
        self._vectors: dict[str, list[float]] = (
            json.loads(self._path.read_text()) if self._path.exists() else {}
        )
        self.embedded_now = 0

    async def require_expected_model(self) -> None:
        await self._embedder.require_expected_model()

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        keys = [_key(text) for text in texts]
        missing = list(
            dict.fromkeys(
                text for text, key in zip(texts, keys, strict=True) if key not in self._vectors
            )
        )
        for start in range(0, len(missing), CHUNK):
            chunk = missing[start : start + CHUNK]
            vectors = await self._embedder.embed(chunk)
            self._vectors.update(zip((_key(text) for text in chunk), vectors, strict=True))
            self.embedded_now += len(chunk)
            self._save()
        return [self._vectors[key] for key in keys]

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self._vectors))
        temporary.replace(self._path)


def _key(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()
