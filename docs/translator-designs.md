# Using a local translator instead of a Gemini call

Every chat message goes through two Gemini calls today: one writes the message in English so we can
search, the other works out the plan. The English one costs 1.5–4 seconds and half our daily quota.

A small translator running on our own machine could do that first job in about a quarter of a
second, for free. This file writes down the four ways we can wire it in, what each one costs, and
what the measurements say.

Measured 22–23 September 2026, on branch `translator-decompose`. Everything here is repeatable:
`make measure` for the default, and `eval/test_measure_translated.py` for the rest.

---

## The tool

- **What it is:** [hasyarshad/roman-urdu-translator](https://github.com/hasyarshad/roman-urdu-translator),
  a trained translator for Roman Urdu and English. 81 million parameters; the weights are 162 MB and
  live on Hugging Face, not in this repository.
- **How fast:** 0.25 s per message on this Mac (slowest seen 0.50 s). It loads once, in about 4 s.
- **How we run it:** as a small service next to the AI layer. It takes `{"text": "..."}` and answers
  `{"english": "..."}`. The AI layer keeps the connection open and warms it up at startup.
- **One rule that matters:** always tell it to translate **into English**. Left to guess, it reads a
  mixed sentence like "credit raise karo" as English and translates it *into* Roman Urdu. That
  mistake alone cost 4 points of search accuracy.

---

## The four designs

### Design A — Gemini writes the English, Jev picks the action (what we run today)

```
message → Gemini writes 1–3 English intents → search → Jev picks → Gemini plans → confirm
```

Gemini does more than translate: it splits "do X and Y" into two, copies names exactly, and its
answer is checked (no Urdu left, every name really in the message).

### Design B — Translator writes the English, no Jev

```
message → translator → search → Gemini plans from all candidates → confirm
```

This was the first idea: drop the Gemini English step, hand the planner the candidates and let it
work from the original Roman Urdu.

### Design C — Translator writes the English, Jev picks from it

```
message → translator → search → Jev picks (sees only the English) → Gemini plans → confirm
```

The fastest arrangement on paper: the cheap English step *and* the short planner prompt.

### Design D — Same as C, but Jev also reads the message as typed

```
message → translator → search → Jev picks (sees the English and the message) → Gemini plans → confirm
```

Jev only ever saw English. Design D gives it the original message too, so a bad translation is not
the last word.

---

## What each design scores

175 messages that should be acted on, 70 that should be refused, English and Roman Urdu, searching
489 school actions.

| | A: today | B: translator only | C: translator + Jev | D: C + the message |
|---|---:|---:|---:|---:|
| **Right action found by search** | **95.4%** | 92.6% | 92.6% | 92.6% |
| **Request planned correctly** | **89.7%** | 87.4% | 81.1% | 85.1% |
| **Acted or refused correctly** | **92.2%** | 90.6% | 85.7% | 87.8% |
| Bad model answers caught | 100% | 100% | 100% | 100% |

Roman Urdu on its own, where the difference shows most:

| | A | B | C | D |
|---|---:|---:|---:|---:|
| Request planned correctly | **88.4%** | 85.7% | 75.9% | 82.1% |
| Acted or refused correctly | **90.5%** | 89.1% | 80.3% | 85.0% |

English is barely affected: 92.1% for A, 90.5% for B, C and D.

---

## What each design costs in time

Measured on the same 10 messages, one after another, on 23 September. Gemini was slow that day, so
compare the columns, not the absolute numbers.

| Step | Time |
|---|---:|
| Translator | 0.25 s |
| Search | 0.24 s |
| Jev picks the action | 0.45 s |
| Gemini plans, from Jev's shortlist | 5.06 s |
| Gemini writes the English (design A only) | 3.64 s |
| Backend lookup and confirmation | 0.1–0.5 s |

| Design | A whole turn |
|---|---:|
| A (today) | ~9.6 s that day, ~6.4 s on a normal day |
| B | not timed; the planner reads every candidate, which cost 2.2 s extra when we measured it |
| C and D | **6.2 s measured** (fastest 4.6 s, slowest 8.4 s) |

So design D saves roughly **3.4 seconds a message**, and halves the Gemini calls, for 4.6 points of
accuracy.

---

## Why the accuracy drops

### 1. The translator does not know school words

Even told to translate into English, it gets the office vocabulary wrong:

| Typed | Translator says | Should be |
|---|---|---|
| credit raise karo | Increase credit | Raise a credit note |
| cancellation raise karo | Repeat the cancellation | Raise a cancellation |
| ye baqaya wasool nahi hoga | This remaining will not be accepted | This outstanding fee will never be recovered |
| saal bhar se 18000 mang rahe hain | They are asking for 18000 years | We have been asking for 18,000 all year |

Four write-off messages stopped reaching their action because of this.

**A partial fix, tested:** swap the school words in the translation before searching (baqaya →
outstanding fees, jurmana → late fee, nadehindagan → defaulters, and a few more). Search accuracy
went from 92.6% to 93.7%, and nothing broke. Still 1.7 points short of design A. Not yet tried end
to end.

### 2. Jev has nothing to fall back on

Gemini plans from the original message, so it survives a poor translation. Jev, in designs C and D,
was only ever shown English.

| What Jev reads | Says "no action fits" when one does |
|---|---:|
| Gemini's intents (design A) | 9 of 172 messages |
| The translation only (design C) | 20 of 169 |
| The translation and the message (design D) | 12 of 169 |

That is the whole difference between C and D: 81.1% against 85.1%.

### 3. The errors stack

The translator loses 2.3 points on its own (A → B). What it then hands to Jev loses 2.3 more
(B → D). Neither loss is large; together they are.

---

## What turned out not to be a problem

- **Splitting two requests into two.** We expected "class 5 blue ke defaulters dikhao aur reminder
  bhej do" to break without Gemini's splitting. It does not: with one translated search, every
  needed action was still among the 30 candidates for **13 of 13** two-part messages (Gemini's
  separate intents managed 12 of 13). Thirty candidates is a wide net.
- **Gemini reading Roman Urdu.** It reads it well. That is why design B loses only 2.3 points while
  search lost 2.9.

---

## What we gave up, besides accuracy

- **The checks.** Gemini's intents are rejected if a name is not in the message, or if Urdu is left
  untranslated. A translator's output is never checked, so a bad translation reaches search unnoticed.
- **Something to ship.** 162 MB of weights and a service to run beside the AI layer, or PyTorch
  inside it.
- **A second thing that can break.** If the translator service is down, chat is down, unless we fall
  back to Gemini.

---

## Where it is clearly worth using

**As a fallback when Gemini cannot be reached** — out of quota, rate limited, or down. Then the
choice is not "translator or Gemini", it is "translator or nothing":

| | Right action found by search |
|---|---:|
| Translator | 92.6% |
| Nothing — search the Roman Urdu as typed | 82.3% |

---

## Recommendation

Keep design A as the default. Design D is 3.4 seconds faster but 4.6 points less accurate, which in
an office means about 1 message in 22 going wrong instead of 1 in 10.

Reconsider if any of these change:
- **The word map is measured end to end.** It already recovered a point of search accuracy.
- **Gemini gets slower or dearer.** The case for design D is speed and quota; if a turn takes 10
  seconds, the trade looks different.
- **A better translator appears**, or this one is fine-tuned on a few hundred school sentences.

---

## How to run each design

```bash
# The translator service: the tool's own Translator class behind about 20 lines of HTTP, on port
# 8099, answering {"text": ...} with {"english": ...}, with the direction forced to English.
# The weights are downloaded from Hugging Face; see the tool's README.

# design A (default)
AI_LAYER_DECOMPOSE_SOURCE=gemini AI_LAYER_CHOOSER_ENABLED=true make ai-run

# design B
AI_LAYER_DECOMPOSE_SOURCE=translator AI_LAYER_CHOOSER_ENABLED=false make ai-run

# design D (C is the same, without the message; the code gives Jev the message
# automatically when the intents come from the translator)
AI_LAYER_DECOMPOSE_SOURCE=translator AI_LAYER_CHOOSER_ENABLED=true make ai-run
```

Measuring again:

```bash
make measure                                            # design A
SCRATCH=<dir> uv run pytest eval/test_measure_translated.py -q -s   # B and D, side by side
```

`SCRATCH` holds `translations_forced.json`, the translator's English for every eval message, so the
measurement does not need the service running.

---

## Where the numbers come from

| Number | Source |
|---|---|
| The four scores per design | `eval/test_measure_translated.py`, replayed from recorded answers |
| Search accuracy | recall@30 on the 489-action stress index |
| Timings | 10 messages, live, 23 Sep 2026 |
| Jev's "none" counts | `eval/recordings/choose*.json` |
| Word map | search only, no model calls |

Related decisions in the main README: 72 and 75 (the chooser), 76 (splitting requests), 77 (the
translator measured against decompose), 78 (how it is wired), 79 (giving Jev the message).
