# Retrieval eval with real intents

Generated 2026-09-23 09:16 UTC by `make ai-eval`. Every sentence was decomposed by `gemini-3.1-flash-lite`, and retrieval searched with its intents on the stress index (489 capabilities indexed). Model `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, candidate cap 30.

Decompose answered 173 of 175 sentences within the rules (174 intents; split into more than one: 1). 2 broke a rule and retrieve nothing here.

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| all sentences, through intents | 175 | **95.4%** | 50.3% | 80.6% | 86.3% | 0.660 |
| English, through intents | 63 | **98.4%** | 54.0% | 87.3% | 95.2% | 0.712 |
| Roman Urdu, through intents | 112 | **93.8%** | 48.2% | 76.8% | 81.2% | 0.631 |

Confusable clusters arrived whole in 160 of the 160 queries that retrieved any member.

By expected capability:

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **75.0%** | 60.0% | 70.0% | 70.0% | 0.644 |
| fee.cancellation.raise | 25 | **100.0%** | 20.0% | 56.0% | 72.0% | 0.421 |
| fee.credit.raise | 26 | **96.2%** | 42.3% | 76.9% | 84.6% | 0.611 |
| fee.latefee.waive | 19 | **94.7%** | 89.5% | 94.7% | 94.7% | 0.921 |
| fee.overdue.list | 21 | **100.0%** | 71.4% | 95.2% | 100.0% | 0.843 |
| fee.payment.record | 22 | **100.0%** | 40.9% | 90.9% | 95.5% | 0.617 |
| fee.reminder.send | 20 | **95.0%** | 65.0% | 90.0% | 90.0% | 0.782 |
| fee.writeoff.propose | 22 | **100.0%** | 27.3% | 77.3% | 86.4% | 0.538 |

Hardest sentences (the intents searched; fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| How many students are enrolled? | ur-Latn | `dashboard.main.read` | - | `student.place`, `withdrawal.return`, `readmission.reactivate` | **no** |
| Provide the numbers for subah | ur-Latn | `dashboard.main.read` | - | `student.transfer.place`, `visitor.pass.retire`, `stock.count.submit` | **no** |
| The concession was not applied; please issue a refund | ur-Latn | `fee.credit.raise` | - | `fee.refund.return`, `fee.refund.approve`, `asset.verification.correction.propose` | **no** |
|  | ur-Latn | `fee.latefee.waive` | - |  | **no** |
|  | ur-Latn | `fee.reminder.send` | - |  | **no** |
| Inquire about how the school is doing today | en | `dashboard.main.read` | 36 | `asset.disposals.read`, `assets.read`, `asset.disposal.approve` | **no** |
| Ask how the school is doing today | ur-Latn | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| Check the current amount of cash available | ur-Latn | `dashboard.main.read` | 35 | `stock.advance.issue`, `finance.voucher.pay`, `stock.advances.read` | **no** |
| The payment has not been received yet | ur-Latn | `fee.cancellation.raise` | - | `fee.payment.correction.raise`, `fee.payment.correction.return`, `appointment.letter.approve` | yes |
| The bill was created incorrectly, please take it back | ur-Latn | `fee.cancellation.raise` | 55 | `stock.request.withdraw`, `asset.verification.correction.return`, `account.reactivate` | yes |
| The student left without paying fees, record this situation | ur-Latn | `fee.writeoff.propose` | 22 | `fee.concession.record`, `exam.makeup.absence.record`, `leave.request.submit` | yes |
| The walid sahib has paid the October fees | ur-Latn | `fee.payment.record` | 22 | `fee.reminder.send`, `finance.voucher.pay`, `dashboard.main.read` | yes |
| The August bill for Sara was created twice; remove one. | ur-Latn | `fee.cancellation.raise` | 21 | `admission.invite.resend`, `fee.structure.revise`, `exam.makeup.request` | yes |
| Print a duplicate fee voucher for Hamza | ur-Latn | `fee.cancellation.raise` | 16 | `asset.register`, `fee.refund.approve`, `finance.voucher.pay` | yes |
| The student has already left the school but a bill was still created; fix this. | ur-Latn | `fee.cancellation.raise` | 15 | `student.transfer.raise`, `asset.disposals.read`, `asset.disposal.approve` | yes |
| Hold the extra amount paid on the account instead of refunding | en | `fee.credit.raise` | 15 | `fee.refund.pay`, `certificate.hold.release`, `fee.refund.return` | yes |
| Credit the invoice | ur-Latn | `fee.credit.raise` | 10 | `fee.batch.reverse`, `fee.credit.approve`, `fee.challan.generate` | yes |
| The cancellation should be done | ur-Latn | `fee.cancellation.raise` | 8 | `exam.cancel`, `fee.cancellation.return`, `meeting.cancel` | yes |
| Provide the monthly financial report. | ur-Latn | `dashboard.main.read` | 8 | `calendar.broadcast.send`, `report.attendance.run`, `report.hr.run` | yes |
| The late fee has been paid, please refund it | ur-Latn | `fee.credit.raise` | 7 | `fee.latefee.waive`, `fee.latefee.policy.return`, `fee.latefee.policy.approve` | yes |
| Inform the parents that the fees are late | ur-Latn | `fee.reminder.send` | 7 | `fee.credit.raise`, `fee.latefee.policy.approve`, `fee.writeoff.propose` | yes |
| Zain left in July but still got a September bill; cancel the bill | en | `fee.cancellation.raise` | 7 | `student.transfer.raise`, `exam.cancel`, `pickup.request.cancel` | yes |
| The Khan family has moved abroad and cannot be contacted; write off their outstanding fees | ur-Latn | `fee.writeoff.propose` | 6 | `fee.plan.request`, `fee.writeoff.reverse`, `fee.latefee.waive` | yes |
| The family has moved and the house is empty, so waive this outstanding fee | ur-Latn | `fee.writeoff.propose` | 6 | `fee.latefee.waive`, `fee.refund.raise`, `fee.plan.request` | yes |
| The family has already paid the money, return it | ur-Latn | `fee.credit.raise` | 5 | `fee.refund.raise`, `fee.writeoff.reverse`, `fee.payment.record` | yes |

Sentences decompose could not answer within the rules:

- is family ko fees ka reminder karo: ENTITY_NOT_IN_SENTENCE
- jurmana galti se laga hai, wapas lo: ENTITY_CHANGED
