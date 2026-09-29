#!/usr/bin/env bash
# Writes throwaway key material to .env.local (git-ignored) so the service can start locally.
# These values cannot talk to GitHub; a real development GitHub App replaces them (checkpoint 21).
set -euo pipefail

cd "$(dirname "$0")/.."
target=".env.local"

if [[ -f "$target" && "${1:-}" != "--force" ]]; then
    echo "$target already exists; rerun with --force to replace it" >&2
    exit 1
fi

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

umask 077
private_key="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
random_base64() { openssl rand -base64 32 | tr -d '\n'; }

cat > "$target" <<EOF
PALLET_GIT_GITHUB_PRIVATE_KEY="${private_key}"
PALLET_GIT_GITHUB_CLIENT_SECRET="$(openssl rand -hex 20)"
PALLET_GIT_WEBHOOK_SECRET="$(openssl rand -hex 32)"
PALLET_GIT_STATE_SIGNING_KEY="$(random_base64)"
PALLET_GIT_USER_SESSION_KEY="$(random_base64)"
EOF

echo "Wrote $target"
