# One-word commands for the AI layer POC. Run `make help` to list them.
#
# Every recipe gets the variables from .env (copied from .env.example), so the
# database, the backend and the AI layer always agree on ports and passwords.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ENV_FILE ?= .env
ifneq (,$(wildcard $(ENV_FILE)))
include $(ENV_FILE)
export
endif

COMPOSE := docker compose --project-directory docker -f docker/docker-compose.yml \
	$(if $(wildcard $(ENV_FILE)),--env-file $(ENV_FILE))
MVNW := cd backend && ./mvnw -B -ntp
UV := cd ai-layer && uv
EMBEDDINGS_URL := http://127.0.0.1:$${EMBEDDINGS_HOST_PORT:-8083}

.PHONY: help
help: ## List the commands
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

.PHONY: env
env: ## Create .env from .env.example (keeps an existing .env)
	@test -f .env && echo ".env already exists" || (cp .env.example .env && echo "created .env")

# --- Database ---------------------------------------------------------------

.PHONY: db-up
db-up: ## Start Postgres + pgvector and wait until healthy
	$(COMPOSE) up -d --wait postgres

.PHONY: db-down
db-down: ## Stop Postgres (keeps the data)
	$(COMPOSE) stop postgres
	$(COMPOSE) rm -f postgres

.PHONY: db-reset
db-reset: ## Delete the database volume and start fresh (re-runs init + all migrations)
	$(COMPOSE) rm -s -f postgres
	docker volume rm -f sms-ai-layer_postgres-data
	$(COMPOSE) up -d --wait postgres

.PHONY: db-psql
db-psql: ## Open psql as the superuser
	$(COMPOSE) exec postgres psql -U "$${POSTGRES_SUPERUSER:-postgres}" -d "$${POSTGRES_DB:-sms}"

# --- Embeddings service (BGE-M3, ~3 GB of memory) -----------------------------

.PHONY: embeddings-up
embeddings-up: ## Start the embeddings service and wait until the model is loaded
	$(COMPOSE) --profile embeddings up -d embeddings
	@echo "waiting for BGE-M3 (first start downloads ~2.3 GB)..."
	@for i in $$(seq 1 900); do \
		if curl -fsS -m 2 "$(EMBEDDINGS_URL)/health" >/dev/null 2>&1; then echo "embeddings ready at $(EMBEDDINGS_URL)"; exit 0; fi; \
		if [ "$$(docker inspect -f '{{.State.Running}}' sms-ai-layer-embeddings-1 2>/dev/null)" != "true" ]; then \
			echo "embeddings container stopped; see: docker logs sms-ai-layer-embeddings-1"; exit 1; fi; \
		sleep 2; done; echo "timed out waiting for the embeddings service"; exit 1

.PHONY: embeddings-down
embeddings-down: ## Stop the embeddings service (keeps the downloaded model)
	$(COMPOSE) --profile embeddings stop embeddings
	$(COMPOSE) --profile embeddings rm -f embeddings

# --- Backend (Spring Boot, port 8080) ----------------------------------------

.PHONY: backend-run
backend-run: ## Run the backend (applies Flyway migrations + seed on start)
	$(MVNW) -pl school-app -am -DskipTests install
	$(MVNW) -f school-app/pom.xml spring-boot:run

.PHONY: backend-test
backend-test: ## Backend unit tests (no Docker needed)
	$(MVNW) test

.PHONY: backend-verify
backend-verify: ## Backend unit + integration tests (Testcontainers, needs Docker)
	$(MVNW) verify

# --- AI layer (FastAPI, port 8081) -------------------------------------------

.PHONY: ai-install
ai-install: ## Install AI layer dependencies with uv
	$(UV) sync

.PHONY: ai-run
ai-run: ## Run the AI layer (applies its index migrations on start)
	$(UV) run python -m app

.PHONY: ai-migrate
ai-migrate: ## Apply the AI layer's plain-SQL index migrations without starting the server
	$(UV) run python -m app.index

.PHONY: ai-test
ai-test: ## Every AI layer test except real model calls (integration needs Docker, build checks need embeddings-up)
	$(UV) run pytest -m "not model"

.PHONY: ai-test-unit
ai-test-unit: ## AI layer unit tests only (no Docker, no embeddings service, no model calls)
	$(UV) run pytest -m "not integration and not embeddings and not model"

.PHONY: ai-model-checks
ai-model-checks: ## Phase 5 checks against the real models (Gemini API; needs AI_LAYER_GEMINI_API_KEY)
	$(UV) run pytest tests/model_checks

.PHONY: plan
plan: ## Plan a sentence end to end and preflight it (nothing runs): make plan Q="class 5 blue ko reminder bhejo"
	$(UV) run python -m app.planning "$(Q)"

.PHONY: ai-build-checks
ai-build-checks: ## Build-time checks that need the embeddings service (description similarity)
	$(UV) run pytest tests/build_checks

.PHONY: ai-eval
ai-eval: ## Retrieval eval: recall@30 gate, Roman Urdu, clusters; writes ai-layer/eval/reports/retrieval.md
	$(UV) run pytest eval -p no:cacheprovider

.PHONY: measure
measure: ## Print the four numbers (recall@30, plan accuracy, refusal correctness, validator catch rate); records missing model answers
	$(UV) run pytest eval/test_measure.py -p no:cacheprovider -q

.PHONY: measure-ci
measure-ci: ## The regression run: every eval from eval/recordings/ only, no model called (what CI runs)
	cd ai-layer && EVAL_MODEL_CALLS=replay AI_LAYER_GEMINI_API_KEY= uv run pytest eval -p no:cacheprovider -q

.PHONY: eval-corpus
eval-corpus: ## Re-extract the eval's contract sentences and distractors from ../planning-contracts
	$(UV) run python scripts/extract_eval_corpus.py

.PHONY: retrieve
retrieve: ## Show the candidates retrieval offers for a sentence: make retrieve Q="remind class 5 blue"
	$(UV) run python -m app.retrieval "$(Q)"

.PHONY: descriptions-report
descriptions-report: ## Print how similar every pair of capability descriptions is
	$(UV) run python -m app.capabilities

.PHONY: ai-lint
ai-lint: ## Lint, format-check and type-check the AI layer
	$(UV) run ruff check .
	$(UV) run ruff format --check .
	$(UV) run mypy

# --- Contract between the two services ---------------------------------------

.PHONY: contracts
contracts: ## Re-export the OpenAPI spec and metadata snapshot, then regenerate the AI layer models
	$(MVNW) -pl school-app -am verify -Dtest=skip-unit-tests -Dsurefire.failIfNoSpecifiedTests=false \
		-Dit.test=OpenApiContractIT,AgentMetadataSnapshotIT -Dfailsafe.failIfNoSpecifiedTests=false \
		-Dcontract.update=true
	$(UV) run python scripts/generate_gateway_models.py

# --- Everything ---------------------------------------------------------------

.PHONY: test
test: backend-verify ai-test ## All tests for both services (needs Docker and embeddings-up)

.PHONY: verify-phase0
verify-phase0: ## Check every Phase 0 "done when" item against the running stack
	./scripts/verify_phase0.sh

.PHONY: verify-phase1
verify-phase1: ## Check every Phase 1 "done when" item against the running stack
	./scripts/verify_phase1.sh

.PHONY: verify-phase2
verify-phase2: ## Check every Phase 2 "done when" item against the running stack
	./scripts/verify_phase2.sh

.PHONY: verify-phase3
verify-phase3: ## Check every Phase 3 "done when" item against the running stack
	./scripts/verify_phase3.sh

.PHONY: verify-phase4
verify-phase4: ## Check every Phase 4 "done when" item (runs the retrieval eval)
	./scripts/verify_phase4.sh

.PHONY: verify-phase5
verify-phase5: ## Check every Phase 5 "done when" item (calls the real models)
	./scripts/verify_phase5.sh

.PHONY: verify-phase6
verify-phase6: ## Check every Phase 6 "done when" item through POST /chat (calls the real models)
	./scripts/verify_phase6.sh

.PHONY: verify-phase7
verify-phase7: ## Check every Phase 7 "done when" item (the regression run from recorded answers)
	./scripts/verify_phase7.sh
