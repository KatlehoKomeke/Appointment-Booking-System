# Makefile
# One entry point for common tasks. `make` or `make help` lists them. Every target prints the commands it runs, so
# they can be copied on systems without make (for example Windows without WSL).
#
# Running the application needs only Docker (`make up`). Developing it also needs JDK 25 and Node 24; see
# README.md, "Getting started".

SHELL := /usr/bin/env bash
GITLEAKS_VERSION := v8.30.1
GITLEAKS_IMAGE := ghcr.io/gitleaks/gitleaks:$(GITLEAKS_VERSION)
# Lets the containerised git trust the mounted repository (owned by a different user ID).
GIT_SAFE_DIR := -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*'

.DEFAULT_GOAL := help
.PHONY: help up down logs psql smoke dev-api dev-web test test-api test-web test-docs contract fmt hooks secrets-scan secrets-scan-history

help: ## List the available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-22s %s\n", $$1, $$2}'

# --- Run -------------------------------------------------------------------------------------------------------------

up: ## Build and start the app and its database, wait until both are healthy, and print the URL
	docker compose up --build --detach --wait --wait-timeout 300
	@echo "BranchBook is running at http://$$(docker compose port app 8080)"

down: ## Stop the app and the database, and delete the local database
	docker compose down --volumes --remove-orphans

logs: ## Follow the application's log
	docker compose logs --follow app

psql: ## Open a SQL shell on the local database
	docker compose exec db sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"'

smoke: ## Check the running app over HTTP (after `make up`)
	scripts/smoke.sh "http://$$(docker compose port app 8080)"

# --- Develop ---------------------------------------------------------------------------------------------------------

dev-api: ## Run the API on port 8080 with a throwaway PostgreSQL in Docker (JDK 25)
	cd api && ./mvnw spring-boot:test-run

dev-web: web/node_modules ## Run the web app on port 5173 with hot reload, calling the API on port 8080 (Node 24)
	cd web && npm run dev

test: test-api test-web test-docs ## Run every check that CI runs on the API, the web app and the docs

test-api: ## Build the API and run its unit and integration tests, formatting and Javadoc checks (JDK 25, Docker)
	cd api && ./mvnw -B verify
	@# As in CI: slots must not depend on the JVM's zone, so the unit and API tests run again in New York (BR-20).
	cd api && TZ=America/New_York ./mvnw -B verify -Dit.test='*ApiIT' -Djacoco.skip=true

test-web: web/node_modules ## Check the API types, then lint, type-check, format-check, test and build the web app (Node 24)
	cd web && npm run check:api && npm run lint && npm run typecheck && npm run format:check && npm test && npm run build

test-docs: ## Check that the tests the README and the proven domain rules cite exist
	scripts/check-test-refs.sh

# After an intended API change: rewrites docs/openapi.yaml from the running API (OpenApiContractIT only), then the
# web app's types from it. Commit both files with the change (ADR-0005).
contract: web/node_modules ## Regenerate docs/openapi.yaml and the web app's API types after an API change (JDK 25, Docker, Node 24)
	cd api && ./mvnw -B -ntp verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=OpenApiContractIT -Dopenapi.update=true -Djacoco.skip=true
	cd web && npm run generate:api

fmt: web/node_modules ## Format the Java and TypeScript code
	cd api && ./mvnw -B -q spotless:apply
	cd web && npm run format

# Installs the web app's dependencies when the lockfile changes.
web/node_modules: web/package.json web/package-lock.json
	cd web && npm ci
	@touch web/node_modules

# --- Repository safety -----------------------------------------------------------------------------------------------

hooks: ## Enable the repository's git hooks (secret scan before every commit, architecture-page reminder after a major one). Run once per clone.
	git config core.hooksPath .githooks
	chmod +x .githooks/*
	@echo "Git hooks enabled: every commit is now scanned for secrets, and a major one reminds you to re-check the architecture page."

secrets-scan: ## Scan the working tree for secrets (tracked and untracked files)
	docker run --rm -v "$(CURDIR):/repo:ro" $(GITLEAKS_IMAGE) \
	  dir /repo --config /repo/.gitleaks.toml --redact --no-banner --verbose

secrets-scan-history: ## Scan every commit in the git history for secrets
	docker run --rm -v "$(CURDIR):/repo:ro" $(GIT_SAFE_DIR) $(GITLEAKS_IMAGE) \
	  git /repo --config /repo/.gitleaks.toml --redact --no-banner --verbose
