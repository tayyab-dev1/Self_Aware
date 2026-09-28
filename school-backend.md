# The school backend stand-in (`backend/school-app`)

The real school backend does not exist yet. `school-app` stands in for it: a small Spring Boot
application that hosts `agent-gateway` and knows about schools — tables, seed data, annotated
controllers, and the resolvers, checks and counts next to them.

**It is the part meant to be thrown away.** `agent-gateway` is the product: a generic library that
makes any action safe for any host application, and its own test suite uses a made-up "notes" domain
so it can never grow school knowledge. `school-app` exists only to prove the gateway's invariants on
something real, and it is retired the day the real school backend adds the gateway as a dependency.

---

## What it is

| | |
| --- | --- |
| Artifact | `school-app`, "School backend application hosting the agent gateway" |
| Runs on | port 8080 by default (`BACKEND_PORT`), Java 21, Spring Boot 3.5 |
| Data | Postgres 16, the `school` schema, owned by the `sms_backend` role |
| Persistence | Spring JDBC (`JdbcClient`), not JPA, so scope and counts are visible in the SQL |
| Depends on | `agent-gateway` (the reverse is impossible: Maven's module direction forbids it) |

It is small but not fake: real tables, real SQL, real migrations, real integration tests against a
Testcontainers Postgres.

---

## What is inside

### The eight capabilities

Declared as annotations above controller methods, so metadata cannot drift from the code. Four of
the eight are marked `@AgentNotImplemented` on purpose — the confusable cluster that retrieval and
the chooser must still tell apart by meaning alone.

| Capability | Controller | Runs? |
| --- | --- | --- |
| `dashboard.main.read` | `dashboard/DashboardController` | yes |
| `fee.overdue.list` | `fee/overdue/FeeOverdueController` | yes |
| `fee.reminder.send` | `fee/reminder/FeeReminderController` | yes, counted write |
| `fee.payment.record` | `fee/payment/FeePaymentController` | yes, single write |
| `fee.cancellation.raise` | `fee/cancellation/FeeCancellationController` | never |
| `fee.credit.raise` | `fee/credit/FeeCreditController` | never |
| `fee.writeoff.propose` | `fee/writeoff/FeeWriteoffController` | never |
| `fee.latefee.waive` | `fee/latefee/LateFeeWaiverController` | never |

Ids come from `planning-contracts/`, never invented here; `CapabilityIdsMatchPlanningContractsTest`
fails the build if one drifts.

### The school's answers to the gateway's questions

The gateway asks; `school-app` answers. Each of these is a Spring bean implementing a
`com.diversive.agent.spi` interface:

| Gateway asks | School answers with |
| --- | --- |
| who is signed in, and where may they act | `DevTokenUserContextResolver` — the POC's stand-in for sign-in: `Authorization: Bearer <BACKEND_DEV_USER_TOKEN>` acts as `BACKEND_DEV_USERNAME` |
| may this user run this capability | `SingleRoleCapabilityPolicy` |
| turn "Hasan Ali in Class 5 Blue" into an id | `InvoiceResolver`, `StudentResolver`, `SectionResolver`, `ClassResolver` (`EntityResolver`) |
| does this precondition hold | `PreconditionCheck` beans, e.g. `FeeReminderAgentBeans.sectionHasDefaulters` |
| how many records would this touch | `AffectedCount` beans, sharing the very query the handler will run |
| write down what happened | `JdbcAuditTrail` |
| fill the confirmation and reply templates | `SchoolTemplateFormatter`, with `SchoolFormats` for money and dates |
| which capabilities cannot run yet | `PendingImplementations` |

`SchoolScope` is the one rule every resolver, check and count obeys: the user's branch goes in the
SQL `WHERE` clause, never in a filter afterwards.

### The database

`src/main/resources/db/migration/` holds the schema, applied by Flyway on start:

```
V1  branches and users
V2  academic sessions, classes, sections
V3  guardians, students, enrolments
V4  fee invoices, lines, payments
V6  the agent audit trail, fee reminders, payment details
```

`src/main/resources/db/seed/V5__seed_demo_school.sql` is the demo school, **frozen at 14 September
2026** and pinned by `DatabaseMigrationIT`, so the eval's expected answers stay valid. Its shape is
chosen to exercise the hard cases rather than to look tidy:

- 1 branch, 1 user, 2 classes, 3 sections, 30 students, 27 guardians, 60 invoices
- Class 5 Blue: 8 students owe but only 7 guardians (twins share one), and only 5 of those 7 have
  WhatsApp — "confirm 7, send 5" is exactly the count drift the count rule exists to prevent
- Class 6 Blue: nobody owes, so "the section has defaulters" fails for real
- "class 5" matches two sections, "blue" matches two sections, "ahmed" matches two students:
  ambiguity the resolvers have to hand back as a choice
- siblings across classes, so a family name alone is not enough

Change the seed and the pinned numbers together, or the build fails.

---

## How it is used

### Running it

```bash
make db-up          # Postgres 16 + pgvector
make backend-run    # applies migrations + seed on start, serves on 8080
```

The AI layer reaches it only through `/agent/**` — `GET /agent/metadata`, `POST /agent/preflight`,
`POST /agent/execute` — and never learns a business URL. `make ai-run` starts the AI layer against
it.

### Generating the contract

`school-app` is where the published contract comes from. Two integration tests export it, and fail
the build when the committed copies are stale:

| Test | Writes |
| --- | --- |
| `OpenApiContractIT` | `openapi/agent-gateway.json` |
| `AgentMetadataSnapshotIT` | `snapshots/agent-metadata.json` |

```bash
make contracts      # re-export both, then regenerate the AI layer's Pydantic models
```

The AI layer's `app/gateway/models.py` is generated from that spec, so a change to an annotation here
becomes a typed model there. The committed snapshot is also what lets the AI layer's tests and the
eval run with no backend at all (`app/capabilities/snapshot.py`).

### Testing it

```bash
make backend-test     # unit tests, no Docker
make backend-verify   # unit + integration, Testcontainers Postgres
```

The integration tests are where the invariants are actually proved: `PreflightIT` (resolution,
counts, tokens), `ExecuteIT` and `ExecuteRollbackIT` (a handler that does not do what it declared is
rolled back), `AgentGatewayEndpointIT` (what the gateway publishes, and what it deliberately keeps
off the wire), `RegistryBuildChecksIT` (the registry refuses to start on a bad declaration).

---

## Adding a capability here

1. Write the planning contract first, in `planning-contracts/`; the id comes from there.
2. Annotate the controller method: `@AgentCapability`, one `@AgentParam` per request-record field,
   `@AgentPrecondition` for each thing that must hold, `@AgentEffect` for what it creates, notifies
   and says afterwards. `docs/CLAUDE.md` has the full worked example and the rules for writing a
   description, which is the single biggest lever on retrieval quality.
3. Add the beans it needs next to the controller: a check per precondition, a count if it touches
   more than one record, a resolver per looked-up parameter.
4. `make contracts`, then `make backend-verify`.
5. The AI layer needs no change: the metadata sync picks the capability up within 30 seconds, and
   `pieces.vocabulary()` reads any new piece names straight from the published parameters.

---

## When the real backend arrives

It adds `agent-gateway` as a dependency, implements the same SPI beans against its own tables, and
annotates its own controllers. Nothing in `agent-gateway` has to change, and nothing in the AI layer
has to change either — it only ever knew capability ids and published metadata. `school-app` is then
deleted.
