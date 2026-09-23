# Measure: the four numbers

Generated 2026-09-23 10:13 UTC by `make measure`. Decompose `gemini-3.1-flash-lite`, planner `gemini-3.1-flash-lite`, answers from `eval/recordings/`, stress index of 489 capabilities, today fixed at 2026-09-14.

```
                      all             English         Roman Urdu      
recall@30              95.4%           98.4%           93.8%
plan accuracy          89.7%           92.1%           88.4%
refusal correctness    92.2%           94.9%           90.5%
validator catch rate  100.0%          100.0%          100.0%
out of: 175 labelled sentences, 70 to refuse, 578 injected faults
```

- **recall@30**: the expected capability is among the candidates retrieved with decompose's intents.
- **plan accuracy**: the answer is exactly one step (or a request for input) for the expected capability.
- **refusal correctness**: across both sets, the system acted exactly when it should: planned or asked for a labelled sentence, refused (or failed safely) for a sentence to refuse.
- **validator catch rate**: of the faults injected into planner answers the validator had accepted, the share it refused. The change in brackets is against `eval/measure/baseline.json`.

## Outcomes

| set | n | plan | needs input | refusal | invalid answer | model call failed |
|---|---:|---:|---:|---:|---:|---:|
| labelled sentences | 175 | 55 | 105 | 10 | 5 | 0 |
| sentences to refuse | 70 | 1 | 3 | 65 | 1 | 0 |

Rules broken by the answers the validator refused: `ENTITY_CHANGED` 2, `NOT_A_MISSING_PARAMETER` 1, `ENTITY_NOT_IN_SENTENCE` 1, `VALUE_NOT_IN_SENTENCE` 1, `WORDS_NOT_IN_SENTENCE` 1.

## Plan accuracy by capability

| expected | n | correct | planned instead |
|---|---:|---:|---|
| `dashboard.main.read` | 20 | 14 | refusal (x6) |
| `fee.cancellation.raise` | 25 | 22 | refusal (x3) |
| `fee.credit.raise` | 26 | 23 | refusal (x1), fee.latefee.waive (x1), invalid (x1) |
| `fee.latefee.waive` | 19 | 18 | invalid (x1) |
| `fee.overdue.list` | 21 | 17 | invalid (x2), dashboard.main.read (x1), fee.overdue.list, fee.overdue.list (x1) |
| `fee.payment.record` | 22 | 22 |  |
| `fee.reminder.send` | 20 | 19 | invalid (x1) |
| `fee.writeoff.propose` | 22 | 22 |  |

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
| required parameter left out | 40 | 40 | 40 |
| value of the wrong type | 35 | 35 | 35 |
| record name the user never wrote | 32 | 32 | 32 |
| reference to a later step | 40 | 40 | 40 |
| value outside the allowed list | 32 | 32 | 32 |
| amount the user never wrote | 7 | 7 | 7 |

## Labelled sentences not planned correctly (18)

| sentence | lang | expected | outcome | detail |
|---|---|---|---|---|
| cash kitna para hai? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| how is the school doing today | en | `dashboard.main.read` | refusal | no_matching_capability |
| how many card requests are pending | en | `dashboard.main.read` | refusal | no_matching_capability |
| kitne students enrolled hain? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| school ka aaj kya haal hai? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| subah ke numbers do | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| paisa abhi tak nahi mila | ur-Latn | `fee.cancellation.raise` | refusal | not_a_request |
| propose cancelling the whole batch we raised for the wrong period | en | `fee.cancellation.raise` | refusal | no_matching_capability |
| concession lagni thi lekin nahi lagi, ab paisa wapas karna hai | ur-Latn | `fee.credit.raise` | refusal | not retrieved |
| jurmana jama ho chuka hai, wapas karo | ur-Latn | `fee.credit.raise` | needs_input | fee.latefee.waive |
| jurmana galti se laga hai, wapas lo | ur-Latn | `fee.latefee.waive` | invalid | ENTITY_CHANGED |
| what's the total outstanding across the school | en | `fee.overdue.list` | plan | dashboard.main.read |
| ye family kitna deti hai? | ur-Latn | `fee.overdue.list` | invalid | NOT_A_MISSING_PARAMETER |
| is family ko fees ka reminder karo | ur-Latn | `fee.reminder.send` | invalid | ENTITY_NOT_IN_SENTENCE |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | plan | fee.overdue.list, fee.overdue.list |
| das hazaar se zyada wale nadehindagan kaun hain | ur-Latn | `fee.overdue.list` | invalid | VALUE_NOT_IN_SENTENCE |
| Hamza ka duplicate bill nikal do | ur-Latn | `fee.cancellation.raise` | refusal | no_matching_capability |
| Ali walon ne duplicate bill bhi bhar diya, agle mahine mein adjust karo | ur-Latn | `fee.credit.raise` | invalid | WORDS_NOT_IN_SENTENCE |

## Sentences to refuse that were acted on (4)

| sentence | lang | contract intent | planned |
|---|---|---|---|
| return this credit — no money was received against that invoice | en | fee.credit.return | fee.cancellation.raise |
| policy wapas bhej do, jurmana bohat zyada hai | ur-Latn | fee.latefee.policy.return | fee.latefee.waive |
| return this correction, the charge is what's wrong not the payment | en | fee.payment.correction.return | fee.credit.raise |
| fees ka dashboard kholo | ur-Latn | dashboard.module.view | dashboard.main.read |
