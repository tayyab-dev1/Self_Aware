# Measure: the capability chooser (Jev) before the planner

Generated 2026-09-21 11:24 UTC by `make measure-jev`. Chooser `jev-1.13.0`, planner `gemini-3.1-flash-lite`, the same decompose answers and retrieval as `measure.md`, answers from `eval/recordings/` (`choose.json`, `plan_after_choose.json`).

Without the chooser the planner sees every candidate the POC can run; with it, only Jev's shortlist: per intent, the candidates holding 90% of the probability that is not "none" (at most 3), nothing when "none" holds 60% or more. An empty shortlist is a refusal with no planner call.

## The four numbers: planner alone -> Jev, then the planner

```
                      all                       English                   Roman Urdu                
recall@30             95.4% -> 95.4% (+0.0)     96.8% -> 96.8% (+0.0)     94.6% -> 94.6% (+0.0)
plan accuracy         90.3% -> 87.4% (-2.9)     92.1% -> 93.7% (+1.6)     89.3% -> 83.9% (-5.4)
refusal correctness   92.2% -> 90.2% (-2.0)     93.9% -> 94.9% (+1.0)     91.2% -> 87.1% (-4.1)
validator catch rate  100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)
```

## Jev on its own

Over the 170 labelled sentences and 44 sentences to refuse that reached it (retrieval found at least one candidate the POC can run).

- **picked the expected capability**: 92.4% (157/170)
- **expected capability in the shortlist**: 93.5% (159/170)
- **"none" for a sentence to refuse**: 86.4% (38/44)
- **shortlist sizes** (labelled): 0: 10, 1: 153, 2: 5, 3: 2
- **time per call** (when recorded, 214 calls): median 0.41 s, 90th percentile 0.47 s, slowest 1.40 s

### Is its confidence honest?

Every sentence that reached Jev, by the confidence of its first intent's pick.

| confidence | sentences | pick right |
|---|---:|---:|
| 0.9 to 1.0 | 158 | 94.9% (150/158) |
| 0.7 to 0.9 | 27 | 85.2% (23/27) |
| 0.5 to 0.7 | 20 | 75.0% (15/20) |
| 0.0 to 0.5 | 9 | 77.8% (7/9) |

## Sentences whose result changed

| sentence | lang | expected | planner alone | with Jev | Jev picked |
|---|---|---|---|---|---|
| ye bacha to chhod chuka tha, phir bhi bill ban gaya | ur-Latn | `fee.cancellation.raise` | right: fee.cancellation.raise | wrong: refusal (chooser_found_none) | none 0.51 |
| concession lagni thi lekin nahi lagi, ab paisa wapas karna hai | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (no_matching_capability) | fee.credit.raise 0.55; none 0.49 |
| credit raise karo | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (chooser_found_none) | none 0.93 |
| family ne paisa de diya hai, phir bhi charge galat tha | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (chooser_found_none) | none 0.77 |
| ye bill kam kar do | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (no_matching_capability) | none 0.46 |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | wrong: fee.overdue.list, fee.overdue.list | right: fee.overdue.list | fee.overdue.list 1.00 |
| unhon ne waqt par diya tha phir bhi late fee lag gayi | ur-Latn | `fee.latefee.waive` | right: fee.latefee.waive | wrong: refusal (chooser_found_none) | none 0.88 |
| return this credit — no money was received against that invoice | en | `refuse` | wrong: fee.cancellation.raise | right: refusal (chooser_found_none) | none 0.66 |
