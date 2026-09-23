# Measure: the capability chooser (Jev) before the planner

Generated 2026-09-23 08:54 UTC by `make measure-jev`. Chooser `jev-1.13.0`, planner `gemini-3.1-flash-lite`, the same decompose answers and retrieval as `measure.md`, answers from `eval/recordings/` (`choose.json`, `plan_after_choose.json`).

Without the chooser the planner sees every candidate the POC can run; with it, only Jev's shortlist: per intent, the candidates holding 90% of the probability that is not "none" (at most 3), nothing when "none" holds 60% or more. An empty shortlist is a refusal with no planner call.

## The four numbers: planner alone -> Jev, then the planner

```
                      all                       English                   Roman Urdu                
recall@30             95.4% -> 95.4% (+0.0)     98.4% -> 98.4% (+0.0)     93.8% -> 93.8% (+0.0)
plan accuracy         89.7% -> 89.7% (+0.0)     92.1% -> 93.7% (+1.6)     88.4% -> 87.5% (-0.9)
refusal correctness   92.2% -> 92.2% (+0.0)     94.9% -> 94.9% (+0.0)     90.5% -> 90.5% (+0.0)
validator catch rate  100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)
```

## Jev on its own

Over the 172 labelled sentences and 43 sentences to refuse that reached it (retrieval found at least one candidate the POC can run).

- **picked the expected capability**: 93.6% (161/172)
- **expected capability in the shortlist**: 94.8% (163/172)
- **"none" for a sentence to refuse**: 88.4% (38/43)
- **shortlist sizes** (labelled): 0: 9, 1: 155, 2: 8, 3: 0
- **time per call** (when recorded, 215 calls): median 0.45 s, 90th percentile 0.53 s, slowest 1.44 s

### Is its confidence honest?

Every sentence that reached Jev, by the confidence of its first intent's pick.

| confidence | sentences | pick right |
|---|---:|---:|
| 0.9 to 1.0 | 168 | 95.2% (160/168) |
| 0.7 to 0.9 | 22 | 86.4% (19/22) |
| 0.5 to 0.7 | 16 | 87.5% (14/16) |
| 0.0 to 0.5 | 9 | 66.7% (6/9) |

## Sentences whose result changed

| sentence | lang | expected | planner alone | with Jev | Jev picked |
|---|---|---|---|---|---|
| ye bill kam kar do | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: fee.cancellation.raise | fee.cancellation.raise 0.49 |
| what's the total outstanding across the school | en | `fee.overdue.list` | wrong: dashboard.main.read | right: fee.overdue.list | fee.overdue.list 0.75 |
| message the over-90-day defaulters | en | `fee.reminder.send` | right: fee.reminder.send | wrong: refusal (no_matching_capability) | fee.reminder.send 0.96 |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | wrong: fee.overdue.list, fee.overdue.list | right: fee.overdue.list | fee.overdue.list 1.00 |
| mahine ka hisaab kitaab kya hai | ur-Latn | `dashboard.main.read` | right: dashboard.main.read | wrong: refusal (chooser_found_none) | none 0.49 |
| Ali walon ne duplicate bill bhi bhar diya, agle mahine mein adjust karo | ur-Latn | `fee.credit.raise` | wrong: invalid (WORDS_NOT_IN_SENTENCE) | right: fee.credit.raise | fee.credit.raise 0.98 |
| return this credit — no money was received against that invoice | en | `refuse` | wrong: fee.cancellation.raise | right: refusal (chooser_found_none) | none 0.54 |
| policy wapas bhej do, jurmana bohat zyada hai | ur-Latn | `refuse` | wrong: fee.latefee.waive | right: refusal (chooser_found_none) | none 0.85 |
| ye payment galat student ke against lag gayi hai | ur-Latn | `refuse` | right: refusal (no_matching_capability) | wrong: fee.credit.raise | none 0.43 |
