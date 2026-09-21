# Measure: the four numbers

Generated 2026-09-21 11:24 UTC by `make measure`. Decompose `gemini-3.1-flash-lite`, planner `gemini-3.1-flash-lite`, answers from `eval/recordings/`, stress index of 489 capabilities, today fixed at 2026-09-14.

```
                      all             English         Roman Urdu      
recall@30              95.4%           96.8%           94.6%
plan accuracy          90.3%           92.1%           89.3%
refusal correctness    92.2%           93.9%           91.2%
validator catch rate  100.0%          100.0%          100.0%
out of: 175 labelled sentences, 70 to refuse, 584 injected faults
```

- **recall@30**: the expected capability is among the candidates retrieved with decompose's intents.
- **plan accuracy**: the answer is exactly one step (or a request for input) for the expected capability.
- **refusal correctness**: across both sets, the system acted exactly when it should: planned or asked for a labelled sentence, refused (or failed safely) for a sentence to refuse.
- **validator catch rate**: of the faults injected into planner answers the validator had accepted, the share it refused. The change in brackets is against `eval/measure/baseline.json`.

## Outcomes

| set | n | plan | needs input | refusal | invalid answer | model call failed |
|---|---:|---:|---:|---:|---:|---:|
| labelled sentences | 175 | 55 | 104 | 12 | 4 | 0 |
| sentences to refuse | 70 | 1 | 2 | 67 | 0 | 0 |

Rules broken by the answers the validator refused: `ENTITY_CHANGED` 2, `NOT_A_MISSING_PARAMETER` 1, `VALUE_NOT_IN_SENTENCE` 1.

## Plan accuracy by capability

| expected | n | correct | planned instead |
|---|---:|---:|---|
| `dashboard.main.read` | 20 | 14 | refusal (x6) |
| `fee.cancellation.raise` | 25 | 22 | refusal (x3) |
| `fee.credit.raise` | 26 | 25 | refusal (x1) |
| `fee.latefee.waive` | 19 | 18 | invalid (x1) |
| `fee.overdue.list` | 21 | 18 | invalid (x2), fee.overdue.list, fee.overdue.list (x1) |
| `fee.payment.record` | 22 | 22 |  |
| `fee.reminder.send` | 20 | 18 | refusal (x2) |
| `fee.writeoff.propose` | 22 | 21 | invalid (x1) |

## Validator faults

| fault | applied | caught | for the right reason |
|---|---:|---:|---:|
| hallucinated capability id | 56 | 56 | 56 |
| capability outside the allow-list | 56 | 56 | 56 |
| capability not among the candidates | 56 | 56 | 56 |
| invented parameter | 56 | 56 | 56 |
| more than three steps | 56 | 56 | 56 |
| field outside the schema | 56 | 56 | 56 |
| answer contradicting its outcome | 56 | 56 | 56 |
| required parameter left out | 41 | 41 | 41 |
| value of the wrong type | 36 | 36 | 36 |
| value outside the allowed list | 33 | 33 | 33 |
| record name the user never wrote | 33 | 33 | 33 |
| reference to a later step | 41 | 41 | 41 |
| amount the user never wrote | 8 | 8 | 8 |

## Labelled sentences not planned correctly (17)

| sentence | lang | expected | outcome | detail |
|---|---|---|---|---|
| cash kitna para hai? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| how is the school doing today | en | `dashboard.main.read` | refusal | no_matching_capability |
| how many card requests are pending | en | `dashboard.main.read` | refusal | not retrieved |
| kitne students enrolled hain? | ur-Latn | `dashboard.main.read` | refusal | not retrieved |
| school ka aaj kya haal hai? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| subah ke numbers do | ur-Latn | `dashboard.main.read` | refusal | not retrieved |
| paisa abhi tak nahi mila | ur-Latn | `fee.cancellation.raise` | refusal | no_matching_capability |
| propose cancelling the whole batch we raised for the wrong period | en | `fee.cancellation.raise` | refusal | no_matching_capability |
| family ne paisa de diya hai, wo wapas karo | ur-Latn | `fee.credit.raise` | refusal | no_matching_capability |
| jurmana galti se laga hai, wapas lo | ur-Latn | `fee.latefee.waive` | invalid | ENTITY_CHANGED |
| ye family kitna deti hai? | ur-Latn | `fee.overdue.list` | invalid | NOT_A_MISSING_PARAMETER |
| is family ko fees ka reminder karo | ur-Latn | `fee.reminder.send` | refusal | no_matching_capability |
| message the over-90-day defaulters | en | `fee.reminder.send` | refusal | no_matching_capability |
| ye baqaya wasool nahi hoga | ur-Latn | `fee.writeoff.propose` | invalid | ENTITY_CHANGED |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | plan | fee.overdue.list, fee.overdue.list |
| das hazaar se zyada wale nadehindagan kaun hain | ur-Latn | `fee.overdue.list` | invalid | VALUE_NOT_IN_SENTENCE |
| Hamza ka duplicate bill nikal do | ur-Latn | `fee.cancellation.raise` | refusal | no_matching_capability |

## Sentences to refuse that were acted on (3)

| sentence | lang | contract intent | planned |
|---|---|---|---|
| return this credit — no money was received against that invoice | en | fee.credit.return | fee.cancellation.raise |
| return this correction, the charge is what's wrong not the payment | en | fee.payment.correction.return | fee.credit.raise |
| fees ka dashboard kholo | ur-Latn | dashboard.module.view | dashboard.main.read |
