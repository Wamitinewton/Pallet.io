# 24. Edge rate limiting as a Redis token bucket

- Status: accepted
- Date: 2026-10-04

## Context

ADR-0012 decision 4 gave `api-gateway` a Redis-backed edge limiter and called it a token bucket,
but the first cut was a fixed-window counter (`INCR` plus a `PEXPIRE` on the first hit), and
`docs/api-gateway/ARCHITECTURE.md` hardening decision 2 accepted that as the v1 algorithm.

A fixed window resets the whole budget at once, and the reset lands on whatever instant the key
happened to expire. A caller who spends a 60-request budget in the last second of one window can
spend another 60 in the first second of the next: twice the budget inside two seconds, which is
the burst an edge limiter exists to absorb. The edge limiter is also the planned home for per-org,
plan-based limits (`PROJECT.md`), where "60 a minute" has to mean what a tenant reads it as. The
caveat stopped being acceptable once the limit became something we'd sell.

## Decision

Replace the fixed window with a token bucket, keeping the class name, the config keys, and the
call sites.

- **Semantics.** `capacity` is the bucket size, the largest burst a subject can send. `window` is
  how long an empty bucket takes to refill, so the sustained rate is `capacity / window`. Over any
  interval of length `t` a subject is admitted at most `capacity + t × capacity / window`
  requests. A bucket drained just before what used to be a window boundary has a fraction of a
  token just after it, not a fresh budget. Per-route `CUSTOM` overrides use the same two fields
  with the same meaning.
- **One Lua script per request.** The script reads the bucket (a hash of `tokens` and
  `refilled_at`), refills it for the elapsed time capped at `capacity`, takes a token if one is
  whole, and writes it back. Redis runs a script atomically, so concurrent requests for the same
  key across every gateway instance never double-spend a token or lose an update.
- **Redis's clock, not the gateway's.** The script reads `TIME` on the Redis server. Gateway
  instances whose clocks disagree would otherwise credit or withhold tokens by the skew between
  them. Elapsed time is clamped at zero, so a clock that steps backwards (a failover to a replica)
  never removes tokens.
- **Fractional tokens.** Tokens are stored as a Lua double, so a slow rate like 1 per hour still
  refills smoothly rather than rounding to zero between requests.
- **Rejections write nothing.** Refill is linear and a rejected bucket is below capacity, so
  recomputing from the stored state later gives the same result. A caller hammering a drained
  bucket costs Redis reads only.
- **Self-cleaning keys.** Each write sets `PEXPIRE` to the time until the bucket would be full
  again. A full bucket and a missing key behave the same, so an idle subject holds no memory.
- **A new key prefix**, `gateway:ratelimit:bucket:`. The old counters were strings and the
  buckets are hashes, so a mixed fleet during a rolling deploy would otherwise hit `WRONGTYPE`
  errors, fail open, and report Redis as unavailable until the old keys expired.
- **`Retry-After` on every 429.** The script returns the milliseconds until the next whole token.
  The gateway rounds that up to whole seconds (minimum 1), because rounding down would tell a
  client to retry while the bucket is still empty.
- **Bounded Redis timeouts.** The fail-open posture (hardening decision 1) is unchanged, but it
  only helps when a Redis call fails quickly. `config-repo/api-gateway.yml` sets a 250 ms command
  timeout and a 1 s connect timeout in place of Lettuce's 60 s default, which would let a hung
  Redis stall every route.

## Consequences

**Easier**: a configured limit now means a rate, not a quota that refills all at once. Clients get
a usable `Retry-After` instead of guessing. Per-plan limits can later swap `capacity`/`window` per
org without changing the algorithm underneath.

**Harder**:

- The worst case over a full `window` is still `2 × capacity`: a full burst, then the refill
  arriving during that window. That is what a token bucket is, not a leftover defect. If a plan
  ever needs a hard per-window ceiling, the knob is a burst size smaller than `capacity`, not a
  different algorithm.
- Tests that count an exact budget have to make refill negligible (a long `window`), because a
  bucket refills between requests where a fixed window didn't.
- Each admitted request is one script call doing a hash read and write instead of an `INCR`.
  It's still one round trip and O(1), so the cost doesn't change in any way that matters.

## Alternatives considered

- **Sliding-window log** (a sorted set of request timestamps). Exact, but memory grows with the
  limit (60 entries per key at 60/min, far more for generous plans) and every request trims a
  sorted set.
- **Sliding-window counter** (weighted current and previous fixed windows). Cheap and close, but
  it approximates by assuming the previous window's requests were spread evenly, and it gives no
  natural "next token at" value for `Retry-After`.
- **GCRA** (generic cell rate algorithm, one timestamp per key). Equivalent admission behavior to
  this bucket with one stored number instead of two. Rejected for readability: the
  tokens-and-refill form maps directly onto `capacity` and `window` as operators already read them,
  and the saving of one hash field is not worth that cost.
- **Spring Cloud Gateway's `RedisRateLimiter`.** It's a reactive `RateLimiter` for the WebFlux
  gateway. ADR-0012 chose Gateway Server MVC, and it would also bring that gateway's own config
  shape and key resolver in alongside `RateLimitFilterFunction`'s per-route overrides.
