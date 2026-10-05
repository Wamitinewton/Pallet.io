# 25. Web BFF session: tokens in encrypted Redis behind an opaque cookie

- Status: accepted
- Date: 2026-10-04

## Context

ADR-0023 makes the Next.js app the dashboard's backend-for-frontend. `identity-service` answers a
sign-in with a Keycloak access token (15 minutes) and a rotating refresh token, and every Pallet
service is a stateless resource server that accepts whatever bearer token reaches it until it expires
or its `sid` is revoked (ADR-0015). Whoever holds the pair holds the account.

Four forces shape where the pair lives and how the browser reaches the backend:

- **Any script on the page can read anything JavaScript can read.** One XSS, one compromised
  dependency, one malicious extension, and a token in `localStorage` or a JS-readable cookie is gone,
  refresh token included, which outlives the access token by hours.
- **Refresh tokens rotate.** Keycloak invalidates a refresh token once it is spent. A page load fires
  several requests at once; if each one refreshes on its own, all but the first spend a dead token and
  the session ends for no reason.
- **The gateway is the security boundary** (ADR-0012), and it rate-limits per client IP for anonymous
  calls. Behind a BFF, every request's peer is the web server.
- **Sessions are revocable platform-wide** (ADR-0015). A session ended from another device has to end
  here too, on the next request, not when the access token happens to expire.

## Decision

1. **Tokens never leave the server.** The browser holds one cookie: 32 random bytes, base64url, minted
   fresh on every sign-in (no fixation). `HttpOnly`, `SameSite=Lax`, `Path=/`, and `__Host-` prefixed
   plus `Secure` whenever `PALLET_PUBLIC_BASE_URL` is `https`. `Max-Age` tracks the refresh token's
   remaining lifetime and is re-issued when a refresh extends it. No route returns a token, the refresh
   token or the session id; `GET /api/session` returns a summary (`userId`, `email`,
   `keycloakSessionId`, `accessExpiresAt`) and nothing else.
2. **The session lives in Redis, encrypted, under a hashed key.** Key `web:session:<sha256(id)>`, so a
   Redis dump or `KEYS` listing never yields a usable cookie value. The value is the session as JSON,
   sealed with AES-256-GCM: a fresh 12-byte IV per write, the Redis key bound in as additional
   authenticated data (a ciphertext can't be moved to another session), and the key id prefixed so
   keys rotate without signing anyone out (`PALLET_SESSION_ENCRYPTION_KEY_ID` writes,
   `PALLET_SESSION_RETIRED_ENCRYPTION_KEYS` still decrypt, and a session is re-encrypted on its next
   write). Anything that fails to authenticate is treated as no session and deleted. The key expires
   at the refresh token's expiry (`PEXPIREAT`), so Redis forgets a session exactly when Keycloak would
   refuse it.
3. **Refresh is single-flight and compare-and-set.** A session whose access token expires within 30
   seconds is refreshed under `SET web:session:<hash>:refresh <owner> NX PX 10000`. The holder re-reads
   the session (another holder may have just finished), calls `POST /identity/auth/refresh`, and
   writes the new pair with a Lua compare-and-set on a SHA-256 fingerprint of the previous refresh
   token, so a slow writer can never replace a newer pair with an older one. Everyone else polls the
   session every 100 ms for up to 5 seconds and takes the new access token when it appears. A `400`
   or `401` from identity ends the session; a network error or `5xx` keeps the current access token
   while it is still valid, so a gateway blip signs nobody out. Server Components resolve the session
   the same way, so a prefetch and a proxied request share one refresh path.
4. **`/bff/*` is the only path from the browser to the backend**, and it is a closed allow-list, not a
   generic proxy. The first segment must be `identity`, `org-team`, `git-integration` or
   `notification`; `v3/api-docs` and `git-integration/webhooks/**` are refused; segments are
   re-encoded and `.`, `..` and empty segments refused. Forwarded request headers are a fixed list
   (`Accept`, `Content-Type`, `Idempotency-Key`, `X-Confirm-Slug`, `X-Correlation-Id`, `traceparent`,
   plus `Authorization` and `X-Forwarded-For` set by the BFF), never `Cookie`. Returned response headers
   are a fixed list (`Content-Type`, `Retry-After`, `X-Correlation-Id`, `Cache-Control: no-store`),
   never `Set-Cookie`. Bodies stream and are capped at 1 MB; the gateway gets 15 seconds.
5. **CSRF defence is layered.** `SameSite=Lax` keeps the cookie off cross-site subrequests; every
   non-`GET` must carry an `Origin` equal to the public origin (or, with no `Origin`,
   `Sec-Fetch-Site: same-origin`) and `X-Pallet-Request: 1`, a header a cross-site form cannot set and
   a cross-site `fetch` cannot send without a CORS preflight this app never grants.
6. **One expiry signal.** A cookie that points at a dead session, a refresh token identity rejects,
   and a `401 AUTHENTICATION_REQUIRED` from the gateway to a request that carried a token (revoked
   elsewhere, ADR-0015) all become `401 SESSION_EXPIRED`, with the session deleted and the cookie
   cleared. Every other `401` passes through untouched, so a wrong current password never signs anyone
   out.
7. **The real client IP is forwarded, never trusted from the client.** `X-Forwarded-For` is read only
   `PALLET_TRUSTED_PROXY_HOPS` entries from the right, the address recorded by the outermost trusted
   proxy; anything further left was written by the client and ignored. With zero trusted hops nothing
   is forwarded, because Next.js only fills the header from the socket when the client sent none, so
   no entry is trustworthy. The gateway accepts it only from its own trusted proxies
   (`api-gateway/09-client-ip-behind-the-web-bff.md`).
8. **`proxy.ts` is an optimistic guard only.** It redirects a dashboard visit without a session cookie
   to `/login?next=...` and touches neither Redis nor the gateway; the dashboard layout resolves the
   session for real.

## Consequences

- An injected script can act as the user while the page is open, but it can never take a token with
  it: there is nothing to exfiltrate and replay from elsewhere.
- Redis is now on the dashboard's critical path; if it is down, nobody is signed in. Losing it loses
  only sessions (everyone signs in again), never data, and it needs no persistence.
- Every browser API call is one extra hop through the BFF, and every page that reads tenant data is
  dynamically rendered (no shared cache could hold it anyway, ADR-0023).
- Rotating the encryption key is a config change, not an outage. Losing the key signs everyone out
  and exposes nothing.
- The BFF's view of the client IP is only as good as `PALLET_TRUSTED_PROXY_HOPS`; it must match the
  number of proxies in front of the app in each environment.
- A new backend service reachable from the browser needs one line in the allow-list, deliberately.
- Follow-ups: sign-in and sign-out (checkpoint 05) build on `startSession`/`endSession`; step-up
  re-authentication (10) on `replaceTokens`; redacted logging and the CSP (21) formalize what this
  checkpoint logs by hashed id prefix only.

## Alternatives considered

- **Tokens in `localStorage` or `sessionStorage`.** Readable by every script on the page; one XSS
  hands over a refresh token that outlives the tab.
- **Tokens in an encrypted cookie, no server-side store.** No Redis, but a Keycloak access plus refresh
  token pair approaches the 4 KB cookie limit once encrypted, every request (images and scripts
  included) carries it, and revocation needs a server-side list anyway.
- **A separate BFF service.** Another deployable, pipeline and network hop for no gain while the
  dashboard is its only client; the route handlers are already a server.
- **Calling the gateway from the browser with CORS.** Puts the tokens back in the browser and makes
  the gateway's CORS policy part of the auth model.
- **Refreshing without a lock and tolerating the races.** Rotating refresh tokens turn every parallel
  page load into a coin toss that signs the person out.
- **A generic pass-through proxy.** Forwards whatever the browser sends, including internal endpoints,
  API docs, webhook paths and spoofable headers; the allow-list costs one line per service.
