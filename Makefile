.DEFAULT_GOAL := help

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: format-check
format-check: ## Check .editorconfig conformance + Java formatting (Spotless/Palantir), repo-wide
	pre-commit run --all-files

## ── Local infrastructure ────────────────────────────────────────────────

.PHONY: up
up: ## Start core infra (postgres, redis, kafka, keycloak, clickhouse, mailpit)
	docker compose --profile core up -d

.PHONY: obs
obs: ## Start the observability stack (otel-collector, prometheus, grafana, jaeger, loki)
	docker compose --profile observability up -d

.PHONY: data
data: ## Start minio + vault
	docker compose --profile data up -d

.PHONY: up-all
up-all: ## Start every profile
	docker compose --profile core --profile observability --profile data --profile ui up -d

.PHONY: down
down: ## Stop all containers, keep volumes
	docker compose --profile core --profile observability --profile data --profile ui down

.PHONY: nuke
nuke: ## Stop everything and delete volumes
	docker compose --profile core --profile observability --profile data --profile ui down -v

.PHONY: logs
logs: ## Tail logs from running infra
	docker compose logs -f

## ── Local Kubernetes (deploy path) ──────────────────────────────────────

.PHONY: kind-up
kind-up: ## Create the local kind cluster
	./deploy/local/bootstrap-kind.sh

.PHONY: kind-down
kind-down: ## Delete the local kind cluster
	kind delete cluster --name pallet
