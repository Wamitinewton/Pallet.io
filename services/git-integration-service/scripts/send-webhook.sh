#!/usr/bin/env bash
# Signs a recorded webhook fixture with the local webhook secret and POSTs it the way GitHub does.
#
#   scripts/send-webhook.sh <fixture.json> [delivery-guid] [event]
#
# The event defaults to the fixture's file-name prefix (push-main.json -> push), the GUID to a new one.
# TARGET overrides the URL (e.g. the gateway on :8083). TAMPER=1 changes the body after signing, which must get a 401.
set -euo pipefail

usage() { echo "usage: $0 <fixture.json> [delivery-guid] [event]" >&2; exit 2; }
[[ $# -ge 1 && $# -le 3 ]] || usage

fixture="$1"
[[ -f "$fixture" ]] || { echo "no such fixture: $fixture" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

service_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$service_dir/keys.properties"
secret="${PALLET_GIT_WEBHOOK_SECRET:-}"
if [[ -z "$secret" && -f "$env_file" ]]; then
  secret="$(grep -E '^PALLET_GIT_WEBHOOK_SECRET=' "$env_file" | head -n 1 | cut -d= -f2-)"
fi
[[ -n "$secret" ]] || { echo "PALLET_GIT_WEBHOOK_SECRET is not set and not in $env_file (run: make dev-secrets)" >&2; exit 1; }

new_guid() {
  if command -v uuidgen >/dev/null; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

default_event() {
  local base
  base="$(basename "$1" .json)"
  case "$base" in
    installation-repositories-*) echo installation_repositories ;;
    github-app-authorization-*) echo github_app_authorization ;;
    *) echo "${base%%-*}" ;;
  esac
}

guid="${2:-$(new_guid)}"
event="${3:-$(default_event "$fixture")}"
target="${TARGET:-http://localhost:8085/api/v1/git-integration/webhooks/github}"

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
cp "$fixture" "$body"

signature="sha256=$(openssl dgst -sha256 -hmac "$secret" -r < "$body" | cut -d' ' -f1)"
if [[ "${TAMPER:-0}" == "1" ]]; then
  printf ' ' >> "$body"
fi

echo "POST $target  event=$event  delivery=$guid" >&2
curl -sS -X POST "$target" \
  -H "Content-Type: application/json" \
  -H "User-Agent: GitHub-Hookshot/pallet-dev" \
  -H "X-GitHub-Event: $event" \
  -H "X-GitHub-Delivery: $guid" \
  -H "X-Hub-Signature-256: $signature" \
  --data-binary "@$body" \
  -w '\nHTTP %{http_code}\n'
