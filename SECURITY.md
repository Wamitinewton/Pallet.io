# Security

## Reporting a vulnerability

Do not open a public issue for a security vulnerability. Email the maintainer
(see the GitHub profile for `Wamitinewton`) with details and a reproduction, and
allow reasonable time for a fix before any disclosure.

## Dependency and code scanning

Two checks run under the `security` Maven profile and in CI:

| Tool | What it covers | Command |
|---|---|---|
| SpotBugs + FindSecBugs | SAST over compiled bytecode (injection, crypto misuse, hardcoded secrets) | `./mvnw -P security verify` |
| OWASP Dependency-Check | Known CVEs in Maven dependencies, checked against the NVD | `./mvnw -P security verify` |

### NVD API key

Dependency-Check is slow and rate-limited without an NVD API key. Get one free
at <https://nvd.nist.gov/developers/request-an-api-key>, then either:

- add it to `~/.m2/settings.xml` as a property `nvd.api.key`, or
- pass `-Dnvd.api.key=...` on the command line.

In CI it comes from the `NVD_API_KEY` repository secret.

### Suppressions

`owasp-suppressions.xml` holds every consciously accepted CVE with a note on
why (confirmed false positive, or no patch available yet with a tracking link).
`spotbugs-exclude.xml` does the same for SAST patterns that do not apply.
Both are reviewed on every dependency bump.

## Security patch overrides

The parent `pom.xml` has a block for overriding Spring Boot BOM-managed
dependency versions when a security patch lands before the BOM catches up. Each
override carries a CVE reference and is rechecked (and usually removed) on every
Spring Boot upgrade.

## Secrets

No real secret belongs in any tracked file. Local `docker-compose.yml`
credentials only unlock the local stack and are not a precedent. Real secrets
come from environment variables or git-ignored override files
(`application-secrets.yaml`, `*-credentials.json`, `.env` — already ignored),
and in production from Vault / Kubernetes Secrets via `secrets-service`.
