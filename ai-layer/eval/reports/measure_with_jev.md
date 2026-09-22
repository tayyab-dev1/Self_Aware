# Measure: the capability chooser (Jev) before the planner

Generated 2026-09-22 10:33 UTC by `make measure-jev`. Chooser `jev-1.13.0`, planner `gemini-3.1-flash-lite`, the same decompose answers and retrieval as `measure.md`, answers from `eval/recordings/` (`choose.json`, `plan_after_choose.json`).

Without the chooser the planner sees every candidate the POC can run; with it, only Jev's shortlist: per intent, the candidates holding 90% of the probability that is not "none" (at most 3), nothing when "none" holds 60% or more. An empty shortlist is a refusal with no planner call.

## The four numbers: planner alone -> Jev, then the planner

```
                      all                       English                   Roman Urdu                
recall@30             96.6% -> 96.6% (+0.0)     96.8% -> 96.8% (+0.0)     96.4% -> 96.4% (+0.0)
plan accuracy         90.3% -> 89.1% (-1.1)     92.1% -> 90.5% (-1.6)     89.3% -> 88.4% (-0.9)
refusal correctness   91.8% -> 91.4% (-0.4)     92.9% -> 92.9% (+0.0)     91.2% -> 90.5% (-0.7)
validator catch rate  100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)
```

## Jev on its own

Over the 174 labelled sentences and 43 sentences to refuse that reached it (retrieval found at least one candidate the POC can run).

- **picked the expected capability**: 93.1% (162/174)
- **expected capability in the shortlist**: 93.7% (163/174)
- **"none" for a sentence to refuse**: 86.0% (37/43)
- **shortlist sizes** (labelled): 0: 11, 1: 158, 2: 5, 3: 0
- **time per call** (when recorded, 217 calls): median 0.41 s, 90th percentile 0.56 s, slowest 2.38 s

### Is its confidence honest?

Every sentence that reached Jev, by the confidence of its first intent's pick.

| confidence | sentences | pick right |
|---|---:|---:|
| 0.9 to 1.0 | 167 | 95.2% (159/167) |
| 0.7 to 0.9 | 20 | 85.0% (17/20) |
| 0.5 to 0.7 | 22 | 72.7% (16/22) |
| 0.0 to 0.5 | 8 | 87.5% (7/8) |

## Sentences whose result changed

| sentence | lang | expected | planner alone | with Jev | Jev picked |
|---|---|---|---|---|---|
| charge galat tha, credit karo | ur-Latn | `fee.credit.raise` | wrong: invalid (MISSING_BUT_GIVEN) | right: fee.credit.raise | fee.credit.raise 0.54 |
| family ne paisa de diya hai, wo wapas karo | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (no_matching_capability) | none 0.51 |
| counter pe paisay jama hue hain | ur-Latn | `fee.payment.record` | right: fee.payment.record | wrong: refusal (chooser_found_none) | none 0.20 |
| how much does Ahmed Raza still owe | en | `fee.overdue.list` | right: fee.overdue.list | wrong: invalid (MALFORMED_PARAMETER) | fee.overdue.list 1.00 |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | wrong: fee.overdue.list, fee.overdue.list | right: fee.overdue.list | fee.overdue.list 0.99 |
| the bank stamped challan came back for Usman, 12,000 received on Monday | en | `fee.payment.record` | right: fee.payment.record | wrong: refusal (chooser_found_none) | none 0.81 |
| return this credit — no money was received against that invoice | en | `refuse` | wrong: fee.cancellation.raise | right: refusal (chooser_found_none) | none 0.74 |
| policy wapas bhej do, jurmana bohat zyada hai | ur-Latn | `refuse` | wrong: fee.latefee.waive | right: refusal (chooser_found_none) | none 0.58 |
| raise a refund for the Khan family, they've left and hold 2,000 credit | en | `refuse` | wrong: fee.credit.raise | right: refusal (no_matching_capability) | fee.credit.raise 0.51 |
