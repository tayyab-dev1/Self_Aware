# AI Layer — Proof of Concept

A natural-language layer over the school management system. Staff type a sentence; the system
works out which action it means, shows a confirmation built from real numbers, and runs it only
after the backend has re-checked everything.

```
"class 5 blue ke defaulters ko whatsapp par reminder bhejo"
"how much did we collect this month"
```

```
Chat UI
   │
   ▼
AI LAYER   ai-layer/   Python 3.12 · FastAPI · port 8081
   │  understands the sentence, builds a plan, talks to the user
   │  never touches business data
   ▼  /agent/metadata · /agent/preflight · /agent/execute
BACKEND    backend/    Java 21 · Spring Boot 3.5 · port 8080
   │  permissions, scope, preconditions, counts, execution, audit
   ▼
Postgres 16 + pgvector   docker/        Embeddings: BGE-M3 service   docker/
```

- **The rules:** [docs/CLAUDE.md](docs/CLAUDE.md), the twelve invariants. Read it before changing anything.
- **The plan:** [docs/POC_Implementation_Plan.md](docs/POC_Implementation_Plan.md), seven phases, one per session.
- **Who does what, in plain words:** [ARCHITECTURE.md](ARCHITECTURE.md): the AI layer, agent-gateway and school-app, and why they are split.
- **Which file holds what:** [STRUCTURE.md](STRUCTURE.md).

---

## Status

| Phase | What | State |
| --- | --- | --- |
| 0 | Scaffold: both services, database, migrations, seed data | **Done** (14 Sep 2026) |
| 1 | Registry and metadata (`@AgentCapability`, versions, build-time checks) | **Done** (14 Sep 2026) |
| 2 | Preflight (resolve → check → count → compose, signed token) | **Done** (14 Sep 2026) |
| 3 | Execute (transaction, audit, idempotency, delta rule) | **Done** (14 Sep 2026) |
| 4 | Retrieval (index sync, hybrid search, eval set, 90% recall gate) | **Done** (14 Sep 2026) |
| 5 | Decompose and plan (Haiku 4.5, Sonnet 5, validator) | **Done** (14 Sep 2026) |
| 6 | Orchestrator and chat (`POST /chat`) | **Done** (14 Sep 2026) |
| 7 | Measure (recall, plan accuracy, refusals, CI regression) | **Done** (14 Sep 2026) |

### What Phase 7 delivers

One command prints the four numbers the POC is judged on, for all sentences, English and Roman Urdu:

```
make measure
                      all             English         Roman Urdu
recall@30              95.4%           96.8%           94.6%
plan accuracy          88.6%           93.7%           85.7%
refusal correctness    92.7%           96.9%           89.8%
validator catch rate  100.0%          100.0%          100.0%
out of: 175 labelled sentences, 70 to refuse, 600 injected faults
```

These are the numbers since decompose stopped thinking (decision 67). With the first recording, made
with thinking, they were 96.6%, 89.7%, 93.5% and 100% (607 faults). The difference is two Roman Urdu
sentences phrased differently, which is normal when Haiku is asked again.

- **recall@30:** the right capability is among the candidates retrieved with Haiku's intents, among 489 capabilities.
- **plan accuracy:** Sonnet's answer is exactly one step, or a request for input, for the right capability.
- **refusal correctness:** the system acted exactly when it should. It checks two sets:
  - the 175 labelled sentences;
  - 70 it must refuse: 60 requests the planning contracts send to capabilities the POC does not have, such as a fee concession, a refund, attendance or a fee structure, plus 10 greetings and off-topic questions.
- **validator catch rate:** faults injected into real planner answers that the validator refused. There are 13 kinds, such as a made-up capability id, an invented parameter, a name the user never wrote, an amount the user never wrote, or a fourth step. It is 600 of 600, each for its own reason.

**How it works:**

- **Recorded model answers.** Every Haiku and Sonnet answer is saved, before validation, in `ai-layer/eval/recordings/`, together with a hash of the exact prompt.
  - `make measure` asks the models only for answers not yet recorded. The first recording took 16 minutes; a run from recordings takes about 25 seconds.
  - A changed prompt, glossary or thinking setting means re-recording.
- **The CI regression run.** `make measure-ci` replays the recordings without calling a model, and fails when any number falls below `ai-layer/eval/measure/baseline.json`. [.github/workflows/ai-layer.yml](../.github/workflows/ai-layer.yml), at the repository root, runs it, plus the backend and AI layer tests.
- **A broken description shows up.** The run replaces `fee.payment.record`'s description with one about library books. Recall@30 for its sentences falls from 100% to 9.1%, and for all sentences from 95.4% to 84.6%.
- **The report** [ai-layer/eval/reports/measure.md](ai-layer/eval/reports/measure.md) lists outcomes per set, plan accuracy per capability, each fault kind, and every sentence that went wrong.
- **Tests:**
  - AI layer: 262 in `make ai-test` (241 unit, 19 integration, 2 build checks, counting the chat page, its pipeline view and decompose without thinking, added after Phase 7), 11 eval tests in `make ai-eval`, and 3 real-model checks;
  - backend: unchanged at 157.

**What the numbers say:**

- **The planner errs towards refusing, not towards the wrong action.** Of the 20 labelled sentences it got wrong:
  - 13 were refusals;
  - 4 were answers the validator refused (2 of them because retrieval, with the new intents, no longer offered the capability the recorded plan chose);
  - 3 picked a different capability, and preflight would still show each of those to the user before anything ran.
- **Most refusals are fair, given what the POC capabilities can do.**
  - `fee.reminder.send` takes a section, so "is family ko fees ka reminder karo" (one family) and "message the over-90-day defaulters" (no section) are refused.
  - The dashboard misses (5) are the tiles its description does not mention (see open questions).
- **Among the 70 sentences to refuse, one was acted on:** "fees ka dashboard kholo" became `dashboard.main.read`, which is arguably right.
- **Measuring changed the validator once.** It was refusing correct lookup phrases the planner built from the user's own words, such as "Zain's September bill" from "Zain … got a September bill". Relaxing that rule raised plan accuracy from 85.1% to 89.7% and refusal correctness from 90.2% to 93.5%, with every fault still caught (decision 60).

### What Phase 6 delivers

`POST /chat` puts every phase together into a conversation. A staff member types a sentence and gets one of
four replies: an **answer**, a **question**, a **confirmation** or a **refusal**.

```
IDLE ─sentence─▶ PLANNING ─plan─▶ preflight ─▶ AWAITING_CONFIRM ─yes─▶ EXECUTING ─▶ RESPONDING ─▶ IDLE
                    │               │                 └─no─▶ IDLE (nothing ran)
                    │               └─ AMBIGUOUS_ENTITY, NOT_FOUND ─▶ AWAITING_INPUT
                    ├─ needs input ───────────────────────────────▶ AWAITING_INPUT
                    └─ refusal ─▶ IDLE
AWAITING_INPUT ─answer─▶ the same plan, completed ─▶ preflight      (never planned again)
```

- **One turn at a time.** Each request carries the user's bearer token and exactly one of: a `message`, a `choice` (an option's id), or `confirm` (true or false).
- **Models are called only for a new sentence.** Answers, choices, confirmations and retries all reuse the plan the session already holds.
- **Reads run in one turn.** A plan that changes nothing needs no confirmation: preflight, then execute, then the backend's reply.
- **Writes wait for "yes".** The confirmation is the backend's own text. "yes", "haan", "theek hai" and `confirm: true` all run it; "no", "nahi", "rehne do" and `confirm: false` cancel it, and nothing runs.
- **Questions:**
  - A name that matches several records is asked as a choice, with the backend's options. Pick by number, by name or by id.
  - A name that matches nothing is asked again, up to 3 tries.
  - A value the sentence left out is asked for from the parameter's meaning.
  - Answers are read by plain code, never a model. An answer that cannot be read is asked again.
- **When things change between turns:**
  - An expired confirmation is shown again from the same plan.
  - A count that moved at execute is confirmed again with the new numbers.
  - A failed precondition is refused in the backend's words.
  - A metadata change refuses and forgets the cached plan.
- **The plan cache** remembers what planning decided (plans, never results; invariant 12). The key is the normalised sentence, the allow-list, every capability version and today's date.
- **Sessions** are kept in memory for 30 minutes, behind an interface. Each belongs to the user who started it, and the allow-list is fetched from the backend every turn.
- **Readiness** now reports `chat`: it needs the Claude CLI.
- **Tests:**
  - AI layer: 236 in `make ai-test` (215 unit, 19 integration, 2 build checks), plus 3 model checks and 6 eval tests;
  - backend: unchanged at 157.

**What the live checks show** (`make verify-phase6`, through the running stack and the real models):

| Turn | Reply |
| --- | --- |
| *class 5 blue mein kis kis ki fees baqaya hai?* | answer: *Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.* (audited STARTED, SUCCEEDED) |
| *class 5 blue ke defaulters ko whatsapp par reminder bhejo* | confirmation: *Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone.* (no audit row yet) |
| `confirm: true` | answer: *Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.* (5 reminders counted with SQL) |
| *class 5 ke defaulters ko sms par reminder bhejo* | question: Class 5 Blue (12 students) or Class 5 Green (10 students)? (one decompose and one plan call) |
| choice: Class 5 Blue | confirmation: *Send a fee reminder to 6 guardians in Class 5 Blue by SMS, …* (no further model call, same plan id) |
| `confirm: false` | answer: *Cancelled. Nothing was changed.* (no audit row for the plan) |

### What Phase 5 delivers

The AI layer can now turn a sentence into a checked plan, using real models for the first time. Nothing
runs yet: Phase 6 sends the plan to preflight, shows the confirmation and executes it.

```
"class 5 blue ke defaulters ko whatsapp par reminder bhejo"
   │ decompose (Haiku 4.5)   ─▶ "Send a WhatsApp reminder to the defaulters in class 5 blue"   (English, names copied)
   │ retrieve (Phase 4)      ─▶ up to 30 candidates the user may use, look-alikes together
   │ plan (Sonnet 5)         ─▶ a plan, "needs input", or a refusal
   │ validate (no model)     ─▶ every id, parameter, form, type, quoted name and amount checked; versions stamped
   ▼
fee.reminder.send  section_id {"raw": "class 5 blue"}  channel {"value": "whatsapp"}
   ─▶ preflight: "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. …"
```

- **Both models run through the Claude Code CLI** on the Claude subscription (`app/llm/`).
  - Each call is one headless subprocess with a pinned model, a JSON schema for structured output, no tools, no MCP servers, no settings files and no saved session.
  - The sentence goes in on stdin, never on the command line.
  - The runner sits behind one small interface, so moving to the Anthropic SDK changes one file.
- **Decompose** (`app/decompose/`) writes 1 to 3 English intents, in order, and lists the names each one carries.
  - A name the user never wrote, a name changed in its intent, or an intent left in Roman Urdu is refused.
  - A short school glossary goes in the prompt (`domain/school/glossary.py`).
- **The planner** (`app/planning/`) gets:
  - the sentence as typed;
  - the intents, labelled "a retrieval aid, not the plan";
  - today's date in the school's time zone;
  - the candidates, each with its parameters.
  
  It answers with exactly one of three shapes:
  - a plan;
  - `needs_input`, naming the missing required parameters (Phase 6 asks the user, from each parameter's meaning);
  - a refusal: `no_matching_capability`, `not_a_request` or `too_many_actions`.
- **The validator** (`app/validation/`) checks the answer before anything uses it.
  - Every capability id must be in the current metadata, in the user's allow-list, and among the candidates the planner was shown.
  - Parameters must be declared, never duplicated or invented, and every required one without a default must be present.
  - A looked-up parameter must carry the user's own words, quoted from the sentence. Any other parameter carries a value of its type, from its allowed values.
  - Amounts must be numbers the user wrote. Dates must be real `YYYY-MM-DD` dates.
  - A value from an earlier step must name a fact that step publishes. There are at most 3 steps.
  - Versions come from the metadata, never from the model.
  - A broken rule refuses the whole answer, with a code for each problem.
- **No candidates, no planner call.** If retrieval finds nothing the user may use, the answer is a refusal without spending a Sonnet call.
- **`make plan Q="..."`** shows every stage for a sentence, ending with what the backend's preflight says.
- **Retrieval measured with real intents.** The eval now also decomposes all 175 sentences with Haiku and searches with its intents: [ai-layer/eval/reports/retrieval_with_intents.md](ai-layer/eval/reports/retrieval_with_intents.md).
- **Tests:**
  - AI layer: 172 in `make ai-test` (151 unit, 19 integration, 2 build checks), plus 3 real-model checks and 6 eval tests;
  - backend: unchanged at 157.

**Retrieval with real intents** (stress index, 489 capabilities, recall@30):

| Queries | As typed (Phase 4) | English gloss (Phase 4 stand-in) | **Haiku's intents** |
| --- | ---: | ---: | ---: |
| All 175 | 82.3% | 94.9% | **96.6%** |
| English (63) | 95.2% | 95.2% | **96.8%** |
| Roman Urdu (112) | 75.0% | 94.6% | **96.4%** |

- These numbers come from the Haiku answers recorded in Phase 7 (`eval/recordings/decompose.json`), where decompose answered all 175 sentences within the rules.
- The first Phase 5 run gave 96.0% (Roman Urdu 95.5%). One of its answers listed a name the user never wrote.
- The weakest capability is `dashboard.main.read`, at 75% (see open questions).

**What the live checks show** (`make verify-phase5`, on the running stack):

| Sentence | Outcome |
| --- | --- |
| *class 5 blue ke defaulters ko whatsapp par reminder bhejo* | `fee.reminder.send`, section "class 5 blue" as typed; preflight: *Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding.* |
| *kal Lahore mein mausam kaisa hoga?* | refusal, `no_matching_capability` |
| The same Roman Urdu sentence, with a mocked planner naming `fee.defaulters.expel` | caught by the validator, `UNKNOWN_CAPABILITY` |
| *Ahmed Raza ki September ki fees 5000 cash aaj mili hai, record karo, phir class 5 blue ka baqaya dikhao* | `fee.payment.record` then `fee.overdue.list`; preflight: *Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice …* |
| *record a payment for Ahmed Raza's September invoice* (tried with `make plan`) | `needs_input`: route, amount_received, payment_date |
| A duplicate charge that was already paid (tried with `make plan`) | `fee.credit.raise` chosen over its three look-alikes; preflight refuses it with `NOT_IMPLEMENTED` |

### What Phase 4 delivers

The AI layer can now answer "which capabilities could this sentence mean?". It keeps its own index of
capability descriptions in step with the backend, and searches it two ways. Nothing is planned or run
yet: retrieval only narrows the choice for the planner in Phase 5.

```
every 30 s:  GET /agent/metadata/versions ─▶ changed? ─▶ GET /agent/metadata ─▶ embed the description (BGE-M3)
             ─▶ upsert into ai_layer.capability_index; delete what the backend no longer lists

a query:     embed it ─▶ dense search (pgvector cosine)  ─┐
                      ─▶ lexical search (Postgres FTS, any word) ─┴▶ RRF: Σ 1/(60 + rank)
             ─▶ each capability followed by its declared siblings ─▶ only the user's allow-list ─▶ at most 30
```

- **Metadata sync** (`app/sync/`).
  - It polls versions only, so an unchanged backend costs one small request.
  - A changed version, or an index row written by another embedding model, is re-embedded.
  - A withdrawn capability is deleted.
  - Embedding finishes before a database connection is taken (invariant 10).
  - It also keeps the full metadata in memory as the catalog the planner will read.
  - Readiness now reports `metadata_sync` and says "not ready" until the first sync has filled the index. A later failed poll is reported, but the index keeps serving what it last synced.
- **Hybrid retrieval** (`app/retrieval/`).
  - Dense and lexical rankings for each query text, fused with reciprocal rank fusion.
  - A capability always arrives together with its `disambiguate_from` siblings, so the four fee corrections come as one group or not at all.
  - The allow-list is required and applied inside the SQL; a sibling outside it is never pulled in.
  - Every candidate says why it is there: its fused score, its dense and lexical ranks, and `sibling_of`.
- **Index migration 0002** adds `version`, `disambiguate_from`, `embedding_model` and `synced_at`.
- **The eval** (`ai-layer/eval/`, `make ai-eval`).
  - 175 labelled sentences: 75 from the planning contracts' own routing examples and near-misses, and 100 written for this eval. 112 are Roman Urdu, each with an English gloss.
  - Every member of the confusable cluster has at least 19 sentences.
  - It measures two indexes with the service's own code:
    - the **POC index** (the 8 capabilities, filled by the real sync);
    - a **stress index**, which adds 481 other planning-contract intents as distractors, because 8 capabilities make recall@30 trivially 100%.
  - The report is [ai-layer/eval/reports/retrieval.md](ai-layer/eval/reports/retrieval.md).
- **`make retrieve Q="..."`** shows the candidates for any sentence, and why.
- **Tests:** AI layer 127 (102 unit, 19 integration, 2 build checks, 4 eval); backend unchanged at 157.

**Results** (recall@30: is the right capability among the candidates the planner would see):

| Queries | POC index (8) | Stress index (489) |
| --- | ---: | ---: |
| **In English: English as typed, Roman Urdu through its gloss** (what retrieval will receive from Phase 5) | **100%** | **94.9%** |
| All 175 as typed | 100% | 82.3% |
| English (63) | 100% | 95.2% |
| Roman Urdu as typed (112) | 100% | 75.0% |
| Roman Urdu through its English gloss (112) | 100% | 94.6% |

- **Clusters:** every query that retrieved any cluster member retrieved the whole cluster (287 of 287 on the POC index, 245 of 245 on the stress index).
- **What the numbers say:**
  - The plan's gate (above 90%) passes, on the POC index and among 489 capabilities.
  - Typed Roman Urdu does not pass among distractors. English does, so the translation step in Phase 5 carries real weight (decision 40).
  - Ranking is weaker than recall: among distractors, for queries in English, the right capability is first 49% of the time and in the top 5 78% of the time. The planner sees 30 candidates, so recall is what matters here, and Phase 7 tracks both.

### What Phase 3 delivers

`POST /agent/execute` runs a plan the user confirmed. It takes the plan, exactly as it went to
preflight, with its token and the user's sentence. Everything preflight checked is checked again,
because preflight was for showing the user and this is enforcement (invariant 4). Still no AI.

```
whole plan:  token (TOKEN_INVALID, TOKEN_EXPIRED) ─▶ versions, permission, implemented, parameters
                                                           │  refused here = an error response, nothing runs
each step, in its own serializable transaction:            ▼
  already succeeded under session:plan:step? ─yes─▶ REPLAYED: the recorded result, nothing runs twice
  │ no
  ▼
  same words still find the confirmed record? (OUT_OF_SCOPE) ─▶ whole request valid? (INVALID_PLAN)
  ─▶ preconditions again (PRECONDITION_FAILED) ─▶ count again (COUNT_CHANGED if it moved)
  ─▶ audit STARTED ─▶ handler runs ─▶ result matches what it declares? (VERIFICATION_FAILED)
  ─▶ reply filled from real values ─▶ audit SUCCEEDED ─▶ commit
a refused or failed step rolls back, is recorded as REFUSED or FAILED, and stops the plan; earlier steps stay done
```

- **Real handlers** for the four capabilities that run, each in a school-app service:
  - `fee.reminder.send` logs one Queued reminder per guardian reached, using the same `FeeReminderRepository.recipients` query that the confirmation's count used (invariant 8);
  - `fee.payment.record` records the payment with the next gapless receipt number;
  - `fee.overdue.list` and `dashboard.main.read` answer from the data.
  - The same methods still work as plain HTTP endpoints for a signed-in user.
- **An append-only audit trail** (`agent_audit`, migration V6). Every step's events hold the user's sentence, ids and labels, the confirmed and actual counts, and any error code. A database trigger refuses UPDATE, DELETE and TRUNCATE.
- **Idempotency.** A write succeeds at most once per `session_id:plan_id:step`, enforced by a unique index. Replaying the key answers with the recorded result. Reads always run again, so data stays fresh (invariant 12).
- **The delta rule.** At or below 20 records, any change since the confirmation stops the write; above that, a change of more than 5% does. Someone approved 17, not 400.
- **Verification.** Execute checks what the handler returns: every declared fact must be there, and a counted write must have touched exactly the number counted inside its transaction. Otherwise the step is rolled back, writes and all.
- **No automatic rollback across steps.** A failed step stops the plan, and the answer says what succeeded, what failed and what did not run (`completed`, `partial` or `failed`).
- **A serialization conflict is retried** up to 3 times, then answered with `CONFLICT`.
- **The reply** comes from the capability's reply template, filled by the backend with real values in the school's wording, e.g. *Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.* The reminder's data also says how many guardians the channel could not reach.
- **Preflight now validates the whole request**, so a rule across fields refuses a plan before anyone confirms it. For example, a class list without a class.
- **AI layer:** `GatewayClient.execute()`.
- **Tests:**
  - backend: 157 (100 gateway unit, 8 school-app unit, 49 integration); 163 since the "which one?" filter (decision 68: 103 gateway unit, 8 school-app unit, 52 integration);
  - AI layer: 91 (76 unit, 13 integration, 2 build checks).

**What the seeded school gives** (checked by `make verify-phase3`):

| Execute | Answer |
| --- | --- |
| The confirmed WhatsApp reminder for Class 5 Blue | *Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.* 5 Queued reminders, audit STARTED then SUCCEEDED |
| The same plan and token again | `replayed`: the same reply, no new reminders |
| PKR 5,000 cash for Ahmed Raza's September invoice | *Recorded PKR 5,000 against Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031. Receipt RCT/LHR/26-27/000047.* |
| A payment confirmed for PKR 9,000, after another PKR 500 was paid | `PRECONDITION_FAILED` (amount_within_balance), nothing written |
| The WhatsApp reminder, after a sixth guardian got a WhatsApp number | `COUNT_CHANGED` (confirmed 5, now 6), nothing sent |
| The overdue list for Class 5 Blue | *Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding.* |
| A handler that writes 5 rows but reports 99 | `VERIFICATION_FAILED`, its 5 rows rolled back |

### What Phase 2 delivers

`POST /agent/preflight` takes a plan and answers with the confirmation the user approves, plus a
signed token for execute. Nothing is written, and no AI is involved.

```
plan in ──▶ read the plan against the registry ──▶ 1 resolve ──▶ 2 check ──▶ 3 count ──▶ 4 compose ──▶ confirmation + token
            INVALID_PLAN, STALE_VERSION,              NOT_FOUND     PRECONDITION_  (real numbers)   ("This cannot
            NOT_PERMITTED, NOT_IMPLEMENTED            AMBIGUOUS_    FAILED + hint                    be undone.")
                                                      ENTITY
```

- **The plan contract.**
  - A plan names capability ids and the versions it was made with.
  - Each parameter comes in one of three forms: a value (`{"value": "whatsapp"}`), the user's own words to look up (`{"raw": "class 5 blue"}`), or a fact from an earlier step (`{"from_step": 1, "field": "receipt_number"}`).
  - After `AMBIGUOUS_ENTITY`, the user's choice goes back with the same words (`{"raw": "class 5", "chosen_id": "2"}`). The choice must be one of the records those words match, so no id can be slipped in.
- **Resolvers for every entity type in the fee capabilities:** class, section, student and invoice.
  - Each searches only the user's branch, inside the SQL (invariant 7), so a record in another branch is simply `NOT_FOUND`.
  - Ambiguity comes back with candidates and what tells them apart: "12 students", or "PKR 9,000 outstanding".
- **Real precondition checks and a real count:**
  - `section_has_defaulters`, and a new `channel_reaches_defaulters`;
  - `invoice_is_open` and `amount_within_balance`;
  - the reminder count.
  - The count and the channel check call the same repository method the send will use in Phase 3 (invariant 8).
- **The confirmation, written by the backend (invariant 3).**
  - Each step's template is filled with real values, in the school's own wording: "PKR 71,500", "14 September 2026", "WhatsApp".
  - Warnings come from the metadata: "This cannot be undone." for anything irreversible, and a warning for branch-wide or organisation-wide writes.
  - Several writes become one numbered message with one approval.
- **A signed token.**
  - The token is `base64url(payload).HMAC-SHA256`, lasting 5 minutes.
  - Its payload holds the plan's SHA-256, the resolved ids, the counts, the user and the expiry, and never the user's words.
  - Preflight stores nothing.
- **The four confusable fee corrections are refused with `NOT_IMPLEMENTED`** before anything is looked up (your decision, 14 Sep 2026). They are marked `@AgentNotImplemented`, which is not published, so the planner still has to tell them apart by meaning.
- **More build-time rules.** The backend refuses to start when:
  - a parameter's resolver has no bean;
  - a confirmation could show a gap (an optional value, or a fact with no count to supply it);
  - a parameter would need a list of names looked up.
- **AI layer:** `GatewayClient.preflight()`, with refusals that carry the candidates or the hint.
- **Tests:**
  - backend: 120 (75 gateway unit, 8 school-app unit, 37 integration);
  - AI layer: 88 (73 unit, 13 integration, 2 build checks).

**What the seeded school gives** (every line checked by `make verify-phase2`):

| Plan | Preflight answers |
| --- | --- |
| Remind "class 5 blue" by WhatsApp | *Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone.* |
| … by SMS / by email | 6 guardians, PKR 78,000 / 1 guardian, PKR 6,500: what each channel can actually reach |
| Remind "class 5" | `AMBIGUOUS_ENTITY`: Class 5 Blue (12 students), Class 5 Green (10 students) |
| Remind "class 6 blue" | `PRECONDITION_FAILED`: *Nobody in this section has overdue fees right now* |
| Remind "class 5 green" by email | `PRECONDITION_FAILED`: *No guardian with overdue fees in this section can be reached by that channel* |
| Record PKR 5,000 cash for "Ahmed Raza's September invoice" | *Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031.* |
| Record PKR 9,500 on the same invoice | `PRECONDITION_FAILED`: *That is more than is outstanding on this invoice* |
| Any of the four fee corrections | `NOT_IMPLEMENTED` |

Class 5 Blue has 8 students who owe, 7 guardians between them, and 5 of those guardians on WhatsApp. The
confirmation says 5, because 5 is what the send will reach.

### What Phases 0 and 1 deliver

- **Postgres 16 with pgvector.** Each service has its own login role and schema, and Postgres refuses
  the AI layer's role on business data (invariant 1).
- **The registry.**
  - `@AgentCapability`, `@AgentParam`, `@AgentPrecondition` and `@AgentEffect` sit on controller methods, and are read into versioned entries at startup.
  - Parameter types and required-ness come from the request record.
  - `GET /agent/metadata`, `/agent/metadata/versions` and `/agent/session/capabilities` serve them.
- **The 8 POC capabilities**, with metadata from their planning contracts (decision 1).
  - Handlers answer 501 until Phase 3.
- **All five build-time assertions.** Four run when the backend starts. The fifth (no near-duplicate descriptions) runs in the AI layer against real BGE-M3 embeddings.
- **A generated contract.** The backend publishes [openapi/agent-gateway.json](openapi/agent-gateway.json)
  and [snapshots/agent-metadata.json](snapshots/agent-metadata.json). The AI layer's Pydantic models are
  generated from the spec, and tests on both sides fail on drift.

**How close the descriptions are** (BGE-M3 cosine similarity; the rule's line is 0.92). Phase 2
changed no description, so these are unchanged:

| Pair | Similarity | Declared siblings |
| --- | --- | --- |
| `fee.cancellation.raise` ~ `fee.credit.raise` | 0.943 | yes |
| `fee.cancellation.raise` ~ `fee.writeoff.propose` | 0.926 | yes |
| `fee.credit.raise` ~ `fee.writeoff.propose` | 0.919 | yes |
| `fee.overdue.list` ~ `fee.reminder.send` | 0.827 | yes |
| `fee.latefee.waive` ~ `fee.payment.record` (closest non-siblings) | 0.750 | no |

---

## Quick start

**You need:** Docker Desktop (give it at least 4 GB of memory; see below), a JDK 21 or newer (tested
on 23), [uv](https://docs.astral.sh/uv/) and `make`. Maven and Python come by themselves.

```bash
make env            # creates .env from .env.example
```

```bash
make db-up          # Postgres + pgvector, waits until healthy
```

```bash
make embeddings-up  # the BGE-M3 service (first start downloads about 2.3 GB)
```

```bash
make backend-run    # terminal 1: migrates and seeds the database, builds the registry
```

```bash
make ai-install && make ai-run   # terminal 2: migrates the index, syncs the metadata into it
```

Phases 5 and 6 also need the Claude desktop app, signed in: its bundled CLI runs Haiku and Sonnet on
your Claude subscription, and the AI layer finds it by itself (or set `AI_LAYER_CLAUDE_CLI_PATH`).

```bash
make verify-phase6  # terminal 3: checks every Phase 6 "done when" item through POST /chat
```

```bash
make verify-phase7  # the four numbers from the recorded answers, and the broken-description check
```

`make verify-phase7` needs only Docker and the embeddings service. It runs the CI regression run
(`make measure-ci`) with no Claude CLI reachable, and should end with
`Phase 7 is done: every check passed.`

Expected output of `make verify-phase6` (about a minute; it sends Class 5 Blue's WhatsApp reminder once more,
which logs 5 reminders and changes no balance):

```
Phase 6: Orchestrator and chat

The full loop through POST /chat (http://127.0.0.1:8081)
  ✓ a token the backend does not accept gets 401, before any model is called
  ✓ read: a Roman Urdu question is answered in one turn, in the backend's words: "Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding."
  ✓ read: the answer carries the step's status and count (8 students)
  ✓ read: the backend audited it: ['STARTED', 'SUCCEEDED']
  ✓ write: the reply is the backend's confirmation: "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone."
  ✓ write: nothing has run before the user confirms (no audit row yet)
  ✓ write: confirming runs it and answers in the backend's words: "Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp."
  ✓ write: 5 reminders were logged (counted here with SQL) and audited STARTED, SUCCEEDED
  ✓ write: the plan executed is the plan that was confirmed

An ambiguous name, resumed without planning again; a cancelled confirmation (model calls counted)
  ✓ "class 5" is ambiguous: the reply is a question with the backend's options ['Class 5 Blue', 'Class 5 Green']
  ✓ planning the sentence took one decompose and one plan call: ['decompose', 'plan']
  ✓ choosing Class 5 Blue resumes the same plan: "Send a fee reminder to 6 guardians in Class 5 Blue by SMS, covering PKR 78,000 outstanding. This cannot be undone."
  ✓ resuming made no second planner call, and kept the same plan id
  ✓ cancelling at the confirmation answers "Cancelled. Nothing was changed."
  ✓ after cancelling, the audit trail has no row for the plan (checked with SQL): []
  ✓ a late "yes" after cancelling runs nothing

The state machine and POST /chat (unit tests with a scripted backend and planner)
  ✓ read and write loops, questions resumed from the same plan, cancel, expired token, moved count, cache, sessions per user, API shape

Phase 6 is done: every check passed.
```

### Chat with it in the browser

With the backend and the AI layer running, open **http://127.0.0.1:8081/**.

1. Paste the dev token (`BACKEND_DEV_USER_TOKEN` from `.env`) under **Sign in** and press **Use**. It is kept in that browser tab only.
2. Type a sentence, or open **Try it** on the right and click an example. The examples are grouped by what the POC can do today:
   - overdue fees and the dashboard (reads, answered straight away);
   - fee reminders and recording a payment (writes, which wait for **Yes, go ahead**);
   - two steps at once;
   - the four fee corrections (understood, then refused as not built yet);
   - requests nothing here does.
3. Replies show their type (answer, question, confirmation, refusal) and code:
   - a question has a button for each option. A question or confirmation waits 30 minutes; answering later says it expired (`SESSION_EXPIRED`), so send the sentence again;
   - a confirmation has **Yes, go ahead** and **Cancel**;
   - an answer can open the backend's data, for example the overdue list as a table.

4. The **Pipeline** tab on the right shows every stage the turn went through, in order. Each stage says
   who did it, what it does in one sentence, how long it took, and what it was given and produced:

   | Stage | Who | What you see |
   | --- | --- | --- |
   | Who is asking | backend | the user id and the capabilities this user may use |
   | Conversation state | AI layer code | the phase before and after the turn (idle, waiting for an answer, waiting for yes) |
   | Plan cache | AI layer code | hit or miss; a hit skips both model calls |
   | Decompose | Haiku | the prompt, and the English intents with the names it copied |
   | Check the intents | AI layer code | the intents, once every name and the English are checked |
   | Embed the intents | BGE-M3 | the vector size and its first numbers |
   | Search the capability index | Postgres | per intent, the ranking by meaning and by words |
   | Fuse, add siblings, cap | AI layer code | the fused scores and ranks, and the candidates the planner sees |
   | Plan | Sonnet | the message, intents, today and candidates it saw, and its raw answer |
   | Validate the plan | AI layer code | the checked plan with versions stamped, or the broken rules |
   | Read the answer | AI layer code | how an option or typed value was put into the waiting plan |
   | Preflight | backend | the names it looked up, the count, and the confirmation (the token is hidden) |
   | Execute | backend | each step's status, count and data |
   | Reply | AI layer code | the reply, written without a model |

   Only the stages a turn needs appear: a "yes" shows no model call. A failed stage is red and says why,
   for example an expired Claude sign-in or a rule the planner broke. Colours tell apart the backend,
   model calls, search and plain code. Click a stage in the row at the top to jump to it, **raw JSON** to
   see the exact data, **Replay** to animate the stages again, and **Pipeline: … ›** on any earlier reply
   to show that turn.

The pill at the top shows the AI layer's readiness; hover it for each check. **New conversation** starts a
fresh session, and **Hide pipeline** gives the chat more room. A new sentence takes about 10 seconds;
answers to questions are instant.

The page is a developer tool served by the AI layer itself (`GET /`). It is one static file, talks
only to `/chat` and `/health/ready` on the same address, and a strict content-security policy stops
it loading or sending anything anywhere else. `AI_LAYER_CHAT_PAGE_ENABLED=false` turns it off.

The pipeline view comes from `POST /chat` with `"trace": true`. The trace is sent back only to the caller
of that turn and is never logged. It never contains the user's token or the backend's confirmation token.
`AI_LAYER_CHAT_TRACE_ENABLED=false` stops the AI layer from returning it.

**Every reply says "Sorry, I can't understand requests right now"?** The Claude CLI could not be used.
The failed **Decompose** stage in the pipeline says why, and so does the AI layer's log
(`planning_unavailable`). Most often it is `OAuth session expired and could not be refreshed`.

The AI layer runs the `claude` CLI bundled with the desktop app, and that CLI keeps its own sign-in in
the macOS keychain (`Claude Code-credentials`). It is separate from the desktop app's own sign-in, so
signing in to the app does not fix it. Sign the CLI in again from a terminal (a browser opens), then check:

```bash
"$(ls -d ~/Library/Application\ Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude | sort -V | tail -1)" auth login
```

```bash
"$(ls -d ~/Library/Application\ Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude | sort -V | tail -1)" auth status
```

`auth status` should say `"loggedIn": true`. The AI layer does not need a restart: the next sentence uses the new sign-in.

### Chat with it from the terminal

The token is `BACKEND_DEV_USER_TOKEN` from `.env`. Send a sentence:

```bash
curl -s -X POST http://127.0.0.1:8081/chat -H "Authorization: Bearer local-dev-token-change-me" -H "Content-Type: application/json" -d '{"message": "class 5 ke defaulters ko sms par reminder bhejo"}' | python3 -m json.tool
```

Answer with the `session_id` it returned, and then confirm or cancel the same way:

```bash
curl -s -X POST http://127.0.0.1:8081/chat -H "Authorization: Bearer local-dev-token-change-me" -H "Content-Type: application/json" -d '{"session_id": "PASTE-IT-HERE", "message": "blue"}' | python3 -m json.tool
```

- `{"session_id": "…", "confirm": false}` cancels, and nothing runs.
- `{"session_id": "…", "confirm": true}` sends the reminder.

<details>
<summary>The earlier phases' checks and their expected output (each still passes)</summary>


```bash
make verify-phase5  # decompose and plan with the real models
```

`make verify-phase5` makes about a dozen model calls; its eval step replays the recorded Haiku
answers in `ai-layer/eval/recordings/`. Expected output:

```
Phase 5: Decompose and plan

The whole pipeline on the running stack: decompose (Haiku) → retrieve → plan (Sonnet) → validate → preflight
  ✓ Roman Urdu: decompose wrote English intents and copied the names untouched
  ✓ Roman Urdu: the plan is fee.reminder.send for the user's own words, versions stamped
  ✓ Roman Urdu: section_id carries "class 5 blue" as typed, and the current version
  ✓ Roman Urdu: the backend's preflight accepts the plan: "Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500 outstanding. This cannot be undone."
  ✓ a sentence matching nothing is refused (no_matching_capability), not guessed
  ✓ a hallucinated capability id in a mocked planner answer is caught by the validator (UNKNOWN_CAPABILITY)
  ✓ a two-part sentence becomes two steps in the right order: fee.payment.record, then fee.overdue.list
  ✓ two parts: preflight accepts both steps: "Record PKR 5,000 received in cash on 14 September 2026 against Ahmed Raza's September 2026 invoice INV/LHR/26-27/000031."

The same four, as tests (validator rules with scripted models; the real models without retrieval)
  ✓ validator: unknown, disallowed and non-candidate ids, invented and missing parameters, forms, types, quoted names and amounts, steps
  ✓ real Haiku and Sonnet: Roman Urdu plan, refusal, two steps in order (make ai-model-checks)

Retrieval with real intents (the Phase 4 follow-up)
  ✓ recall@30 > 90% on the 489-capability stress index when searching with Haiku's intents; clusters whole (make ai-eval)

Phase 5 is done: every check passed.
```

To see every stage for a sentence of your own (preflight writes nothing):

```bash
make plan Q="Ahmed Raza ke September ke bill ka jurmana maaf kar do, bank band tha"
```

`make verify-phase4` checks retrieval and does not call the models. The first `make verify-phase4` embeds about 750 eval texts on the CPU, which took about 3 minutes on
the 8 GB Intel Mac this was built on. Later runs read them from `ai-layer/eval/.cache/` and take under
a minute. Expected output:

```
Phase 4: Retrieval

Metadata sync and retrieval in the running AI layer (http://127.0.0.1:8081)
  ✓ the AI layer is ready and says metadata sync has filled the index
  ✓ the index holds exactly the backend's 8 capabilities at their current versions
  ✓ a row whose version no longer matches the backend is re-embedded within one poll (30s)
  ✓ a sentence about one confusable correction retrieves all four, via siblings
  ✓ sibling expansion is visible on the candidates (sibling_of is set)
  ✓ retrieval never offers a capability outside the allow-list, not even a declared sibling

Retrieval eval (175 labelled sentences, POC index and a 489-capability stress index)
  ✓ the eval set is sound: 150-200 sentences, Roman Urdu glossed, every cluster member covered
  ✓ recall@30 > 90% on the POC index and among distractors, whole clusters, Roman Urdu reported (make ai-eval)

  From ai-layer/eval/reports/retrieval.md:
    (recall@30 for each index and query group, then the cluster lines)

Sync, fusion, siblings and index queries (unit and integration tests)
  ✓ sync re-embeds only what changed, deletes what was withdrawn, survives an outage; fusion and siblings
  ✓ real Postgres: migrations 0001-0002, dense and lexical rankings, siblings, startup sync before ready

Phase 4 is done: every check passed.
```

The live checks touch only the AI layer's own index: they mark one row stale and wait for sync to
repair it.

To see what retrieval offers for a sentence:

```bash
make retrieve Q="ye charge galat tha lekin family paisa de chuki hai"
```

Expected output of `make verify-phase3` (execute, which needs only the database and the backend):

```
Phase 3: Execute

Execute against the running backend (http://127.0.0.1:8080)
  ✓ a confirmed reminder runs and passes verification: "Sent a fee reminder to 5 guardians in Class 5 Blue by WhatsApp." (count 5, as confirmed)
  ✓ it logged 5 reminders, Queued, covering PKR 71,500 (counted here with SQL)
  ✓ it produced audit rows STARTED and SUCCEEDED, each holding the user's sentence (read here with SQL)
  ✓ replaying the same session, plan and step answers "replayed" and sends nothing more
  ✓ state changed after preflight (a PKR 500 payment added here) gives PRECONDITION_FAILED, and no payment is written
  ✓ a count that moved (a WhatsApp number added here: 5 guardians became 6) gives COUNT_CHANGED, and nothing is sent
  ✓ a token issued for another plan is refused as a whole (TOKEN_INVALID), and the attempt is recorded
  ✓ a read runs and answers from the data: "Overdue fees for Class 5 Blue: 8 students, PKR 84,500 outstanding."
  ✓ the audit trail refuses UPDATE and DELETE (tried here with SQL)

AI layer calls preflight and execute through its generated models
  ✓ the AI layer's client confirms a plan, executes it, and reads the step's reply

Rollback, payments and the rules behind execute (Maven)
  ✓ gateway: a replay runs nothing twice, the delta rule, a misreporting handler, partial plans, expired and altered tokens, stale versions
  ✓ real backend: a payment with the next receipt number, a closed section out of scope, a partial plan, and a misreporting handler rolled back with its writes

Phase 3 is done: every check passed.
```

The Phase 3 live checks only change the demo school in ways they undo. They add a test payment and a
WhatsApp number and then remove them, and the reminders they log change no balance. The audit trail
keeps what it recorded, because it is append-only. The earlier phases' checks still pass afterwards:

- `make verify-phase2` checks preflight.
- `make verify-phase0` checks the scaffold, and also needs `make ai-install` and `make ai-run`.
- `make verify-phase1` checks the registry, and also needs `make embeddings-up`, which downloads about 2.3 GB on first start.

</details>

### Try preflight and execute yourself

The token is `BACKEND_DEV_USER_TOKEN` from `.env`. A plan must carry the capability's current version:

```bash
VERSION=$(curl -s http://127.0.0.1:8080/agent/metadata/versions | python3 -c "import json,sys; print({v['id']: v['version'] for v in json.load(sys.stdin)['versions']}['fee.reminder.send'])")
```

```bash
curl -s -X POST http://127.0.0.1:8080/agent/preflight -H "Authorization: Bearer local-dev-token-change-me" -H "Content-Type: application/json" -d "{\"plan\": {\"plan_id\": \"try-1\", \"session_id\": \"try\", \"steps\": [{\"step\": 1, \"capability_id\": \"fee.reminder.send\", \"capability_version\": \"$VERSION\", \"params\": {\"section_id\": {\"raw\": \"class 5\"}, \"channel\": {\"value\": \"sms\"}}}]}}" | python3 -m json.tool
```

That answers `AMBIGUOUS_ENTITY` with two candidates. Send the same words back with `"chosen_id": "2"`
next to `"raw"` and it answers with the confirmation and a token.

To execute, send the same plan back with that token and the user's sentence. For example, the
overdue list, which changes nothing:

```bash
VERSION=$(curl -s http://127.0.0.1:8080/agent/metadata/versions | python3 -c "import json,sys; print({v['id']: v['version'] for v in json.load(sys.stdin)['versions']}['fee.overdue.list'])")
```

```bash
PLAN="{\"plan_id\": \"try-2\", \"session_id\": \"try\", \"steps\": [{\"step\": 1, \"capability_id\": \"fee.overdue.list\", \"capability_version\": \"$VERSION\", \"params\": {\"scope\": {\"value\": \"section\"}, \"section_id\": {\"raw\": \"class 5 blue\"}}}]}"
```

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/agent/preflight -H "Authorization: Bearer local-dev-token-change-me" -H "Content-Type: application/json" -d "{\"plan\": $PLAN}" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
```

```bash
curl -s -X POST http://127.0.0.1:8080/agent/execute -H "Authorization: Bearer local-dev-token-change-me" -H "Content-Type: application/json" -d "{\"plan\": $PLAN, \"token\": \"$TOKEN\", \"sentence\": \"class 5 blue mein kis ki fees baqaya hai\"}" | python3 -m json.tool
```

### Commands

| Command | What it does |
| --- | --- |
| `make help` | Lists every command |
| `make db-up` / `db-down` / `db-reset` | Start, stop, or wipe and recreate the database (the model cache is kept) |
| `make db-psql` | psql as the superuser |
| `make embeddings-up` / `embeddings-down` | Start (and wait for) or stop the BGE-M3 embeddings service |
| `make backend-run` | Run the backend (applies migrations + seed, builds the registry) |
| `make backend-test` | Backend unit tests, no Docker |
| `make backend-verify` | Backend unit + integration tests (needs Docker) |
| `make ai-install` | Install AI layer dependencies |
| `make ai-run` | Run the AI layer (applies index migrations) |
| `make ai-migrate` | Apply index migrations only |
| `make ai-test` | Every AI layer test except real model calls (integration needs Docker, build checks need `embeddings-up`) |
| `make ai-test-unit` | AI layer unit tests only, no Docker, no embeddings service, no model calls |
| `make measure` | The four numbers (recall@30, plan accuracy, refusal correctness, validator catch rate); records any missing model answer |
| `make measure-ci` | The regression run: every eval from the recorded answers, no model called; fails below the baseline (what CI runs) |
| `make ai-model-checks` | The Phase 5 checks against the real Haiku and Sonnet (Claude CLI, uses the subscription) |
| `make plan Q="..."` | Every stage for a sentence: intents, candidates, the checked plan, and preflight's answer (nothing runs) |
| `make ai-build-checks` | The description-similarity build check |
| `make descriptions-report` | How similar every pair of capability descriptions is |
| `make ai-eval` | The retrieval eval: recall gates, clusters, Roman Urdu, and retrieval with Haiku's intents; writes `ai-layer/eval/reports/` (needs Docker, `embeddings-up` and the Claude CLI) |
| `make retrieve Q="..."` | The candidates retrieval offers for a sentence, with scores and ranks (needs the AI layer to have synced once) |
| `make eval-corpus` | Re-extract the eval's contract sentences and distractors from `../planning-contracts` |
| `make ai-lint` | ruff + format check + mypy (strict) |
| `make contracts` | Re-export the OpenAPI spec and metadata snapshot, then regenerate the AI layer models |
| `make test` | Every test in both services (needs Docker and `embeddings-up`) |
| `make verify-phase0` … `verify-phase7` | Check the running stack against that phase's definition of done |

**After changing any `@Agent*` annotation, or a gateway request or response:** run `make contracts`.

- It re-exports `snapshots/agent-metadata.json` and `openapi/agent-gateway.json`, and regenerates the AI layer's models.
- The backend build fails until you do.
- The similarity check then runs on the new descriptions.

### Configuration and ports

Everything is in `.env` (copied from [.env.example](.env.example), every setting documented).
The Makefile loads it into every command.

- **Everything binds to 127.0.0.1:** Postgres (5433), the backend (8080), the AI layer (8081) and
  the embeddings service (8083).
- **Dev sign-in:** `BACKEND_DEV_USER_TOKEN` stands in for real authentication. A request with
  `Authorization: Bearer <token>` acts as `BACKEND_DEV_USERNAME`. Leave the token empty to disable
  it.
- **Preflight tokens:** `BACKEND_AGENT_TOKEN_SECRET` signs them and must be at least 32 characters.
  - Left empty, the backend picks a random key at each start and logs a warning. That is safe, but a confirmation does not survive a restart.
  - A real deployment must set it.
- **Execute:**
  - `agent.gateway.execute.small-count` (default 20) and `count-tolerance-percent` (default 5) set the delta rule;
  - `max-attempts` (default 3) says how often a serialization conflict is retried.
  - They live in `application.yml`.
- **Sync and retrieval:**
  - `AI_LAYER_METADATA_SYNC_INTERVAL_SECONDS` (default 30) sets how often versions are polled; `AI_LAYER_METADATA_SYNC_ENABLED=false` turns sync off.
  - `AI_LAYER_RETRIEVAL_CANDIDATE_CAP` (30), `AI_LAYER_RETRIEVAL_RRF_K` (60) and `AI_LAYER_RETRIEVAL_BRANCH_LIMIT` (50, how deep each search looks) tune retrieval. The eval runs on the defaults.
- **Models:**
  - `AI_LAYER_CLAUDE_CLI_PATH` points at the `claude` binary. Empty means the newest one inside the Claude desktop app.
  - `AI_LAYER_MODEL_TIMEOUT_SECONDS` (default 120) limits one model call.
  - `AI_LAYER_PLAN_MAX_STEPS` (default 3, and never more) caps a plan.
  - The model ids are pinned in code (`app/llm/runner.py`), not settings: changing a model is a deploy.
- **Chat:**
  - `AI_LAYER_CHAT_SESSION_TTL_SECONDS` (default 1800) is how long an unanswered question or confirmation is kept.
  - `AI_LAYER_PLAN_CACHE_SIZE` (default 256; 0 turns it off) is how many plans are remembered.
- **Docker memory:** the embeddings service needs about 3 GB. On the 8 GB Intel Mac this was built on,
  Docker has 3.8 GB, which fits Postgres, the embeddings service and the test containers. With less,
  raise it in Docker Desktop → Settings → Resources.
- **Port already in use?** Change it in `.env`, and update `AI_LAYER_BACKEND_BASE_URL` if you move
  the backend. On the machine this was built on, 8080 belongs to another project and 8090 to
  AgentDVR, so its local `.env` uses `BACKEND_PORT=8082`. On macOS a port taken over IPv4 can still
  look free over IPv6, so check with `lsof -nP -iTCP:<port> -sTCP:LISTEN`.
- **Changed a database password or the init script?** Run `make db-reset`.

---

## Decisions so far

Recorded so later sessions do not re-open them by accident. Each can be revisited, but on purpose.

**1. The POC uses the Fee module (modules/4_Fee_and_finance).** It has everything the plan asks
for: reads and writes, a counted write, single-record writes, entities to resolve, and the
confusable cluster. This is the registered set:

| Kind | Capability id | What it does | Contract | Handler |
| --- | --- | --- | --- | --- |
| Read | `fee.overdue.list` | who has not paid, and how much is outstanding | UC-04-06-PC-1 | Phase 3 |
| Read | `dashboard.main.read` | "aaj ka collection kitna hua?", the main dashboard figures | UC-01-07-PC-1 | Phase 3 |
| Write, counted | `fee.reminder.send` | remind every overdue family in a section | UC-04-07-PC-1 | Phase 3 |
| Write, single | `fee.payment.record` | record money received against one invoice | UC-04-05-PC-1 | Phase 3 |
| Confusable | `fee.cancellation.raise` | cancel a wrong charge nobody has paid | UC-04-08-PC-1 | never |
| Confusable | `fee.credit.raise` | credit a wrong charge that was already paid | UC-04-10-PC-1 | never |
| Confusable | `fee.writeoff.propose` | write off a debt that will never be collected | UC-04-09-PC-1 | never |
| Confusable | `fee.latefee.waive` | waive a late fee | UC-04-15-PC-4 | never |

**2. General engine, school-specific edges.** [ARCHITECTURE.md](ARCHITECTURE.md) explains the split in
plain words.

- `backend/agent-gateway` is generic, and Maven's module direction keeps it that way: its test
  suite uses a made-up "notes" domain, not schools. It is the part to keep: the real school backend
  adds it as a dependency.
- `backend/school-app` is a **stand-in for the real school backend**, which does not exist yet. It
  holds everything about schools: tables, seed, the annotated controllers, and the resolvers, checks
  and counts next to them. It is small but not fake (real tables, SQL and tests), because Phases
  1–3 prove the invariants on it. It is retired once the real backend adopts the gateway.
- In the AI layer, school vocabulary will go in `ai-layer/domain/school/` (Phase 5). The engine
  stays domain-free.

**3. Invariant 1 is enforced by Postgres, not by convention.** Two login roles, each owning one
schema; the AI layer role has no privilege on `school` and cannot create objects in `public`.

**4. The contract is generated, and so is the metadata snapshot.**

- The backend publishes the `agent-gateway` OpenAPI group to `openapi/agent-gateway.json` and
  `GET /agent/metadata` to `snapshots/agent-metadata.json`.
- Backend tests fail when either file is stale. AI layer tests fail when the generated models
  differ from the spec.
- Every generated model has `extra="forbid"`, and enums keep their Java names.
- `make contracts` updates all three.

**5. Backend persistence is Spring JDBC (`JdbcClient`), not JPA.** Scope in the SQL `WHERE` clause
(invariant 7) and counts that share the handler's query (invariant 8) are easier to see in explicit SQL.

**6. The wire format for `/agent/**` is snake_case**, set per type with `@JsonNaming`. The backend
rejects unknown JSON fields. Since Phase 2 the gateway also refuses to start in an application that
would accept them.

**7. The demo school is frozen at 14 September 2026.** Exact numbers are in the header of
[V5__seed_demo_school.sql](backend/school-app/src/main/resources/db/seed/V5__seed_demo_school.sql)
and pinned by `DatabaseMigrationIT`.

- **Class 5 Blue:** 8 students owe money, but they have only 7 guardians, and only 5 of those guardians have WhatsApp.
- **Class 6 Blue:** nobody owes anything.
- **Ambiguous names:** "class 5", "blue" and "ahmed" each match more than one record.

**8. Both model calls go through the Claude Code CLI (Phase 5)**, on the owner's subscription.

- The CLI path is versioned inside the desktop app, so it has to be a setting.
- The pinned model ids are `claude-haiku-4-5-20251001` and `claude-sonnet-5`.
- There is no temperature control. Consistency comes from structured output, the validator and the plan cache.
- CLAUDE.md records this under "On the Claude CLI".

**9. Versions:** Spring Boot 3.5.16, springdoc 2.8.17, Flyway 11, Maven 3.9.16 through the wrapper,
pgvector `0.8.6-pg16`, Text Embeddings Inference `cpu-1.9.3`, FastAPI 0.141, Pydantic 2.13, uv 0.11.

**10. Capability ids are the planning-contract ids** (14 Sep 2026). They are dotted and lower-case,
taken from `planning-contracts/dist/capability-index.json` and never invented.
`CapabilityIdsMatchPlanningContractsTest` enforces this, and also checks that `readOnly` matches the
contract's kind and that `reverses` names a real operation.

**11. CLAUDE.md is kept in line with the code.** It shows the modular repository layout, the Claude
CLI and embeddings service, the real `fee.reminder.send` example, the plan contract, and every error code.

**12. Embeddings run as a Docker service** (Text Embeddings Inference, CPU). Current PyTorch and
ONNX Runtime ship no Intel-Mac builds.

- The model (`BAAI/bge-m3`) and its revision are pinned in docker compose. The AI layer's client
  refuses a service reporting anything else, because the 0.92 threshold is model-specific.
- The batch is capped at 2048 tokens: the default warm-up needed more memory than Docker had, and
  the container was OOM-killed.
- The service is an optional compose profile, so `make db-up` does not start 3 GB of model.

**13. Parameters come from the request record.**

- A capability handler takes at most one record. Every field needs an `@AgentParam`, and every
  `@AgentParam` must name a field.
- Preflight turns plan values into that record's field types with the application's own Jackson, and
  runs the record's validation constraints on them. So a value preflight accepts is one the endpoint
  accepts: a payment dated in the future is `INVALID_PLAN` before anyone confirms it.
- Where the stand-in data model is simpler than the contract, the POC parameters are simplified too,
  and the class Javadoc says so:
  - a reminder goes to a whole section rather than hand-picked families;
  - a cancellation covers one invoice, not a batch;
  - the late fee id travels in the body, not the path;
  - payments are keyed only as cash or bank challan.

**14. Blast radius levels are generic:** `NONE` (reads, and only reads), `SINGLE`, `GROUP` (for
example one class), `BRANCH`, `ORGANISATION`. CLAUDE.md's old `CLASS` became `GROUP`, so the
gateway carries no school words.

**15. Only running capabilities need running code.**

- Phase 1's placeholder checks and count are gone.
- The four capabilities that can run have real resolvers, checks and a count.
- The four confusable ones carry `@AgentNotImplemented`, so the registry does not ask for their check, count or resolver beans. If the annotation is removed, the build asks for those beans again.
- Since Phase 3 their handlers run. Only the four confusable ones still answer 501 when called directly.

**16. Sign-in is a dev token for the POC.** The gateway defines the interfaces
(`UserContextResolver`, `CapabilityPolicy`); `school-app` implements them with a constant-time token
compare and the `app_users` row. The error code `UNAUTHENTICATED` is part of the contract.

**17. Preflight refuses the four confusable fee corrections with `NOT_IMPLEMENTED`** (your decision,
14 Sep 2026). It checks this after versions and permissions, and before any parameter is read, so no
name is resolved and no check runs for a capability that can never run. The marker is kept out of the
published metadata, so retrieval and the planner still have to tell the four apart by meaning.

**18. The plan contract** (`agent-gateway/…/plan`).

- A plan has `plan_id`, `session_id` and up to 3 `steps`.
- Each step has `step`, `capability_id`, `capability_version` and `params`.
- Each parameter is exactly one of `{"value"}`, `{"raw"}` (optionally with `"chosen_id"`) or `{"from_step", "field"}`.
- A parameter left out takes its declared default.
- A step that needs an earlier step's fact is confirmed with its pending template, and its checks and count wait for execute.
- The same plan goes back to execute: the token holds its hash, computed from canonical JSON exactly like capability versions.

**19. Error codes and statuses live in one place** (`AgentErrorCodes`). The OpenAPI spec documents each
status preflight can answer with, and the codes it carries.

| Status | Codes |
| --- | --- |
| 400 | `INVALID_PLAN` |
| 401 | `UNAUTHENTICATED` |
| 403 | `NOT_PERMITTED` |
| 409 | `STALE_VERSION` |
| 422 | `NOT_FOUND`, `AMBIGUOUS_ENTITY`, `PRECONDITION_FAILED` |
| 501 | `NOT_IMPLEMENTED` |

- **Reserved for Phase 3:** `TOKEN_EXPIRED` and `TOKEN_INVALID`.
- **`OUT_OF_SCOPE` is reserved too, and preflight never sends it.** A resolver cannot see outside the user's scope, so a name there is `NOT_FOUND`. That follows invariant 7 and CLAUDE.md's "vague message" rule, rather than the Preflight_Implementation flowchart, which shows `OUT_OF_SCOPE` coming from resolve.

**20. Names match as whole words, and exact names win.**

- "class 5 blue" finds Class 5 Blue, "blue" finds both Blue sections, and "5" does not find a Class 15.
- An invoice is found by its number, or by the student's name plus an optional month and year, in English or Roman Urdu: "Ahmed Raza's September invoice", "ayesha ki september ki fees".
- Resolvers return at most 50 matches, and a refusal lists at most 10 candidates.
- Words longer than 200 characters are refused as `INVALID_PLAN`.

**21. The reminder counts what the channel can reach, and a channel that reaches nobody is refused.**

- The count is guardians with overdue fees who have a contact for the channel, so Class 5 Blue by WhatsApp is 5, not 7.
- The new precondition `channel_reaches_defaulters` refuses a send that would reach nobody, rather than confirming "0 guardians".
- The count also publishes itself in words ("1 guardian", "5 guardians"), because a template cannot choose a plural.

**22. Preflight's three database passes run in one read-only, repeatable-read transaction**, so the count
sees the same data the checks passed on. There are no model calls anywhere near it.

**23. How values read is the host's choice.** The gateway calls a `TemplateFormatter`, with a plain default.

- school-app formats every decimal as rupees ("PKR 71,500"), because every decimal a fee capability confirms is money.
- Dates are written in words ("14 September 2026").
- Request enums supply a display name: `whatsapp` reads as "WhatsApp", and `cash` as "in cash".

**24. You said "No need to confirm" (14 Sep 2026)**, so the decisions from Phase 3 on were made without
asking. Each is recorded here, so any of them can be reopened.

**25. Each step runs in its own serializable transaction.**

- Inside it, in order: the checks again, the audit `STARTED` event, the handler, verification, and the `SUCCEEDED` event. They commit or roll back together.
- A refused or failed step is rolled back, then recorded as `REFUSED` or `FAILED` in a transaction of its own, so the attempt stays on record.
- Serializable isolation is what stops two payments passing the same balance check. A conflict is retried up to 3 times, then answered with `CONFLICT`.

**26. The audit trail is a table the host owns, behind the gateway's `AuditTrail` interface.**

- In school-app it is `agent_audit` (V6). A trigger makes it append-only, even for its owner.
- Each event holds the user's sentence, the step's values as ids, the labels the user saw, the confirmed and actual counts, and the error code.
- It never holds a token.

**27. Idempotency lives in the audit trail.**

- A unique index allows one `SUCCEEDED` write per `session_id:plan_id:step`.
- A replay answers with the recorded reply and data, and appends `REPLAYED`.
- Reads are never replayed: they run again, because results are never cached (invariant 12).

**28. The delta rule:** at or below 20 records (confirmed or now), any change stops the write; above
that, a change of more than 5% does. Both numbers are settings.

**29. Verification reads what the handler returns.**

- Every declared fact must be a property of the handler's return type. A counted write must also have a `count` equal to the count taken inside its transaction; any other write must have touched 1 record.
- The registry checks the return type when the application starts, so a handler that could never pass verification does not start.
- A mismatch is `VERIFICATION_FAILED`, and the step's writes are rolled back.

**30. A handler is the controller method itself.** The gateway looks it up in its registry and calls it
with the request record and, when the method asks for one, the signed-in `UserContext`. The same method
answers plain HTTP calls: the gateway resolves the `UserContext` from the request, or answers 401.

**31. Scope is re-checked by searching again.** At execute, each parameter's original words are
searched again inside the user's scope, and the record the user confirmed must still be among the
matches. If it is not (for example, the section was closed), the step is `OUT_OF_SCOPE`. No resolver
needs a second method.

**32. Refused as a whole versus refused per step.**

- **Before any step runs:** a token, version, permission or parameter problem is an error response, with the codes in decision 19 plus `TOKEN_INVALID` (403) and `TOKEN_EXPIRED` (409).
- **Once the plan is accepted:** the answer is 200, with each step's `status` and, for a failed step, the same error body as any refusal.
- **New codes:** `COUNT_CHANGED`, `CONFLICT`, `EXECUTION_FAILED` and `VERIFICATION_FAILED`.

**33. The reply is written by the backend** at execute, from the capability's reply template and the
same formatter as the confirmation. The AI layer shows it; it never composes one.

**34. Who the reminder misses** (the Phase 2 open question): the confirmation stays as it is. The
execute result's data says how many guardians with overdue fees the channel did not reach
(`not_reached`), so Phase 6 can show it.

**35. Preflight validates the whole request record** once its names are resolved. A rule across
fields, such as "a class list needs a class", is refused before anyone confirms. This settles the
Phase 2 open question about cross-field rules.

**36. Receipt numbers** follow the seeded pattern `RCT/<branch>/<session>/<number>`. Each is the next
number in its branch and session, and gapless because a failed payment rolls back with its number.
Receipt numbers are unique, so two payments at once cannot share one.

**37. The index row holds the description only**, as the plan says, plus what sync and siblings need:
version, `disambiguate_from`, and the embedding model and revision. A different model re-embeds
everything rather than mixing vectors from two models.

**38. Lexical search matches any word of the query, not every word.** A sentence rarely repeats a
description's words, so requiring all of them finds almost nothing. Postgres still does the stemming
and drops stop words.

**39. Lexical ranking is not divided by description length.** Dividing by length favoured short
descriptions over ones that also say what they are not for. It cost 4 points of recall@30 in the stress
eval (78.3% as typed, against 82.3%), and it penalises exactly the contrast lines the rules ask for.
This was chosen on the same eval set it is measured on, so Phase 7 should confirm it on fresh
sentences.

**40. The stress gate is measured in English, and typed Roman Urdu is reported beside it.**

- Retrieval will receive decompose's English intents, not the typed sentence (invariant 11).
- Until Phase 5 exists, each Roman Urdu sentence's English gloss stands in for its intents.
- In English the stress index reaches 94.9%. Typed Roman Urdu reaches 75.0%, which shows how much depends on that translation.
- Phase 5 must re-run the eval with real Haiku intents in place of the glosses.

**41. Siblings join straight after the capability that brought them, and a group is never split.**
When a whole group does not fit under the cap, retrieval stops there, so a lower-ranked capability
never takes a higher-ranked group's place. Expansion goes one step: siblings are declared both ways, so
one step covers a cluster.

**42. The eval has two indexes.** With 8 capabilities, recall@30 is 100% whatever retrieval does. The
stress index adds the other 481 planning-contract intents' one-line summaries as distractors, so the
labelled capabilities compete with about as many as the full product will index. A check confirmed
the distractors' shorter style is not what makes it hard: with contract summaries in place of our
descriptions, dense recall was about the same.

**43. Model calls use the Claude Code CLI headless, never `--bare`.**

- `--bare` accepts only an API key, not the subscription's sign-in.
- Instead, each call turns off tools, MCP servers and settings files, keeps no session, and runs in an empty temporary directory.
- The sentence goes in on stdin, and nothing the CLI prints on stderr is repeated in errors or logs.

**44. Decompose lists the names in each intent, and they are checked.**

- A name must appear in the sentence as written, and unchanged in its intent (ignoring case and spacing).
- An intent still containing common Urdu function words (ke, ko, hai, karo, …) outside a name is refused as not English.
- There is no retry: a broken answer is an error, and Phase 6 decides what to tell the user.

**45. The planner never writes words the user sees.** It answers with a plan, `needs_input` (which
parameters are missing) or a refusal code. Phase 6 builds any question from the parameter's `meaning`,
and the backend still writes every confirmation and reply (invariant 3).

**46. The validator is stricter than the plan asked.**

- Beyond "exists" and "allowed", an id must be one of the candidates the planner was shown.
- A looked-up parameter must quote the user's words.
- An amount must be a number the user wrote, so "paanch hazaar" (five thousand, in words) is refused rather than trusted.
- Versions are stamped by the AI layer from the metadata.

**47. The planner is given today's date** in the school's time zone (`Asia/Karachi`, in
`domain/school`), so "aaj" and "Friday" become real dates. The model writes the date, the validator
checks it is a real date, and the user sees it in the backend's confirmation.

**48. Retrieval searches with the intents only, not the typed sentence.** With real intents it
reaches 96.0–96.6% on the stress index, better than the Phase 4 stand-in, so adding the typed sentence is
not needed now (decision 40).

**49. Real model calls stay out of `make ai-test`.** They use the subscription and take time.
`make ai-model-checks`, `make ai-eval` and `make verify-phase5` run them. The eval replays Haiku's
answers from `eval/recordings/` (Phase 7), so a changed prompt or glossary must be re-recorded.

**50. There is no temperature.** The CLI has no sampling setting and Sonnet 5 rejects one. Consistency
comes from the schema, the validator and, in Phase 6, the plan cache. The live checks use sentences
a correct planner gets right every time. Phase 7 measures how often it doesn't.

**51. "Needs input" carries the step so far.** The planner's answer includes the parameters it could fill,
checked like any step. The user's answers complete that same step, so nothing is planned again
(the plan's "never re-plan after the user answers").

**52. Answers are read by plain code.**

- Yes and no are read from a short list of English and Roman Urdu words.
- An option is picked by its number, its name or its id.
- A value is read by its type: one number, a certain date ("today", "aaj", "yesterday", or a written date), one of the allowed values, or the text itself.
- "kal" means both yesterday and tomorrow, so it is asked again.

**53. A plan that changes nothing runs without a confirmation.** Preflight says so
(`requires_confirmation: false`), and the user gets the answer in one turn.

**54. What each preflight refusal becomes:**

- `AMBIGUOUS_ENTITY` becomes a choice.
- `NOT_FOUND` asks for the name again, up to 3 tries.
- `PRECONDITION_FAILED` is refused with the backend's hint.
- Everything else is refused, with fixed wording for permission, a version change or an invalid plan.

**55. An expired confirmation, or a count that moved, is confirmed again from the same plan.** Execute
replays any step that already succeeded under the same session, plan and step, so confirming again
never runs a step twice.

**56. The plan cache holds refusals and "needs input" too.** They are planning outcomes, not results.

- A failed model call is never cached.
- A plan the backend calls stale or invalid is forgotten.
- Today's date is in the key, because the planner writes "aaj" as a date.

**57. Sessions are in memory and belong to one user.**

- The user comes from `GET /agent/session/capabilities`, which is also the allow-list, fetched every turn so a permission change applies straight away.
- Another user's session id simply starts a fresh session.
- A restart forgets all sessions.

**58. A new sentence while something waits replaces it.** A confirmation or question that is not answered is
dropped, and nothing from it ran. "no" and "nahi" cancel explicitly.

**59. Chat's own wording is English and fixed** ("Cancelled. Nothing was changed.", "Which one do you
mean?"). Everything with a number or a name in it comes from the backend.

**60. A lookup phrase may rearrange the user's words, but never add a name or a number.**

- The planner builds lookup phrases such as "Zain's September bill" out of "Zain … got a September bill".
- The validator used to require the phrase word for word, and refused 8 correct plans in the measurement.
- Now every word must be one the user wrote, except connecting words ("of", "ka", "'s") and a few generic record nouns from `domain/school` ("fee", "bill", "invoice").
- Names and numbers still cannot be invented: the injected "record name the user never wrote" fault is caught every time.
- This was changed after measuring, on the same sentences, so new sentences should confirm it.

**61. Model answers are recorded, and CI replays them.**

- A model is not deterministic, and CI has no Claude subscription. The measurement therefore replays answers recorded before validation, keyed by the exact prompt, and checks them again.
- A planner answer is recorded per sentence, not per candidate list. If retrieval changes, the recorded answer is checked against the new candidates, so a capability that is no longer retrieved shows up as a failure.
- Changing a prompt or the glossary means re-recording with `make measure`.

**62. The refusal set comes from the contracts, like the labelled set.**

- It has one sentence for every fee intent the POC does not publish (the hardest to refuse), and one from each other module.
- One contract label was left out as debatable: "43 defaulters kaun hain?" goes to the dashboard's drill-through, but `fee.overdue.list` lists exactly those families.
- "Acted" means planned, or asked for a value. An answer the validator refused counts as not acting, because the user gets a refusal.

**63. The baseline is a floor, not a target.**

- `make measure-ci` fails when a number falls below the baseline by more than its tolerance: 2 points for recall, 3 for plan accuracy and refusal correctness, 0 for the catch rate.
- Replayed answers give the same numbers every run. The tolerance is room for a fresh recording.
- `EVAL_UPDATE_BASELINE=1 make measure` accepts new numbers on purpose.

**64. The chat test page is served by the AI layer, from one static file.** Being on the same
address as `/chat` avoids any cross-origin setup, and the page needs no build step or framework.

- It is a developer tool, not the product's interface, and can be turned off.
- Replies are inserted as text, never as markup.
- The token stays in the browser tab.

**68. "Which one?" offers only records the action could use.** Before, a name matching several records
offered all of them. "Maryam Javed" for a payment offered her August invoice, already paid in full, beside
September. Now, in preflight's resolve pass (agent-gateway, so every capability gets it):

- Each match is tried against the step's preconditions, in declared order, as if it had been chosen. Only
  the matches that get furthest are offered. The paid invoice fails `invoice_is_open`, so it is dropped.
- One match left needs no question. The confirmation names it ("…against Maryam Javed's September 2026
  invoice"), so the user still sees which record before anything runs. If it breaks a later rule, the
  refusal gives that rule's hint instead. PKR 2,000 against a PKR 500 balance answers "That is more than is
  outstanding on this invoice" rather than asking which invoice.
- Several still equally good are a real question. Both of Omar Farooq's invoices are open, so it asks.
- A read has no preconditions, so "Ahmed" for the overdue list still offers both Ahmeds.
- It needs the step's other values, so it applies only when the step has one name still unresolved and
  nothing waits on an earlier step. Otherwise every match is offered, as before.
- Execute is unchanged: it finds the confirmed record again and checks every rule.

Two small fixes came with it. "blue section" now finds the Blue sections, because the section lookup ignores
the word "section". A name not found is asked again without the backend's developer text ("Step 1: no
section matches …").

**67. Decompose runs without extended thinking.** The Claude CLI lets the model think before answering by
default. For decompose, that hidden thinking was up to 3,571 tokens for a one-line intent, and the call
took anywhere from 4 to 40 s. Without it (`ModelRequest.thinking=False`, which sets `MAX_THINKING_TOKENS=0`
for that call) Haiku writes about 130 tokens in 2-3 s at the API, so a new sentence takes about 10 s in all.

- Only decompose changed. The planner keeps thinking, because its choices are what plan accuracy measures.
- Decompose was re-recorded with the real model. Recall@30 went from 96.6% to 95.4%: two Roman Urdu
  sentences were phrased differently ("Return the money…" instead of "Refund the money…"). Asking the same
  two sentences five more times each, with thinking on and off, gave the same mix of phrasings, so this is
  Haiku's normal variation, not a loss from turning thinking off. The new numbers are the baseline.
- A recording made without thinking says so (`"thinking": false`), and replay refuses to mix the two.

**65. CI lives at the repository root and watches `self_aware/` only.** The repository also holds the use
cases and planning contracts, which do not affect these jobs. A push that changes only those documents
does not start them.

**66. The pipeline view is a trace each stage records about itself.** It is not a second copy of the
pipeline written for the page.

- `app/core/trace.py` keeps a list of stages for one request in a context variable. Decompose, retrieval,
  planning, validation, preflight and execute each wrap their own work in `trace.stage(...)`. The trace
  therefore shows what really ran, in order, with real timings, and a new stage appears by adding one block.
- With no trace active, a stage keeps nothing. The eval runs and the tests are unchanged.
- A request asks for it (`"trace": true`), and a setting can refuse it. Traces hold the sentence, prompts
  and the backend's data, so they go back only to the caller of that turn and are never logged. The two
  secrets, the user's token and the preflight token, are never put in.

## Open questions

- **Lists of names.** The registry refuses a parameter that looks up a list of names, and no POC
  capability needs one. The real reminder contract's `student_ids` will, so the gateway will need it
  before that capability moves to the real backend.
- **A record taken from an earlier step has no label.** When step 2 takes an id published by step 1,
  nothing looked that record up by name, so its label is unknown. Its reply template must use the id
  or a fact instead. Resolvers finding a record by id would fix this, when a real pair of steps needs it.
- **One precondition id, two parameter names.** `amount_within_balance` is shared by the payment
  (`amount_received`) and the write-off (`amount`). The check reads either name for now. If more
  checks are shared like this, a check may need to know which capability it runs for.
- **The audit trail keeps names.** Sentences like "Ahmed Raza's September invoice" are stored as typed.
  Before real student data, this needs a retention rule, and a decision on who may read `agent_audit`.
- **Service credential for the AI layer.** `/agent/metadata` and `/versions` still need no credential;
  preflight and execute need the user's.
- **The dashboard is the weakest capability to find** (75% recall@30 with real intents). The contract
  routes "kitne students enrolled hain?", "subah ke numbers do" and "how many card requests are
  pending" to the dashboard, but its description only names fee figures. Either the description
  should name the other tiles, or those sentences belong to other capabilities.
- **The glossary shares words with the eval** (`baqaya`, `jurmana`, `wasooli`, `naqad`, `raseed`).
  They are genuine office words, but that makes the eval a little kinder than new sentences would be.
  Phase 7 should add sentences from real staff.
- **Speed.** A new sentence takes about 10 seconds end to end through the CLI (it was 15-60 s before
  decompose stopped thinking, decision 67): a new process for each of the two calls, plus retrieval. Answers, choices and confirmations take well under a second, because
  they make no model call. The plan cache makes a repeated sentence fast; the SDK would remove the
  process start.
- **Questions read awkwardly.** A missing value is asked from the parameter's `meaning`, which was written
  for the planner ("I need one more detail. Exactly the amount received, never rounded and never assumed
  to be the balance (an amount)?"). A short `question` on `@AgentParam`, written by the backend, would read
  better.
- **English replies to Roman Urdu.** The backend's confirmations and replies, and chat's own wording,
  are English. Staff who type Roman Urdu may want Urdu replies. That is a backend formatter decision, not
  a model one.
- **Sessions do not survive a restart**, and do not work across several AI layer processes. Redis goes
  behind `SessionStore` when that matters.
- **Redaction is still out of scope** (as the plan says). Before real student data, names in the sentence
  must be replaced with placeholders before any model sees it.
- **Free text written by the model.** A reason or description (for example a cancellation's
  description) is the planner's faithful English, not the user's own Roman Urdu. The approver reads
  the model's words, while the audit trail keeps the sentence.
- **Dense search is the weak branch among many capabilities.** Alone, among distractors, it finds
  81% of English sentences and 35% of typed Roman Urdu, against 95% and 78% for lexical. Fusion still
  helps, but if the full index stays like this, BGE-M3's sparse output or an English query instruction
  may be worth measuring. Nothing in the POC depends on it yet.
- **About half the eval set was written for this eval**, by Claude.
- **The reminder only reaches a whole section.** The contracts also route "is family ko fees ka reminder
  karo" and "message the over-90-day defaulters" to `fee.reminder.send`, and the planner rightly refuses
  both today. The real capability's `student_ids` (see "Lists of names") would cover them.
- **CI has not run on GitHub yet.** The workflow is at the repository root
  (`.github/workflows/ai-layer.yml`) and runs only when `self_aware/` changes. All three jobs pass in a clean
  clone on this Mac (backend, AI layer, and `measure`, which took 7.5 minutes with nothing cached), but
  a GitHub runner is Linux. Its `measure` job downloads the 2.3 GB model once and caches it. The 75 contract sentences score a little
  lower than the 100 written here (see the report). Phase 7 should add sentences from real staff.
- **The other two docs still use old wording.** `docs/POC_Implementation_Plan.md` and
  `docs/Preflight_Implementation.md` still show snake_case example ids and "temperature 0". The
  preflight doc also shows `OUT_OF_SCOPE` coming from resolution (see decision 19). Only CLAUDE.md
  was updated.
