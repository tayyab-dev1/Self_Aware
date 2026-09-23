# Retrieval eval

Generated 2026-09-23 10:14 UTC by `make ai-eval`. Model `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, candidate cap 30, RRF k 60.

175 labelled sentences: 75 from planning-contracts, 100 written for this eval; 112 Roman Urdu, 63 English.

The first row is what retrieval will receive once decompose (Phase 5) turns every sentence into English intents; the English gloss of a Roman Urdu sentence stands in for those intents until then. The as-typed rows show what retrieval does without that step.

Recall@30 counts the expected capability anywhere in the candidates the planner would see (siblings included). @1/@3/@5 and MRR use the fused rank from the two searches alone, before siblings are pulled in.

## POC index (8 capabilities indexed)

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| in English: English as typed, Roman Urdu glossed | 175 | **100.0%** | 77.7% | 93.1% | 98.3% | 0.862 |
| all sentences, as typed | 175 | **100.0%** | 65.7% | 86.3% | 96.0% | 0.777 |
| English | 63 | **100.0%** | 81.0% | 92.1% | 98.4% | 0.880 |
| Roman Urdu, as typed | 112 | **100.0%** | 57.1% | 83.0% | 94.6% | 0.719 |
| Roman Urdu, English gloss | 112 | **100.0%** | 75.9% | 93.8% | 98.2% | 0.852 |

Confusable clusters arrived whole in 287 of the 287 queries that retrieved any member.

By expected capability (as typed):

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **100.0%** | 65.0% | 85.0% | 95.0% | 0.772 |
| fee.cancellation.raise | 25 | **100.0%** | 68.0% | 100.0% | 100.0% | 0.827 |
| fee.credit.raise | 26 | **100.0%** | 69.2% | 92.3% | 100.0% | 0.819 |
| fee.latefee.waive | 19 | **100.0%** | 63.2% | 84.2% | 94.7% | 0.763 |
| fee.overdue.list | 21 | **100.0%** | 57.1% | 76.2% | 95.2% | 0.698 |
| fee.payment.record | 22 | **100.0%** | 77.3% | 90.9% | 95.5% | 0.848 |
| fee.reminder.send | 20 | **100.0%** | 80.0% | 90.0% | 100.0% | 0.872 |
| fee.writeoff.propose | 22 | **100.0%** | 45.5% | 68.2% | 86.4% | 0.604 |

By where the sentence came from (as typed):

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| authored | 100 | **100.0%** | 71.0% | 88.0% | 94.0% | 0.811 |
| contract | 75 | **100.0%** | 58.7% | 84.0% | 98.7% | 0.732 |

Hardest sentences (as typed, fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| walid sahib ne October ki fees ada kar di | ur-Latn | `fee.payment.record` | 8 | `fee.credit.raise`, `fee.reminder.send`, `dashboard.main.read` | yes |
| saal bhar se 18000 mang rahe hain, ab nahi milenge | ur-Latn | `fee.writeoff.propose` | 8 | `fee.overdue.list`, `fee.reminder.send`, `dashboard.main.read` | yes |
| family ka koi pata nahi, ghar khali hai, ye raqam chhor do | ur-Latn | `fee.writeoff.propose` | 8 | `fee.overdue.list`, `fee.payment.record`, `fee.latefee.waive` | yes |
| Ahmed Raza ka kitna baqaya hai | ur-Latn | `fee.overdue.list` | 7 | `fee.credit.raise`, `fee.writeoff.propose`, `fee.cancellation.raise` | yes |
| family's phone is off and the address is empty, we give up on the outstanding amount | en | `fee.writeoff.propose` | 7 | `fee.overdue.list`, `fee.cancellation.raise`, `fee.reminder.send` | yes |
| school ka aaj kya haal hai? | ur-Latn | `dashboard.main.read` | 6 | `fee.cancellation.raise`, `fee.overdue.list`, `fee.credit.raise` | yes |
| Danish ke October ke bill se jurmana hata do, bank band tha | ur-Latn | `fee.latefee.waive` | 6 | `fee.credit.raise`, `fee.cancellation.raise`, `fee.payment.record` | yes |
| how is the school doing today | en | `dashboard.main.read` | 5 | `fee.credit.raise`, `fee.cancellation.raise`, `fee.overdue.list` | yes |
| family ne paisa de diya hai, wo wapas karo | ur-Latn | `fee.credit.raise` | 5 | `dashboard.main.read`, `fee.overdue.list`, `fee.payment.record` | yes |
| kis kis ne fees nahi di? | ur-Latn | `fee.overdue.list` | 5 | `fee.credit.raise`, `fee.cancellation.raise`, `fee.reminder.send` | yes |
| kis ne fees nahi di? | ur-Latn | `fee.overdue.list` | 5 | `fee.credit.raise`, `fee.cancellation.raise`, `fee.reminder.send` | yes |
| family ne aadhi fees di hai — 2000 | ur-Latn | `fee.payment.record` | 5 | `fee.latefee.waive`, `fee.credit.raise`, `fee.overdue.list` | yes |
| ye debt maaf kar do | ur-Latn | `fee.writeoff.propose` | 5 | `fee.credit.raise`, `fee.latefee.waive`, `fee.overdue.list` | yes |
| ye debt wasool nahi hoga, maaf kar do | ur-Latn | `fee.writeoff.propose` | 5 | `fee.credit.raise`, `fee.latefee.waive`, `fee.cancellation.raise` | yes |
| parents ko batao ke fees late ho gayi hai | ur-Latn | `fee.reminder.send` | 5 | `fee.credit.raise`, `fee.cancellation.raise`, `fee.writeoff.propose` | yes |
| do mahine se purane defaulters dikhao | ur-Latn | `fee.overdue.list` | 5 | `fee.credit.raise`, `fee.writeoff.propose`, `fee.cancellation.raise` | yes |
| kitne students enrolled hain? | ur-Latn | `dashboard.main.read` | 4 | `fee.overdue.list`, `fee.cancellation.raise`, `fee.reminder.send` | yes |
| invoice credit kar do | ur-Latn | `fee.credit.raise` | 4 | `fee.latefee.waive`, `fee.cancellation.raise`, `fee.payment.record` | yes |
| Grade 4 ka baqaya dikhao | ur-Latn | `fee.overdue.list` | 4 | `fee.credit.raise`, `fee.writeoff.propose`, `fee.cancellation.raise` | yes |
| ye baqaya wasool nahi hoga | ur-Latn | `fee.writeoff.propose` | 4 | `fee.reminder.send`, `fee.credit.raise`, `fee.cancellation.raise` | yes |
| text all families with unpaid invoices in class 2 blue | en | `fee.reminder.send` | 4 | `fee.overdue.list`, `fee.cancellation.raise`, `fee.latefee.waive` | yes |
| the late fee was added even though they paid on the due date, take it off | en | `fee.latefee.waive` | 4 | `fee.cancellation.raise`, `fee.credit.raise`, `fee.writeoff.propose` | yes |
| ghar mein fotgi hui thi, jurmana na lagaen | ur-Latn | `fee.latefee.waive` | 4 | `fee.payment.record`, `fee.reminder.send`, `dashboard.main.read` | yes |
| this old balance will never be paid, the father passed away and there is no income | en | `fee.writeoff.propose` | 4 | `fee.cancellation.raise`, `fee.credit.raise`, `fee.overdue.list` | yes |

## Stress index (with planning-contract distractors) (489 capabilities indexed)

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| in English: English as typed, Roman Urdu glossed | 175 | **94.9%** | 48.6% | 70.9% | 78.3% | 0.611 |
| all sentences, as typed | 175 | **82.3%** | 28.0% | 44.6% | 53.1% | 0.397 |
| English | 63 | **95.2%** | 54.0% | 74.6% | 81.0% | 0.657 |
| Roman Urdu, as typed | 112 | **75.0%** | 13.4% | 27.7% | 37.5% | 0.250 |
| Roman Urdu, English gloss | 112 | **94.6%** | 45.5% | 68.8% | 76.8% | 0.585 |

Confusable clusters arrived whole in 245 of the 245 queries that retrieved any member.

By expected capability (as typed):

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **55.0%** | 35.0% | 35.0% | 35.0% | 0.372 |
| fee.cancellation.raise | 25 | **92.0%** | 16.0% | 44.0% | 52.0% | 0.340 |
| fee.credit.raise | 26 | **88.5%** | 19.2% | 38.5% | 42.3% | 0.330 |
| fee.latefee.waive | 19 | **89.5%** | 42.1% | 57.9% | 73.7% | 0.530 |
| fee.overdue.list | 21 | **76.2%** | 33.3% | 42.9% | 52.4% | 0.422 |
| fee.payment.record | 22 | **77.3%** | 13.6% | 36.4% | 40.9% | 0.266 |
| fee.reminder.send | 20 | **100.0%** | 60.0% | 85.0% | 90.0% | 0.723 |
| fee.writeoff.propose | 22 | **77.3%** | 13.6% | 22.7% | 45.5% | 0.257 |

By where the sentence came from (as typed):

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| authored | 100 | **85.0%** | 32.0% | 50.0% | 56.0% | 0.435 |
| contract | 75 | **78.7%** | 22.7% | 37.3% | 49.3% | 0.346 |

Hardest sentences (as typed, fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| how many card requests are pending | en | `dashboard.main.read` | - | `pickup.card.limit.set`, `pickup.queue.read`, `pickup.card.request` | **no** |
| kitne students enrolled hain? | ur-Latn | `dashboard.main.read` | - | `withdrawal.return`, `student.place`, `readmission.reactivate` | **no** |
| school ka aaj kya haal hai? | ur-Latn | `dashboard.main.read` | - | `account.reactivate`, `assets.read`, `complaint.parent.reopen` | **no** |
| paisa abhi tak nahi mila | ur-Latn | `fee.cancellation.raise` | - | `stock.request.reject`, `staff.advance.reject`, `meetingday.return` | **no** |
| concession lagni thi lekin nahi lagi, ab paisa wapas karna hai | ur-Latn | `fee.credit.raise` | - | `fee.concession.approve`, `stock.reimbursement.refuse`, `fee.concession.record` | **no** |
| jurmana jama ho chuka hai, wapas karo | ur-Latn | `fee.credit.raise` | - | `stock.round.open`, `stock.reimbursement.refuse`, `stock.issue.record` | **no** |
| jurmana galti se laga hai, wapas lo | ur-Latn | `fee.latefee.waive` | - | `teacher.meeting.request.respond`, `visitor.pass.recover`, `branch.reactivate` | **no** |
| Grade 4 ka baqaya dikhao | ur-Latn | `fee.overdue.list` | - | `exam.results.approve`, `grade.bands.define`, `stock.reimbursement.refuse` | **no** |
| teen mahine se zyada purana baqaya kitna hai? | ur-Latn | `fee.overdue.list` | - | `stock.item.reactivate`, `stock.issues.outstanding.read`, `pickup.cards.read` | **no** |
| counter pe paisay jama hue hain | ur-Latn | `fee.payment.record` | - | `stock.request.reject`, `complaint.parent.takeup`, `stock.reimbursement.refuse` | **no** |
| do saal purana baqaya hai, maaf karne ki tajweez bhejo | ur-Latn | `fee.writeoff.propose` | - | `asset.verification.submit`, `branch.reactivate`, `asset.disposal.approve` | **no** |
| ye baqaya wasool nahi hoga | ur-Latn | `fee.writeoff.propose` | - | `stock.issue.chase`, `asset.verification.submit`, `account.reactivate` | **no** |
| Ahmed Raza ka kitna baqaya hai | ur-Latn | `fee.overdue.list` | - | `stock.request.reject`, `visitor.pass.retire`, `gate.barcode.rotate` | **no** |
| do mahine se purane defaulters dikhao | ur-Latn | `fee.overdue.list` | - | `audit.access.grant`, `stock.reimbursement.refuse`, `branch.consolidatedview.grant` | **no** |
| das hazaar se zyada wale nadehindagan kaun hain | ur-Latn | `fee.overdue.list` | - | `roles.read`, `mfa.status.read`, `branches.read` | **no** |
| Bilal ke abbu 9000 naqad de gaye hain, entry kar do | ur-Latn | `fee.payment.record` | - | `fee.cancellation.approve`, `website.inquiryform.read`, `fee.credit.approve` | **no** |
| counter par 4500 wasool hue, raseed bana do | ur-Latn | `fee.payment.record` | - | `stock.count.submit`, `stock.reimbursement.refuse`, `finance.budget.approve` | **no** |
| aaj kitne paisay aaye | ur-Latn | `dashboard.main.read` | - | `pickup.requests.mine.read`, `complaints.parent.read`, `website.inquiryform.read` | **no** |
| mujhe aaj ki summary dikhao | ur-Latn | `dashboard.main.read` | - | `dashboard.module.view`, `complaint.parent.outcome.confirm`, `complaint.staff.outcome.confirm` | **no** |
| mahine ka hisaab kitaab kya hai | ur-Latn | `dashboard.main.read` | - | `stock.count.record`, `finance.reconciliation.match`, `stock.return.record` | **no** |
| we billed Sara twice for August, the second one needs to go | en | `fee.cancellation.raise` | - | `exam.makeup.request`, `mfa.status.read`, `admission.invite.resend` | **no** |
| ghar mein fotgi hui thi, jurmana na lagaen | ur-Latn | `fee.latefee.waive` | - | `expulsion.appeal.record`, `complaints.parent.read`, `geofence.read` | **no** |
| saal bhar se 18000 mang rahe hain, ab nahi milenge | ur-Latn | `fee.writeoff.propose` | - | `branches.read`, `stock.issues.outstanding.read`, `website.inquiryform.read` | **no** |
| walid ka intiqal ho gaya, ye purana baqaya kabhi ada nahi hoga | ur-Latn | `fee.writeoff.propose` | - | `asset.verification.submit`, `stock.issue.chase`, `meetingday.revise` | **no** |
| is mahine ki wasooli kitni hui | ur-Latn | `dashboard.main.read` | 50 | `geofence.read`, `gate.log.access.read`, `stock.item.reactivate` | **no** |
