#!/usr/bin/env bash
# Publishes a well-formed build.dispatched to the local Kafka, the way build-queue-service does: keyed by orgId, with
# the event-id, event-type and org-id headers the platform stamps, and a fresh event id every time.
#
#   scripts/send-dispatch.sh <orgId> <appId> <repo> <repoId> <sha> [options]
#
#   --build-id <uuid>      reuse a build id, to send a duplicate or a later attempt (default: a fresh one)
#   --attempt <n>          the dispatch attempt (default 1)
#   --root <dir>           the root directory inside the repository (default: the repository root)
#   --start-by-in <n>[smh] the start deadline relative to now; negative for an already-late dispatch (default 5m)
#   --max-duration <n>[smh] the build's maximum duration (default 30m)
#   --branch <name>        the branch (default main)
#   --installation-id <n>  the GitHub App installation (default 1)
#
# KAFKA_CONTAINER overrides the container to produce from (default pallet-kafka, from `make up`).
set -euo pipefail

usage() { sed -n '4,15p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
fail() { echo "$1" >&2; exit 2; }
[[ $# -ge 5 ]] || usage

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

org_id="$1"
app_id="$2"
repo="$3"
repo_id="$4"
sha="$5"
shift 5

new_uuid() {
  if command -v uuidgen >/dev/null; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

# A signed duration like 5m, -1m, 90s or 2h, in seconds.
seconds() {
  [[ "$1" =~ ^(-?)([0-9]+)([smh]?)$ ]] || fail "duration must look like 30s, 5m, -1m or 2h"
  local sign="${BASH_REMATCH[1]}" value="${BASH_REMATCH[2]}" unit="${BASH_REMATCH[3]:-s}"
  case "$unit" in
    s) echo "${sign}${value}" ;;
    m) echo "${sign}$((value * 60))" ;;
    h) echo "${sign}$((value * 3600))" ;;
  esac
}

build_id="$(new_uuid)"
attempt=1
root=""
start_by_in="5m"
max_duration="30m"
branch="main"
installation_id=1

while [[ $# -gt 0 ]]; do
  [[ $# -ge 2 ]] || usage
  case "$1" in
    --build-id) build_id="$2" ;;
    --attempt) attempt="$2" ;;
    --root) root="$2" ;;
    --start-by-in) start_by_in="$2" ;;
    --max-duration) max_duration="$2" ;;
    --branch) branch="$2" ;;
    --installation-id) installation_id="$2" ;;
    *) usage ;;
  esac
  shift 2
done

# Every value lands inside a JSON string unescaped, so only characters that need no escaping are accepted.
[[ "$org_id" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || fail "orgId must be 1-64 of [A-Za-z0-9._-]"
[[ "$app_id" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || fail "appId must be 1-64 of [A-Za-z0-9._-]"
[[ "$repo" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || fail "repo must be owner/name"
[[ "$repo_id" =~ ^[1-9][0-9]*$ ]] || fail "repoId must be a positive integer"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || fail "sha must be 40 lowercase hex characters"
[[ "$build_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "build id must be a lowercase UUID"
[[ "$attempt" =~ ^[1-9][0-9]*$ ]] || fail "attempt must be a positive integer"
[[ "$root" =~ ^[A-Za-z0-9._/-]{0,255}$ ]] || fail "root must be up to 255 of [A-Za-z0-9._/-]"
[[ "$branch" =~ ^[A-Za-z0-9._/-]{1,255}$ ]] || fail "branch must be 1-255 of [A-Za-z0-9._/-]"
[[ "$installation_id" =~ ^[1-9][0-9]*$ ]] || fail "installation id must be a positive integer"

now="$(date -u +%s)"
start_by="$(date -u -d "@$((now + $(seconds "$start_by_in")))" +%Y-%m-%dT%H:%M:%SZ)"
max_duration_seconds="$(seconds "$max_duration")"
[[ "$max_duration_seconds" -gt 0 ]] || fail "max duration must be positive"

event_id="$(new_uuid)"
push_event_id="$(new_uuid)"
occurred_at="$(date -u -d "@$now" +%Y-%m-%dT%H:%M:%SZ)"
container="${KAFKA_CONTAINER:-pallet-kafka}"

payload="$(printf '{"eventId":"%s","eventType":"build.dispatched","orgId":"%s","occurredAt":"%s","buildId":"%s","attempt":%s,"appId":"%s","branch":"%s","commitSha":"%s","pushEventId":"%s","trigger":"MANUAL","provider":"GITHUB","installationId":%s,"repoId":%s,"repo":"%s","rootDirectory":"%s","startBy":"%s","maxDurationSeconds":%s}' \
  "$event_id" "$org_id" "$occurred_at" "$build_id" "$attempt" "$app_id" "$branch" "$sha" "$push_event_id" \
  "$installation_id" "$repo_id" "$repo" "$root" "$start_by" "$max_duration_seconds")"

echo "build.dispatched  orgId=$org_id appId=$app_id buildId=$build_id attempt=$attempt startBy=$start_by eventId=$event_id" >&2
printf 'event-id:%s,event-type:build.dispatched,org-id:%s\t%s\t%s\n' "$event_id" "$org_id" "$org_id" "$payload" |
  docker exec -i "$container" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic build.dispatched \
    --property parse.headers=true \
    --property parse.key=true
