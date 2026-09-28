# Measure: planning from the pieces, instead of a second model call

Generated 2026-09-28 05:50 UTC by `make measure-pieces`. Decompose `gemini-3.1-flash-lite` (asked for the pieces), the chooser and the value readings from Jev, the planner `gemini-3.1-flash-lite` only where the pieces could not fill the plan.

## Both ways, over the same cases

```
                      all                       English                   Roman Urdu                
recall@30             95.4% -> 96.0% (+0.6)     98.4% -> 96.8% (-1.6)     93.8% -> 95.5% (+1.8)
plan accuracy         89.1% -> 89.7% (+0.6)     90.5% -> 95.2% (+4.8)     88.4% -> 86.6% (-1.8)
refusal correctness   91.0% -> 91.4% (+0.4)     93.9% -> 94.9% (+1.0)     89.1% -> 89.1% (+0.0)
validator catch rate  100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)
```

- **Built without a planner call**: 126 of 245 (51.4%); en 51, ur-Latn 75
- **Asked the planner**: 119 of 245

## What the pieces got wrong that the planner got right

| sentence | lang | expected | planner | from the pieces |
|---|---|---|---|---|
| cash kitna para hai? | ur-Latn | `dashboard.main.read` | wrong: refusal (no_matching_capability) | right: dashboard.main.read (planner asked) |
| propose cancelling the whole batch we raised for the wrong period | en | `fee.cancellation.raise` | wrong: refusal (no_matching_capability) | right: fee.cancellation.raise |
| concession lagni thi lekin nahi lagi, ab paisa wapas karna hai | ur-Latn | `fee.credit.raise` | wrong: refusal | right: fee.credit.raise (planner asked) |
| do dafa bill bana tha aur dono jama ho gaye | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: invalid (ENTITY_NOT_IN_SENTENCE) (planner asked) |
| family ne paisa de diya hai, phir bhi charge galat tha | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: fee.cancellation.raise |
| family ne paisa de diya hai, wo wapas karo | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: refusal (no_matching_capability) (planner asked) |
| jurmana jama ho chuka hai, wapas karo | ur-Latn | `fee.credit.raise` | wrong: fee.latefee.waive | right: fee.credit.raise |
| ye bill kam kar do | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: fee.cancellation.raise |
| jurmana galti se laga hai, wapas lo | ur-Latn | `fee.latefee.waive` | wrong: invalid (ENTITY_CHANGED) | right: fee.latefee.waive |
| what's the total outstanding across the school | en | `fee.overdue.list` | wrong: dashboard.main.read | right: fee.overdue.list |
| message the over-90-day defaulters | en | `fee.reminder.send` | wrong: refusal (no_matching_capability) | right: fee.reminder.send |
| Grade 9 ke jin ki fees due hai unhe msg kar do | ur-Latn | `fee.reminder.send` | right: fee.reminder.send | wrong: invalid (WRONG_FORM) |
| show me everyone more than 60 days overdue | en | `fee.overdue.list` | wrong: fee.overdue.list, fee.overdue.list | right: fee.overdue.list |
| walid sahib ne October ki fees ada kar di | ur-Latn | `fee.payment.record` | right: fee.payment.record | wrong: invalid (WRONG_FORM) |
| Ali walon ne duplicate bill bhi bhar diya, agle mahine mein adjust karo | ur-Latn | `fee.credit.raise` | right: fee.credit.raise | wrong: invalid (WORDS_NOT_IN_SENTENCE) |
| Danish ke October ke bill se jurmana hata do, bank band tha | ur-Latn | `fee.latefee.waive` | wrong: invalid (WORDS_NOT_IN_SENTENCE) | right: fee.latefee.waive (planner asked) |
| we've tried for a year to recover 18,000 from this family, time to write it off | en | `fee.writeoff.propose` | right: fee.writeoff.propose | wrong: invalid (ENTITY_CHANGED) (planner asked) |
| poora batch reverse kar do, galat period ka bill ban gaya tha | ur-Latn | `refuse` | right: refusal (no_matching_capability) | wrong: fee.cancellation.raise |
| approve the cancellation on Bilal's September invoice | en | `refuse` | right: refusal (no_matching_capability) | wrong: fee.cancellation.raise |
| cancellation wapas bhej do, wajah saaf nahi hai | ur-Latn | `refuse` | wrong: fee.cancellation.raise | right: refusal (chooser_found_none) (planner asked) |
| return this credit — no money was received against that invoice | en | `refuse` | wrong: fee.cancellation.raise | right: refusal (chooser_found_none) (planner asked) |
| policy wapas bhej do, jurmana bohat zyada hai | ur-Latn | `refuse` | wrong: fee.latefee.waive | right: refusal (chooser_found_none) (planner asked) |
