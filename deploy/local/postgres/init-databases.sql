-- Databases created on first Postgres startup.
--
-- Database strategy is still an open question in PROJECT.md (instance-per-service
-- vs shared cluster with a schema per service). For local dev this uses one
-- shared cluster: a `pallet` database that services carve schemas out of, plus
-- a dedicated `keycloak` database since Keycloak owns its whole schema.
--
-- audit-log-service and usage-metering-service do NOT appear here — they are on
-- ClickHouse, not Postgres.

CREATE DATABASE keycloak OWNER pallet;
