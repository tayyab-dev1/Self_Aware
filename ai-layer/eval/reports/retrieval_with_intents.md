# Retrieval eval with real intents

Generated 2026-09-21 06:39 UTC by `make ai-eval`. Every sentence was decomposed by `gemini-3.1-flash-lite`, and retrieval searched with its intents on the stress index (489 capabilities indexed). Model `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, candidate cap 30.

Decompose answered 173 of 175 sentences within the rules (180 intents; split into more than one: 7). 2 broke a rule and retrieve nothing here.

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| all sentences, through intents | 175 | **95.4%** | 48.0% | 70.3% | 77.1% | 0.609 |
| English, through intents | 63 | **96.8%** | 52.4% | 74.6% | 82.5% | 0.658 |
| Roman Urdu, through intents | 112 | **94.6%** | 45.5% | 67.9% | 74.1% | 0.582 |

Confusable clusters arrived whole in 157 of the 157 queries that retrieved any member.

By expected capability:

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **70.0%** | 50.0% | 50.0% | 55.0% | 0.529 |
| fee.cancellation.raise | 25 | **100.0%** | 20.0% | 64.0% | 68.0% | 0.435 |
| fee.credit.raise | 26 | **100.0%** | 26.9% | 53.8% | 73.1% | 0.452 |
| fee.latefee.waive | 19 | **94.7%** | 94.7% | 94.7% | 94.7% | 0.947 |
| fee.overdue.list | 21 | **100.0%** | 66.7% | 85.7% | 95.2% | 0.781 |
| fee.payment.record | 22 | **100.0%** | 45.5% | 90.9% | 90.9% | 0.646 |
| fee.reminder.send | 20 | **100.0%** | 85.0% | 95.0% | 95.0% | 0.900 |
| fee.writeoff.propose | 22 | **95.5%** | 13.6% | 36.4% | 50.0% | 0.307 |

Hardest sentences (the intents searched; fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| Count the number of students enrolled. | ur-Latn | `dashboard.main.read` | - | `student.transfer.place`, `readmission.reactivate`, `student.place` | **no** |
| Inquire about the current status of the school | ur-Latn | `dashboard.main.read` | - | `website.items.read`, `assets.read`, `asset.disposals.read` | **no** |
| Provide the attendance numbers for the morning session | ur-Latn | `dashboard.main.read` | - | `report.attendance.run`, `admission.test.mark`, `payroll.run.return` | **no** |
|  | ur-Latn | `fee.latefee.waive` | - |  | **no** |
|  | ur-Latn | `fee.writeoff.propose` | - |  | **no** |
| Inquire about the number of pending card requests. | en | `dashboard.main.read` | 69 | `pickup.queue.read`, `pickup.card.limit.set`, `pickup.requests.mine.read` | **no** |
| Inquire about the total amount of cash available | ur-Latn | `dashboard.main.read` | 49 | `stock.advance.issue`, `stock.advances.read`, `stock.cashreturned.record` | **no** |
| Ask how the school is doing today | en | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| Report that payment has not been received yet. | ur-Latn | `fee.cancellation.raise` | - | `fee.payment.correction.raise`, `fee.payment.correction.return`, `fee.payment.record` | yes |
| Waive the remaining amount of 4,200 | en | `fee.writeoff.propose` | - | `fee.latefee.waive`, `stock.advance.issue`, `appointment.letter.decline` | yes |
| Waive the outstanding amount for the family as they have moved out and the house is empty. | ur-Latn | `fee.writeoff.propose` | 66 | `fee.refund.raise`, `fee.plan.request`, `fee.latefee.waive` | yes |
| Waive the outstanding balance because the father passed away and there is no income | en | `fee.writeoff.propose` | 28 | `fee.refund.raise`, `payslip.mine.read`, `fee.plan.request` | yes |
| Report that the bill was generated twice and both payments were made | ur-Latn | `fee.credit.raise` | 27 | `fee.payment.record`, `fee.writeoff.reverse`, `fee.payment.correction.raise` | yes |
| Record this credit | ur-Latn | `fee.credit.raise` | 27 | `fee.refund.pay`, `asset.assign`, `branch.user.assign` | yes |
| propose waiving the debt for this family as they are untraceable | en | `fee.writeoff.propose` | 21 | `expulsion.appeal.record`, `fee.invoice.issue`, `admission.invite.resend` | yes |
| Write off 18,000 for this family | en | `fee.writeoff.propose` | 20 | `fee.writeoff.reverse`, `fee.latefee.waive`, `fee.invoice.issue` | yes |
| Apply the concession that was supposed to be added | Process a refund for the amount paid | ur-Latn | `fee.credit.raise` | 18 | `stock.advance.issue`, `fee.refund.pay`, `asset.verification.correction.propose` | yes |
| Check why a bill was generated for a child who had already left | ur-Latn | `fee.cancellation.raise` | 15 | `pickup.collections.read`, `expulsion.recommend`, `asset.disposals.read` | yes |
| Correct the wrong bill and take it back | ur-Latn | `fee.cancellation.raise` | 13 | `exam.paper.attendance.correct`, `meetingday.revise`, `asset.verification.correction.return` | yes |
| Process the refund for the fine that has been paid | ur-Latn | `fee.credit.raise` | 13 | `fee.refund.pay`, `expulsion.clearance.settle`, `fee.latefee.waive` | yes |
| Send a proposal to waive the 2 year old outstanding dues | ur-Latn | `fee.writeoff.propose` | 13 | `fee.refund.raise`, `fee.cancellation.return`, `fee.concession.return` | yes |
| Record receipt of the stamped challan for Usman for 12000 | ur-Latn | `fee.payment.record` | 13 | `stock.receipt.record`, `expulsion.appeal.record`, `fee.challan.generate` | yes |
| Provide a brief report on collection | ur-Latn | `dashboard.main.read` | 13 | `pickup.collections.read`, `report.operational.run`, `pickup.collection.verify` | yes |
| Print a duplicate fee bill for Hamza | ur-Latn | `fee.cancellation.raise` | 13 | `fee.batch.authorise`, `fee.structure.approve`, `fee.structure.submit` | yes |
| Ask how much money was collected today | ur-Latn | `dashboard.main.read` | 12 | `stock.request.approve`, `fee.payment.record`, `stock.issue.chase` | yes |

Sentences decompose could not answer within the rules:

- jurmana galti se laga hai, wapas lo: ENTITY_CHANGED
- ye baqaya wasool nahi hoga: ENTITY_CHANGED
