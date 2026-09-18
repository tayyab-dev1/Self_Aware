from __future__ import annotations

import json
from contextlib import aclosing

import httpx
import pytest

from app.embeddings.client import (
    DIMENSIONS,
    EXPECTED_MODEL_ID,
    EXPECTED_MODEL_REVISION,
    EmbeddingsClient,
    EmbeddingsProtocolError,
    EmbeddingsUnavailableError,
    UnexpectedEmbeddingModelError,
    cosine_similarity,
)


def service(handler: httpx.MockTransport) -> EmbeddingsClient:
    return EmbeddingsClient(httpx.AsyncClient(base_url="http://embeddings.test", transport=handler))


def info(
    model_id: str = EXPECTED_MODEL_ID, revision: str = EXPECTED_MODEL_REVISION
) -> httpx.Response:
    return httpx.Response(
        200, json={"model_id": model_id, "model_sha": revision, "max_batch_tokens": 2048}
    )


async def test_the_pinned_model_is_accepted() -> None:
    async with aclosing(service(httpx.MockTransport(lambda _: info()))) as client:
        await client.require_expected_model()


@pytest.mark.parametrize(
    ("model_id", "revision"),
    [("BAAI/bge-small-en", EXPECTED_MODEL_REVISION), (EXPECTED_MODEL_ID, "0" * 40)],
)
async def test_any_other_model_or_revision_is_refused(model_id: str, revision: str) -> None:
    async with aclosing(service(httpx.MockTransport(lambda _: info(model_id, revision)))) as client:
        with pytest.raises(UnexpectedEmbeddingModelError):
            await client.require_expected_model()


async def test_texts_are_embedded_in_batches_and_kept_in_order() -> None:
    batches: list[list[str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["normalize"] is True
        assert body["truncate"] is False
        batches.append(body["inputs"])
        return httpx.Response(
            200, json=[[float(len(text))] * DIMENSIONS for text in body["inputs"]]
        )

    async with aclosing(service(httpx.MockTransport(handler))) as client:
        vectors = await client.embed(["a", "bb", "ccc"], batch_size=2)

    assert batches == [["a", "bb"], ["ccc"]]
    assert [vector[0] for vector in vectors] == [1.0, 2.0, 3.0]


async def test_vectors_of_the_wrong_size_are_refused() -> None:
    handler = httpx.MockTransport(lambda _: httpx.Response(200, json=[[0.1, 0.2]]))

    async with aclosing(service(handler)) as client:
        with pytest.raises(EmbeddingsProtocolError, match="2 dimensions"):
            await client.embed(["a"])


async def test_a_missing_vector_is_refused() -> None:
    handler = httpx.MockTransport(lambda _: httpx.Response(200, json=[]))

    async with aclosing(service(handler)) as client:
        with pytest.raises(EmbeddingsProtocolError, match="0 vectors for 1 texts"):
            await client.embed(["a"])


async def test_an_unreachable_service_says_how_to_start_it() -> None:
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with aclosing(service(httpx.MockTransport(refuse))) as client:
        with pytest.raises(EmbeddingsUnavailableError, match="make embeddings-up"):
            await client.embed(["a"])


def test_cosine_similarity() -> None:
    assert cosine_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert cosine_similarity([1.0, 0.0], [0.0, 2.0]) == pytest.approx(0.0)
    with pytest.raises(ValueError, match="zero vector"):
        cosine_similarity([0.0, 0.0], [1.0, 0.0])
