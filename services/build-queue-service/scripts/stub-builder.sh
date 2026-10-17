#!/usr/bin/env bash
# Stands in for build-service against the local Kafka: consumes build.dispatched and answers with build.started, then
# build.succeeded or build.failed, keyed by orgId, the way build-service's contract requires (ARCHITECTURE.md
# §Contract with build-service). A buildId it has already accepted is ignored whatever the attempt, and an attempt
# whose startBy has passed is dropped and counted.
#
#   scripts/stub-builder.sh [--ignore | --fail | --start-only] [--slow <seconds>] [--late-start <seconds>]
#
#   --ignore              consume and do nothing: a dead builder
#   --fail                start, then fail every build
#   --start-only          start, never finish: a builder that dies mid-build
#   --slow <seconds>      run each build for this long (default 2)
#   --late-start <secs>   wait this long, then start even past startBy: a contract violator
#
# Each report carries the dispatch's traceparent header, so it joins the trace the build was requested in.
#
# It joins its own consumer group (STUB_BUILDER_GROUP, default stub-builder), so only dispatches it hasn't read yet are
# run. KAFKA_CONTAINER overrides the container to consume and produce from (default pallet-kafka, from `make up`).
set -euo pipefail

usage() {
  echo "usage: $0 [--ignore | --fail | --start-only] [--slow <seconds>] [--late-start <seconds>]" >&2
  exit 2
}

behaviour=normal
run_seconds=2
late_start_seconds=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ignore) behaviour=ignore ;;
    --fail) behaviour=fail ;;
    --start-only) behaviour=start-only ;;
    --slow) [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || usage; run_seconds="$2"; shift ;;
    --late-start) [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || usage; late_start_seconds="$2"; shift ;;
    *) usage ;;
  esac
  shift
done

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

container="${KAFKA_CONTAINER:-pallet-kafka}"
group="${STUB_BUILDER_GROUP:-stub-builder}"
kafka_bin=/opt/kafka/bin
# Tags every Kafka client this run starts, so cleanup stops exactly those inside the container: killing a docker exec
# client leaves its process running there, and a leftover consumer would keep a seat in the group.
run_id="stub-builder-$$"

cleanup() {
  trap - EXIT INT TERM
  docker exec "$container" pkill -f -- "client.id=$run_id" 2>/dev/null || true
  local running
  running="$(jobs -p)"
  [[ -z "$running" ]] || kill $running 2>/dev/null || true
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

new_uuid() {
  if command -v uuidgen >/dev/null; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

producer() {
  docker exec -i "$container" "$kafka_bin/kafka-console-producer.sh" \
    --bootstrap-server localhost:9092 \
    --topic "$1" \
    --producer-property "client.id=$run_id-$1" \
    --property parse.headers=true \
    --property parse.key=true >/dev/null
}

# One producer per topic for the whole run. Each report is a single short line written in one call, so reports from
# builds running side by side never interleave.
exec {started_fd}> >(producer build.started)
exec {succeeded_fd}> >(producer build.succeeded)
exec {failed_fd}> >(producer build.failed)

# report <fd> <event-type> <traceparent> <orgId> <buildId> <appId> <commitSha> <pushEventId> [reason]
report() {
  local fd="$1" type="$2" traceparent="$3" org_id="$4" build_id="$5" app_id="$6" commit_sha="$7" push_event_id="$8"
  local reason="${9:-}" event_id payload headers
  event_id="$(new_uuid)"
  payload="$(jq -cn \
    --arg eventId "$event_id" --arg eventType "$type" --arg orgId "$org_id" \
    --arg occurredAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg buildId "$build_id" --arg appId "$app_id" \
    --arg commitSha "$commit_sha" --arg pushEventId "$push_event_id" --arg reason "$reason" \
    '{eventId: $eventId, eventType: $eventType, orgId: $orgId, occurredAt: $occurredAt, buildId: $buildId,
      appId: $appId, commitSha: $commitSha, pushEventId: $pushEventId}
     + (if $eventType == "build.failed" then {reason: $reason} else {} end)')"
  headers="event-id:$event_id,event-type:$type,org-id:$org_id${traceparent:+,traceparent:$traceparent}"
  printf '%s\t%s\t%s\n' "$headers" "$org_id" "$payload" >&"$fd"
  echo "$type  orgId=$org_id appId=$app_id buildId=$build_id" >&2
}

build() {
  local traceparent="$1" org_id="$2" build_id="$3" app_id="$4" commit_sha="$5" push_event_id="$6"
  sleep "$late_start_seconds"
  report "$started_fd" build.started "$traceparent" "$org_id" "$build_id" "$app_id" "$commit_sha" "$push_event_id"
  [[ "$behaviour" == start-only ]] && return
  sleep "$run_seconds"
  if [[ "$behaviour" == fail ]]; then
    report "$failed_fd" build.failed "$traceparent" "$org_id" "$build_id" "$app_id" "$commit_sha" "$push_event_id" \
      "stub-builder.sh failed the build"
  else
    report "$succeeded_fd" build.succeeded "$traceparent" "$org_id" "$build_id" "$app_id" "$commit_sha" \
      "$push_event_id"
  fi
}

declare -A accepted
dropped_late=0
dropped_duplicate=0

echo "stub-builder: behaviour=$behaviour run=${run_seconds}s late-start=${late_start_seconds}s group=$group" >&2

while IFS=$'\t' read -r build_id attempt org_id app_id commit_sha push_event_id start_by traceparent; do
  if [[ -n "${accepted[$build_id]:-}" ]]; then
    dropped_duplicate=$((dropped_duplicate + 1))
    echo "ignored  buildId=$build_id attempt=$attempt: already accepted (duplicates=$dropped_duplicate)" >&2
    continue
  fi
  if [[ "$late_start_seconds" -eq 0 && "$(date -u +%s)" -ge "$(date -u -d "$start_by" +%s)" ]]; then
    dropped_late=$((dropped_late + 1))
    echo "dropped  buildId=$build_id attempt=$attempt: past startBy $start_by (late=$dropped_late)" >&2
    continue
  fi
  accepted[$build_id]=1
  echo "accepted buildId=$build_id attempt=$attempt orgId=$org_id appId=$app_id" >&2
  [[ "$behaviour" == ignore ]] && continue
  build "$traceparent" "$org_id" "$build_id" "$app_id" "$commit_sha" "$push_event_id" &
done < <(docker exec "$container" "$kafka_bin/kafka-console-consumer.sh" \
  --bootstrap-server localhost:9092 \
  --topic build.dispatched \
  --group "$group" \
  --consumer-property "client.id=$run_id" \
  --property print.headers=true |
  jq --unbuffered -R -r 'capture("^(?<headers>[^\t]*)\t(?<value>.*)$")
    | (.headers | capture("(^|,)traceparent:(?<t>[^,]+)").t // "") as $traceparent
    | .value | fromjson
    | [.buildId, .attempt, .orgId, .appId, .commitSha, .pushEventId, .startBy, $traceparent] | @tsv')
