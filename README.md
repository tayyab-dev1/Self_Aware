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

Everything else about this POC is in this file:

| Section | What it answers |
| --- | --- |
| [Who does what](#who-does-what) | Which part does what, and why it is split that way |
| [Status](#status) | What each phase delivers, and what it proves |
| [Quick start](#quick-start) | How to run it, try it and check it |
| [Where everything lives](#where-everything-lives) | What every folder and file is for |
| [Decisions so far](#decisions-so-far) | Every choice made, and why |
| [Open questions](#open-questions) | What is still undecided |

---

## Who does what

The AI layer, agent-gateway and school-app, in plain words. The rules behind this split are in
[docs/CLAUDE.md](docs/CLAUDE.md).

### The picture

```
Staff member types: "class 5 blue ke defaulters ko whatsapp par reminder bhejo"
        │
        ▼
┌───────────────────────────────┐
│ AI LAYER          ai-layer/   │  understands the words, talks to the user
└───────────────┬───────────────┘
                │  HTTP, /agent/** only. The plan names a capability id, never a URL.
┌───────────────▼───────────────┐  ┐
│ AGENT GATEWAY  agent-gateway/ │  │  makes any action safe, for any product
├───────────────────────────────┤  │  one Spring Boot backend
│ SCHOOL APP     school-app/    │  │  knows the school: data, rules, actions
└───────────────┬───────────────┘  ┘
                ▼
     Postgres (school data) + the AI layer's own capability index
```

The backend is **one application built from two parts**. `agent-gateway` is a library, and
`school-app` is the program that includes it. The AI layer is a separate service.

### Each part's one job

| Part | Its job | Knows about schools? | Reads business data? |
| --- | --- | --- | --- |
| **AI layer** | Turn a sentence into a plan, and talk to the user | Only school words (a glossary) | **Never.** Only its own capability index |
| **agent-gateway** | The safety process every action goes through, the same for every action | **No** | No: it asks school-app |
| **school-app** | The school's tables, rules and actions | Yes | Yes |

### What lives where

| Piece of the architecture | Lives in | Phase |
| --- | --- | --- |
| Splitting the sentence into intents, in English (a model call) | AI layer | 5 |
| School words such as challan, haazri, baqaya | AI layer (`domain/school`) | 5 |
| Keeping its own index of capability descriptions in step with the backend (polling versions) | AI layer (`app/sync`) | 4 |
| Finding the likely capabilities, each with its look-alikes, only among those the user may use | AI layer (`app/retrieval`) | 4 |
| Measuring whether it finds the right one (the eval set) | AI layer (`eval/`) | 4 |
| The four numbers (recall, plan accuracy, refusals, validator catch rate) and the CI regression run | AI layer (`eval/measure`, recorded model answers) | 7 |
| Choosing the capability and filling its inputs (a model call) | AI layer | 5 |
| Checking everything the model wrote before using it | AI layer (`app/validation`) | 5 |
| Calling the model (Gemini 3.1 Flash-Lite, through the Gemini API), behind one interface | AI layer (`app/llm`) | 5, then decision 69 |
| The conversation: asking questions, showing the confirmation, the plan cache | AI layer (`app/orchestration`, `POST /chat`) | 6 |
| The test page and its pipeline view (each stage's input, output and timing) | AI layer (`app/web`, `app/core/trace.py`) | after 7 |
| **Declaring** a capability: description, inputs, preconditions, templates | school-app, as annotations on the controller | 1 |
| **Reading** those declarations, checking them at startup, versioning them | agent-gateway | 1 |
| Publishing them (`GET /agent/metadata`) | agent-gateway | 1 |
| Sign-in, and who may use which capability | school-app decides; agent-gateway asks | 1 |
| The preflight process: pass order, error codes, filling templates, signing the token | agent-gateway | 2 |
| Saying a capability is declared but not built yet, e.g. the four fee corrections | school-app marks it; agent-gateway refuses it with `NOT_IMPLEMENTED` | 2 |
| Turning "class 5 blue" into one record, searching only what the user may see | school-app (resolvers) | 2 |
| Precondition rules, e.g. "the section has defaulters" | school-app | 2 |
| Counting what a write will touch, with the same query the action uses | school-app | 2 |
| How values read in a confirmation, e.g. `PKR 71,500`, `WhatsApp` | school-app (a formatter); agent-gateway has a plain default | 2 |
| Execute: re-check everything in one transaction, verify the result, audit events, no double runs | agent-gateway | 3 |
| The audit table, append-only | school-app stores it; agent-gateway writes to it through an interface | 3 |
| The action itself, e.g. logging the reminders or recording a payment | school-app | 3 |
| Tables, migrations, demo data | school-app | 0 |

**A simple test for new code:**

- **Is it about understanding language or talking to the user?** It goes in the AI layer.
- **Would it be exactly the same for a hospital or a shop?** It goes in agent-gateway.
- **Does it mention students, fees, sections or invoices?** It goes in school-app. Only school *words* the model needs go in `ai-layer/domain/school`.

### Why this split is better

1. **The model can only suggest.** It never touches data and never writes a number the user
   sees. Every step that changes something is ordinary code that checks again. When the model is
   wrong, the worst outcome is a refusal, or a confirmation the user rejects.
2. **Safety is written once.** The gateway runs the same checks for every capability. A new
   capability is annotations plus a few small beans, and it cannot skip preflight. If the
   declarations are inconsistent, the backend refuses to start.
3. **The engine is reusable.** The gateway contains no school words. Its own tests use a made-up
   "notes" app. The real school backend will take it as a dependency, the same way school-app
   does, and nothing in the gateway or the AI layer changes.
4. **Each part changes at its own pace.** Tuning prompts and retrieval is Python work in the AI
   layer. Business rules are Java work in school-app. Neither redeploys the other.
5. **Numbers cannot drift.** A count and its action live in the same place and share the same query,
   so the confirmation cannot say 7 when the send reaches 5.
6. **The boundary is enforced by the database.** The AI layer logs in to Postgres as a role that has
   no permission on school data at all.
7. **Each part can be tested alone.** The gateway is tested with fake capabilities, school-app
   with real SQL, and the AI layer with an evaluation set.

### One sentence, start to finish

1. **AI layer:** reads "class 5 blue ke defaulters ko whatsapp par reminder bhejo".
   - Decompose (a model call) rewrites it in English: "Send a WhatsApp reminder to the defaulters in class 5 blue", keeping "class 5 blue" exactly as typed.
   - Retrieval finds the likely capabilities in its own index. `fee.reminder.send` comes with its look-alike `fee.overdue.list`, so the planner has to choose between them.
   - The planner (a second model call) picks `fee.reminder.send` with section "class 5 blue" and channel whatsapp.
   - Plain code checks the plan: the id is real and allowed, and "class 5 blue" really is the user's words. Only then does the plan go to the backend.
2. **agent-gateway:** checks that the plan fits the capability, that its version is current, and
   that the user may use it. It then runs the four preflight passes, asking school-app for each
   piece.
3. **school-app:**
   - finds Class 5 Blue, looking only inside the user's campus;
   - confirms that the section has overdue fees;
   - counts the guardians who can be reached on WhatsApp: 5 of them, owing PKR 71,500.
4. **agent-gateway:** fills the template, adds "This cannot be undone.", and signs a token that
   holds the plan's fingerprint, the section id and the count.
5. **AI layer:** shows the user
   *"Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp, covering PKR 71,500
   outstanding. This cannot be undone."*
6. **On "yes":** the AI layer sends the same plan back with the token.
   - The gateway checks everything again inside one transaction: the count is still 5, and nobody left the section.
   - It records that the step started, and school-app logs the 5 reminders.
   - The gateway checks that 5 were logged, records success, and replies *"Sent a fee reminder to 5 guardians in
     Class 5 Blue by WhatsApp."*
   - Sending the same plan again only replays that answer.

### When the real school backend exists

`school-app` is a stand-in: it exists so the POC has real data to work against. The real backend
adds `agent-gateway` as a dependency and moves in the annotations, resolvers, checks and counts.
Then `school-app` is deleted. The AI layer keeps calling the same `/agent/**` endpoints and does
not notice.

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

### Since the POC: the models moved to Gemini (21 Sep 2026)

Both model calls now go to the Gemini API, to `gemini-3.1-flash-lite`, instead of Haiku 4.5 and
Sonnet 5 through the Claude CLI (decision 69). The phase write-ups below are kept as they were: they
describe what was delivered and measured **with Claude**.

- **Gemini's four numbers** (recorded 21 Sep 2026, with each lookup's description shown to the
  planner, decision 71, and decompose's rules for stated problems, decision 73). The Phase 7 block
  below keeps Claude's for comparison.

  ```
                        all             English         Roman Urdu
  recall@30              95.4%           98.4%           93.8%
  plan accuracy          89.7%           92.1%           88.4%
  refusal correctness    92.2%           94.9%           90.5%
  validator catch rate  100.0%          100.0%          100.0%
  out of: 175 labelled sentences, 70 to refuse, 578 injected faults
  ```

  Recorded after decision 76. The run before it read 96.6 / 90.3 / 91.8, and before decision 73,
  95.4 / 90.3 / 92.2. Decompose rewords a sentence differently on nearly every run (decision 76), and
  retrieval follows the wording, so one or two sentences move between runs: treat a point either way as
  noise, not a change.

  Against Claude: recall is the same, even though only 28 of 245 decompose answers match word for word.
  Plan accuracy is 1.7 points higher (Roman Urdu +3.6, English -1.6). Refusal correctness is 0.4 lower:
  English fell 3.1 points, because Gemini acted on two sentences asking to send a pending approval back
  ("return this credit…"), planning a credit or cancellation instead. Both of those are not built, so
  the backend refuses them anyway.
- **The recordings and baseline are Gemini's.** `ai-layer/eval/recordings/` and
  `eval/measure/baseline.json` hold Gemini's answers and numbers, so `make measure-ci` and the CI
  `measure` job replay them again. Recording took 7 runs of `make measure`: Gemini often answered 503
  "high demand" under 6 parallel calls, and each run records only the answers still missing.
- **Needs a key.** Set `AI_LAYER_GEMINI_API_KEY` in `.env`. Without it, chat is off and readiness
  says why. The Claude desktop app is no longer needed.

### Jev chooses the capability before the planner (branch `jev-chooser`, on since 22 Sep 2026)

TypeSafe's Jev, a System One model that picks from given options with a probability for each and
writes no text, chooses which candidates the planner sees (decision 72). It is on by default
when a TypeSafe key is set (`AI_LAYER_CHOOSER_ENABLED`, decision 75). `make measure-jev` plans every eval sentence both ways from recorded
answers and writes `ai-layer/eval/reports/measure_with_jev.md`.

```
                      all                       English                   Roman Urdu
recall@30             95.4% -> 95.4% (+0.0)     98.4% -> 98.4% (+0.0)     93.8% -> 93.8% (+0.0)
plan accuracy         89.7% -> 89.7% (+0.0)     92.1% -> 93.7% (+1.6)     88.4% -> 87.5% (-0.9)
refusal correctness   92.2% -> 92.2% (+0.0)     94.9% -> 94.9% (+0.0)     90.5% -> 90.5% (+0.0)
validator catch rate  100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)   100.0% -> 100.0% (+0.0)
```

The gap closed as decompose improved: -2.9 points overall before decision 73, -1.1 after it, none after
decision 76. Jev now costs nothing measurable and still saves about 1.5-2 s a turn.

- **Jev on its own** puts the expected capability in its shortlist for 94.8% of labelled sentences
  (155 of 172 get a shortlist of one), and answers "none" for 88.4% of the sentences to refuse. Its
  confidence is honest: picks at 0.9 or above are right about 95% of the time.
- **Faster planning.** With only the shortlist, the planner's prompt is a quarter of the size and
  its call took a median of 2.7 s instead of 4.9 s (16 sentences, live). Jev itself takes a median of
  0.41 s, so a sentence is about 1.8 s faster. With 30 candidates in production the gap would be wider.
- **Where it loses.** At first, every lost sentence was one Jev answered "none" because decompose's
  English intent was wrong ("credit raise karo" became "Increase the credit limit"). Decision 73
  fixed most of those. What is left is small and mixed: Jev still says "none" for a few payment
  sentences ("counter pe paisay jama hue hain"), and it now refuses three sentences the planner alone
  wrongly acted on. Giving Jev the original message as well did not help.
- **On by default since 22 Sep 2026** (decision 75), and since decision 76 it costs no plan accuracy
  at all while saving about 1.5-2 s per sentence.

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
  - backend: 157 (100 gateway unit, 8 school-app unit, 49 integration); 166 since the "which one?" filter, names with a class and section, and published lookups (decisions 68, 70 and 71: 104 gateway unit, 8 school-app unit, 54 integration);
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

Phases 5 and 6 also need a Gemini API key: set `AI_LAYER_GEMINI_API_KEY` in `.env` (a key from
[Google AI Studio](https://aistudio.google.com/apikey)). Both model calls go to `gemini-3.1-flash-lite`.

```bash
make verify-phase6  # terminal 3: checks every Phase 6 "done when" item through POST /chat
```

```bash
make verify-phase7  # the four numbers from the recorded answers, and the broken-description check
```

`make verify-phase7` needs only Docker and the embeddings service. It runs the CI regression run
(`make measure-ci`) with no model API key, and should end with
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
   | Decompose | Gemini | the prompt, and the English intents with the names it copied |
   | Check the intents | AI layer code | the intents, once every name and the English are checked |
   | Embed the intents | BGE-M3 | the vector size and its first numbers |
   | Search the capability index | Postgres | per intent, the ranking by meaning and by words |
   | Fuse, add siblings, cap | AI layer code | the fused scores and ranks, and the candidates the planner sees |
   | Plan | Gemini | the message, intents, today and candidates it saw, and its raw answer |
   | Validate the plan | AI layer code | the checked plan with versions stamped, or the broken rules |
   | Read the answer | AI layer code | how an option or typed value was put into the waiting plan |
   | Preflight | backend | the names it looked up, the count, and the confirmation (the token is hidden) |
   | Execute | backend | each step's status, count and data |
   | Reply | AI layer code | the reply, written without a model |

   Only the stages a turn needs appear: a "yes" shows no model call. A failed stage is red and says why,
   for example a used-up Gemini quota or a rule the planner broke. Colours tell apart the backend,
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

**Every reply says "Sorry, I can't understand requests right now"?** The Gemini API could not be used.
The failed **Decompose** stage in the pipeline says why, and so does the AI layer's log
(`planning_unavailable`). The usual causes:

- **No key.** Readiness says `POST /chat is off: No Gemini API key`. Set `AI_LAYER_GEMINI_API_KEY` in
  `.env` (a key from [Google AI Studio](https://aistudio.google.com/apikey)) and restart the AI layer.
- **A key Google refused** (`400 INVALID_ARGUMENT` or `403 PERMISSION_DENIED`). Check the key, and
  that the Gemini API is enabled for its project.
- **A used-up quota** (`429 RESOURCE_EXHAUSTED`). Limits are per project, not per key; the daily
  limit resets at midnight Pacific time. See your limits in
  [AI Studio](https://aistudio.google.com/rate-limit).

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

`make verify-phase5` makes about a dozen model calls; its eval step replays the recorded decompose
answers in `ai-layer/eval/recordings/`. Expected output:

```
Phase 5: Decompose and plan

The whole pipeline on the running stack: decompose → retrieve → plan (both Gemini) → validate → preflight
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
  ✓ real models: Roman Urdu plan, refusal, two steps in order (make ai-model-checks)

Retrieval with real intents (the Phase 4 follow-up)
  ✓ recall@30 > 90% on the 489-capability stress index when searching with decompose's intents; clusters whole (make ai-eval)

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
| `make decompose-repeat` | Decompose every sentence in `request_counts.jsonl` 3 times: the right number of intents every time? Records missing runs |
| `make measure-jev` | The chooser experiment: every eval sentence planned without and with Jev choosing first; records any missing answer (needs `AI_LAYER_TYPESAFE_API_KEY` for those) |
| `make measure-ci` | The regression run: every eval from the recorded answers, no model called; fails below the baseline (what CI runs) |
| `make ai-model-checks` | The Phase 5 checks against the real models (Gemini API; needs `AI_LAYER_GEMINI_API_KEY`) |
| `make plan Q="..."` | Every stage for a sentence: intents, candidates, the checked plan, and preflight's answer (nothing runs) |
| `make ai-build-checks` | The description-similarity build check |
| `make descriptions-report` | How similar every pair of capability descriptions is |
| `make ai-eval` | The retrieval eval: recall gates, clusters, Roman Urdu, and retrieval with decompose's intents; writes `ai-layer/eval/reports/` (needs Docker, `embeddings-up`, and a Gemini API key for any answer not yet recorded) |
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
  - `AI_LAYER_GEMINI_API_KEY` is the Gemini API key. Empty turns chat off, and readiness says why.
    A free-tier key's requests may be used by Google to improve its products; a paid one's are not.
  - `AI_LAYER_MODEL_TIMEOUT_SECONDS` (default 120) limits one model call, the SDK's retries of
    408, 429 and 5xx included (at most 3 attempts).
  - `AI_LAYER_PLAN_MAX_STEPS` (default 3, and never more) caps a plan.
  - `AI_LAYER_CHOOSER_ENABLED` (default true) puts Jev before the planner (decisions 72 and 75). It needs
    `AI_LAYER_TYPESAFE_API_KEY` (from console.typesafe.ai); without one the chooser stays off and
    chat plans as before. `AI_LAYER_CHOOSER_TIMEOUT_SECONDS` (default 15) limits one call, retries included.
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

## Where everything lives

What each file is for, in plain words. Update this section in the same change that adds, moves or
removes a file.

**Last updated:** 21 Sep 2026, the move to Gemini (decision 69), after Phase 7 and decisions 64-68.

### The big picture

The git repository is the folder above `self_aware/`, which also holds the use cases (`modules/`) and
the planning contracts. CI for the AI layer is at the repository root:
`.github/workflows/ai-layer.yml` (backend tests, AI layer tests, and the measure regression run). It
runs only when `self_aware/` changes.

```
self_aware/
├── README.md              this file: what this is, who does what, how to run it, status,
│                          where every file lives, and the decisions behind it
├── Makefile               short commands for everything (make help)
├── .env.example           every setting with a safe local default; copy to .env
├── .gitignore
│
├── docs/                  the specification (read before changing code)
├── docker/                Postgres 16 + pgvector, and the BGE-M3 embeddings service
├── openapi/               the API contract between the two services (generated)
├── snapshots/             the capability metadata the backend serves (generated)
├── scripts/               checks that run against the whole stack
│
├── backend/               Java · Spring Boot · port 8080
│   ├── agent-gateway/     generic: the only door the AI layer uses into the backend
│   └── school-app/        POC stand-in for the real school backend: data, capabilities, seed
│
└── ai-layer/              Python · FastAPI · port 8081
    ├── app/               the service code
    ├── domain/school/     school words the models need (glossary, time zone)
    ├── migrations/        plain-SQL migrations for the capability index
    ├── scripts/           code generation, eval corpus extraction
    ├── tests/             unit, integration (Docker), build checks (embeddings), model checks (Gemini API)
    └── eval/              labelled sentences, recorded model answers, the retrieval eval and the four numbers
```


#### The one rule that keeps it modular

Dependencies point one way only, from the specific to the general:

```
backend:   school-app ──depends on──▶ agent-gateway        (never the reverse)
ai-layer:  main / resources ──▶ api ──▶ gateway, index, embeddings, capabilities ──▶ core
across:    ai-layer ──HTTP──▶ /agent/** only               (never a business endpoint)
           ai-layer ──SQL───▶ schema ai_layer only         (never schema school)
```

The general parts (`agent-gateway`, and everything in the AI layer engine) know nothing about
schools. The gateway's own tests use a made-up "notes" domain to keep it that way. School knowledge
lives in `school-app`, and later in `ai-layer/domain/school/`. To point the engine at another
product, you replace those edges and leave the engine alone. [Who does what](#who-does-what)
explains the split, and why.

---


### `docs/` — the specification


| File                          | What it has                                                                                                                                                                                               |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CLAUDE.md`                   | The rules: architecture, the twelve invariants, the repository layout (matches this file), capability ids, the plan contract, the model calls, embeddings, build-time assertions, error codes, conventions |
| `POC_Implementation_Plan.md`  | The seven phases and each one's "done when"                                                                                                                                                               |
| `Preflight_Implementation.md` | The design preflight and execute were built from (Phases 2 and 3). Where the code differs, README decisions 17–36 say so                                                                                  |


### `docker/` — local infrastructure


| File                                             | What it has                                                                                                                                                                                                                                                                                             |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `docker-compose.yml`                             | Two services. `postgres`: Postgres 16 + pgvector on 127.0.0.1:5433. `embeddings` (optional profile): BGE-M3 at a pinned revision, served by Text Embeddings Inference on 127.0.0.1:8083, its batch capped at 2048 tokens so it fits in Docker's memory. Each has a named volume; the model's is ~2.3 GB |
| `postgres/initdb/01-roles-schemas-extensions.sh` | Runs once, when the database volume is first created. Installs `vector`, creates the two login roles, gives each its own schema, and gives the AI layer role nothing on business data. The integration tests on both sides mount this same file                                                         |


### `openapi/` and `snapshots/` — generated, checked on both sides


| File                            | What it has                                                                                                                                                                                                                       |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `openapi/agent-gateway.json`    | **Generated. Do not edit.** The OpenAPI description of `/agent/`**: request and response shapes, and for preflight and execute every error status with the codes it carries. The AI layer's Pydantic models are generated from it |
| `snapshots/agent-metadata.json` | **Generated. Do not edit.** Exactly what `GET /agent/metadata` serves: every capability with its version. The AI layer's build checks and tests read it without a running backend                                                 |


Both are rewritten by `make contracts`. The backend's `OpenApiContractIT` and `AgentMetadataSnapshotIT` fail when either is stale.

### `scripts/` — whole-stack checks

A check that cannot run counts as a failure in every script.


| File               | What it has                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `verify_phase0.sh` | Checks every Phase 0 "done when" item against the running database, backend and AI layer (`make verify-phase0`)                                                                                                                                                                                                                                                                                                                            |
| `verify_phase1.sh` | Checks every Phase 1 "done when" item against the running backend and embeddings service. It recomputes versions independently and runs the tests that prove a broken rule fails the build (`make verify-phase1`)                                                                                                                                                                                                                          |
| `verify_phase2.sh` | Checks every Phase 2 "done when" item against the running backend: resolution, ambiguity, a failing precondition and the rendered confirmation. It counts guardians again with SQL, recomputes the token's plan hash in Python, calls preflight through the AI layer's client, and runs the token and scope tests (`make verify-phase2`)                                                                                                   |
| `verify_phase3.sh` | Checks every Phase 3 "done when" item against the running backend: a confirmed reminder runs, is verified and audited (counted with SQL), a replay runs nothing twice, a payment added with SQL makes a precondition fail, a WhatsApp number added with SQL makes the count change, and the audit trail refuses changes. It undoes what it changes, and runs the rollback and payment tests on a throwaway database (`make verify-phase3`) |
| `verify_phase4.sh` | Checks every Phase 4 "done when" item: the running AI layer's index matches the backend's versions, a row marked stale is repaired within one poll, a fee-correction sentence retrieves the whole cluster, the allow-list holds; then runs the retrieval eval and prints its recall table, plus the sync, fusion and index-query tests (`make verify-phase4`)                                                                              |
| `verify_phase5.sh` | Checks every Phase 5 "done when" item with the real models on the running stack: a Roman Urdu sentence becomes a plan with English intents and names untouched that preflight accepts, a sentence matching nothing is refused, a hallucinated id from a mocked planner is caught, a two-part sentence becomes two steps in order; then runs the validator tests, the model checks and the eval with real intents (`make verify-phase5`)    |
| `verify_phase7.sh` | Checks the Phase 7 "done when" items: `make measure-ci` prints the four numbers from the recordings with no model available, and a deliberately broken description shows up as a recall drop (`make verify-phase7`)                                                                                                                                                                                                                        |
| `verify_phase6.sh` | Checks every Phase 6 "done when" item: through the running `POST /chat`, a read answered in one turn and a write confirmed then run (both audited, reminders counted with SQL); in process with model calls counted, an ambiguous name asked and resumed with no second planner call, and a cancelled confirmation leaving no audit row (`make verify-phase6`)                                                                             |


---


### `backend/` — the Spring Boot backend


| File                                | What it has                                                                   |
| ----------------------------------- | ----------------------------------------------------------------------------- |
| `pom.xml`                           | Parent build: Spring Boot 3.5.16, Java 21, springdoc version, the two modules |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven wrapper: downloads Maven 3.9.16 on first use, so nobody installs Maven  |


#### `backend/agent-gateway/` — generic, reusable

The gateway knows capabilities, plans, checks and execution, never schools. It plugs into any Spring
Boot app through auto-configuration. `pom.xml` lists its few dependencies: web, validation API,
transactions, and springdoc as optional.


| File (under `src/main/java/com/diversive/agent/`)                                                                    | What it has                                                                                                                                                                                                                                                                                                                                              |
| -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `annotation/AgentCapability.java`                                                                                    | Marks a controller method as a capability: id, module, read-only or not, blast radius, what reverses it, description, siblings                                                                                                                                                                                                                           |
| `annotation/AgentParam.java`, `AgentParams.java`                                                                     | Describes one field of the request record for the planner: meaning, resolver and label, allowed values, default. `AgentParams` is the container Java needs to repeat it                                                                                                                                                                                  |
| `annotation/AgentPrecondition.java`, `AgentPreconditions.java`                                                       | Something that must hold before the capability runs: id, rule text, and the hint the user sees                                                                                                                                                                                                                                                           |
| `annotation/AgentEffect.java`                                                                                        | What running it creates and who hears about it, plus the confirmation, pending and reply templates and the extra facts they may use                                                                                                                                                                                                                      |
| `annotation/AgentNotImplemented.java`                                                                                | "Declared, but cannot run yet": preflight refuses it with `NOT_IMPLEMENTED`. Not published, so the planner cannot tell                                                                                                                                                                                                                                   |
| `annotation/BlastRadius.java`                                                                                        | How much a capability can change: NONE (reads only), SINGLE, GROUP, BRANCH, ORGANISATION                                                                                                                                                                                                                                                                 |
| `spi/PreconditionCheck.java`                                                                                         | What the host app implements for each precondition id                                                                                                                                                                                                                                                                                                    |
| `spi/AffectedCount.java`                                                                                             | What the host app implements to count what a wide write touches, plus `CountResult` (count, unit, extra facts)                                                                                                                                                                                                                                           |
| `spi/EntityResolver.java`, `EntityMatch.java`                                                                        | What the host app implements for each entity type: the user's words in, every matching record the user may see out, each with an id, a label and what tells it apart; and `lookup()`, what it searches by in plain words                                                                                                                                                                                     |
| `spi/TemplateFormatter.java`                                                                                         | How a value reads inside a confirmation (money, dates, names); the host may supply one                                                                                                                                                                                                                                                                   |
| `spi/UserContext.java`, `UserContextResolver.java`                                                                   | Who a request is for (id, roles, scope), and how the host app works it out from the request                                                                                                                                                                                                                                                              |
| `spi/CapabilityPolicy.java`                                                                                          | Which capabilities a user may use; the host app decides                                                                                                                                                                                                                                                                                                  |
| `spi/AuditTrail.java`, `AuditEvent.java`                                                                             | Where execute records what it did (the host owns the table): append an event; find a write that already succeeded under an idempotency key. An event holds the sentence, ids, labels, counts and error code, never a token                                                                                                                               |
| `registry/CapabilityScanner.java`                                                                                    | Reads the annotations into entries. Takes parameter names, types and whether they are required from the request record, notes where the request and the signed-in user go in the handler's arguments and what the handler returns, and reports every rule a single entry breaks                                                                          |
| `registry/RegistryRules.java`                                                                                        | The rules that need the whole registry or the app's beans: precondition, count and resolver beans, symmetric siblings, producible placeholders, confirmations without gaps, and a handler returning the facts and count execute reads. Bean rules skip capabilities that are not implemented                                                             |
| `registry/CapabilityVersioner.java`                                                                                  | Version = SHA-256 of the entry's canonical JSON (every field but the version, keys sorted)                                                                                                                                                                                                                                                               |
| `registry/CapabilityRegistryBuilder.java`                                                                            | Scan, apply every rule, add each resolver's lookup text to the parameters it finds, version. Throws with every problem listed at once                                                                                                                                                                                                                                                                                |
| `registry/CapabilityRegistry.java`                                                                                   | The built registry: every capability, sorted by id, immutable                                                                                                                                                                                                                                                                                            |
| `registry/RegisteredCapability.java`                                                                                 | One capability as the backend knows it: public metadata, plus the handler, its request record's fields, its argument positions, what it returns and whether it is implemented, which never leave the backend                                                                                                                                             |
| `registry/CapabilityRegistryException.java`                                                                          | "The registry is invalid", with the list of problems                                                                                                                                                                                                                                                                                                     |
| `registry/TemplatePlaceholders.java`                                                                                 | Finds `{placeholders}` in templates, spots malformed braces, and fills them (plain substitution)                                                                                                                                                                                                                                                         |
| `metadata/AgentMetadataController.java`                                                                              | `GET /agent/metadata` and `GET /agent/metadata/versions`                                                                                                                                                                                                                                                                                                 |
| `metadata/CapabilityMetadata.java`                                                                                   | One capability as the AI layer sees it: full detail, no URL                                                                                                                                                                                                                                                                                              |
| `metadata/ParamMetadata.java`, `ParamType.java`                                                                      | One input: name, type (string, integer, decimal, boolean, date), list or not, required, meaning, resolver, label, lookup (what the resolver searches by), allowed values, default                                                                                                                                                                                                                |
| `metadata/PreconditionMetadata.java`, `EffectMetadata.java`                                                          | A precondition and the effect, as published                                                                                                                                                                                                                                                                                                              |
| `metadata/AgentMetadataResponse.java`, `CapabilityVersionsResponse.java`, `CapabilityVersion.java`                   | The two response bodies                                                                                                                                                                                                                                                                                                                                  |
| `session/AgentSessionController.java`, `SessionCapabilitiesResponse.java`                                            | `GET /agent/session/capabilities`: the ids this user may use (the allow-list)                                                                                                                                                                                                                                                                            |
| `plan/Plan.java`, `PlanStep.java`                                                                                    | What the AI layer wants done: plan and session ids, and up to 3 steps, each a capability id, its version and its parameters                                                                                                                                                                                                                              |
| `plan/ParamValue.java`                                                                                               | One parameter, in one of three forms: a value, the user's words to look up (with the user's choice after an ambiguity), or a fact from an earlier step                                                                                                                                                                                                   |
| `plan/PlanHasher.java`                                                                                               | A plan's fingerprint: SHA-256 of its canonical JSON, recomputable anywhere                                                                                                                                                                                                                                                                               |
| `step/PlanReader.java`                                                                                               | Reads a plan against the registry before any data is touched: shape and plain ids, versions, permissions, implemented, parameters. Used by preflight and execute                                                                                                                                                                                         |
| `step/StepPasses.java`                                                                                               | The passes preflight and execute both run: resolve names (offering, for an ambiguous name, only the matches that get furthest through the step's preconditions; at execute, find the confirmed record again), validate the whole request, check preconditions, count, fill templates                                                                     |
| `step/ParamBinder.java`                                                                                              | Turns plan values into the request record's Java types with the app's Jackson, builds the whole record, and runs the record's validation constraints                                                                                                                                                                                                     |
| `step/PreparedStep.java`                                                                                             | One step as preflight or execute works through it: typed values, names to resolve, values from earlier steps, resolved records and labels, count, confirmation line                                                                                                                                                                                      |
| `step/StepRejections.java`                                                                                           | Every way a plan or a step is refused, as error bodies: invalid plan, stale version, not permitted, not implemented, not found, ambiguous (up to 10 candidates), precondition failed (with its hint), out of scope, count changed, conflict, execution and verification failed                                                                           |
| `step/CapabilityBeans.java`                                                                                          | The host's checks, counts and resolvers, by the id each claims                                                                                                                                                                                                                                                                                           |
| `step/PlainTemplateFormatter.java`                                                                                   | The default formatter: plain text, enums as their JSON value                                                                                                                                                                                                                                                                                             |
| `preflight/PreflightController.java`                                                                                 | `POST /agent/preflight`: signs the user in, tags log lines with plan and session ids, logs refusals by code only                                                                                                                                                                                                                                         |
| `preflight/PreflightService.java`                                                                                    | Preflight: read the plan, then resolve, validate, check, count and compose in one read-only snapshot; sign the token                                                                                                                                                                                                                                     |
| `preflight/ConfirmationComposer.java`                                                                                | Joins the lines into one message, numbered when there are several, and adds "cannot be undone" and branch-wide warnings                                                                                                                                                                                                                                  |
| `preflight/PreflightRequest.java`, `PreflightResponse.java`, `PreflightStepResult.java`, `ResolvedEntity.java`       | The request and response bodies: the confirmation, warnings, what each step resolved and counted, the token and its expiry                                                                                                                                                                                                                               |
| `preflight/PreflightProperties.java`                                                                                 | Settings under `agent.gateway.preflight`: token secret, token lifetime (5 minutes), most steps per plan (3)                                                                                                                                                                                                                                              |
| `execute/ExecuteController.java`                                                                                     | `POST /agent/execute`: signs the user in, tags log lines with plan and session ids, logs refusals by code only                                                                                                                                                                                                                                           |
| `execute/ExecuteService.java`                                                                                        | Execute: verify the token and read the plan; then each step in a serializable transaction of its own: replay a write that already succeeded, find the confirmed records again, validate, check, count with the delta rule, audit, run the handler, verify, reply. Records refusals and failures after rollback, retries conflicts, reports partial plans |
| `execute/CapabilityHandlers.java`                                                                                    | Calls a capability's handler: the registry's method, on the application's bean, with the request record and the signed-in user                                                                                                                                                                                                                           |
| `execute/ResponseReader.java`                                                                                        | Reads a handler's response by JSON name with its Java types, and turns values into text and JSON for the audit trail                                                                                                                                                                                                                                     |
| `execute/DeltaRule.java`                                                                                             | When a count moved too far from the confirmed one: any change at or below 20, more than 5% above                                                                                                                                                                                                                                                         |
| `execute/VerificationFailure.java`                                                                                   | "The handler did not do what it declares": a missing fact, or a different count; its step is rolled back                                                                                                                                                                                                                                                 |
| `execute/ExecuteRequest.java`, `ExecuteResponse.java`, `ExecutedStep.java`, `ExecuteOutcome.java`, `StepStatus.java` | The request (plan, token, sentence) and the response: completed, partial or failed, and per step succeeded, replayed, failed or not run, with its reply, count, data or error                                                                                                                                                                            |
| `execute/ExecuteProperties.java`                                                                                     | Settings under `agent.gateway.execute`: the delta rule's small count and tolerance, conflict retries, the longest sentence                                                                                                                                                                                                                               |
| `web/PlanLogContext.java`                                                                                            | Puts plan and session ids on every log line while a plan is handled, only when they are plain ids                                                                                                                                                                                                                                                        |
| `web/UserContextArgumentResolver.java`                                                                               | Lets a handler take the signed-in `UserContext` when it is called as a plain HTTP endpoint; 401 without one                                                                                                                                                                                                                                              |
| `token/PreflightTokens.java`                                                                                         | Signs and verifies tokens: `base64url(payload).HMAC-SHA256`. Verifying checks form, signature, expiry, user and plan hash, in that order                                                                                                                                                                                                                 |
| `token/PreflightToken.java`                                                                                          | What a token vouches for: plan hash, user, expiry, and per step the resolved ids and the count                                                                                                                                                                                                                                                           |
| `token/TokenVerificationException.java`                                                                              | Why a token was refused, as `TOKEN_EXPIRED` or `TOKEN_INVALID`                                                                                                                                                                                                                                                                                           |
| `error/AgentErrorResponse.java`, `EntityCandidate.java`                                                              | The error body: a `code` the AI layer branches on, a message, and where they apply the step, parameter, candidates, precondition, hint, and the confirmed and current counts                                                                                                                                                                             |
| `error/AgentErrorCodes.java`                                                                                         | Every error code, and the HTTP status each is sent with                                                                                                                                                                                                                                                                                                  |
| `error/AgentRejectionException.java`, `AgentUnauthenticatedException.java`                                           | "Refuse with this error body": any refusal, and the 401 for no valid credential                                                                                                                                                                                                                                                                          |
| `error/AgentGatewayExceptionHandler.java`                                                                            | Turns refusals into error bodies for every gateway endpoint                                                                                                                                                                                                                                                                                              |
| `error/AgentRequestBodyExceptionHandler.java`                                                                        | A body that is not JSON or has an unknown field becomes `INVALID_PLAN`, never echoing the body                                                                                                                                                                                                                                                           |
| `config/AgentGatewayAutoConfiguration.java`                                                                          | Switches the gateway on in any servlet web app. Builds the registry against the app's checks, counts and resolvers, wires preflight, execute and tokens with their transactions, and stops the app if a rule is broken, unknown JSON fields would be accepted, or there is no audit trail                                                                |
| `config/AgentGatewayOpenApiConfiguration.java`                                                                       | When the host uses springdoc: documents each error status of preflight and execute with the codes it carries, and keeps `UserContext` out of the docs                                                                                                                                                                                                    |
| `src/main/resources/META-INF/spring/…AutoConfiguration.imports`                                                      | The lines that tell Spring Boot the two auto-configurations exist                                                                                                                                                                                                                                                                                        |


| Test (under `src/test/java/com/diversive/agent/`) | What it proves                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fixtures/NotesCapabilities.java`                 | A made-up "notes" domain: share a folder (group write), archive a note (single write), find notes (read), and edited or broken variants of archive                                                                                                                                                                                              |
| `fixtures/PreflightFixtures.java`                 | The notes domain with behaviour: resolvers, checks, counts and handlers over a `NotesStore`, a capability that is not implemented, copy-then-pin for steps that depend on each other, and a branch-wide sweep                                                                                                                                   |
| `fixtures/NotesStore.java`                        | The notes data in memory, which tests change between preflight and execute: ambiguous names, an empty folder, an archived note                                                                                                                                                                                                                  |
| `fixtures/MemoryAuditTrail.java`                  | An audit trail in a list, for tests without a database                                                                                                                                                                                                                                                                                          |
| `fixtures/BrokenCapabilities.java`                | Handlers that break rules on purpose                                                                                                                                                                                                                                                                                                            |
| `registry/CapabilityRegistryBuilderTest.java`     | Full detail from annotations and records; lists, dates, decimals; versions are stable SHA-256; one changed character changes only that entry's version                                                                                                                                                                                          |
| `registry/RegistryRulesTest.java`                 | Each rule rejects what it should (including a missing resolver bean, a confirmation with a gap, a list of names), a not-implemented capability needs no beans, all problems reported together                                                                                                                                                   |
| `config/AgentGatewayAutoConfigurationTest.java`   | In a running app: preflight is wired; a missing precondition or resolver bean, a one-sided sibling, a short token secret or lenient JSON stops startup                                                                                                                                                                                          |
| `metadata/AgentMetadataControllerTest.java`       | The JSON the endpoints serve, and that no handler or path leaks                                                                                                                                                                                                                                                                                 |
| `session/AgentSessionControllerTest.java`         | 401 without a user; only what the policy permits                                                                                                                                                                                                                                                                                                |
| `plan/PlanHasherTest.java`                        | The plan hash equals SHA-256 of hand-written canonical JSON; parameter order does not matter; one character changes it                                                                                                                                                                                                                          |
| `preflight/PreflightServiceTest.java`             | The passes: labels, candidates, only matches the step could act on (one left needs no question; all breaking the same rule are all offered), the user's choice, not found before ambiguity, a rule across fields, hints, typed values, real counts, defaults, numbered warnings, pending steps, reads, the token, and 19 ways a plan is invalid |
| `preflight/PreflightControllerTest.java`          | The wire format both ways, and each refusal's status and body                                                                                                                                                                                                                                                                                   |
| `preflight/PreflightTestSupport.java`             | Builds a preflight over the fixtures, and plans against it                                                                                                                                                                                                                                                                                      |
| `token/PreflightTokensTest.java`                  | A tampered plan hash, an edited plan, another key, another user, garbage and a token past 5 minutes all fail; the payload has no words; short secrets refused                                                                                                                                                                                   |
| `execute/ExecuteServiceTest.java`                 | A write runs once and is audited; a replay runs nothing twice; reads run again; a value from an earlier step; a precondition, a count or a record that changed; a misreporting or broken handler; a partial plan; altered and expired tokens; stale versions; the sentence required                                                             |
| `execute/ExecuteControllerTest.java`              | The wire format: 200 step by step, a step's error inside it, a token refused as a whole, 401, unknown fields                                                                                                                                                                                                                                    |
| `execute/DeltaRuleTest.java`                      | Any change on a small count, a few percent on a large one                                                                                                                                                                                                                                                                                       |
| `execute/ExecuteTestSupport.java`                 | A preflight and an execute over one notes store, to confirm a plan, change the world, then execute                                                                                                                                                                                                                                              |
| `GatewayTestApplication.java`                     | A tiny app, so the gateway's tests run without school-app                                                                                                                                                                                                                                                                                       |


#### `backend/school-app/` — the school application

A stand-in for the real school management backend, which does not exist yet. It is here so the AI
layer has real data and real endpoints to work against during the POC. It is small but not fake:
real tables, real SQL and real tests, because Phases 1–3 prove the invariants on it. When the real
backend exists, that backend adds `agent-gateway` as a dependency and this module is retired.

Each business package keeps its agent beans (resolvers, checks, counts) next to the data they read.


| File (under `src/main/java/com/diversive/school/`)                               | What it has                                                                                                                                                                                                             |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SchoolApplication.java`                                                         | The `main` method                                                                                                                                                                                                       |
| `academic/agent/SectionResolver.java`                                            | "class 5 blue" (or "class 5 ka blue section") → the section Class 5 Blue, among the open session's sections in the user's branch                                                                                        |
| `academic/agent/ClassResolver.java`                                              | "class 5" → the class Class 5, the same way                                                                                                                                                                             |
| `student/agent/StudentResolver.java`                                             | A student by name (optionally with class and section) or admission number, in the user's branch; namesakes told apart by section                                                                                                                        |
| `fee/overdue/FeeOverdueController.java`, `FeeOverdueQuery.java`                  | `fee.overdue.list` (read): who owes what, by school, class, section or student; the query checks its scope has exactly its own target                                                                                   |
| `fee/overdue/FeeOverdueService.java`, `FeeOverdueResponse.java`                  | The list: one row per student with overdue fees, filtered by age band and minimum amount, with the total                                                                                                                |
| `fee/reminder/FeeReminderController.java`, `FeeReminderRequest.java`             | `fee.reminder.send` (group write, counted): remind every overdue family in a section. The channel reads as "WhatsApp", "SMS" or "email"                                                                                 |
| `fee/reminder/FeeReminderRepository.java`                                        | Who a section's reminder reaches: the one query both the count and the send use, with "overdue" written once; logs a reminder; counts every guardian who owes                                                           |
| `fee/reminder/FeeReminderService.java`, `FeeReminderResponse.java`               | The send: one Queued reminder log entry per guardian reached, what they owe, and how many the channel did not reach                                                                                                     |
| `fee/reminder/FeeReminderAgentBeans.java`                                        | `section_has_defaulters`, `channel_reaches_defaulters`, and the reminder count (with the outstanding total and "5 guardians" in words)                                                                                  |
| `fee/payment/FeePaymentController.java`, `FeePaymentRequest.java`                | `fee.payment.record` (single write): money received against one invoice. The route reads as "in cash" or "by bank challan"                                                                                              |
| `fee/payment/FeePaymentService.java`, `FeePaymentResponse.java`                  | Records the payment with the next gapless receipt number, after locking the invoice and checking its balance again                                                                                                      |
| `fee/invoice/InvoiceResolver.java`, `InvoicePhrase.java`                         | An invoice by number, or by student (optionally with class and section) and month in English or Roman Urdu; candidates say what is still owed                                                                                                            |
| `fee/invoice/InvoiceBalances.java`                                               | What is billed, paid and owed on one invoice of the user's branch                                                                                                                                                       |
| `fee/invoice/InvoiceAgentBeans.java`                                             | `invoice_is_open` and `amount_within_balance`                                                                                                                                                                           |
| `fee/cancellation/FeeCancellationController.java`, `FeeCancellationRequest.java` | `fee.cancellation.raise`: metadata only (`@AgentNotImplemented`), one of the four confusable corrections                                                                                                                |
| `fee/credit/FeeCreditController.java`, `FeeCreditRequest.java`                   | `fee.credit.raise`: metadata only, confusable                                                                                                                                                                           |
| `fee/writeoff/FeeWriteoffController.java`, `FeeWriteoffRequest.java`             | `fee.writeoff.propose`: metadata only, confusable                                                                                                                                                                       |
| `fee/latefee/LateFeeWaiverController.java`, `LateFeeWaiverRequest.java`          | `fee.latefee.waive`: metadata only, confusable                                                                                                                                                                          |
| `dashboard/DashboardController.java`                                             | `dashboard.main.read` (read): the main dashboard figures, e.g. today's collection                                                                                                                                       |
| `dashboard/DashboardService.java`, `DashboardResponse.java`                      | Today's and this month's collection and what is outstanding, for the user's branch, with when they were calculated                                                                                                      |
| `platform/agent/DevTokenUserContextResolver.java`, `DevUserProperties.java`      | POC sign-in: `Authorization: Bearer <dev token>` acts as the configured user from `app_users`, with their branch as scope                                                                                               |
| `platform/agent/SingleRoleCapabilityPolicy.java`                                 | POC permissions: the one role may use every capability                                                                                                                                                                  |
| `platform/agent/SchoolScope.java`                                                | Reads the user's branch from their scope, for every resolver, check and count                                                                                                                                           |
| `platform/agent/NameSearch.java`                                                 | How names match: whole words, any order, exact names first; the same splitting in Java and SQL                                                                                                                          |
| `platform/agent/SchoolTemplateFormatter.java`, `DisplayName.java`                | How values read in confirmations: rupees, dates in words, enums by display name                                                                                                                                         |
| `platform/agent/JdbcAuditTrail.java`                                             | The gateway's audit trail in the `agent_audit` table: inserts only, and finds a write that already succeeded                                                                                                            |
| `platform/agent/PendingImplementations.java`                                     | The 501 answer of the four capabilities that are declared but never built                                                                                                                                               |
| `platform/format/SchoolFormats.java`                                             | "PKR 71,500", "14 September 2026", "14 September 2026 at 3:05 pm", "September 2026", "5 guardians"                                                                                                                      |
| `platform/time/ClockConfiguration.java`                                          | The school's clock (Asia/Karachi): "today" for overdue fees, and token expiry                                                                                                                                           |
| `platform/openapi/OpenApiConfiguration.java`                                     | Title, fixed server URL and named enums for the published OpenAPI, so the exported contract is the same everywhere                                                                                                      |
| `src/main/resources/application.yml`                                             | Database, Flyway, port and bind address, the `agent-gateway` OpenAPI group, the dev user, preflight token and execute settings, the school's time zone, plan and session ids on log lines, "reject unknown JSON fields" |


**Database migrations** (`src/main/resources/db/`). Flyway applies them in version order on start.
Both folders share one numbering. Never edit a migration that has run; add a new one.


| File                                                          | What it has                                                                                                                                                    |
| ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `migration/V1__core_branches_and_users.sql`                   | Branches (campuses) and staff users                                                                                                                            |
| `migration/V2__academic_sessions_classes_sections.sql`        | Academic sessions, classes ("Class 5"), sections ("Blue")                                                                                                      |
| `migration/V3__guardians_students_enrolments.sql`             | Guardians (one per family code, with contact channels), students, enrolments                                                                                   |
| `migration/V4__fee_invoices_lines_payments.sql`               | Invoices, invoice lines, payments, and the `fee_invoice_balances` view                                                                                         |
| `seed/V5__seed_demo_school.sql`                               | The demo school (POC only). Its header lists every number the tests pin                                                                                        |
| `migration/V6__agent_audit_fee_reminders_payment_details.sql` | The append-only `agent_audit` table (a trigger refuses changes; one success per idempotency key), `fee_reminders`, and a payment's bank stamp date and remarks |


**Tests** (`src/test/java/com/diversive/school/`). Classes ending in `IT` need Docker.


| File                                                                   | What it proves                                                                                                                                                                                                                                                                                                                                                                                                    |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agent/ExecuteIT.java`                                                 | Phase 3 on the seeded school: a reminder sent once, verified and audited; a replay; a payment with the next receipt; a precondition, a count and a section that changed after the confirmation; a partial plan; an altered token; the audit trail refusing changes; reads; handlers as plain endpoints. Puts the seed back after each test                                                                        |
| `agent/ExecuteRollbackIT.java`                                         | A handler that writes 5 rows but reports 99 fails verification: its rows are rolled back and only `FAILED` is recorded (boots its own backend)                                                                                                                                                                                                                                                                    |
| `agent/PreflightIT.java`                                               | Phase 2 on the seeded school: labels in the confirmation, candidates, a paid invoice or a section without defaulters never offered, the furthest match refused with its hint, a real choice still asked, the user's choice, hints, counts per channel checked against SQL, invoices, reads, the four refused corrections, a value the endpoint would reject, another branch invisible, the token binding the plan |
| `agent/CapabilityIdsMatchPlanningContractsTest.java`                   | Every capability id is an operation in `planning-contracts/`, with a matching read or write kind (no Docker)                                                                                                                                                                                                                                                                                                      |
| `agent/AgentGatewayEndpointIT.java`                                    | The running backend registers exactly the 8 capabilities and serves them with full detail; the dev user gets every id; a missing or wrong token gets 401                                                                                                                                                                                                                                                          |
| `agent/RegistryBuildChecksIT.java`                                     | The real backend refuses to start without a precondition bean, without a resolver bean, or with a one-directional sibling                                                                                                                                                                                                                                                                                         |
| `agent/OpenApiContractIT.java`                                         | `openapi/agent-gateway.json` matches what the backend serves                                                                                                                                                                                                                                                                                                                                                      |
| `agent/AgentMetadataSnapshotIT.java`                                   | `snapshots/agent-metadata.json` matches what the backend serves, and every school resolver says what it searches by                                                                                                                                                                                                                                                                                                                                                   |
| `database/DatabaseMigrationIT.java`                                    | All six migrations run clean, pgvector works, and the seed has the exact shape later phases rely on                                                                                                                                                                                                                                                                                                               |
| `fee/invoice/InvoicePhraseTest.java`                                   | Invoice words: names, months, years, Roman Urdu particles (no Docker)                                                                                                                                                                                                                                                                                                                                             |
| `platform/agent/SchoolWordingTest.java`                                | Whole-word matching, exact names first, rupees, dates, display names, plurals (no Docker)                                                                                                                                                                                                                                                                                                                         |
| `support/SchoolPostgresContainer.java`, `PostgresIntegrationTest.java` | One pgvector container per test run, and the base class that boots the backend against it signed in with a test token                                                                                                                                                                                                                                                                                             |
| `support/CommittedJson.java`                                           | "This committed JSON file must equal what the backend serves", or rewrite it with `-Dcontract.update=true`                                                                                                                                                                                                                                                                                                        |


---


### `ai-layer/` — the FastAPI service


| File                                        | What it has                                                                                                      |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `pyproject.toml`                            | Dependencies, and pytest (markers `integration`, `embeddings`) / ruff / mypy settings (mypy also checks `eval/`) |
| `uv.lock`                                   | Exact versions of every dependency (uv writes it; commit it)                                                     |
| `.python-version`                           | Python 3.12                                                                                                      |
| `migrations/0001_capability_index.sql`      | The `capability_index` table from the plan                                                                       |
| `migrations/0002_capability_index_sync.sql` | Adds what sync and siblings need: `version`, `disambiguate_from`, `embedding_model`, `synced_at`                 |


#### `ai-layer/app/` — the service code


| File           | What it has                                                                                                                                                                                                       |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `__main__.py`  | `python -m app` starts the server                                                                                                                                                                                 |
| `main.py`      | `create_app()`: logging, resources opened at startup and closed at shutdown, middleware, routes                                                                                                                   |
| `resources.py` | Opens the index database (running migrations if enabled), the gateway and embeddings clients, metadata sync, the retriever, the Gemini model and chat (when a key is set); starts the sync loop, and at shutdown stops it and closes every client |


`app/core/` — the bottom layer, imports nothing else from `app`


| File          | What it has                                                                                                                                                                         |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `settings.py` | Every setting, read from `AI_LAYER_*` variables. Unknown or misspelled variables are an error                                                                                       |
| `logging.py`  | structlog setup; tokens, passwords and secrets are replaced with `[REDACTED]`                                                                                                       |
| `trace.py`    | The per-turn pipeline trace for the chat page: `stage(...)` times a block and keeps what it was given and produced, only while a request collects a trace; no secrets, never logged |


`app/api/` — what the AI layer serves over HTTP


| File           | What it has                                                                                                                                                                                         |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `health.py`    | `GET /health/live` and `GET /health/ready` (index, backend, whether metadata sync has filled the index, and whether chat is on)                                                                     |
| `chat.py`      | `POST /chat`: the bearer token, one turn (a message, a choice or a confirm), and the typed reply (answer, question, confirmation, refusal); with `"trace": true`, every pipeline stage the turn ran |
| `chat_page.py` | `GET /`: serves the chat test page with a strict content-security policy (off with `AI_LAYER_CHAT_PAGE_ENABLED=false`)                                                                              |


`app/web/` — the chat test page


| File              | What it has                                                                                                                                                                                                                                                                                                                                                                            |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `chat.html`       | One self-contained page: the chat on the left (sign in with the dev token, send sentences, answer questions with option buttons, confirm or cancel, the backend's data as tables); on the right, the **Pipeline** tab showing each stage of the selected turn with its input, output and timing, and the **Try it** tab with examples of everything the POC can do; the readiness pill |
| `middleware.py`   | One log line per request, with a request id echoed on the response                                                                                                                                                                                                                                                                                                                     |
| `dependencies.py` | How a route gets the shared resources                                                                                                                                                                                                                                                                                                                                                  |


`app/gateway/` — the only way into the backend


| File        | What it has                                                                                                                                                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `client.py` | Async client for `/agent/metadata`, `/agent/metadata/versions`, `/agent/session/capabilities`, `POST /agent/preflight` and `POST /agent/execute`. It sends the user token and never logs it, and validates every response against the generated models |
| `errors.py` | `GatewayUnavailableError`, `GatewayProtocolError`, and `GatewayRejectedError`, which carries the backend's error body: the `code`, and the candidates or hint where they apply                                                                         |
| `models.py` | **Generated. Do not edit.** Pydantic models from `openapi/agent-gateway.json` (plans, preflight, execute, metadata, errors), all `extra="forbid"`                                                                                                      |


`app/index/` — the only database access


| File            | What it has                                                                                                                                                                                                                                                                         |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `database.py`   | The connection pool; connections never leave its methods, so none is held across a model call. The index queries: `indexed_versions`, `apply_sync` (upserts and deletes in one transaction), `dense_ranking` (pgvector cosine), `lexical_ranking` (full text, any word), `siblings` |
| `migrations.py` | Runs `ai-layer/migrations/*.sql` once each, in order, with checksums                                                                                                                                                                                                                |
| `__main__.py`   | `python -m app.index` applies the migrations without starting the server (`make ai-migrate`)                                                                                                                                                                                        |


`app/embeddings/` — vectors from the embeddings service


| File        | What it has                                                                                                                                                                         |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `client.py` | Async client for Text Embeddings Inference: checks the service runs pinned `BAAI/bge-m3`, embeds texts in small batches, and checks vector count and size. Also `cosine_similarity` |


`app/sync/` — keeping the index in step with the backend


| File               | What it has                                                                                                                                                                                                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `metadata_sync.py` | `MetadataSync`: polls versions, fetches and re-embeds only what changed (or was embedded by another model), deletes what was withdrawn, keeps the latest metadata as the `catalog`, and reports its status to readiness. `run_forever()` polls every 30 s and survives outages |


`app/retrieval/` — which capabilities the planner may choose from


| File          | What it has                                                                                                                                                                                              |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fusion.py`   | The pure parts: `fuse` (reciprocal rank fusion, k = 60) and `expand_with_siblings` (each capability followed by its siblings, groups never split, capped)                                                |
| `hybrid.py`   | `HybridRetriever.retrieve(queries, allowed)`: embeds first, then dense and lexical search per query inside the allow-list, fusion, siblings, cap 30. The result keeps the fused order too, for measuring |
| `__main__.py` | `python -m app.retrieval "sentence"` prints the candidates with scores, ranks and `sibling_of` (`make retrieve Q="..."`)                                                                                 |


`app/llm/` — model calls behind one interface


| File        | What it has                                                                                                                                                                                                      |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `runner.py` | `StructuredModel` (the interface), `ModelRequest`, the errors, and the pinned model ids: `gemini-3.1-flash-lite` for both decompose and the planner, kept as two names so either can move alone                   |
| `gemini.py` | `GeminiModel`: one Gen AI SDK client for every call (pinned model, system instruction, JSON schema, no tools, thinking `minimal` or `high`, one timeout over the SDK's retries), and `gemini_schema`, which rewrites a schema into the JSON Schema subset Gemini accepts |


`app/choosing/` — the capability chooser (on by default when a TypeSafe key is set)


| File         | What it has |
| ------------ | ----------- |
| `jev.py`     | `JevModel`: TypeSafe's `POST /v1/systemone` over httpx, with the key as a bearer token; retries 429, 529 and 5xx with backoff, all within one timeout; `DecisionRequest` and the `DecisionModel` interface |
| `chooser.py` | `CapabilityChooser.choose(intents, candidates)`: one choice question per intent (the candidates, each with what it does and needs, plus "none"), the answer checked, and the shortlist the planner sees. The model is pinned here (`jev-1.13.0`) |


`app/decompose/` — model call 1


| File               | What it has                                                                                                                                                                                                             |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `decomposer.py`    | `Decomposer.decompose(sentence)`: 1-3 English intents with the names each carries, the output schema, and `check_decomposition` (schema, names quoted from the sentence and kept in their intent, no untranslated Urdu) |
| `system_prompt.md` | Decompose's instructions; the glossary is filled in at startup                                                                                                                                                          |


`app/planning/` — model call 2, and the pipeline


| File               | What it has                                                                                                                                               |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `outcomes.py`      | What planning ends in (`PlannedSteps`, `NeedsInput`, `Refusal`) and the strict shape of the planner's answer, with its JSON schema                        |
| `planner.py`       | `Planner.plan(...)`: builds the prompt (sentence, intents as a retrieval aid, today, candidates with their parameters and, for a looked-up one, what it is found by) and validates the answer           |
| `system_prompt.md` | The planner's instructions                                                                                                                                |
| `service.py`       | `SentencePlanner.understand(sentence, allowed, session_id)`: decompose → retrieve → (choose, when the chooser is on) → plan → validate. No candidates, or an empty shortlist, means a refusal without a planner call |
| `__main__.py`      | `python -m app.planning "sentence"` shows every stage and the backend's preflight answer (`make plan Q="..."`)                                            |


`app/validation/` — checks on model output, no model involved


| File                | What it has                                                                                                                                                                                                                                                                                   |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `problems.py`       | `Problem`, `InvalidModelOutputError` (with a code per broken rule), and the quoting helpers                                                                                                                                                                                                   |
| `plan_validator.py` | `validate_plan`: ids in the metadata, the allow-list and the candidates; parameters declared, not duplicated, required ones present; forms, types, allowed values, real dates; names and amounts quoted from the sentence; earlier-step facts; step count; versions stamped from the metadata |


`app/orchestration/` — the conversation


| File              | What it has                                                                                                                                                                                                                                                                         |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `orchestrator.py` | `ChatOrchestrator.handle(session_id, turn, user_token)`: the state machine. Plans a new sentence (or takes it from the cache), preflights, asks, resumes the same plan with the answer, confirms, executes, and handles an expired token, a moved count and every preflight refusal |
| `session.py`      | `Session` (phase, sentence, plan, what was asked, the token), the phases, `SessionStore` and the in-memory store with its time limit                                                                                                                                                |
| `answers.py`      | Reading answers without a model: yes and no in English and Roman Urdu, an option by number or name, a value by type                                                                                                                                                                 |
| `plan_cache.py`   | `PlanCache` (least recently used) and `plan_cache_key` (normalised sentence, allow-list, capability versions, today)                                                                                                                                                                |


`app/response/` — what the user reads


| File         | What it has                                                                                                                                                                                          |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `replies.py` | `ChatReply`, and every reply: refusals by code, questions (a choice, a name again, a missing value), confirmations (the backend's text), cancelled, and answers built from each step's backend reply |


`app/capabilities/` — capability metadata as data


| File            | What it has                                                                                                      |
| --------------- | ---------------------------------------------------------------------------------------------------------------- |
| `snapshot.py`   | Loads `snapshots/agent-metadata.json` into the generated models                                                  |
| `similarity.py` | Build-time assertion 5: pairwise description similarity, and the pairs above 0.92 that are not declared siblings |
| `__main__.py`   | `python -m app.capabilities` prints every pair's similarity (`make descriptions-report`)                         |


#### `ai-layer/domain/school/`


| File          | What it has                                                                                                |
| ------------- | ---------------------------------------------------------------------------------------------------------- |
| `glossary.py` | A short glossary of office words (challan, baqaya, jurmana, wasooli, …) for both prompts                   |
| `calendar.py` | The school's time zone (`Asia/Karachi`) and `school_today()`, for the planner's "today" and the plan cache |


#### `ai-layer/scripts/`


| File                         | What it has                                                                                                                                            |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `generate_gateway_models.py` | Regenerates `app/gateway/models.py` from the OpenAPI contract with fixed options (part of `make contracts`)                                            |
| `extract_eval_corpus.py`     | Rewrites `eval/retrieval/data/contract_sentences.jsonl` and `distractors.jsonl` from `planning-contracts/dist/routing-index.json` (`make eval-corpus`) |


#### `ai-layer/tests/`

The folder decides the marker:

- `integration/` needs Docker;
- `build_checks/` needs `make embeddings-up`;
- `model_checks/` calls the real models through the Gemini API.

`make ai-test-unit` runs none of them, and `make ai-test` runs everything except `model_checks/`.


| File                                             | What it proves                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `conftest.py`                                    | Hides your `AI_LAYER_*` variables from tests, and marks tests by folder                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `unit/test_settings.py`                          | Defaults, overrides, typo detection, `.env.example` documenting exactly the real settings                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `unit/test_logging.py`                           | Sensitive values never reach a log line                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `unit/test_gateway_client.py`                    | Parsing real metadata, unknown and missing fields, versions, the bearer token, error codes, unreachable and slow backends; preflight's request body (no empty fields), its confirmation, and refusals carrying candidates or a hint; execute's request, step results and a whole-plan refusal                                                                                                                                                                                                                                                                       |
| `unit/test_gateway_models.py`                    | Generated models match the contract, and all reject unknown fields                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `unit/test_capability_snapshot.py`               | The snapshot parses, versions are SHA-256, siblings are registered and symmetric, no URLs                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `unit/test_description_similarity.py`            | Pair ordering, the 0.92 threshold, siblings allowed, descriptions are what gets embedded                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `unit/test_embeddings_client.py`                 | Pinned model accepted, any other refused, batching, wrong sizes refused, a helpful error when the service is down                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `unit/test_health_api.py`                        | Live/ready for every failure mode, request ids, shutdown, startup without a database; not ready until the first sync, still ready after a later failed poll                                                                                                                                                                                                                                                                                                                                                                                                         |
| `unit/test_metadata_sync.py`                     | The first sync indexes every description; nothing is fetched or embedded when nothing changed; a new version re-embeds only that one; withdrawn capabilities are deleted; another model's rows are re-embedded; a wrong model writes nothing; the loop survives a backend outage                                                                                                                                                                                                                                                                                    |
| `unit/test_retrieval.py`                         | Fusion scores, ranks and ties; siblings follow their capability, keep their own score, never split, never come from outside the allow-list; the cap; the retriever embeds before touching the index, searches every intent, and retrieves nothing for an empty allow-list                                                                                                                                                                                                                                                                                           |
| `unit/test_eval_dataset.py`                      | The committed eval set is sound (size, labels, duplicates, Roman Urdu with glosses, every cluster member covered), the rules catch a bad set, and the recall and cluster metrics                                                                                                                                                                                                                                                                                                                                                                                    |
| `unit/test_migration_files.py`                   | Migration file naming, numbering, checksums, schema-name safety                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `unit/test_gemini.py` | With a fake SDK client: the request (pinned model, system instruction, prompt as the only content, JSON schema, no tools), `minimal` or `high` thinking, the schema rewritten into Gemini's subset (`$ref`s inlined, type lists as `anyOf`, property names kept), API errors, an unreachable API and the timeout as unavailable, a blocked or cut-off answer and non-JSON as output errors, a missing key, closing the client |
| `unit/test_decomposer.py`                        | The pinned model and glossary are used, without thinking; a name the user never wrote, a changed name, untranslated Urdu and anything outside the schema are refused                                                                                                                                                                                                                                                                                                                                                                                                |
| `unit/test_plan_validator.py`                    | A good two-step plan with versions from the metadata; a hallucinated id; ids outside the allow-list or candidates; invented, duplicate and missing parameters; defaults; forms, types, allowed values, dates; words and amounts not in the sentence; earlier-step facts; step limits; refusals and `needs_input`; contradictory or unknown shapes                                                                                                                                                                                                                   |
| `unit/test_planning_service.py`                  | With scripted models: retrieval searches with the English intent, the planner sees the sentence, the intents as a retrieval aid, today and the candidates; nothing retrieved means no planner call; the allow-list holds; a traced run shows all seven understanding stages, and a rejected plan is a failed stage listing the broken rules                                                                                                                                                                                                                         |
| `unit/test_chat_orchestrator.py`                 | With a scripted backend and planner: a read in one turn; a write after yes; an ambiguous name resumed without planning; a missing value filled; cancelling runs nothing; an expired token and a moved count confirmed again; a failed precondition in the backend's words; a name not found asked up to three times; the plan cache; model failures not cached; a new sentence replacing a waiting plan; sessions per user; an answer after the session expired; a stale version; the stages a traced question, choice and confirmation show, with no token in them |
| `unit/test_chat_answers.py`                      | Yes and no words, choosing options, reading amounts, dates, allowed values and free text; the plan cache key and eviction                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `unit/test_measure_logic.py`                     | Recordings (asked once, replayed without a model, refused for a changed prompt or thinking setting, outages not recorded), every fault caught for its own reason, and how the four numbers are counted                                                                                                                                                                                                                                                                                                                                                              |
| `unit/test_chat_page.py`                         | `GET /` serves the page self-contained and locked to its own origin, inserts replies as text, can be turned off, and stays out of the API description                                                                                                                                                                                                                                                                                                                                                                                                               |
| `unit/test_chat_api.py`                          | `POST /chat` over HTTP: the reply's shape, exactly one kind of turn, 401 without a token or when the backend refuses it, 503 when chat or the backend is off; the trace comes back when asked, and not when the setting is off                                                                                                                                                                                                                                                                                                                                      |
| `unit/test_trace.py`                             | Stages kept in the order they start, with input, output and timing; a failed stage marked with its error; nothing kept outside a request; long text cut                                                                                                                                                                                                                                                                                                                                                                                                             |
| `model_checks/test_planning_with_real_models.py` | The real models (Gemini API): a Roman Urdu sentence becomes a valid plan with names intact, a sentence matching nothing is refused, a two-part sentence becomes two steps in order                                                                                                                                                                                                                                                                                                                                                                                         |
| `integration/conftest.py`                        | The container and connection fixtures                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `integration/test_database_boundary.py`          | Invariant 1 in Postgres                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `integration/test_index_migrations.py`           | The index table matches the plan; edited, failed or unknown migrations are handled safely                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `integration/test_startup_and_readiness.py`      | The real startup path migrates the index and reports ready; with sync on, it fills the index from the backend's metadata before reporting ready, and the retriever finds the reminder                                                                                                                                                                                                                                                                                                                                                                               |
| `integration/test_capability_index_queries.py`   | Against Postgres + pgvector: sync upserts and deletes, dense ranking by cosine within the allow-list and embedding model, lexical ranking on any word with stemming, stop words match nothing, siblings                                                                                                                                                                                                                                                                                                                                                             |
| `build_checks/test_capability_descriptions.py`   | Against the live embeddings service: it runs pinned BGE-M3, and no two descriptions embed above 0.92 unless declared siblings. Fails, not skips, when the service is down                                                                                                                                                                                                                                                                                                                                                                                           |


#### `ai-layer/eval/` — measuring the AI layer

Run with `make ai-eval` (every eval) or `make measure` (the four numbers); both need Docker and
`make embeddings-up`, and record any model answer not yet recorded. `make measure-ci` replays the
recordings without calling a model. None of this is part of `make ai-test`.


| File                                                | What it has                                                                                                                                                                                                   |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `conftest.py`                                       | Reuses the integration tests' throwaway Postgres; opens empty, migrated indexes that last the whole module                                                                                                    |
| `test_retrieval_recall.py`                          | The Phase 4 gates: recall@30 above 90% on the POC index, and among distractors for queries in English; whole clusters on both; Roman Urdu reported on its own                                                 |
| `test_retrieval_with_intents.py`                    | Retrieval as it really runs: every sentence decomposed by the decompose model, the stress index searched with its intents, held to the same 90% gate                                                                        |
| `retrieval/intents.py`                              | Decomposes every eval sentence with the decompose model, through the recordings                                                                                                                                             |
| `test_measure.py`                                   | Phase 7: prints the four numbers (all, English, Roman Urdu) and fails if one fell below the baseline; the validator catches every injected fault; a deliberately broken description shows up as a recall drop |
| `test_decompose_repeat.py`                          | Decompose gives the right number of intents every time (decision 76): each sentence in `measure/data/request_counts.jsonl` decomposed 3 times, each run recorded; fails on any wrong count; writes `reports/decompose_repeat.md`. Needs no Docker or embeddings |
| `measure/data/request_counts.jsonl`                 | 33 sentences with how many requests each makes: 14 asking for two or three things ("dikhao aur reminder bhej do"), 19 asking for one, English and Roman Urdu with glosses |
| `recordings/decompose_repeat.json`                  | The recorded answers for those runs, one per sentence and run                                                                                                                                                 |
| `test_measure_with_jev.py`                          | The chooser experiment (decision 72): every case planned without and with Jev choosing first; checks every sentence got an outcome and the validator still catches every fault, and writes `reports/measure_with_jev.md` |
| `measure/choose_report.py`                          | The comparison table, Jev on its own (right picks, shortlist sizes, time per call, confidence against accuracy) and every sentence whose result changed |
| `recordings/choose.json`, `recordings/plan_after_choose.json` | Jev's recorded answers, and the planner's answers when it sees only Jev's shortlist |
| `reports/measure_with_jev.md`                       | The latest chooser comparison                                                                                                                                                                                 |
| `measure/pipeline.py`                               | Every case (175 labelled sentences, 70 to refuse) through decompose, retrieval on the stress index, the planner and the validator, keeping each stage's result                                                |
| `measure/recordings.py`                             | Recorded model answers in `eval/recordings/`: record mode asks only for what is missing, replay mode never calls a model; a changed prompt invalidates the recording                                          |
| `measure/faults.py`                                 | Thirteen ways to break a real planner answer (a made-up id, an invented parameter, a name the user never wrote, …) and the tally of what the validator caught                                                 |
| `measure/scores.py`                                 | The four numbers per language                                                                                                                                                                                 |
| `measure/report.py`                                 | The printed table and `reports/measure.md`                                                                                                                                                                    |
| `measure/baseline.json`                             | The numbers a run must not fall below (update with `EVAL_UPDATE_BASELINE=1 make measure`)                                                                                                                     |
| `measure/data/refusal_sentences.jsonl`              | 60 requests the POC must refuse, labelled by the contracts as routing to capabilities it does not publish: every other fee intent, and one per other module (generated)                                       |
| `measure/data/refusal_authored.jsonl`               | 10 non-requests and out-of-scope questions (greetings, weather, a joke), English and Roman Urdu                                                                                                               |
| `recordings/decompose.json`, `recordings/plan.json` | The recorded model answers, before validation, committed so CI measures without a model. Each file names its model; they hold Gemini's answers (see Status)                                                                                                            |
| `retrieval/data/contract_sentences.jsonl`           | 75 sentences labelled by the planning contracts: routing examples and near-misses for the 8 capabilities (generated)                                                                                          |
| `retrieval/data/authored_sentences.jsonl`           | 100 sentences written for this eval, English and Roman Urdu with glosses, weighted towards the four fee corrections                                                                                           |
| `retrieval/data/distractors.jsonl`                  | 481 other planning-contract intents' one-line summaries, for the stress index (generated)                                                                                                                     |
| `retrieval/dataset.py`                              | Loads the files and lists why a set would give a flattering number (too small, unknown labels, duplicates, Roman Urdu without glosses, thin cluster members)                                                  |
| `retrieval/harness.py`                              | Builds the POC index with the real sync and the stress index with distractors, then retrieves every sentence as typed and every Roman Urdu gloss                                                              |
| `retrieval/metrics.py`                              | Recall@30 from the candidates; recall@1/3/5 and MRR from the fused order; the cluster check                                                                                                                   |
| `retrieval/embedding_cache.py`                      | Keeps eval embeddings in `eval/.cache/` (not committed), one file per model revision                                                                                                                          |
| `retrieval/report.py`                               | Writes `reports/retrieval.md`                                                                                                                                                                                 |
| `reports/retrieval.md`                              | The latest results: every index and query group, per capability, per source, and the hardest sentences                                                                                                        |
| `reports/measure.md`                                | The four numbers, outcomes per set, plan accuracy per capability, faults per kind, and every sentence planned wrongly or acted on when it should have been refused                                            |
| `reports/retrieval_with_intents.md`                 | The latest results with real intents, and any sentence decompose could not answer within the rules                                                                                                            |


---


### Where the next phases go


| Phase         | Backend                             | AI layer                                                                                       |
| ------------- | ----------------------------------- | ---------------------------------------------------------------------------------------------- |
| After the POC | the real backend adds agent-gateway | Redis behind `SessionStore`, redaction before any model call (the SDK behind `StructuredModel` is done: decision 69) |


The "Repository layout" section of `docs/CLAUDE.md` shows this same structure, including these
future folders. Change both together.

---


### Recipes

**Add a capability.**

1. Find its operation name in `planning-contracts/dist/capability-index.json`. Never invent one: if
  it is missing, write the planning contract first.
2. Put `@AgentCapability`, one `@AgentParam` per request-record field, any `@AgentPrecondition`s and
  an `@AgentEffect` on the controller method.
3. Add what it needs to run, next to the data it reads:
  - a `PreconditionCheck` bean for each new precondition id;
  - an `AffectedCount` bean if the blast radius is above `SINGLE`, or if its confirmation uses a fact;
  - an `EntityResolver` bean for each new resolver type.
   If it is declared only so retrieval must tell it apart, mark it `@AgentNotImplemented` instead.
   Otherwise the handler returns a record carrying every declared fact, and `count` when it has a
   count bean; it may take the signed-in `UserContext` as a second parameter.
4. Run `make contracts`, then `make test`. A broken rule stops the backend with every problem
  listed. A description too close to a non-sibling fails the build checks.

**Add an entity type to resolve.** Implement `EntityResolver` in the package that owns the data (for
example `academic/agent/`).

- Put the user's branch in the SQL `WHERE` clause with `SchoolScope`, never in a filter afterwards.
- Match with `NameSearch`.
- Return a label the confirmation can use, and a context that tells namesakes apart.
- Name the type in `@AgentParam(resolver = "...", label = "...")`.

**Add a precondition check.** Declare `@AgentPrecondition(id, text, hint)` on the capability. Add a
`PreconditionCheck.of(id, ...)` bean beside the repository it reads. It receives the parameter values
already typed, and ids already resolved. The hint is what the user sees; never suggest a way around it.

**Add a backend table.** Create `school-app/src/main/resources/db/migration/V<next>__what_it_is.sql`,
run `make backend-verify`, and list the file in this document.

**Add or change a gateway endpoint.** Change `agent-gateway`, run `make contracts` (it rewrites the
OpenAPI spec, the snapshot and `app/gateway/models.py`), then use the new models in
`app/gateway/client.py`. Never hand-write a model the backend already describes.

**Add an index migration.** Create `ai-layer/migrations/<next NNNN>_what_it_is.sql`. Never edit an
applied one: the runner refuses to start.

**Change a prompt or the glossary.** Edit `app/decompose/system_prompt.md`,
`app/planning/system_prompt.md` or `domain/school/glossary.py`. Then run `make ai-model-checks` and
`make ai-eval`: the eval decomposes every sentence again for the new prompt, and says whether retrieval
still clears 90%.

**Add eval sentences.** Append lines to `eval/retrieval/data/authored_sentences.jsonl`:
`{"text", "lang": "en" | "ur-Latn", "expected": "<capability id>", "source": "authored", "gloss"}`,
with a gloss for every Roman Urdu line. `tests/unit/test_eval_dataset.py` says whether the set is still
sound. Then run `make ai-eval`. Never add a description's own wording as a sentence.

**Add an AI layer setting.** Add a field to `app/core/settings.py` and the matching
`AI_LAYER_…` line to `.env.example`. A test fails if either one is missing.

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

**2. General engine, school-specific edges.** [Who does what](#who-does-what) explains the split in
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
*Superseded by decision 69: both calls now go to the Gemini API.*

- The CLI path is versioned inside the desktop app, so it has to be a setting.
- The pinned model ids are `claude-haiku-4-5-20251001` and `claude-sonnet-5`.
- There is no temperature control. Consistency comes from structured output, the validator and the plan cache.
- CLAUDE.md recorded this under "On the Claude CLI" (now "On the model calls").

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

**43. Model calls use the Claude Code CLI headless, never `--bare`.** *Superseded by decision 69.*

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

**49. Real model calls stay out of `make ai-test`.** They use the API quota and take time.
`make ai-model-checks`, `make ai-eval` and `make verify-phase5` run them. The eval replays decompose's
answers from `eval/recordings/` (Phase 7), so a changed prompt or glossary must be re-recorded.

**50. There is no temperature.** The CLI had no sampling setting and Sonnet 5 rejects one; with Gemini
(decision 69) it stays at the default of 1.0, as Google recommends for Gemini 3. Consistency
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

**69. Both model calls go to the Gemini API, to `gemini-3.1-flash-lite` (21 Sep 2026).** Your
decision, for its daily request allowance, and as the first step towards running the AI layer in the
cloud: the Claude CLI was one subprocess per call, signed in through a desktop app's keychain entry,
which a server cannot have. It replaces decisions 8 and 43.

- **One file changed hands.** `app/llm/claude_cli.py` is gone, and `app/llm/gemini.py` implements
  the same `StructuredModel` interface with Google's Gen AI SDK. Decompose, the planner, the
  validator and the eval did not change.
- **Pinned.** `gemini-3.1-flash-lite` is Google's stable model code, not an alias. Both calls use it
  but keep separate names (`DECOMPOSE_MODEL`, `PLANNER_MODEL`), so either can move alone.
- **Thinking.** `thinking=False` (decompose, decision 67) asks for `minimal`, Gemini 3's lowest
  level; Google says it matches "no thinking" for most requests but does not guarantee it.
  `thinking=True` (the planner) asks for `high`, where the model decides how much to think, as Sonnet
  did. Changing either level means re-recording.
- **The schema is rewritten for Gemini, not changed.** Gemini documents only a subset of JSON Schema,
  so the transport inlines `$ref`s, writes a list of types as `anyOf` and drops keywords outside the
  subset (the length limits). What the model returns is still parsed with `extra="forbid"` models and
  checked by the validator.
- **Errors.** A missing key, an API error (with Google's status and message, which never repeat the
  prompt), an unreachable API and the timeout are `ModelUnavailableError`. A blocked prompt, an answer
  cut off early and anything but a JSON object are `ModelOutputError`. The SDK retries 408, 429 and
  5xx, at most 3 attempts, inside one timeout.
- **Recordings and CI.** Recordings name their model, so Claude's recordings no longer replayed. Gemini's
  answers were recorded on 21 Sep 2026, and they are the baseline now (see Status).
- **Tests.** `tests/unit/test_gemini.py` replaces `test_claude_cli.py`. A unit test's stand-in model
  told decompose and plan calls apart by model id, which are now equal; it uses the call's purpose.

**70. A student's or invoice's name may include the class and section.** "Record payment of 2000 for
Ahmed Raza in Class 5 Blue received as cash" failed with "I couldn't find…". The planner rightly kept the
user's words, "Ahmed Raza Class 5 Blue fees", but the invoice lookup wanted every word in the student's
name. Now the student and invoice lookups match the words against the name plus the class and section
(the invoice's own section), so the extra words narrow the search instead of breaking it.

- At least one word must come from the student's name. "class 5 blue fees" names no student, so it is
  not a lookup; "Ahmed Raza class 6 blue" finds nothing, because he is in Class 5 Blue.
- Joining words ("in", "of", "section", "student", "ka/ki/ke") are ignored, as "fees" and "invoice" were.
- With decision 68, the paid August invoice is not offered, so the sentence goes straight to the
  confirmation for September.

**71. Each lookup says what it searches by, and the planner is told.** The planner used to know only that a
parameter is "looked up from the user's words". It did not know what an invoice lookup can use, so it guessed
differently on each call ("Ahmed Raza Class 5 Blue fees", "Ahmed Raza in Class 5 Blue").

- **Where it lives.** `EntityResolver` (agent-gateway) has an optional `lookup()`: what that resolver searches
  by, in plain words. The school's four resolvers fill it in. For example the invoice one says "the student's
  name, optionally with the month or year billed and the class and section, e.g. Ahmed Raza September or Ahmed
  Raza class 5 blue; or the invoice number, e.g. INV/LHR/26-27/000031".
- **Published per parameter.** It belongs to the resolver, so one text serves every parameter that uses it:
  payment, credit, cancellation and write-off all look up an invoice. The registry copies it into each such
  parameter's metadata as `lookup`, and it counts towards the version. A changed text re-versions the
  capabilities using it, and the AI layer re-syncs on its own. `AgentMetadataSnapshotIT` fails if a school
  resolver says nothing.
- **Two uses in the AI layer.** The planner sees it as `looked_up_by` beside `looked_up_from_words`. It is told
  to pass the user's words that fit it, to leave out words about something else (amounts, "record",
  "payment"), and never to add a word. "Hamza Sheikh class 5 blue ki August ki fees 3500 naqad aaj mili" now
  becomes "Hamza Sheikh class 5 blue August". When a name is not found, the question says what to type
  ("Please type the student's name, optionally with the month…") instead of a vague "type the name again".
- **Kept as it was.** The words are still only the user's own (decision 60), and the lookups stay forgiving
  (decision 70). The hint makes a good phrase likely, not required. Separate fields (`student_name`, `month`)
  in the plan were considered and not done: they would change the plan contract and move parsing from the
  backend into the model.
- **Measured with Gemini**, together with the move to Gemini (decision 69): recall 95.4%, plan accuracy
  90.3%, refusal correctness 92.2%, catch rate 100%. See Status for the comparison with Claude.

**72. Try Jev to choose the capability before the planner (an experiment, off by default).** The planner does two
jobs: choosing the capability and filling its parameters. TypeSafe's Jev (a System One model: typed choices with
calibrated probabilities, no text, about 0.4 s) can do the first, so the planner only fills in.

- **Where it sits.** Between retrieval and the planner, in `app/choosing/`. For each intent, Jev gets one choice
  question. The options are the candidates plus "none". Each candidate says what it does and is not for, whether it
  changes records, and what it needs: every parameter's meaning, and for a looked-up one what it is found by
  (decision 71). Jev sees the English intents, not the original message.
- **The shortlist.** Per intent, Jev's options are kept in order of probability until they hold 90% of the
  probability that is not "none", at most 3. When "none" holds 0.6 or more, that intent adds nothing. The planner
  sees only the shortlist, and the validator holds it to that (`NOT_A_CANDIDATE`). An empty shortlist is a refusal
  with no planner call. If Jev cannot be reached, the planner sees every candidate, as with the chooser off.
- **Two changes after the first measurement.** Jev at first answered "none" for requests that left out details
  ("credit raise karo"), because the options list what they need. The instructions now say that missing details
  are asked for later, but that a different action on the same thing (approving, returning or paying out instead
  of proposing) does not fit. That took Jev from 153 to 158 right among the labelled sentences, and from 37 to 38
  among those to refuse. From the recorded probabilities, "none" wins at 0.6 rather than 0.5: two more labelled
  sentences, no refusal lost. Higher lets refusals through.
- **Pinned and recorded like the models.** `jev-1.13.0`, never `jev-latest`. Its answers are recorded in
  `eval/recordings/choose.json`, and the planner's answers after it in `plan_after_choose.json`, so `make
  measure-ci` replays the comparison with no calls. `JevModel` retries 429, 529 and 5xx with backoff, within
  `AI_LAYER_CHOOSER_TIMEOUT_SECONDS`.
- **Result.** Faster (about 1.8 s per sentence) and better in English, but 2.9 points lower plan accuracy
  overall, all from Roman Urdu sentences whose English intent was wrong. See Status.

**73. Decompose states a problem as a problem to fix, and keeps the request.** Short Roman Urdu messages often
state a problem instead of asking ("unhon ne waqt par diya tha phir bhi late fee lag gayi"). Decompose turned
them into "Check why…" or "Report that…", dropped the request after an explanation ("…, credit bana do"), read
"raise" as "increase", and added details ("attendance numbers" for "subah ke numbers"). Retrieval and Jev then
searched for the wrong thing.

- **The rules.** A problem with a fee record becomes the problem followed by a request to act on it ("…; fix this"),
  without choosing the remedy (no cancel, refund, credit, waive or write off the user did not ask for). An
  explanation followed by a request keeps both. School words are translated too ("outstanding fees", not
  "baqaya"). Nothing is narrowed ("the numbers" stays "the numbers"). The glossary adds "raise karna": to create
  or file, not to increase.
- **Examples are not from the eval.** The prompt's examples ("The admission fee was billed twice; fix this")
  appear in no eval sentence, so the numbers are not flattered. A first draft that named remedies ("forgive it")
  had them copied into intents, so the rule now forbids adding one.
- **Result.** Recall@30 95.4% -> 96.6% (Roman Urdu 94.6% -> 96.4%), plan accuracy unchanged at 90.3%, refusal
  correctness 92.2% -> 91.8%. With Jev choosing first, the gap to the planner alone fell from 2.9 to 1.1 points.
  Still wrong: "subah ke numbers do" becomes "Provide the numbers for subah".
- **Recording.** Intents changed for 197 of 245 sentences, so only those sentences' planner and Jev answers were
  asked again. The free Gemini tier allows 500 requests a day per project; a full re-record uses most of that.

**74. Where a chat turn's time goes, and what was changed.** A turn took 5-10 s, sometimes more. Timed stage by
stage on 12 live turns, and each API called on its own:

- **Almost all of it is the two Gemini calls.** Decompose took 1.5-4.2 s and the planner 2.0-5.3 s. Gemini answers a
  two-word "ok" in 1.2-2.9 s, so that is its own response time; keeping its connection open longer changed nothing.
  Our code outside the stages adds under 0.2 s; search, fuse and validate under 50 ms; the backend 0.03-0.46 s.
- **Jev's connection was reopened on every turn (fixed).** httpx closes an idle connection after 5 s, and a new one to
  TypeSafe costs 0.7-1 s (~330 ms round trip). Jev took 1.1-1.5 s after 10 s idle; kept open for 120 s, 0.44-0.57 s.
- **The first turn after a restart was slow (fixed).** The embedding model was cold (3-5.5 s) and every connection new.
  At startup the AI layer now embeds one text, looks up the Gemini model (no generate quota) and opens Jev's
  connection, in the background; a failure is only logged.
- **The planner's thinking stays "high".** "minimal" took the median planner call from 3.7 s to 2.2 s but plan accuracy
  from 90.3% to 73.1% (refusal correctness 91.8% -> 78.0%): the planner broke the answer rules, 23 answers filling a
  value and listing it as missing, 12 contradicting their outcome. "low" was no better: on the 169 labelled sentences
  both recorded (the daily quota ran out 13 short), 77.5% right against 90.5% for "high", with 28 answers the validator
  refused against 3. `ModelRequest.thinking` now takes "low" and "medium" as well as high (True) and minimal (False).
- **The free tier allows 15 requests a minute** per project and model, as well as 500 a day. A recording run at 6
  parallel calls hits it, and so can a chat turn made while one runs.

**75. Jev is on by default, and a Gemini key never leaves the client in an error.**

- **The chooser is on.** Chosen for speed: with Jev's shortlist the planner's call took a median of 2.7 s instead of
  4.9 s, and Jev about 0.5 s, so a reply is about 1.5-2 s faster. The price is 1.1 points of plan accuracy (89.1%
  against 90.3%), mostly Jev answering "none" for a request an action does fit. Without `AI_LAYER_TYPESAFE_API_KEY`
  it stays off and chat plans from every candidate; `AI_LAYER_CHOOSER_ENABLED=false` turns it off with a key.
- **The key leak.** Google's error text can name the key it refused ("Consumer 'api_key:AQ.…' has been
  suspended"), and that text went into the error, and from there to logs, the chat trace and the reply.
  `GeminiModel` now replaces the key, and anything Google labels `api_key:`, with `[hidden]` before the text goes
  anywhere. Logging's own redaction only hides fields by name, so it could not catch this.

**76. Decompose must give the same, right number of intents every time.** The number of intents is the plan's
skeleton: each intent is searched on its own, the chooser picks one capability per intent, and the planner writes
one step per request. A merge silently drops an action, a split adds one nobody asked for, and a count that changes
between runs makes the same message behave differently. The rest of the eval has only single-request sentences
(the 8 it used to split were one request each, merged by decision 73), so none of this could show up.

- **Tested on 22 Sep 2026** with 33 sentences, 3 runs each, our exact prompt and checks. Temperature is Gemini's
  default of 1.0, so the wording varies (only 1 of 15 single-request sentences came back word for word the same
  three times); the count must not.

  | model | same count in all 3 runs | right count in every run |
  |---|---:|---:|
  | `gemini-3.1-flash-lite` (ours) | 33 / 33 | 33 / 33 |
  | `openai/gpt-oss-120b` (Groq) | 31 / 33 | 29 / 33 |
  | `qwen/qwen3.8-27b` (Groq) | 27 / 33 | 21 / 33, and 13 calls failed |
  | `gemini-3.5-flash-lite` (1 run) | — | 14 / 18 multi-request |

  The others mostly merge: "Ahmed Raza ki fees 2000 aur Hamza ki 3000 cash mili" became one intent every time on
  gpt-oss, so one payment would never be recorded.
- **The check.** `eval/test_decompose_repeat.py` decomposes every sentence in `request_counts.jsonl` 3 times, each
  run its own recorded answer (`recordings/decompose_repeat.json`), and fails on any wrong count. `make
  measure-ci` replays it, so a changed decompose prompt or model has to pass it again. Live calls are paced to 12
  a minute, under the free tier's 15.
- **What it found straight away**, all three now fixed by the rules below (the check reported each one as it
  started passing; `KNOWN_GAPS` is empty again):
  - "Ahmed Raza ki fees 2000 aur Hamza ki 3000 cash mili" becomes one intent every run, so one of the two
    payments would never be recorded;
  - "send reminders to class 5 blue and class 6 green" gave 1, 2, 2 intents in three runs;
  - "Usman ki challan wapas aa gayi hai, 12000, record kar do" left Urdu in one run's intent ("Usman ki
    challan"), which the NOT_ENGLISH check refuses.

- **The fix.** Decompose now splits only for two different actions, or the same action on two different records
  (two students, two classes, two sections, two invoices). A reason, a condition or a second fact about the same
  record never becomes its own intent, and several facts about one record stay one problem. A first version of the
  rule split a request from its reason ("jin ka fee overdue hai unhe chase karna hai, list do" became a list and a
  reminder) and cost a point of plan accuracy, so it was tightened twice, each time against the sentences it had
  to keep right. After it: **33 of 33 sentences right in all three runs**, and with Jev choosing first the gap to
  the planner alone closed to nothing.
- **What it cost.** Re-recording every answer: 245 decompose + 99 repeat runs + 150 planner + 190 planner-after-Jev,
  spread over two days of the free tier's 500 calls a day, paced with `EVAL_CALLS_PER_MINUTE=12` so that refused
  calls stop wasting quota.
- **Comparing models.** `AI_LAYER_GROQ_API_KEY` is accepted for such comparisons; chat never uses it.

**77. A plan cache by meaning would be unsafe; a Roman Urdu translator cannot replace decompose.** Two ways to
save a model call, both measured with the embeddings service alone (no model quota), both rejected.

- **Caching plans by meaning.** The words that decide the outcome are the ones a similarity score ignores. Pairs
  that must never share a plan: August vs September invoice 0.954, WhatsApp vs SMS 0.946, class 5 vs class 6
  0.938, 2000 vs 3000 0.937, "send" vs "do not send" 0.899. Pairs that safely could share one: 0.740 for the same
  request worded differently, 0.856 Roman Urdu vs English, 0.858 a typo. The two groups overlap completely, so no
  cut-off separates them. Of all 29,890 pairs of eval sentences only 2 are above 0.95. What is worth doing
  instead: match the existing exact cache after lower-casing and trimming, answer a few fixed phrases in code
  with no model call, and cache retrieval by meaning (safe: the planner still reads the real message).
- **A translator in place of decompose** (hasyarshad/roman-urdu-translator, an 81M-parameter Roman Urdu <-> English
  model, 0.2 s a sentence on this machine, direction forced to English). It would remove one of the two Gemini
  calls. Measured end to end, with the translation as the only search query and the planner still reading the
  original message:

  | | recall@30 | plan accuracy | refusal correctness |
  |---|---:|---:|---:|
  | decompose's intents (today) | 95.4% | 89.7% | 92.2% |
  | the translator's sentence | 92.6% | 87.4% | 90.6% |
  | the translation, direction left on auto | 88.6% | not measured | not measured |
  | the sentence as typed, no English | 82.3% | not measured | not measured |

  The planner recovers part of the loss (recall -2.9, plan accuracy -2.3) because it reads the Roman Urdu itself,
  and **splitting turned out not to matter for retrieval**: with one query, every needed action was among the 30
  candidates for 13 of 13 multi-request messages (decompose's intents managed 12). What the translator loses is
  the school vocabulary: "credit raise karo" -> "Increase credit", "ye baqaya wasool nahi hoga" -> "This remaining
  will not be accepted", and four write-off messages stopped reaching their capability. Replacing "baqaya",
  "jurmana", "nadehindagan", "wasooli", "tajweez" and "raqam" in the translation before searching won two of them
  back (recall 93.7%), still 1.7 points short. Kept as a fallback for when the model is unreachable, where 92.6%
  beats refusing everything; not as a replacement, which would cost about one message in 45.

**78. The search query can come from a local translator instead of a model call** (branch `translator-decompose`,
23 Sep 2026). `AI_LAYER_DECOMPOSE_SOURCE=translator` replaces the Gemini decompose call with one HTTP call to a
translator running beside the AI layer: it answers `{"text": ...}` with `{"english": ...}`, direction forced to
English, and that one sentence is the only intent. Gemini still plans, still from the message as typed.

- **Why.** It removes one of the two Gemini calls: measured on the same 10 messages within a minute of each other,
  the Gemini English step took a median of 3.64 s and the translator 0.25 s. A whole turn with the translator and
  the chooser measured 6.2 s median (translate 0.25, search 0.24, choose 0.45, plan 5.06; the backend adds
  0.1-0.5), against about 10 s for the same work with the Gemini step. It also halves the model quota a message
  uses, and it keeps working when the model is unreachable.
- **What it costs.** Measured without the chooser (decision 77): recall@30 95.4% -> 92.6%, plan accuracy
  89.7% -> 87.4%, refusal correctness 92.2% -> 90.6%. With the chooser the accuracy is not measured yet.
- **What is given up.** No splitting into intents, and no checks on the result (no "every name is in the message",
  no "no Urdu left"). Splitting costs retrieval nothing (13 of 13 multi-request messages still had every needed
  action among the 30 candidates), but nothing now catches a bad translation before it reaches search.
- **The designs side by side**, with every measurement and how to run each one: `docs/translator-designs.md`.
- **Where it lives.** `app/decompose/translator.py` implements the same `IntentSource` the planner already took,
  so the pipeline is unchanged; `TranslatorDecomposer` keeps its connection open and warms up at startup like the
  other clients. The service is hasyarshad/roman-urdu-translator (81M parameters, weights on Hugging Face, not in
  this repo); readiness says which source is in use. Default stays `gemini`.

**79. The chooser reads the message as typed when the intents are a translation, and the fast combination still
costs too much** (branch `translator-decompose`). Jev sees only the English, so a weak translation ends the turn:
with the translator's sentence it answered "none" for 20 of 169 labelled messages, against 9 of 172 with
decompose's intents. Giving it the message as well (`CapabilityChooser(with_message=True)`, set when
`AI_LAYER_DECOMPOSE_SOURCE=translator`) takes it back to keeping the expected action for 92.9% of them, from 87.6%.

Measured end to end, all four ways:

| | plan accuracy | refusal correctness |
|---|---:|---:|
| Gemini's intents + Jev (the default) | 89.7% | 92.2% |
| the translator, no chooser | 87.4% | 90.6% |
| the translator + Jev, with the message | 85.1% | 87.8% |
| the translator + Jev, translation only | 81.1% | 85.7% |

The errors compound: the translator loses 2.3 points on its own, and what it hands Jev loses 2.3 more. The
fastest setup (6.2 s a turn measured, against about 10 s with a slow Gemini) is 4.6 points behind the default, so
the default stays Gemini's intents. The branch keeps the translator for a fallback, and as the starting point if
the translation quality improves: a hand-written map for school words already won back a point of recall
(decision 77).

## Open questions

- **Is Jev's accuracy cost worth it?** It is on for speed (decision 75), 1.1 points behind in plan
  accuracy. Watch the requests it answers "none" for; `make measure-jev` shows them.
- **The free tier's terms.** On the free tier, Google may use requests to improve its products; on a
  paid tier it does not. Every sentence, with any student's name in it, goes into the prompt. Before
  real school data, the key needs a paid (billing-enabled) project, redaction (below), or both.
- **Daily quota.** The free tier allows 500 requests a day per project and model, reset at midnight
  Pacific time. Recording the eval alone takes about 465 calls, and each new chat sentence takes two.

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
- **Speed.** Through the CLI a new sentence took about 10 seconds end to end (15-60 s before decompose
  stopped thinking, decision 67): a new process for each of the two calls, plus retrieval. The Gemini
  SDK (decision 69) removes the process start and reuses connections; the new time is not measured
  yet. Answers, choices and confirmations take well under a second, because they make no model call,
  and the plan cache makes a repeated sentence fast.
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
- **The 75 contract sentences score lower than the 100 written here** (see the report), so the eval
  is a little kinder than new sentences would be. Phase 7 should add sentences from real staff.
- **The other two docs still use old wording.** `docs/POC_Implementation_Plan.md` and
  `docs/Preflight_Implementation.md` still show snake_case example ids and "temperature 0". The
  preflight doc also shows `OUT_OF_SCOPE` coming from resolution (see decision 19). Only CLAUDE.md
  was updated.
