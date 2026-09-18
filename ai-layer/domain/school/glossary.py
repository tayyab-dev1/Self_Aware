"""Words school office staff in Pakistan use that a model may not read the way they mean them.

Given to decompose and the planner as plain lines. Kept short on purpose: a glossary that lists
every phrase becomes a lookup table the eval would then flatter. Add a word only when its meaning
in a school office differs from the dictionary, or it is Urdu the model might misread.
"""

from __future__ import annotations

from collections.abc import Mapping

GLOSSARY: Mapping[str, str] = {
    "challan": "a fee voucher the family pays at a bank; the bank stamps a copy",
    "baqaya": "outstanding or overdue fees still owed",
    "defaulters": "families with overdue fees",
    "jurmana": "a fine; for fees, the late fee",
    "wasooli": "collection of money owed, or recovering a debt",
    "haazri": "attendance",
    "raseed": "a receipt",
    "naqad": "cash",
    "maaf karna": "to waive or forgive (a fine or a debt)",
    "jama karna": "to pay in or deposit money",
    "walidain": "parents or guardians",
    "tajweez": "a proposal that someone else must approve",
}


# Generic record nouns a lookup phrase may add to the user's words ("Zain's September bill"); none
# of them names a person, a class or an amount.
RECORD_WORDS = frozenset(
    {
        "fee",
        "fees",
        "invoice",
        "invoices",
        "bill",
        "bills",
        "challan",
        "section",
        "class",
        "student",
    }
)


def glossary_lines(glossary: Mapping[str, str] = GLOSSARY) -> str:
    return "\n".join(f"- {term}: {meaning}" for term, meaning in glossary.items())
