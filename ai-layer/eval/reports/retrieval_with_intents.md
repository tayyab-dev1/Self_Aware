# Retrieval eval with real intents

Generated 2026-09-16 13:54 UTC by `make ai-eval`. Every sentence was decomposed by `claude-haiku-4-5-20251001`, and retrieval searched with its intents on the stress index (489 capabilities indexed). Model `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, candidate cap 30.

Decompose answered 175 of 175 sentences within the rules (176 intents; split into more than one: 1). 0 broke a rule and retrieve nothing here.

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| all sentences, through intents | 175 | **95.4%** | 52.6% | 75.4% | 80.0% | 0.652 |
| English, through intents | 63 | **96.8%** | 58.7% | 79.4% | 84.1% | 0.701 |
| Roman Urdu, through intents | 112 | **94.6%** | 49.1% | 73.2% | 77.7% | 0.624 |

Confusable clusters arrived whole in 163 of the 163 queries that retrieved any member.

By expected capability:

| | n | recall@30 | @1 | @3 | @5 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| dashboard.main.read | 20 | **70.0%** | 30.0% | 45.0% | 55.0% | 0.411 |
| fee.cancellation.raise | 25 | **100.0%** | 28.0% | 52.0% | 64.0% | 0.454 |
| fee.credit.raise | 26 | **96.2%** | 46.2% | 65.4% | 69.2% | 0.570 |
| fee.latefee.waive | 19 | **100.0%** | 100.0% | 100.0% | 100.0% | 1.000 |
| fee.overdue.list | 21 | **100.0%** | 81.0% | 100.0% | 100.0% | 0.897 |
| fee.payment.record | 22 | **100.0%** | 40.9% | 81.8% | 90.9% | 0.610 |
| fee.reminder.send | 20 | **100.0%** | 75.0% | 95.0% | 95.0% | 0.857 |
| fee.writeoff.propose | 22 | **95.5%** | 31.8% | 72.7% | 72.7% | 0.513 |

Hardest sentences (the intents searched; fused rank worse than 3, at most 25):

| sentence | lang | expected | fused rank | top 3 | in top 30 |
|---|---|---|---:|---|---|
| Find out how many students are enrolled | ur-Latn | `dashboard.main.read` | - | `report.student.run`, `student.place`, `readmission.reactivate` | **no** |
| Give me the morning numbers | ur-Latn | `dashboard.main.read` | - | `branch.user.assign`, `student.transfer.place`, `exam.paper.schedule` | **no** |
| Provide the monthly accounts record | ur-Latn | `dashboard.main.read` | 77 | `staff.leaveallowance.set`, `accounts.read`, `asset.assign` | **no** |
| Check how many card requests are pending | en | `dashboard.main.read` | 53 | `pickup.queue.read`, `pickup.card.limit.set`, `pickup.card.request` | **no** |
| Note that 18000 has been demanded for the whole year and payment will not be possible now | ur-Latn | `fee.writeoff.propose` | 48 | `fee.payment.correction.return`, `fee.refund.approve`, `fee.batch.reverse` | **no** |
| Return the money because a concession that was supposed to be applied was not given | ur-Latn | `fee.credit.raise` | 41 | `appointment.letter.return`, `fee.refund.raise`, `salary.return` | **no** |
| Ask how the school is doing today | en | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| Ask how the school is doing today | ur-Latn | `dashboard.main.read` | 36 | `stock.request.approve`, `asset.disposals.read`, `pickup.card.request` | **no** |
| Report that money has not been received yet | ur-Latn | `fee.cancellation.raise` | - | `fee.payment.correction.raise`, `stock.cashreturned.record`, `fee.refund.raise` | yes |
| Write off 18,000 as uncollectible after one year of recovery attempts | en | `fee.writeoff.propose` | 53 | `fee.writeoff.approve`, `fee.writeoff.return`, `exam.cumulative.run` | yes |
| Waive an old balance because the father has passed away and there is no income | en | `fee.writeoff.propose` | 34 | `card.replace`, `payslip.mine.read`, `finance.reconciliation.start` | yes |
| Stop collection efforts on the outstanding amount for this family due to inability to contact them | en | `fee.writeoff.propose` | 25 | `fee.plan.request`, `fee.overdue.list`, `pickup.cards.void.all` | yes |
| Return money to a family | ur-Latn | `fee.credit.raise` | 19 | `fee.refund.raise`, `account.reactivate`, `fee.invoice.issue` | yes |
| Adjust duplicate bill for Ali in next month's account | ur-Latn | `fee.credit.raise` | 19 | `branch.update`, `account.reactivate`, `fee.latefee.policy.return` | yes |
| A student left without paying fees and the staff member is reporting this situation | ur-Latn | `fee.writeoff.propose` | 18 | `fee.reminder.send`, `fee.overdue.list`, `quiz.student.absent` | yes |
| Hold the extra amount they paid on the account instead of refunding | en | `fee.credit.raise` | 15 | `fee.refund.pay`, `fee.refund.return`, `account.disable` | yes |
| A bill was created for a student who had already left the school | ur-Latn | `fee.cancellation.raise` | 14 | `asset.disposals.read`, `asset.disposal.approve`, `fee.refund.raise` | yes |
| Two bills were created and both have been paid in. | ur-Latn | `fee.credit.raise` | 13 | `fee.writeoff.reverse`, `finance.voucher.pay`, `fee.credit.approve` | yes |
| Cancel this | ur-Latn | `fee.cancellation.raise` | 11 | `exam.cancel`, `leave.request.cancel`, `staff.leave.cancel` | yes |
| Refund or waive the fine because it has been paid | ur-Latn | `fee.credit.raise` | 11 | `fee.latefee.waive`, `expulsion.clearance.settle`, `fee.batch.reverse` | yes |
| Provide a brief report of the collection | ur-Latn | `dashboard.main.read` | 11 | `pickup.collections.read`, `pickup.collection.verify`, `report.operational.run` | yes |
| Credit the invoice | ur-Latn | `fee.credit.raise` | 10 | `fee.batch.reverse`, `fee.credit.approve`, `fee.challan.generate` | yes |
| Credit this amount | ur-Latn | `fee.credit.raise` | 10 | `fee.credit.approve`, `stock.advance.issue`, `fee.refund.pay` | yes |
| Record that the stamped challan for Usman came back with 12,000 received on Monday | en | `fee.payment.record` | 9 | `stock.return.record`, `stock.cashreturned.record`, `visitor.pass.recover` | yes |
| Tell me how much money was collected today | ur-Latn | `dashboard.main.read` | 8 | `stock.cashreturned.record`, `fee.payment.record`, `asset.disposals.read` | yes |
