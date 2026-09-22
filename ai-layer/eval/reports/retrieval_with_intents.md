# Retrieval eval with real intents

Generated 2026-09-22 11:49 UTC by `make ai-eval`. Every sentence was decomposed by `gemini-3.1-flash-lite`, and retrieval searched with its intents on the stress index (489 capabilities indexed). Model `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, candidate cap 30.

Decompose answered 174 of 175 sentences within the rules (174 intents; split into more than one: 0). 1 broke a rule and retrieve nothing here.

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| all sentences, through intents | 175 | **96.6%** | 52.0% | 73.1% | 82.9% | 0.648 |
| English, through intents | 63 | **96.8%** | 55.6% | 79.4% | 84.1% | 0.686 |
| Roman Urdu, through intents | 112 | **96.4%** | 50.0% | 69.6% | 82.1% | 0.626 |

Confusable clusters arrived whole in 163 of the 163 queries that retrieved any member.

By expected capability:

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **75.0%** | 40.0% | 45.0% | 60.0% | 0.476 |
| fee.cancellation.raise | 25 | **100.0%** | 20.0% | 56.0% | 80.0% | 0.429 |
| fee.credit.raise | 26 | **100.0%** | 46.2% | 73.1% | 80.8% | 0.614 |
| fee.latefee.waive | 19 | **100.0%** | 94.7% | 100.0% | 100.0% | 0.974 |
| fee.overdue.list | 21 | **100.0%** | 71.4% | 95.2% | 100.0% | 0.837 |
| fee.payment.record | 22 | **95.5%** | 40.9% | 68.2% | 77.3% | 0.553 |
| fee.reminder.send | 20 | **100.0%** | 80.0% | 95.0% | 95.0% | 0.882 |
| fee.writeoff.propose | 22 | **100.0%** | 36.4% | 59.1% | 72.7% | 0.510 |

Hardest sentences (the intents searched; fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| How many students are enrolled? | ur-Latn | `dashboard.main.read` | - | `student.place`, `withdrawal.return`, `readmission.reactivate` | **no** |
| provide the morning attendance numbers | ur-Latn | `dashboard.main.read` | - | `report.attendance.run`, `exam.paper.attendance.correct`, `admission.test.mark` | **no** |
|  | ur-Latn | `fee.payment.record` | - |  | **no** |
| count how many card requests are pending | en | `dashboard.main.read` | 45 | `pickup.queue.read`, `pickup.card.limit.set`, `pickup.requests.mine.read` | **no** |
| Ask how the school is doing today | en | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| Ask how the school is doing today | ur-Latn | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| The money has not been received yet | ur-Latn | `fee.cancellation.raise` | - | `fee.payment.correction.raise`, `stock.cashreturned.record`, `appointment.letter.approve` | yes |
| The family has left and the house is empty, waive the amount | ur-Latn | `fee.writeoff.propose` | 60 | `fee.latefee.waive`, `fee.refund.raise`, `fee.plan.request` | yes |
| The family's phone is off and the address is empty, deal with the outstanding amount | en | `fee.writeoff.propose` | 59 | `fee.plan.request`, `fee.overdue.list`, `fee.reminder.send` | yes |
| The bill was created incorrectly; take it back | ur-Latn | `fee.cancellation.raise` | 54 | `stock.request.withdraw`, `asset.verification.correction.return`, `fee.credit.raise` | yes |
| The concession was supposed to be applied but was not, so refund the money | ur-Latn | `fee.credit.raise` | 26 | `fee.refund.approve`, `fee.refund.return`, `fee.payment.correction.raise` | yes |
| The walid sahib has paid the fees for October | ur-Latn | `fee.payment.record` | 23 | `finance.voucher.pay`, `fee.reminder.send`, `fee.batch.authorise` | yes |
| The fine has been paid, return it | ur-Latn | `fee.credit.raise` | 21 | `expulsion.clearance.settle`, `fee.batch.reverse`, `fee.latefee.waive` | yes |
| The family has been asking for 18000 for a year and now will not pay | ur-Latn | `fee.writeoff.propose` | 20 | `fee.plan.request`, `complaints.parent.read`, `fee.overdue.list` | yes |
| The student left without paying fees, so there is nothing to be done | ur-Latn | `fee.writeoff.propose` | 15 | `certificate.request`, `exam.makeup.refuse`, `timetable.freeteachers.list` | yes |
| Hold the extra amount paid on the account instead of refunding | en | `fee.credit.raise` | 15 | `fee.refund.pay`, `certificate.hold.release`, `fee.refund.return` | yes |
| The stamped copy of challan CH-2026-0417 has arrived from the bank | ur-Latn | `fee.payment.record` | 13 | `fee.challan.generate`, `paper.question.add`, `paper.create` | yes |
| Provide a brief report on collection | ur-Latn | `dashboard.main.read` | 13 | `pickup.collections.read`, `report.operational.run`, `pickup.collection.verify` | yes |
| This cancellation should happen | ur-Latn | `fee.cancellation.raise` | 12 | `meeting.cancel`, `exam.cancel`, `leave.request.cancel` | yes |
| Credit the invoice | ur-Latn | `fee.credit.raise` | 10 | `fee.batch.reverse`, `fee.credit.approve`, `fee.challan.generate` | yes |
| Provide the accounts for the month | ur-Latn | `dashboard.main.read` | 9 | `account.reactivate`, `accounts.read`, `calendar.broadcast.send` | yes |
| Zain left in July but still got a September bill, remove it | en | `fee.cancellation.raise` | 9 | `student.transfer.raise`, `asset.disposals.read`, `salary.approval.submit` | yes |
| The bank stamped challan for Usman for 12,000 received on Monday has been returned; update the record | en | `fee.payment.record` | 8 | `stock.return.record`, `account.reactivate`, `withdrawal.property.record` | yes |
| The family has paid the money, return it | ur-Latn | `fee.credit.raise` | 7 | `fee.refund.raise`, `fee.writeoff.reverse`, `fee.payment.record` | yes |
| Inform the parents that the fees are late | ur-Latn | `fee.reminder.send` | 7 | `fee.credit.raise`, `fee.latefee.policy.approve`, `fee.writeoff.propose` | yes |

Sentences decompose could not answer within the rules:

- Bilal ke abbu 9000 naqad de gaye hain, entry kar do: NOT_ENGLISH
