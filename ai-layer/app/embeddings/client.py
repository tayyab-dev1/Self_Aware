"""Async HTTP client for the embeddings service (Hugging Face Text Embeddings Inference)."""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Self

import httpx
from pydantic import TypeAdapter, ValidationError

from app.core.settings import Settings

# Similarity thresholds only mean something for one exact model, so the model is pinned here and
# checked against what the service reports, not merely configured.
EXPECTED_MODEL_ID = "BAAI/bge-m3"
EXPECTED_MODEL_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
DIMENSIONS = 1024

# The service accepts at most 2048 tokens per batch (docker-compose.yml); a few short texts per
# request stays well inside that.
DEFAULT_BATCH_SIZE = 8

_VECTORS = TypeAdapter(list[list[float]])


class EmbeddingsError(Exception):
    """Base class for every embeddings failure."""


class EmbeddingsUnavailableError(EmbeddingsError):
    """The embeddings service could not be reached. Start it with `make embeddings-up`."""


class EmbeddingsProtocolError(EmbeddingsError):
    """The service answered with something other than the expected vectors."""


class UnexpectedEmbeddingModelError(EmbeddingsError):
    """The service runs a different model or revision than the one thresholds were set for."""


@dataclass(frozen=True, slots=True)
class ModelIdentity:
    model_id: str
    revision: str


class EmbeddingsClient:
    def __init__(self, http: httpx.AsyncClient) -> None:
        self._http = http

    @classmethod
    def from_settings(
        cls, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> Self:
        http = httpx.AsyncClient(
            base_url=str(settings.embeddings_base_url),
            timeout=settings.embeddings_timeout_seconds,
            transport=transport,
        )
        return cls(http)

    async def model(self) -> ModelIdentity:
        """The model the service is running, as it reports it (`GET /info`)."""
        body = await self._request("GET", "/info")
        if not isinstance(body, dict) or not isinstance(body.get("model_id"), str):
            raise EmbeddingsProtocolError("/info did not report a model_id")
        return ModelIdentity(model_id=body["model_id"], revision=str(body.get("model_sha") or ""))

    async def require_expected_model(self) -> None:
        identity = await self.model()
        expected = ModelIdentity(EXPECTED_MODEL_ID, EXPECTED_MODEL_REVISION)
        if identity != expected:
            raise UnexpectedEmbeddingModelError(
                f"Embeddings service runs {identity.model_id}@{identity.revision}, "
                f"expected {expected.model_id}@{expected.revision}"
            )

    async def embed(
        self, texts: Sequence[str], *, batch_size: int = DEFAULT_BATCH_SIZE
    ) -> list[list[float]]:
        """Unit-length vectors, one per text, in the order given."""
        vectors: list[list[float]] = []
        for start in range(0, len(texts), batch_size):
            batch = list(texts[start : start + batch_size])
            body = await self._request(
                "POST", "/embed", json={"inputs": batch, "normalize": True, "truncate": False}
            )
            try:
                batch_vectors = _VECTORS.validate_python(body)
            except ValidationError as exc:
                raise EmbeddingsProtocolError("/embed did not return a list of vectors") from exc
            if len(batch_vectors) != len(batch):
                raise EmbeddingsProtocolError(
                    f"/embed returned {len(batch_vectors)} vectors for {len(batch)} texts"
                )
            for vector in batch_vectors:
                if len(vector) != DIMENSIONS:
                    raise EmbeddingsProtocolError(
                        f"/embed returned {len(vector)} dimensions, expected {DIMENSIONS}"
                    )
            vectors.extend(batch_vectors)
        return vectors

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = await self._http.request(method, path, **kwargs)
        except httpx.TransportError as exc:
            raise EmbeddingsUnavailableError(
                f"Could not reach the embeddings service at {self._http.base_url} "
                f"({type(exc).__name__}). Start it with `make embeddings-up`."
            ) from exc
        if response.status_code != httpx.codes.OK:
            raise EmbeddingsProtocolError(f"{path} returned HTTP {response.status_code}")
        try:
            return response.json()
        except ValueError as exc:
            raise EmbeddingsProtocolError(f"{path} did not return JSON") from exc


def cosine_similarity(first: Sequence[float], second: Sequence[float]) -> float:
    dot = math.fsum(a * b for a, b in zip(first, second, strict=True))
    norms = math.sqrt(math.fsum(a * a for a in first)) * math.sqrt(math.fsum(b * b for b in second))
    if norms == 0:
        raise ValueError("cannot compare a zero vector")
    return dot / norms
