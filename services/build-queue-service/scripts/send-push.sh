#!/usr/bin/env bash
# Publishes a well-formed git.push.received to the local Kafka, the way git-integration-service does: keyed by orgId,
# with the event-id, event-type and org-id headers the platform stamps, and a fresh event id every time.
#
#   scripts/send-push.sh <orgId> <appId> <branch> [sha] [trigger]
#
# The SHA defaults to a random one, the trigger to WEBHOOK (also MANUAL, LINKED, RECONCILED).
# KAFKA_CONTAINER overrides the container to produce from (default pallet-kafka, from `make up`).
set -euo pipefail

usage() { echo "usage: $0 <orgId> <appId> <branch> [sha] [trigger]" >&2; exit 2; }
[[ $# -ge 3 && $# -le 5 ]] || usage

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

org_id="$1"
app_id="$2"
branch="$3"
trigger="${5:-WEBHOOK}"

# Every value lands inside a JSON string unescaped, so only characters that need no escaping are accepted.
[[ "$org_id" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "orgId must be 1-64 of [A-Za-z0-9._-]" >&2; exit 2; }
[[ "$app_id" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "appId must be 1-64 of [A-Za-z0-9._-]" >&2; exit 2; }
[[ "$branch" =~ ^[A-Za-z0-9._/-]{1,255}$ ]] || { echo "branch must be 1-255 of [A-Za-z0-9._/-]" >&2; exit 2; }
[[ "$trigger" =~ ^(WEBHOOK|MANUAL|LINKED|RECONCILED)$ ]] || { echo "trigger must be WEBHOOK, MANUAL, LINKED or RECONCILED" >&2; exit 2; }

new_uuid() {
  if command -v uuidgen >/dev/null; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

random_sha() {
  od -An -N20 -tx1 /dev/urandom | tr -d ' \n'
}

sha="${4:-$(random_sha)}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "sha must be 40 lowercase hex characters" >&2; exit 2; }

event_id="$(new_uuid)"
occurred_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
container="${KAFKA_CONTAINER:-pallet-kafka}"

payload="$(printf '{"eventId":"%s","eventType":"git.push.received","orgId":"%s","occurredAt":"%s","repo":"local/%s","branch":"%s","commitSha":"%s","appId":"%s","provider":"GITHUB","installationId":1,"repoId":1,"rootDirectory":null,"beforeSha":null,"forced":false,"trigger":"%s","headCommitMessage":"Sent by send-push.sh","headCommitAuthor":"local","deliveryId":"%s"}' \
  "$event_id" "$org_id" "$occurred_at" "$app_id" "$branch" "$sha" "$app_id" "$trigger" "$event_id")"

echo "git.push.received  orgId=$org_id appId=$app_id branch=$branch sha=$sha trigger=$trigger eventId=$event_id" >&2
printf 'event-id:%s,event-type:git.push.received,org-id:%s\t%s\t%s\n' "$event_id" "$org_id" "$org_id" "$payload" |
  docker exec -i "$container" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic git.push.received \
    --property parse.headers=true \
    --property parse.key=true
