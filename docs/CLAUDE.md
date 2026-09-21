# CLAUDE.md

Context for working on this repository. Read fully before making changes.

---

## What this is

A natural-language layer over a school management system. Staff type a sentence; the system figures out which action to take, confirms it, and runs it.

```
"class 5 blue ke defaulters ko whatsapp par reminder bhejo"
"how much did we collect this month"
```

This is a **proof of concept**. The goal is to prove the risky parts work — retrieval, planning, and safe execution — not to cover the product.

---

## Architecture in one picture

```
Chat UI
   │
   ▼
AI LAYER  (Python / FastAPI, port 8081)
   │  understands the sentence, builds a plan, talks to the user
   │  NEVER touches the database
   │
   ▼  POST /agent/preflight   POST /agent/execute   GET /agent/metadata
   │
BACKEND  (Spring Boot, port 8080)
   │  permissions, scope, preconditions, counts, execution, audit
   │
   ▼
Postgres + pgvector
```

Two services, one database. The AI layer has no datasource of its own except the vector index.

---

## The twelve invariants

These are not style preferences. Breaking one is a defect.

1. **The AI layer never queries the business database.** It has no repository for students, fees, or attendance. Its only database access is the capability vector index.

2. **A plan carries `capability_id`, never a URL or endpoint.** The backend resolves the endpoint from its own registry. If the plan named the URL, the AI layer could address any endpoint in the system.

3. **Confirmation text is built by the backend** from the capability's declared template and real counts. A model never writes it and never restates a number.

4. **The backend re-checks everything at execute** — permissions, scope, preconditions, versions — even though preflight already did. Preflight is for showing the user. Execute is enforcement.

5. **Preconditions run after parameters are resolved,** never before. "The invoice must be unpaid" is uncheckable until you know which invoice.

6. **Preconditions are re-checked inside the write transaction.** A human sat between preflight and execute; state may have moved.

7. **Scope goes in the SQL `WHERE` clause, not a filter afterwards.** A teacher searching "class 5" must not be able to distinguish "no such class" from "not your class."

8. **The count function and the handler share the same repository method.** Re-implementing the predicate is how you confirm 17 and send 14.

9. **Everything a model outputs is validated before use.** Parse into a typed record, reject unknown fields, verify every capability ID against current metadata. A parse failure triggers one re-plan, then a refusal.

10. **Never hold a database connection across a model call.** Model calls take 2–4 seconds. Release first — and in Python, never block the event loop on one.

11. **Intents are normalised to English** by the decompose step, whatever language the user typed. Retrieval is not a language model.

12. **Cache plans, never results.** Data must stay fresh.

---

## What must never enter the registry

Safeguarding and child-protection records, wellbeing and counselling, student medical records, behaviour incidents, individual education plans.

These are not filtered at query time. They are never annotated as capabilities, so no prompt and no plan can reach them. If a task asks you to add one, stop and flag it.

---

## Stack

| | |
| --- | --- |
| Backend | Java 21, Spring Boot 3.x, port 8080 |
| AI layer | Python 3.12, FastAPI, port 8081 |
| Package manager | `uv` |
| Database | Postgres 16 with the `vector` extension |
| Migrations | Flyway (backend), plain SQL (AI layer's index table) |
| LLM client | The Gemini API through Google's Gen AI SDK (`google-genai`), behind an interface — two call shapes do not need a framework |
| Embeddings | BGE-M3 at a pinned revision, served on CPU by Hugging Face Text Embeddings Inference in docker compose; the AI layer calls it over HTTP |
| Validation | Pydantic, `extra="forbid"` everywhere |
| Decompose model | Gemini 3.1 Flash-Lite, pinned as `gemini-3.1-flash-lite` |
| Plan model | Gemini 3.1 Flash-Lite, pinned as `gemini-3.1-flash-lite` (its own name, so it can move alone) |
| Session state | In-memory dict for the POC. Redis later — keep it behind an interface |
| Eval harness | pytest, imports the retrieval code directly |

**On the two languages.** The AI layer is Python because this is where iteration happens — prompts, retrieval tuning, evaluation. The cost is that request and response shapes are defined twice. Mitigate it: the backend publishes OpenAPI, and the AI layer generates its Pydantic models from that spec rather than hand-writing them. Do not let the two drift by hand.

Pin model version strings. Never use a floating alias — a silent model update changes plan behaviour with no deploy.

**On the model calls.** Both model calls go to the Gemini API with Google's Gen AI SDK (`google-genai`), in `app/llm/gemini.py`, using an API key (`AI_LAYER_GEMINI_API_KEY`). README decision 69 records the move from the Claude CLI.

- **One client, async.** One SDK client serves every call, through `client.aio`, so no call blocks the event loop and connections are reused. Close it at shutdown.
- **One request, one JSON object.** Each call sends the pinned model, the system prompt as the system instruction, the user-turn text as the only content, and the schema as `response_json_schema`. No tools, no chat history.
- **Gemini takes a subset of JSON Schema.** The transport rewrites each schema into it (`gemini_schema`); never loosen a schema to suit a provider. The validator is what enforces it.
- **There is no temperature setting.** Gemini 3 is left at its default of 1.0, as Google recommends. Consistency comes from the declared schema, the validator and the plan cache.
- **Thinking is a level.** A simple call sets `thinking=False` on its `ModelRequest`, which asks for `minimal`; the planner keeps `thinking=True`, which asks for `high`. Changing a level changes answers, so it means re-recording.
- **One timeout over the retries.** The SDK retries 408, 429 and 5xx (at most 3 attempts); `AI_LAYER_MODEL_TIMEOUT_SECONDS` bounds the whole call.
- **Error text.** Google's error messages never repeat the prompt, so they may be logged and shown to a developer. Never log the prompt itself: it holds the user's sentence.
- **Keep the runner behind an interface,** so changing provider touches one file.

**On embeddings.** Current PyTorch and ONNX Runtime ship no Intel-Mac builds, so BGE-M3 runs in a Linux container (`make embeddings-up`) rather than in-process. Retrieval and the description-similarity check call the same service. The 0.92 threshold means something only for this exact model, so the client refuses a service that reports any other model or revision. The container needs about 3 GB of Docker's memory, and its batch is capped at 2048 tokens so warm-up fits.

---

## Repository layout

Everything lives under `self_aware/`. Folders marked with a phase do not exist yet; they are created by that phase. README.md's "Where everything lives" explains every file.

```
/backend                    Java, Spring Boot, port 8080. Maven, two modules
  /agent-gateway            the gateway. Generic: never imports school code
    …/agent/annotation      @AgentCapability, @AgentParam, @AgentPrecondition, @AgentEffect,
                              @AgentNotImplemented, BlastRadius
    …/agent/spi             what the host app implements: PreconditionCheck, AffectedCount,
                              EntityResolver, TemplateFormatter, AuditTrail, UserContextResolver,
                              CapabilityPolicy
    …/agent/registry        annotation scanning, rules, versions (fails startup on a broken rule)
    …/agent/metadata        GET /agent/metadata, GET /agent/metadata/versions
    …/agent/session         GET /agent/session/capabilities (the user's allow-list)
    …/agent/plan            the plan contract, and its hash
    …/agent/step            what preflight and execute share: read a plan, resolve, validate, check, count
    …/agent/preflight       POST /agent/preflight: compose the confirmation, sign the token
    …/agent/execute         POST /agent/execute: re-check, run, verify and audit each step; replays
    …/agent/token           signing and verifying preflight tokens
    …/agent/web             plan and session ids on log lines; the signed-in user for handlers
    …/agent/error           error bodies and codes the AI layer branches on, with their statuses
  /school-app               the school backend: runnable, hosts the gateway
    …/school/fee            business controllers, and beside them their repositories, resolvers,
    …/school/student          precondition checks and counts, so a count and its handler share
    …/school/academic         one repository method (invariant 8)
    …/school/platform       sign-in, policy, scope, name matching, wording, the audit table, the clock
    …/resources/db          migration/ = Flyway schema (agent_audit too), seed/ = demo school (POC only)

/ai-layer                   Python, FastAPI, port 8081, uv
  /app
    main.py, resources.py   app factory; opens and closes the index pool, clients, sync loop, retriever
    /core                   settings, logging, the per-turn pipeline trace. Imports nothing else from app
    /api                    HTTP routes: health; POST /chat; GET / (the chat test page)
    /web                    the chat test page with its pipeline view: one static HTML file, a developer tool
    /gateway                client for /agent/*; models.py generated from /openapi
    /index                  the only database access: pool, migration runner, index queries
    /embeddings             client for the embeddings service; refuses an unpinned model
    /capabilities           the metadata snapshot, and the description-similarity check
    /sync                   polls versions, re-embeds what changed, keeps the catalog
    /retrieval              dense + lexical, RRF, sibling expansion, allow-list, cap
    /llm                    the Gemini runner behind an interface; pinned model ids
    /decompose              AI call 1: sentence → 1-3 English intents, names checked
    /planning               AI call 2, and the pipeline: decompose → retrieve → plan → validate
    /validation             deterministic checks on model output
    /orchestration          state machine, sessions, answers read without a model, plan cache
    /response               every reply the user reads: fixed wording around the backend's text
  /domain/school            school vocabulary: glossary (challan, haazri, baqaya), time zone
  /migrations               plain SQL for the capability index
  /tests                    unit/; integration/ (Postgres); build_checks/ (embeddings); model_checks/ (Gemini API)
  /eval                     labelled sentences, recorded model answers, the four numbers, reports

/openapi                    agent-gateway.json: the contract, exported by the backend
/snapshots                  agent-metadata.json: GET /agent/metadata, exported by the backend
/docker                     docker compose: Postgres 16 + pgvector; embeddings service (profile)
/scripts                    whole-stack checks: verify_phase0.sh … verify_phase7.sh
(repository root)
  .github/workflows/ai-layer.yml   CI: backend tests, AI layer tests, measure regression run
/docs                       this file, the implementation plan, the preflight design
```

**Generic versus school-specific.** The engine — `agent-gateway` and `ai-layer/app` — must stay free of school knowledge. Anything about schools goes in `school-app` or `ai-layer/domain/school`. Pointing the engine at another product means replacing those, not editing the engine.

---

## Capability metadata

Declared as annotations above the controller method so it cannot drift from the code.

**Capability ids come from the planning contracts.** The ids are the operation names in `planning-contracts/` (one file per use case in `modules/*/PlanningContracts/`, all of them listed in `planning-contracts/dist/capability-index.json`). They are dotted and lower-case: `fee.reminder.send`, `fee.overdue.list`, `fee.payment.record`. Never invent an id the capability index does not contain; if one is missing, the planning contract is written first.

The real `fee.reminder.send`, from `school-app/…/fee/reminder/FeeReminderController.java`:

```java
@AgentCapability(
    id = "fee.reminder.send",
    module = "fee",
    readOnly = false,                   // true exactly when blastRadius is NONE
    blastRadius = BlastRadius.GROUP,    // NONE, SINGLE, GROUP, BRANCH, ORGANISATION
    reverses = "",                      // id of the capability that undoes it; empty means irreversible
    description = """
        Sends a fee reminder by WhatsApp, SMS or email to the guardians of every student in
        a section who has overdue fees, each message carrying that family's own amount and
        due date. Use this for chasing overdue payments.
        Not for seeing who owes money without contacting anyone - use fee.overdue.list.
        """,
    disambiguateFrom = {"fee.overdue.list"}
)
@AgentParam(name = "section_id", resolver = "section", label = "section_name",
            meaning = "The section whose families with overdue fees are reminded, e.g. Class 5 Blue")
@AgentParam(name = "channel", defaultValue = "whatsapp",
            meaning = "How the reminder is delivered")
@AgentPrecondition(id = "section_has_defaulters",
            text = "The section must have at least one unpaid invoice past its due date",
            hint = "Nobody in this section has overdue fees right now")
@AgentPrecondition(id = "channel_reaches_defaulters",
            text = "At least one guardian with overdue fees in the section must have a contact for the chosen channel",
            hint = "No guardian with overdue fees in this section can be reached by that channel")
@AgentEffect(
    creates = "one reminder log entry per guardian reached",
    notifies = "the guardians of students with overdue fees in the section",
    // "This cannot be undone." is added by preflight, because reverses is empty
    confirmationTemplate = "Send a fee reminder to {guardians} in {section_name} by {channel}, "
            + "covering {total_outstanding} outstanding.",
    pendingTemplate = "Send a fee reminder to the families with overdue fees.",
    replyTemplate = "Sent a fee reminder to {guardians} in {section_name} by {channel}.",
    facts = {"guardians", "total_outstanding"}   // published by the count: "5 guardians", 71500.00
)
@PostMapping("/api/v1/fee-reminders")
public void send(@Valid @RequestBody FeeReminderRequest request) { ... }

@JsonNaming(SnakeCaseStrategy.class)
public record FeeReminderRequest(@NotNull Long sectionId, @NotNull Channel channel) { ... }
```

**Parameters come from the request record.**

- A handler takes at most one record. Each of its fields needs an `@AgentParam`, and each `@AgentParam` must name a field.
- The type (`string`, `integer`, `decimal`, `boolean`, `date`, or a list of one) is read from the field.
- Whether it is required is read from `@NotNull`, `@NotBlank`, `@NotEmpty` or a primitive type.
- For an enum, the allowed values are its constants.
- Preflight turns plan values into the field's own type with the application's Jackson, and runs the record's validation constraints. So the planner's view of an input cannot disagree with what the endpoint accepts.
- `resolver` and `label` always go together: the label is the template key the resolved record's name is published under.
- A list cannot have a resolver yet.

**A capability that is declared but cannot run** carries `@AgentNotImplemented`. Preflight refuses it with `NOT_IMPLEMENTED` before resolving anything, and the registry asks for no check, count or resolver beans for it. The marker is not published: retrieval and the planner must still tell it apart by meaning. The four confusable fee corrections are marked this way in the POC.

### Writing descriptions

This is the single biggest lever on quality. Two rules:

**Use the user's words, not the endpoint's.** "Sends a fee reminder," not "creates MessageLog entities."

**Always say what it is NOT,** naming the alternative. The fee module holds `fee.cancellation.raise`, `fee.credit.raise`, `fee.writeoff.propose` and `fee.latefee.waive`: cancel an unpaid charge, credit a charge already paid, write off a debt, waive a late fee. They are four near-synonyms with very different consequences, and the contrast lines are what keeps them apart in retrieval.

### Version

SHA-256 of the entry's canonical JSON: every field except the version, keys sorted, no whitespace. It is computed when the registry is built at startup, and never bumped by hand. Changing one character of one description changes that entry's version and no other. Plans stamp the versions they used; execute rejects a stale one.

---

## Plans and preflight

The AI layer sends a plan to `POST /agent/preflight`. The backend answers with the confirmation text and a signed token, or with an error code. Nothing is written.

```json
{"plan": {"plan_id": "p-81", "session_id": "s-12", "steps": [
  {"step": 1, "capability_id": "fee.reminder.send", "capability_version": "<sha-256 from metadata>",
   "params": {"section_id": {"raw": "class 5 blue"}, "channel": {"value": "whatsapp"}}}]}}
```

**A parameter is exactly one of three forms.**

| Form | For | Example |
| --- | --- | --- |
| `value` | a parameter without a resolver, given as is | `{"value": "whatsapp"}` |
| `raw`, optionally with `chosen_id` | a parameter with a resolver: the user's own words, untranslated | `{"raw": "class 5"}`; after `AMBIGUOUS_ENTITY`, `{"raw": "class 5", "chosen_id": "2"}` |
| `from_step` and `field` | a fact an earlier step publishes when it runs | `{"from_step": 1, "field": "receipt_number"}` |

- A left-out parameter takes its declared default.
- A plan has at most 3 steps, numbered from 1.
- `chosen_id` must be one of the records the same words match, so the AI layer cannot slip an id in.
- A step taking a fact from an earlier step is confirmed with its pending template; its checks and count wait for execute.

**Preflight runs in this order**, and each stage refuses with its own code:

1. **Read the plan against the registry.** Shape and capability ids (`INVALID_PLAN`), then versions (`STALE_VERSION`), then permission (`NOT_PERMITTED`), then `NOT_IMPLEMENTED`, then parameters (`INVALID_PLAN`).
2. **Resolve** every name, with scope in the SQL (`NOT_FOUND`, `AMBIGUOUS_ENTITY` with candidates). When a name matches several records, each is tried against the step's preconditions, in order, as if chosen. Only the matches that get furthest are offered. So a payment never offers an invoice that is already paid, and a reminder never offers a section with no defaulters. One match left is simply the record: the confirmation names it, or the check refuses it with its hint. This applies when it is the step's only unresolved name and nothing waits on an earlier step.
3. **Check** every precondition (`PRECONDITION_FAILED` with the hint).
4. **Count** each write with its `AffectedCount` bean, or 1.
5. **Compose.** Fill each write's template through the host's `TemplateFormatter`. Add "This cannot be undone." for a write with no `reverses`, and a warning for `BRANCH` or `ORGANISATION` writes. Several writes are numbered in one message.

Resolve, check and count share one read-only, repeatable-read transaction.

**The response** carries:

- `confirmation`, absent for a plan of reads;
- `requires_confirmation` and `warnings`;
- per step, the resolved ids and labels, the count and the line;
- `token` and `expires_at`.

**The token** is `base64url(payload) + "." + base64url(HMAC-SHA256(payload))`. The payload holds the plan's SHA-256, the resolved ids, the counts, the user id and the expiry: 5 minutes, never the user's words.

- The plan hash is computed from the plan's canonical JSON, the same way as capability versions.
- Execute receives the same plan and the token. It verifies the signature, the expiry, the user and the hash before anything else.
- The AI layer never opens a token.

---

## Execute

`POST /agent/execute` takes the confirmed plan, unchanged, with its token and the user's sentence.

**Refused as a whole, before any step runs.** An altered token or one for another plan or user is
`TOKEN_INVALID`; an expired one is `TOKEN_EXPIRED`. Versions, permission, `NOT_IMPLEMENTED` and
parameters are read again. A refusal here is an error response.

**Then each step, in order, in one serializable transaction of its own:**

1. A write that already succeeded under `session_id:plan_id:step` is answered from the audit trail as `replayed`, and nothing runs again. Reads always run again (invariant 12).
2. Each name is searched again inside the user's scope, and must still find the record the user confirmed (`OUT_OF_SCOPE`).
3. Values an earlier step published are filled in. The whole request is validated, and preconditions are checked again, inside the transaction (invariants 4 and 6).
4. A write is counted again. If the count moved materially from the confirmed one, the step is refused with `COUNT_CHANGED`: any change at or below 20 records, more than 5% above.
5. An audit `STARTED` event is appended, and the handler runs: the capability's controller method, called directly with the request record and, if it asks, the signed-in `UserContext`.
6. The result is verified. Every declared fact must be in what the handler returns, and a counted write must report exactly the count taken in step 4 (any other write, 1). Otherwise the step is `VERIFICATION_FAILED` and its writes roll back.
7. The reply template is filled with real values (invariant 3), and an audit `SUCCEEDED` event records the result.

A refused or failed step rolls back, is recorded as `REFUSED` or `FAILED` in a new transaction, and stops the
plan. Steps that already succeeded stay done: there is **no automatic rollback**. The 200 response gives
the outcome (`completed`, `partial` or `failed`), and for each step its status (`succeeded`, `replayed`,
`failed`, `not_run`), reply, count, data or error. A serialization conflict is retried up to 3 times, then `CONFLICT`.

**The audit trail** is the host's `AuditTrail`, the `agent_audit` table in school-app.

- It is append-only: a trigger refuses UPDATE, DELETE and TRUNCATE.
- Each event holds the sentence, ids and labels, the confirmed and actual counts, and the error code.
- A unique index allows one success per idempotency key for writes.

---

## Retrieval

**The index** is `ai_layer.capability_index`: one row per capability, holding its description, its
siblings, its version and the embedding model that produced the vector. It never holds business data
(invariant 1).

**Metadata sync** polls `GET /agent/metadata/versions` every 30 seconds.

- A capability whose version, or embedding model, differs from its row is fetched in full and re-embedded. A capability the backend no longer lists is deleted.
- The texts are embedded before a connection is taken (invariant 10).
- The full metadata is kept in memory as the catalog the planner reads.
- Readiness stays "not ready" until the first sync has filled the index.

**A query**, one per English intent (invariant 11), goes through these steps:

1. Embed every query first.
2. Search the allow-list two ways: dense (pgvector cosine, exact scan) and lexical (Postgres full text on any word of the query, ranked without length normalisation). Each goes 50 deep.
3. Fuse every list with reciprocal rank fusion: `score = Σ 1/(60 + rank)`.
4. Take capabilities in fused order, each followed straight away by its declared siblings. A group never splits: if it does not fit under the cap of 30, stop there.

The allow-list goes into the SQL, and expansion only uses siblings inside it. Each candidate carries
its score, its dense and lexical ranks, and `sibling_of`.

**The eval** (`ai-layer/eval/`, `make ai-eval`) is the gate for any change to retrieval or to a description.

- It uses 175 labelled sentences, from the planning contracts and written for the eval, with Roman Urdu glossed in English.
- It measures the POC index, and a stress index that adds every other planning-contract intent as a distractor.
- Gates: recall@30 above 90% on both (on the stress index, for queries in English), and every retrieved cluster member arriving with its whole cluster.
- Typed Roman Urdu is reported beside the gates. Never tune retrieval on the eval without saying so in the README decisions.

---

## Decompose and plan

**Decompose** (model call 1) turns the sentence into 1 to 3 English intents, in order.

- Each intent lists the names it carries, copied as the user wrote them.
- The output is refused when a name is not in the sentence, a name is changed in its intent, or an intent still holds Urdu function words.

**The planner** (model call 2) gets the sentence as typed, the intents labelled "a retrieval aid, not the plan", today's date in the school's time zone, and the candidates with their parameters. A parameter with a resolver is marked `looked_up_from_words`, with `looked_up_by`: the resolver's own `lookup()` text from the metadata, saying what the record is found by (for an invoice, the student's name, optionally the month, class and section, or the invoice number). The planner passes the user's words that fit it and adds none. The same text tells the user what to type when a name is not found. Version strings are never shown.

**The capability chooser** (an experiment, off by default, `AI_LAYER_CHOOSER_ENABLED`) sits between retrieval and the planner, in `app/choosing/`. TypeSafe's Jev (`jev-1.13.0`, pinned) gets one choice question per English intent, over the candidates plus "none", each described by what it does and needs; it answers with a probability per option and writes no text. The planner then sees only Jev's shortlist, and the validator holds it to that. An empty shortlist is a refusal; if Jev cannot be reached, the planner sees every candidate. `make measure-jev` compares both ways (README decision 72).

It answers with exactly one of:
- **a plan:** steps of capability id and parameters, each parameter as `words` (looked up), `value`, or `from_step` + `field`;
- **`needs_input`:** the capability and the missing required parameter names;
- **a refusal:** `no_matching_capability`, `not_a_request` or `too_many_actions`.

The model never writes a sentence the user reads.

**The validator** runs before anything uses the answer, and refuses the whole answer on any broken rule. It checks:

- the schema, with no extra fields;
- ids in the current metadata, in the allow-list, and among the candidates;
- parameters declared, not duplicated, required ones present unless they have a default;
- the right form for each parameter;
- types, allowed values and real dates;
- looked-up words and amounts quoted from the sentence;
- earlier-step fields among that step's facts;
- at most 3 steps.

It stamps each step's version from the metadata.

**When retrieval finds no candidate, the answer is a refusal and the planner is not called.**

---

## Chat

`POST /chat` takes the user's bearer token and one turn: a `message`, a `choice` or a `confirm`. It
answers with one of `answer`, `question`, `confirmation` or `refusal`.

- **Models run only for a new sentence**, and not when the plan cache has it. The key is the normalised sentence, the allow-list, every capability version and today's date.
- **Waiting for input resumes the same plan.** Nothing is planned again after the user answers:
  - a choice fills `chosen_id`;
  - a name typed again replaces `raw`;
  - a missing value completes the step the planner started.
- **Answers are read by plain code**: yes and no words, an option by number or name, a value by its type. What cannot be read is asked again.
- **A read-only plan runs without a confirmation.** A write waits in `AWAITING_CONFIRM` until yes. No, or a new sentence, drops it, and nothing runs.
- **An expired token (`TOKEN_EXPIRED`) or a moved count (`COUNT_CHANGED`)** is preflighted again from the same plan and confirmed again. Execute replays steps that already succeeded.
- **Every word the user reads with a number or a name in it comes from the backend.** Chat's own wording is fixed.
- **Sessions** belong to the user the backend names for the token. The allow-list is fetched every turn.
- **A waiting question or confirmation expires** after `AI_LAYER_CHAT_SESSION_TTL_SECONDS` (30 minutes). A late choice or yes/no gets `SESSION_EXPIRED` and nothing runs; a session id of another user gets the same reply, so nothing is revealed.
- **`"trace": true`** returns every stage the turn ran, with its input, output and timing, for the test page's pipeline view (`app/core/trace.py`). A stage records itself with `trace.stage(...)`. Traces go only to that caller and are never logged. The user's token and the preflight token never go in. `AI_LAYER_CHAT_TRACE_ENABLED=false` turns them off.

---

## Measure

`make measure` prints four numbers, for all sentences, for English and for Roman Urdu:

- **recall@30:** the expected capability is among the candidates retrieved with decompose's intents, on the stress index.
- **plan accuracy:** the planner's answer is one step, or a request for input, for the expected capability.
- **refusal correctness:** the system acts exactly when it should, across the labelled sentences and a set it must refuse.
- **validator catch rate:** faults injected into real planner answers that the validator refuses.

Model answers are recorded in `eval/recordings/` before validation, keyed by the exact system prompt.

- `make measure` asks the models only for missing answers.
- `make measure-ci` never calls a model: it is the CI regression run, and fails when a number falls below `eval/measure/baseline.json`.
- A changed prompt or glossary must be re-recorded. A description change needs no recording, and shows up in recall.

---

## Build-time assertions

Fail the build, not a runtime log:

- Every `@AgentPrecondition` id resolves to a `PreconditionCheck` bean
- `disambiguateFrom` is symmetric — if A names B, B must name A
- Every placeholder in a template is producible from a parameter, a resolved label, `count`, or a declared fact key
- Every capability with `blastRadius` above `SINGLE` declares an `AffectedCount` bean
- No two descriptions embed above 0.92 cosine without being declared siblings

Preflight and execute add four more of the same kind:

- Every `resolver` a parameter names has an `EntityResolver` bean
- A confirmation never has a gap: each placeholder is a required parameter, a parameter with a default, or the label of one. A fact counts only when the capability has an `AffectedCount` bean to supply it before running
- No parameter looks up a list of names
- A handler returns every declared fact, and a `count` when it has an `AffectedCount` or its reply uses `{count}`

The rules about beans skip capabilities marked `@AgentNotImplemented`. The gateway also refuses to start in an application that accepts unknown JSON fields.

**Where they run.**

- **All but the similarity check:** the gateway's registry checks them, together with the structural rules (id format, parameters matching the record, reads that confirm nothing, and so on), when the application starts. A broken rule stops the application, so every test that boots it fails, and the build with it. All problems are listed at once.
- **The similarity check:** it needs the embedding model, so it runs in the AI layer's `tests/build_checks` against `snapshots/agent-metadata.json`. The backend's own build fails when that snapshot is stale, so no description change skips the check.
- **Capability ids:** `CapabilityIdsMatchPlanningContractsTest` fails when an id is not in the planning contracts.

---

## Error codes

The AI layer branches on these, so they are part of the contract. `AgentErrorCodes` holds them with their HTTP statuses. At execute, a step's refusal arrives inside the 200 response with the same body. The error body always has `code` and `message`, and carries `step`, `param`, `candidates`, `precondition` and `hint` where they apply.

| Code | Status | Meaning | AI layer response |
| --- | --- | --- | --- |
| `INVALID_PLAN` | 400 | The plan does not fit the metadata: unknown capability or parameter, wrong type, missing value, or a value the endpoint would reject | A validator miss: re-plan once, then refuse |
| `STALE_VERSION` | 409 | Plan used an older capability version | Refresh metadata, re-plan once, then refuse |
| `NOT_PERMITTED` | 403 | Capability not allowed for this user | Refuse. Refresh the allow-list |
| `NOT_IMPLEMENTED` | 501 | The capability is declared but cannot run yet | Refuse: that action is not available yet. Do not re-plan |
| `OUT_OF_SCOPE` | 403 | At execute, a record the user confirmed is no longer one they may see. Preflight never sends it: a resolver cannot see outside the scope, so such a name is `NOT_FOUND` | Refuse with a vague message — do not confirm the entity exists |
| `PRECONDITION_FAILED` | 422 | Check failed | Refuse, show the hint. **Do not re-plan** |
| `AMBIGUOUS_ENTITY` | 422 | Name matched several records; `candidates` lists up to 10 | Ask the user to choose, then send the same words back with `chosen_id` |
| `NOT_FOUND` | 422 | Name matched nothing the user may see | Refuse |
| `TOKEN_EXPIRED` | 409 | Confirmation sat too long | Re-run preflight |
| `TOKEN_INVALID` | 403 | Token altered, or issued for another plan or user | Re-run preflight; never execute |
| `COUNT_CHANGED` | 409 | At execute, what the step would touch moved materially since it was confirmed; carries `confirmed_count` and `current_count` | Re-run preflight and ask again. Nothing was done |
| `CONFLICT` | 409 | At execute, the data kept changing while the step ran, even after retrying | Re-run preflight. Nothing of the step was written |
| `EXECUTION_FAILED` | 500 | At execute, the step broke while running; rolled back | Refuse, say it failed. Do not retry on your own |
| `VERIFICATION_FAILED` | 500 | At execute, the step did not do what it declares; rolled back. A bug | Refuse, say it failed. Someone must look |
| `UNAUTHENTICATED` | 401 | No valid user credential on the request | Refuse; the user must sign in again |

**Never re-plan after a precondition failure.** Looking for a route around a check that just failed is the behaviour we specifically do not want.

---

## Conventions

**Backend (Java)**
- Records for DTOs. Reject unknown JSON fields.
- A capability handler takes at most one request record. Session scope (branch, academic session) is never a field on it.
- Resolvers, checks and counts read the user's scope into their SQL `WHERE` clause, and use the injected `Clock` for "today", never `LocalDate.now()`.
- A check or count lives beside the repository its handler uses, and calls the same method.
- A handler returns a record: every declared fact, and `count` when execute verifies one. It may take the signed-in `UserContext` as its second parameter.
- SLF4J, never `System.out`.
- One integration test per capability: preflight → confirm → execute → verify.

**AI layer (Python)**
- Pydantic models with `model_config = ConfigDict(extra="forbid")`. Model output that carries an unexpected field is rejected, not tolerated.
- `async` throughout. Never block the loop on a model call.
- Pydantic models generated from the backend's OpenAPI spec, not hand-written.
- `structlog`, never `print`.
- Anything that talks to the index, a service or a model sits behind a small `Protocol`, so unit tests pass fakes instead of patching.
- Prompts live in `system_prompt.md` beside the code that uses them; the user's sentence only ever goes in the user turn.
- Real model calls are marked `model` and stay out of `make ai-test`.

**Both**
- Every AI call: structured output against a declared schema, validated before use. There is no temperature setting to rely on (see "On the model calls").
- Every log line for a request carries `plan_id` and `session_id`.
- Never log a user token.
