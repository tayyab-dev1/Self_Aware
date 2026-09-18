from __future__ import annotations

from typing import Any

import pytest

from app.decompose.decomposer import Decomposer, check_decomposition
from app.llm.runner import DECOMPOSE_MODEL, ModelRequest
from app.validation.problems import InvalidModelOutputError
from domain.school.glossary import glossary_lines

SENTENCE = (
    "class 5 blue ke defaulters ko whatsapp par reminder bhejo aur Ahmed Raza ka baqaya batao"
)


def intents(*items: tuple[str, list[str]]) -> dict[str, Any]:
    return {"intents": [{"text": text, "entities": entities} for text, entities in items]}


class ScriptedModel:
    def __init__(self, output: dict[str, Any]) -> None:
        self.output = output
        self.requests: list[ModelRequest] = []

    async def generate(self, request: ModelRequest) -> dict[str, Any]:
        self.requests.append(request)
        return self.output


async def test_decompose_asks_the_pinned_model_with_the_glossary_for_english_intents() -> None:
    model = ScriptedModel(
        intents(
            ("Send a WhatsApp fee reminder to the defaulters in class 5 blue", ["class 5 blue"]),
            ("Show how much Ahmed Raza still owes", ["Ahmed Raza"]),
        )
    )

    decomposition = await Decomposer(model, glossary_lines()).decompose(SENTENCE)

    assert decomposition.texts == [
        "Send a WhatsApp fee reminder to the defaulters in class 5 blue",
        "Show how much Ahmed Raza still owes",
    ]
    assert decomposition.intents[1].entities == ("Ahmed Raza",)
    request = model.requests[0]
    assert request.model == DECOMPOSE_MODEL
    assert request.thinking is False  # a simple call: thinking only made it slow
    assert SENTENCE in request.prompt
    assert SENTENCE not in request.system
    assert "- baqaya: outstanding or overdue fees still owed" in request.system
    assert "1 to 3 intents" in request.system


def test_a_name_the_user_never_wrote_is_refused() -> None:
    output = intents(("Show how much Ahmed Raza Khan still owes", ["Ahmed Raza Khan"]))

    with pytest.raises(InvalidModelOutputError) as raised:
        check_decomposition(SENTENCE, output)

    assert raised.value.codes == ["ENTITY_NOT_IN_SENTENCE"]


def test_a_name_translated_or_dropped_from_its_intent_is_refused() -> None:
    output = intents(("Remind the defaulters in grade five blue", ["class 5 blue"]))

    with pytest.raises(InvalidModelOutputError) as raised:
        check_decomposition(SENTENCE, output)

    assert raised.value.codes == ["ENTITY_CHANGED"]


def test_an_intent_left_in_roman_urdu_is_refused_but_names_may_contain_urdu() -> None:
    untranslated = intents(("class 5 blue ke defaulters ko reminder bhejo", ["class 5 blue"]))
    with pytest.raises(InvalidModelOutputError) as raised:
        check_decomposition(SENTENCE, untranslated)
    assert raised.value.codes == ["NOT_ENGLISH"]
    assert "bhejo" in str(raised.value)

    named = "Bilal ke abbu ki fees record karo"
    ok = check_decomposition(
        named, intents(("Record the fee paid by Bilal ke abbu", ["Bilal ke abbu"]))
    )
    assert ok.intents[0].entities == ("Bilal ke abbu",)


@pytest.mark.parametrize(
    "output",
    [
        {"intents": []},
        intents(*[("Show the dashboard", [])] * 4),
        {"intents": [{"text": "Show the dashboard", "entities": [], "capability": "dashboard"}]},
        {"intents": [{"text": "Show the dashboard"}]},
        {"intents": [{"text": "Show the dashboard", "entities": []}], "note": "extra"},
    ],
)
def test_output_outside_the_schema_is_refused(output: dict[str, Any]) -> None:
    with pytest.raises(InvalidModelOutputError) as raised:
        check_decomposition("dashboard dikhao", output)

    assert set(raised.value.codes) == {"SCHEMA"}
