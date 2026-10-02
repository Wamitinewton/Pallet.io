.DEFAULT_GOAL := help
PNPM := pnpm

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: install
install: ## Install dependencies from the lockfile
	$(PNPM) install --frozen-lockfile

.PHONY: dev
dev: ## Run the dev server on http://localhost:5173
	$(PNPM) dev

.PHONY: build
build: ## Production build (standalone output)
	$(PNPM) build

.PHONY: typecheck
typecheck: ## Generate route types and type-check
	$(PNPM) typecheck

.PHONY: lint
lint: ## Lint, including layer boundaries
	$(PNPM) lint

.PHONY: test
test: ## Run unit and component tests
	$(PNPM) test

.PHONY: test-int
test-int: ## Run integration tests (Docker required)
	$(PNPM) test:int

.PHONY: e2e
e2e: ## Run Playwright against a running app (PLAYWRIGHT_START_SERVER=1 to start one)
	$(PNPM) e2e

.PHONY: verify
verify: ## Typecheck, lint, format check, all tests and the build: the single gate
	$(PNPM) verify

.PHONY: api-types
api-types: ## Regenerate openapi/*.json and the API types from the running gateway (PALLET_GATEWAY_URL)
	$(PNPM) api:types

.PHONY: format
format: ## Format every file with Prettier
	$(PNPM) format

.PHONY: format-check
format-check: ## Check formatting
	$(PNPM) format:check

.PHONY: clean
clean: ## Remove build output, reports and caches
	rm -rf .next coverage playwright-report test-results blob-report next-env.d.ts *.tsbuildinfo
