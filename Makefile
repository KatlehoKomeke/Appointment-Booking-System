# Makefile
# One entry point for common tasks. Every target prints what it runs, so you can copy the raw
# command on systems without make (for example Windows without WSL).
#
# Targets for building, testing and running the application (up, down, dev, test, e2e, smoke, fmt)
# arrive with the first code milestone (M0) and are documented in README.md as they land.

SHELL := /usr/bin/env bash
GITLEAKS_VERSION := v8.30.1
GITLEAKS_IMAGE := ghcr.io/gitleaks/gitleaks:$(GITLEAKS_VERSION)
# Lets the containerised git trust the mounted repository (owned by a different user ID).
GIT_SAFE_DIR := -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*'

.DEFAULT_GOAL := help
.PHONY: help hooks secrets-scan secrets-scan-history

help: ## List the available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-22s %s\n", $$1, $$2}'

hooks: ## Enable the repository's git hooks (secret scan before every commit). Run once per clone.
	git config core.hooksPath .githooks
	chmod +x .githooks/*
	@echo "Git hooks enabled: every commit is now scanned for secrets."

secrets-scan: ## Scan the working tree for secrets (tracked and untracked files)
	docker run --rm -v "$(CURDIR):/repo:ro" $(GITLEAKS_IMAGE) \
	  dir /repo --config /repo/.gitleaks.toml --redact --no-banner --verbose

secrets-scan-history: ## Scan every commit in the git history for secrets
	docker run --rm -v "$(CURDIR):/repo:ro" $(GIT_SAFE_DIR) $(GITLEAKS_IMAGE) \
	  git /repo --config /repo/.gitleaks.toml --redact --no-banner --verbose
