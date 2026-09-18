# POC Implementation Plan

Seven phases. Each has a definition of done you can actually check. Work them in order — later phases depend on earlier ones being measurably correct, not just present.

**Read** `CLAUDE.md` **first.** The twelve invariants there constrain every phase.

---

## Choosing the capability set

The set is not fixed yet. Two rules for picking it:

**Keep it small.** Five to eight. Every capability is an untested retrieval path, and the POC is meant to surface problems, not cover the product.

**Include near-synonyms deliberately.** This is the part that is easy to get wrong. A set of five well-separated capabilities will retrieve perfectly and prove nothing, because the real difficulty is telling similar things apart — `cancel_fee_invoice` versus `reverse_fee_charge` versus `write_off_fee` versus `waive_late_fee`. Four actions, one meaning in plain English, very different consequences.

So include at least one confusable cluster. The extra members do not need real handlers: give them full metadata and a handler returning `NOT_IMPLEMENTED`. They exist to make retrieval hard.

**Also aim for:** at least two reads and two writes, one write with a real count (something affecting many records) and one single-record write, and at least two entity types needing resolution.

**One role for the POC.** Call `GET /agent/session/capabilities` anyway and have it return every ID — the plumbing should exist even when the answer is trivial.

---



## Phase 0 — Scaffold

**Build:**

- `backend`: Spring Boot 3.x, Java 21, port 8080
- `ai-layer`: FastAPI, Python 3.12, `uv` for dependencies, port 8081
- `docker-compose`: Postgres 16 with the `vector` extension
- Flyway on the backend; a plain SQL migration for the AI layer's index table
- Seed data: 2 classes, 30 students, ~60 fee invoices with a realistic paid/unpaid mix, 1 user

**Done when:** both services start, migrations run clean, `SELECT '[1,2,3]'::vector` works, and the AI layer can reach `GET /agent/metadata`.

---



## Phase 1 — Registry and metadata

**Build:**

- `@AgentCapability`, `@AgentParam`, `@AgentPrecondition`, `@AgentEffect`
- A startup scanner that walks annotated controller methods and builds registry entries
- Version = SHA-256 of the serialised entry, computed at startup
- `GET /agent/metadata` — all entries
- `GET /agent/metadata/versions` — IDs and hashes only
- `GET /agent/session/capabilities` — returns capability IDs for the user
- The five build-time assertions from `CLAUDE.md`

**Do this with two or three capabilities first.** Prove the pipeline end to end before writing metadata for the whole set.

**Done when:**

- `GET /agent/metadata` returns every entry with full detail
- Changing one character of a description changes only that entry's version
- Removing a `PreconditionCheck` bean fails the build
- Making `disambiguateFrom` one-directional fails the build

---



## Phase 2 — Preflight (./Preflight_[Implementation.md](http://Implementation.md))

**Build:**

- `EntityResolver` interface + one implementation per entity type in the chosen set. **Scope inside the SQL**, not a filter afterwards
- `PreconditionCheck` interface + every check the chosen capabilities declare
- `AffectedCount` interface + a real count for the multi-record write. Default 1 elsewhere
- `POST /agent/preflight` running the four passes in order: resolve → check → count → compose
- Template rendering: facts map → filled string → joined message + warnings
- Signed token: `base64(payload) + "." + hmac(payload)`, payload holds plan hash, resolved IDs, counts, user, expiry. 5 minute TTL

**The count rule:** the count function and the handler must call the **same** repository method. Write the predicate once, use it twice. Re-implementing it is how you confirm 17 and send 14.

**Done when:**

- An unambiguous name resolves to an ID **and a label** — the label is what the confirmation text uses
- An ambiguous name returns `AMBIGUOUS_ENTITY` with candidates
- A failing precondition returns `PRECONDITION_FAILED` carrying its hint
- The confirmation text comes out fully rendered with the real count, e.g. `Send a fee reminder to 17 guardians in Class 5 Blue by WhatsApp. This cannot be undone.`
- A tampered plan hash fails token verification

---



## Phase 3 — Execute

**Build:**

- `POST /agent/execute`
- Per step, in one transaction: verify token → check versions → re-check permissions and scope → **re-check preconditions** → write audit row → call the service → verify result against declared effects → complete audit row
- Idempotency key: `session_id:plan_id:step`
- Audit table, append-only, holding the user's original sentence
- Delta rule: re-count inside the transaction; fail if it moved materially from the confirmed number
- Partial failure response — report what ran and what did not. **No automatic rollback**

**Done when:**

- A successful write produces an audit row and passes verification
- Changing the underlying state between preflight and execute produces `PRECONDITION_FAILED`
- Replaying the same idempotency key does not double-execute
- A count that moved materially between preflight and execute fails rather than proceeding

**Everything up to here has no AI in it.** If phases 1–3 are not solid, nothing above them can be.

---



## Phase 4 — Retrieval

**Build:**

- Metadata sync: poll `/versions` every 30s, pull changed entries, re-embed
- Index table, one row per capability, description only:

```sql
CREATE TABLE capability_index (
  capability_id text PRIMARY KEY,
  content       text NOT NULL,
  module        text NOT NULL,
  read_only     boolean NOT NULL,
  embedding     vector(1024),
  tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);
CREATE INDEX ON capability_index USING gin(tsv);
-- no vector index: exact scan is faster and more accurate at this size
```

- Dense search (pgvector) and lexical search (Postgres FTS), merged with RRF: `score = Σ 1/(60 + rank)`
- Sibling expansion: pull in `disambiguate_from` targets whether or not they scored
- Allow-list filter, cap at 30

**Build the eval set before measuring:** 150–200 sentences, each labelled with the correct capability. Include Roman Urdu. Include sentences targeting every member of the confusable cluster — those are the discriminations that matter, and a test set without them will report a flattering number.

**Done when:**

- Recall@30 is above 90% on the eval set
- A query matching any member of the confusable cluster retrieves the whole cluster, via sibling expansion
- Recall is reported separately for Roman Urdu

**Do not proceed until recall clears 90%.** Below that, no amount of planner work will help, and you will spend weeks debugging the wrong component.

---



## Phase 5 — Decompose and plan

**Build:**

- Decompose (Haiku 4.5): sentence → 1–3 intents, **output in English**, entity names passed through untranslated, short domain glossary in the prompt (`challan`, `haazri`, `baqaya`), cap at 3
- Planner (Sonnet 5): original sentence + intents + ~30 candidates → plan or refusal. Prompt must state that intents are a retrieval aid, not the plan
- Structured output against a declared schema on both. Temperature 0
- Validator as Pydantic models with `extra="forbid"`, plus explicit checks: IDs exist in current metadata, IDs in the allow-list, parameter names and types match, no invented parameters, versions stamped, step count ≤ 3

**Done when:**

- A Roman Urdu sentence produces a valid plan, with intents rendered in English and entity names untouched
- A sentence matching nothing produces a refusal, not a guess
- Injecting a hallucinated capability ID into a mocked model response is caught by the validator
- A two-part sentence produces two steps in the right order

---



## Phase 6 — Orchestrator and chat

**Build:**

- Session state machine: `Idle → Planning → AwaitingInput → AwaitingConfirm → Executing → Responding`
- **AwaitingInput resumes with the same plan.** Never re-plan after the user answers
- `POST /chat` returning one of: `answer`, `question`, `confirmation`, `refusal`
- Response builder: fill `replyTemplate` with real values. No model call
- Plan cache keyed on normalised text + allow-list hash + capability versions

Redaction is **out of scope for the POC**. Add it before any real student data goes near a model — the sentence sent to the model gets names replaced with placeholders, the map stays in the AI layer, and the backend receives the original text for audit.

**Done when:**

- The full loop works end to end for one read and one write
- An ambiguous entity produces a question, then resumes **without a second planner call**
- Cancelling at the confirmation leaves no audit row for an execution

---



## Phase 7 — Measure

**Build:**

- pytest harness in `/ai-layer/eval`. Since the AI layer is Python, it imports the retrieval and planning code directly rather than going over HTTP — faster runs and easier debugging
- Reports: recall@30, plan accuracy, refusal correctness, validator catch rate
- All four reported separately for Roman Urdu
- A regression run wired into CI

**Done when:** one command prints the four numbers, and a deliberately broken description shows up as a recall drop.

---



## Suggested order of work

Phases 0–3 first and completely. They are pure backend, fully testable, and every AI phase sits on top of them.

Then phase 4, and **stop at the 90% gate**. It is tempting to move on to the interesting part; don't. Retrieval quality sets the ceiling for everything after it, and a retrieval failure is invisible downstream — a confidently wrong plan looks exactly like a right one until it runs.

Then 5, 6, 7.

---



## What is deliberately not in the POC

Redaction, workflows, the semantic layer for free-form metrics, document extraction, multi-role permissions, Redis, module routing, embedding fine-tuning, automatic rollback, MCP.

Each was considered and deferred for a recorded reason. If one seems necessary mid-build, that is a signal worth raising rather than a gap to fill quietly.