# Measure: the four numbers

Generated 2026-09-18 09:31 UTC by `make measure`. Decompose `claude-haiku-4-5-20251001`, planner `claude-sonnet-5`, answers from `eval/recordings/`, stress index of 489 capabilities, today fixed at 2026-09-14.

```
                      all             English         Roman Urdu      
recall@30              95.4%           96.8%           94.6%
plan accuracy          88.6%           93.7%           85.7%
refusal correctness    92.7%           96.9%           89.8%
validator catch rate  100.0%          100.0%          100.0%
out of: 175 labelled sentences, 70 to refuse, 600 injected faults
```

- **recall@30**: the expected capability is among the candidates retrieved with Haiku's intents.
- **plan accuracy**: the answer is exactly one step (or a request for input) for the expected capability.
- **refusal correctness**: across both sets, the system acted exactly when it should: planned or asked for a labelled sentence, refused (or failed safely) for a sentence to refuse.
- **validator catch rate**: of the faults injected into planner answers the validator had accepted, the share it refused. The change in brackets is against `eval/measure/baseline.json`.

## Outcomes

| set | n | plan | needs input | refusal | invalid answer | model call failed |
|---|---:|---:|---:|---:|---:|---:|
| labelled sentences | 175 | 57 | 101 | 13 | 4 | 0 |
| sentences to refuse | 70 | 1 | 0 | 68 | 1 | 0 |

Rules broken by the answers the validator refused: `NOT_A_CANDIDATE` 2, `NOT_A_MISSING_PARAMETER` 1, `VALUE_NOT_IN_SENTENCE` 1, `ENTITY_NOT_IN_SENTENCE` 1.

## Plan accuracy by capability

| expected | n | correct | planned instead |
|---|---:|---:|---|
| `dashboard.main.read` | 20 | 14 | refusal (x5), invalid (x1) |
| `fee.cancellation.raise` | 25 | 23 | refusal (x2) |
| `fee.credit.raise` | 26 | 21 | refusal (x2), invalid (x1), fee.payment.record (x1), fee.latefee.waive (x1) |
| `fee.latefee.waive` | 19 | 19 |  |
| `fee.overdue.list` | 21 | 18 | invalid (x2), dashboard.main.read (x1) |
| `fee.payment.record` | 22 | 22 |  |
| `fee.reminder.send` | 20 | 18 | refusal (x2) |
| `fee.writeoff.propose` | 22 | 20 | refusal (x2) |

## Validator faults

| fault | applied | caught | for the right reason |
|---|---:|---:|---:|
| hallucinated capability id | 58 | 58 | 58 |
| capability outside the allow-list | 58 | 58 | 58 |
| capability not among the candidates | 58 | 58 | 58 |
| invented parameter | 58 | 58 | 58 |
| more than three steps | 58 | 58 | 58 |
| field outside the schema | 58 | 58 | 58 |
| answer contradicting its outcome | 58 | 58 | 58 |
| required parameter left out | 42 | 42 | 42 |
| value of the wrong type | 38 | 38 | 38 |
| value outside the allowed list | 35 | 35 | 35 |
| record name the user never wrote | 32 | 32 | 32 |
| reference to a later step | 42 | 42 | 42 |
| amount the user never wrote | 5 | 5 | 5 |

## Labelled sentences not planned correctly (20)

| sentence | lang | expected | outcome | detail |
|---|---|---|---|---|
| how is the school doing today | en | `dashboard.main.read` | refusal | not_a_request |
| how many card requests are pending | en | `dashboard.main.read` | refusal | no_matching_capability |
| kitne students enrolled hain? | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| school ka aaj kya haal hai? | ur-Latn | `dashboard.main.read` | refusal | not_a_request |
| subah ke numbers do | ur-Latn | `dashboard.main.read` | refusal | no_matching_capability |
| paisa abhi tak nahi mila | ur-Latn | `fee.cancellation.raise` | refusal | not_a_request |
| concession lagni thi lekin nahi lagi, ab paisa wapas karna hai | ur-Latn | `fee.credit.raise` | invalid | NOT_A_CANDIDATE |
| do dafa bill bana tha aur dono jama ho gaye | ur-Latn | `fee.credit.raise` | needs_input | fee.payment.record |
| family ne paisa de diya hai, wo wapas karo | ur-Latn | `fee.credit.raise` | refusal | no_matching_capability |
| jurmana jama ho chuka hai, wapas karo | ur-Latn | `fee.credit.raise` | needs_input | fee.latefee.waive |
| ye bill kam kar do | ur-Latn | `fee.credit.raise` | refusal | no_matching_capability |
| what's the total outstanding across the school | en | `fee.overdue.list` | plan | dashboard.main.read |
| ye family kitna deti hai? | ur-Latn | `fee.overdue.list` | invalid | NOT_A_MISSING_PARAMETER |
| is family ko fees ka reminder karo | ur-Latn | `fee.reminder.send` | refusal | no_matching_capability |
| message the over-90-day defaulters | en | `fee.reminder.send` | refusal | no_matching_capability |
| bacha bina fees diye chala gaya, ab kuch nahi hoga | ur-Latn | `fee.writeoff.propose` | refusal | not_a_request |
| das hazaar se zyada wale nadehindagan kaun hain | ur-Latn | `fee.overdue.list` | invalid | VALUE_NOT_IN_SENTENCE |
| mahine ka hisaab kitaab kya hai | ur-Latn | `dashboard.main.read` | invalid | NOT_A_CANDIDATE |
| Hamza ka duplicate bill nikal do | ur-Latn | `fee.cancellation.raise` | refusal | no_matching_capability |
| saal bhar se 18000 mang rahe hain, ab nahi milenge | ur-Latn | `fee.writeoff.propose` | refusal | no_matching_capability |

## Sentences to refuse that were acted on (1)

| sentence | lang | contract intent | planned |
|---|---|---|---|
| fees ka dashboard kholo | ur-Latn | dashboard.module.view | dashboard.main.read |
