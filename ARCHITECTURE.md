# Who does what: AI layer, agent-gateway, school-app

This is the short version of the architecture, in plain words. The rules behind it are in
[docs/CLAUDE.md](docs/CLAUDE.md); which file holds what is in [STRUCTURE.md](STRUCTURE.md).

---

## The picture

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

## Each part's one job

| Part | Its job | Knows about schools? | Reads business data? |
| --- | --- | --- | --- |
| **AI layer** | Turn a sentence into a plan, and talk to the user | Only school words (a glossary) | **Never.** Only its own capability index |
| **agent-gateway** | The safety process every action goes through, the same for every action | **No** | No: it asks school-app |
| **school-app** | The school's tables, rules and actions | Yes | Yes |

## What lives where

| Piece of the architecture | Lives in | Phase |
| --- | --- | --- |
| Splitting the sentence into intents, in English (Haiku) | AI layer | 5 |
| School words such as challan, haazri, baqaya | AI layer (`domain/school`) | 5 |
| Keeping its own index of capability descriptions in step with the backend (polling versions) | AI layer (`app/sync`) | 4 |
| Finding the likely capabilities, each with its look-alikes, only among those the user may use | AI layer (`app/retrieval`) | 4 |
| Measuring whether it finds the right one (the eval set) | AI layer (`eval/`) | 4 |
| The four numbers (recall, plan accuracy, refusals, validator catch rate) and the CI regression run | AI layer (`eval/measure`, recorded model answers) | 7 |
| Choosing the capability and filling its inputs (Sonnet) | AI layer | 5 |
| Checking everything the model wrote before using it | AI layer (`app/validation`) | 5 |
| Calling the models (Haiku, Sonnet) through the Claude CLI, behind one interface | AI layer (`app/llm`) | 5 |
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

## Why this split is better

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

## One sentence, start to finish

1. **AI layer:** reads "class 5 blue ke defaulters ko whatsapp par reminder bhejo".
   - Haiku rewrites it in English: "Send a WhatsApp reminder to the defaulters in class 5 blue", keeping "class 5 blue" exactly as typed.
   - Retrieval finds the likely capabilities in its own index. `fee.reminder.send` comes with its look-alike `fee.overdue.list`, so the planner has to choose between them.
   - Sonnet picks `fee.reminder.send` with section "class 5 blue" and channel whatsapp.
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

## When the real school backend exists

`school-app` is a stand-in: it exists so the POC has real data to work against. The real backend
adds `agent-gateway` as a dependency and moves in the annotations, resolvers, checks and counts.
Then `school-app` is deleted. The AI layer keeps calling the same `/agent/**` endpoints and does
not notice.
