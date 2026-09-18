# Project structure

Where everything lives and what each file is for, in plain words. Update this file in the same
change that adds, moves or removes a file.

**Last updated:** Phase 7 (14 Sep 2026).

---

## The big picture

The git repository is the folder above `self_aware/`, which also holds the use cases (`modules/`) and
the planning contracts. CI for the AI layer is at the repository root:
`.github/workflows/ai-layer.yml` (backend tests, AI layer tests, and the measure regression run). It
runs only when `self_aware/` changes.

```
self_aware/
├── README.md              start here: what this is, how to run it, phase status, decisions
├── ARCHITECTURE.md        who does what: the AI layer, agent-gateway and school-app, and why
├── STRUCTURE.md           this file
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
    ├── tests/             unit, integration (Docker), build checks (embeddings), model checks (Claude CLI)
    └── eval/              labelled sentences, recorded model answers, the retrieval eval and the four numbers
```



### The one rule that keeps it modular

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
product, you replace those edges and leave the engine alone. [ARCHITECTURE.md](ARCHITECTURE.md)
explains the split, and why.

---



## `docs/` — the specification


| File                          | What it has                                                                                                                                                                                               |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CLAUDE.md`                   | The rules: architecture, the twelve invariants, the repository layout (matches this file), capability ids, the plan contract, the Claude CLI, embeddings, build-time assertions, error codes, conventions |
| `POC_Implementation_Plan.md`  | The seven phases and each one's "done when"                                                                                                                                                               |
| `Preflight_Implementation.md` | The design preflight and execute were built from (Phases 2 and 3). Where the code differs, README decisions 17–36 say so                                                                                  |




## `docker/` — local infrastructure


| File                                             | What it has                                                                                                                                                                                                                                                                                             |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `docker-compose.yml`                             | Two services. `postgres`: Postgres 16 + pgvector on 127.0.0.1:5433. `embeddings` (optional profile): BGE-M3 at a pinned revision, served by Text Embeddings Inference on 127.0.0.1:8083, its batch capped at 2048 tokens so it fits in Docker's memory. Each has a named volume; the model's is ~2.3 GB |
| `postgres/initdb/01-roles-schemas-extensions.sh` | Runs once, when the database volume is first created. Installs `vector`, creates the two login roles, gives each its own schema, and gives the AI layer role nothing on business data. The integration tests on both sides mount this same file                                                         |




## `openapi/` and `snapshots/` — generated, checked on both sides


| File                            | What it has                                                                                                                                                                                                                       |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `openapi/agent-gateway.json`    | **Generated. Do not edit.** The OpenAPI description of `/agent/`**: request and response shapes, and for preflight and execute every error status with the codes it carries. The AI layer's Pydantic models are generated from it |
| `snapshots/agent-metadata.json` | **Generated. Do not edit.** Exactly what `GET /agent/metadata` serves: every capability with its version. The AI layer's build checks and tests read it without a running backend                                                 |


Both are rewritten by `make contracts`. The backend's `OpenApiContractIT` and `AgentMetadataSnapshotIT` fail when either is stale.

## `scripts/` — whole-stack checks

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



## `backend/` — the Spring Boot backend


| File                                | What it has                                                                   |
| ----------------------------------- | ----------------------------------------------------------------------------- |
| `pom.xml`                           | Parent build: Spring Boot 3.5.16, Java 21, springdoc version, the two modules |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven wrapper: downloads Maven 3.9.16 on first use, so nobody installs Maven  |




### `backend/agent-gateway/` — generic, reusable

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
| `spi/EntityResolver.java`, `EntityMatch.java`                                                                        | What the host app implements for each entity type: the user's words in, every matching record the user may see out, each with an id, a label and what tells it apart                                                                                                                                                                                     |
| `spi/TemplateFormatter.java`                                                                                         | How a value reads inside a confirmation (money, dates, names); the host may supply one                                                                                                                                                                                                                                                                   |
| `spi/UserContext.java`, `UserContextResolver.java`                                                                   | Who a request is for (id, roles, scope), and how the host app works it out from the request                                                                                                                                                                                                                                                              |
| `spi/CapabilityPolicy.java`                                                                                          | Which capabilities a user may use; the host app decides                                                                                                                                                                                                                                                                                                  |
| `spi/AuditTrail.java`, `AuditEvent.java`                                                                             | Where execute records what it did (the host owns the table): append an event; find a write that already succeeded under an idempotency key. An event holds the sentence, ids, labels, counts and error code, never a token                                                                                                                               |
| `registry/CapabilityScanner.java`                                                                                    | Reads the annotations into entries. Takes parameter names, types and whether they are required from the request record, notes where the request and the signed-in user go in the handler's arguments and what the handler returns, and reports every rule a single entry breaks                                                                          |
| `registry/RegistryRules.java`                                                                                        | The rules that need the whole registry or the app's beans: precondition, count and resolver beans, symmetric siblings, producible placeholders, confirmations without gaps, and a handler returning the facts and count execute reads. Bean rules skip capabilities that are not implemented                                                             |
| `registry/CapabilityVersioner.java`                                                                                  | Version = SHA-256 of the entry's canonical JSON (every field but the version, keys sorted)                                                                                                                                                                                                                                                               |
| `registry/CapabilityRegistryBuilder.java`                                                                            | Scan, apply every rule, version. Throws with every problem listed at once                                                                                                                                                                                                                                                                                |
| `registry/CapabilityRegistry.java`                                                                                   | The built registry: every capability, sorted by id, immutable                                                                                                                                                                                                                                                                                            |
| `registry/RegisteredCapability.java`                                                                                 | One capability as the backend knows it: public metadata, plus the handler, its request record's fields, its argument positions, what it returns and whether it is implemented, which never leave the backend                                                                                                                                             |
| `registry/CapabilityRegistryException.java`                                                                          | "The registry is invalid", with the list of problems                                                                                                                                                                                                                                                                                                     |
| `registry/TemplatePlaceholders.java`                                                                                 | Finds `{placeholders}` in templates, spots malformed braces, and fills them (plain substitution)                                                                                                                                                                                                                                                         |
| `metadata/AgentMetadataController.java`                                                                              | `GET /agent/metadata` and `GET /agent/metadata/versions`                                                                                                                                                                                                                                                                                                 |
| `metadata/CapabilityMetadata.java`                                                                                   | One capability as the AI layer sees it: full detail, no URL                                                                                                                                                                                                                                                                                              |
| `metadata/ParamMetadata.java`, `ParamType.java`                                                                      | One input: name, type (string, integer, decimal, boolean, date), list or not, required, meaning, resolver, label, allowed values, default                                                                                                                                                                                                                |
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




### `backend/school-app/` — the school application

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
| `student/agent/StudentResolver.java`                                             | A student by name or admission number, in the user's branch; namesakes told apart by section                                                                                                                            |
| `fee/overdue/FeeOverdueController.java`, `FeeOverdueQuery.java`                  | `fee.overdue.list` (read): who owes what, by school, class, section or student; the query checks its scope has exactly its own target                                                                                   |
| `fee/overdue/FeeOverdueService.java`, `FeeOverdueResponse.java`                  | The list: one row per student with overdue fees, filtered by age band and minimum amount, with the total                                                                                                                |
| `fee/reminder/FeeReminderController.java`, `FeeReminderRequest.java`             | `fee.reminder.send` (group write, counted): remind every overdue family in a section. The channel reads as "WhatsApp", "SMS" or "email"                                                                                 |
| `fee/reminder/FeeReminderRepository.java`                                        | Who a section's reminder reaches: the one query both the count and the send use, with "overdue" written once; logs a reminder; counts every guardian who owes                                                           |
| `fee/reminder/FeeReminderService.java`, `FeeReminderResponse.java`               | The send: one Queued reminder log entry per guardian reached, what they owe, and how many the channel did not reach                                                                                                     |
| `fee/reminder/FeeReminderAgentBeans.java`                                        | `section_has_defaulters`, `channel_reaches_defaulters`, and the reminder count (with the outstanding total and "5 guardians" in words)                                                                                  |
| `fee/payment/FeePaymentController.java`, `FeePaymentRequest.java`                | `fee.payment.record` (single write): money received against one invoice. The route reads as "in cash" or "by bank challan"                                                                                              |
| `fee/payment/FeePaymentService.java`, `FeePaymentResponse.java`                  | Records the payment with the next gapless receipt number, after locking the invoice and checking its balance again                                                                                                      |
| `fee/invoice/InvoiceResolver.java`, `InvoicePhrase.java`                         | An invoice by number, or by student and month in English or Roman Urdu; candidates say what is still owed                                                                                                               |
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
| `agent/AgentMetadataSnapshotIT.java`                                   | `snapshots/agent-metadata.json` matches what the backend serves                                                                                                                                                                                                                                                                                                                                                   |
| `database/DatabaseMigrationIT.java`                                    | All six migrations run clean, pgvector works, and the seed has the exact shape later phases rely on                                                                                                                                                                                                                                                                                                               |
| `fee/invoice/InvoicePhraseTest.java`                                   | Invoice words: names, months, years, Roman Urdu particles (no Docker)                                                                                                                                                                                                                                                                                                                                             |
| `platform/agent/SchoolWordingTest.java`                                | Whole-word matching, exact names first, rupees, dates, display names, plurals (no Docker)                                                                                                                                                                                                                                                                                                                         |
| `support/SchoolPostgresContainer.java`, `PostgresIntegrationTest.java` | One pgvector container per test run, and the base class that boots the backend against it signed in with a test token                                                                                                                                                                                                                                                                                             |
| `support/CommittedJson.java`                                           | "This committed JSON file must equal what the backend serves", or rewrite it with `-Dcontract.update=true`                                                                                                                                                                                                                                                                                                        |




---



## `ai-layer/` — the FastAPI service


| File                                        | What it has                                                                                                      |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `pyproject.toml`                            | Dependencies, and pytest (markers `integration`, `embeddings`) / ruff / mypy settings (mypy also checks `eval/`) |
| `uv.lock`                                   | Exact versions of every dependency (uv writes it; commit it)                                                     |
| `.python-version`                           | Python 3.12                                                                                                      |
| `migrations/0001_capability_index.sql`      | The `capability_index` table from the plan                                                                       |
| `migrations/0002_capability_index_sync.sql` | Adds what sync and siblings need: `version`, `disambiguate_from`, `embedding_model`, `synced_at`                 |




### `ai-layer/app/` — the service code


| File           | What it has                                                                                                                                                                                                       |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `__main__.py`  | `python -m app` starts the server                                                                                                                                                                                 |
| `main.py`      | `create_app()`: logging, resources opened at startup and closed at shutdown, middleware, routes                                                                                                                   |
| `resources.py` | Opens the index database (running migrations if enabled), the gateway and embeddings clients, metadata sync, the retriever and chat (when the Claude CLI is found); starts the sync loop and stops it at shutdown |


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


| File            | What it has                                                                                                                                                                                                          |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `runner.py`     | `StructuredModel` (the interface), `ModelRequest`, the errors, and the pinned model ids: `claude-haiku-4-5-20251001` for decompose, `claude-sonnet-5` for the planner                                                |
| `claude_cli.py` | `ClaudeCliModel`: one headless `claude -p` subprocess per call (pinned model, JSON schema, no tools, no MCP, no settings, no session, empty working directory, prompt on stdin, timeout), and `find_desktop_app_cli` |


`app/decompose/` — model call 1


| File               | What it has                                                                                                                                                                                                             |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `decomposer.py`    | `Decomposer.decompose(sentence)`: 1-3 English intents with the names each carries, the output schema, and `check_decomposition` (schema, names quoted from the sentence and kept in their intent, no untranslated Urdu) |
| `system_prompt.md` | Decompose's instructions; the glossary is filled in at startup                                                                                                                                                          |


`app/planning/` — model call 2, and the pipeline


| File               | What it has                                                                                                                                               |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `outcomes.py`      | What planning ends in (`PlannedSteps`, `NeedsInput`, `Refusal`) and the strict shape of the planner's answer, with its JSON schema                        |
| `planner.py`       | `Planner.plan(...)`: builds the prompt (sentence, intents as a retrieval aid, today, candidates with their parameters) and validates the answer           |
| `system_prompt.md` | The planner's instructions                                                                                                                                |
| `service.py`       | `SentencePlanner.understand(sentence, allowed, session_id)`: decompose → retrieve → plan → validate. No candidates means a refusal without a planner call |
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




### `ai-layer/domain/school/`


| File          | What it has                                                                                                |
| ------------- | ---------------------------------------------------------------------------------------------------------- |
| `glossary.py` | A short glossary of office words (challan, baqaya, jurmana, wasooli, …) for both prompts                   |
| `calendar.py` | The school's time zone (`Asia/Karachi`) and `school_today()`, for the planner's "today" and the plan cache |




### `ai-layer/scripts/`


| File                         | What it has                                                                                                                                            |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `generate_gateway_models.py` | Regenerates `app/gateway/models.py` from the OpenAPI contract with fixed options (part of `make contracts`)                                            |
| `extract_eval_corpus.py`     | Rewrites `eval/retrieval/data/contract_sentences.jsonl` and `distractors.jsonl` from `planning-contracts/dist/routing-index.json` (`make eval-corpus`) |




### `ai-layer/tests/`

The folder decides the marker:

- `integration/` needs Docker;
- `build_checks/` needs `make embeddings-up`;
- `model_checks/` calls the real models through the Claude CLI.

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
| `unit/test_claude_cli.py`                        | With a stand-in `claude` script: the prompt goes in on stdin and never on the command line, the flags (pinned model, schema, no tools, never `--bare`), error results, missing structured output, stderr never echoed, no thinking budget for a call that asks for none, the timeout, finding the newest desktop-app CLI                                                                                                                                                                                                                                            |
| `unit/test_decomposer.py`                        | The pinned model and glossary are used, without thinking; a name the user never wrote, a changed name, untranslated Urdu and anything outside the schema are refused                                                                                                                                                                                                                                                                                                                                                                                                |
| `unit/test_plan_validator.py`                    | A good two-step plan with versions from the metadata; a hallucinated id; ids outside the allow-list or candidates; invented, duplicate and missing parameters; defaults; forms, types, allowed values, dates; words and amounts not in the sentence; earlier-step facts; step limits; refusals and `needs_input`; contradictory or unknown shapes                                                                                                                                                                                                                   |
| `unit/test_planning_service.py`                  | With scripted models: retrieval searches with the English intent, the planner sees the sentence, the intents as a retrieval aid, today and the candidates; nothing retrieved means no planner call; the allow-list holds; a traced run shows all seven understanding stages, and a rejected plan is a failed stage listing the broken rules                                                                                                                                                                                                                         |
| `unit/test_chat_orchestrator.py`                 | With a scripted backend and planner: a read in one turn; a write after yes; an ambiguous name resumed without planning; a missing value filled; cancelling runs nothing; an expired token and a moved count confirmed again; a failed precondition in the backend's words; a name not found asked up to three times; the plan cache; model failures not cached; a new sentence replacing a waiting plan; sessions per user; an answer after the session expired; a stale version; the stages a traced question, choice and confirmation show, with no token in them |
| `unit/test_chat_answers.py`                      | Yes and no words, choosing options, reading amounts, dates, allowed values and free text; the plan cache key and eviction                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `unit/test_measure_logic.py`                     | Recordings (asked once, replayed without a model, refused for a changed prompt or thinking setting, outages not recorded), every fault caught for its own reason, and how the four numbers are counted                                                                                                                                                                                                                                                                                                                                                              |
| `unit/test_chat_page.py`                         | `GET /` serves the page self-contained and locked to its own origin, inserts replies as text, can be turned off, and stays out of the API description                                                                                                                                                                                                                                                                                                                                                                                                               |
| `unit/test_chat_api.py`                          | `POST /chat` over HTTP: the reply's shape, exactly one kind of turn, 401 without a token or when the backend refuses it, 503 when chat or the backend is off; the trace comes back when asked, and not when the setting is off                                                                                                                                                                                                                                                                                                                                      |
| `unit/test_trace.py`                             | Stages kept in the order they start, with input, output and timing; a failed stage marked with its error; nothing kept outside a request; long text cut                                                                                                                                                                                                                                                                                                                                                                                                             |
| `model_checks/test_planning_with_real_models.py` | Real Haiku and Sonnet: a Roman Urdu sentence becomes a valid plan with names intact, a sentence matching nothing is refused, a two-part sentence becomes two steps in order                                                                                                                                                                                                                                                                                                                                                                                         |
| `integration/conftest.py`                        | The container and connection fixtures                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `integration/test_database_boundary.py`          | Invariant 1 in Postgres                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `integration/test_index_migrations.py`           | The index table matches the plan; edited, failed or unknown migrations are handled safely                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `integration/test_startup_and_readiness.py`      | The real startup path migrates the index and reports ready; with sync on, it fills the index from the backend's metadata before reporting ready, and the retriever finds the reminder                                                                                                                                                                                                                                                                                                                                                                               |
| `integration/test_capability_index_queries.py`   | Against Postgres + pgvector: sync upserts and deletes, dense ranking by cosine within the allow-list and embedding model, lexical ranking on any word with stemming, stop words match nothing, siblings                                                                                                                                                                                                                                                                                                                                                             |
| `build_checks/test_capability_descriptions.py`   | Against the live embeddings service: it runs pinned BGE-M3, and no two descriptions embed above 0.92 unless declared siblings. Fails, not skips, when the service is down                                                                                                                                                                                                                                                                                                                                                                                           |




### `ai-layer/eval/` — measuring the AI layer

Run with `make ai-eval` (every eval) or `make measure` (the four numbers); both need Docker and
`make embeddings-up`, and record any model answer not yet recorded. `make measure-ci` replays the
recordings without calling a model. None of this is part of `make ai-test`.


| File                                                | What it has                                                                                                                                                                                                   |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `conftest.py`                                       | Reuses the integration tests' throwaway Postgres; opens empty, migrated indexes that last the whole module                                                                                                    |
| `test_retrieval_recall.py`                          | The Phase 4 gates: recall@30 above 90% on the POC index, and among distractors for queries in English; whole clusters on both; Roman Urdu reported on its own                                                 |
| `test_retrieval_with_intents.py`                    | Retrieval as it really runs: every sentence decomposed by Haiku, the stress index searched with its intents, held to the same 90% gate                                                                        |
| `retrieval/intents.py`                              | Decomposes every eval sentence with Haiku, through the recordings                                                                                                                                             |
| `test_measure.py`                                   | Phase 7: prints the four numbers (all, English, Roman Urdu) and fails if one fell below the baseline; the validator catches every injected fault; a deliberately broken description shows up as a recall drop |
| `measure/pipeline.py`                               | Every case (175 labelled sentences, 70 to refuse) through decompose, retrieval on the stress index, the planner and the validator, keeping each stage's result                                                |
| `measure/recordings.py`                             | Recorded model answers in `eval/recordings/`: record mode asks only for what is missing, replay mode never calls a model; a changed prompt invalidates the recording                                          |
| `measure/faults.py`                                 | Thirteen ways to break a real planner answer (a made-up id, an invented parameter, a name the user never wrote, …) and the tally of what the validator caught                                                 |
| `measure/scores.py`                                 | The four numbers per language                                                                                                                                                                                 |
| `measure/report.py`                                 | The printed table and `reports/measure.md`                                                                                                                                                                    |
| `measure/baseline.json`                             | The numbers a run must not fall below (update with `EVAL_UPDATE_BASELINE=1 make measure`)                                                                                                                     |
| `measure/data/refusal_sentences.jsonl`              | 60 requests the POC must refuse, labelled by the contracts as routing to capabilities it does not publish: every other fee intent, and one per other module (generated)                                       |
| `measure/data/refusal_authored.jsonl`               | 10 non-requests and out-of-scope questions (greetings, weather, a joke), English and Roman Urdu                                                                                                               |
| `recordings/decompose.json`, `recordings/plan.json` | The recorded Haiku and Sonnet answers, before validation, committed so CI measures without a model                                                                                                            |
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



## Where the next phases go


| Phase         | Backend                             | AI layer                                                                                       |
| ------------- | ----------------------------------- | ---------------------------------------------------------------------------------------------- |
| After the POC | the real backend adds agent-gateway | Redis behind `SessionStore`, redaction before any model call, the SDK behind `StructuredModel` |


The "Repository layout" section of `docs/CLAUDE.md` shows this same structure, including these
future folders. Change both together.

---



## Recipes

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