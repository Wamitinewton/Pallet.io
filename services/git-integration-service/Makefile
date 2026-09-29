.DEFAULT_GOAL := help
MVN := ./mvnw
ENV_FILE := .env.local

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Build this service, skipping tests
	$(MVN) -B clean package -DskipTests

.PHONY: test
test: ## Run unit tests
	$(MVN) -B test

.PHONY: verify
verify: ## Run unit + integration tests (Testcontainers)
	$(MVN) -B verify

.PHONY: security
security: ## Run SpotBugs (FindSecBugs) + OWASP Dependency-Check
	$(MVN) -B verify -P security

.PHONY: format-check
format-check: ## Check Java formatting (Spotless/Palantir)
	$(MVN) -q spotless:check

.PHONY: format
format: ## Auto-fix Java formatting (Spotless/Palantir)
	$(MVN) -q spotless:apply

.PHONY: dev-secrets
dev-secrets: ## Generate throwaway local key material into .env.local (git-ignored)
	./scripts/dev-secrets.sh

.PHONY: run
run: ## Run git-integration-service locally (needs: make up at the repo root, then make dev-secrets)
	@if [ -f $(ENV_FILE) ]; then set -a; . ./$(ENV_FILE); set +a; fi; $(MVN) -B spring-boot:run
