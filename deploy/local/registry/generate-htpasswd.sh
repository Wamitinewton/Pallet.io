#!/usr/bin/env bash
# Creates the local registry's single user and the push credential build pods mount.
# Idempotent: an existing password is kept, so a running registry and the cluster's Secret stay valid.
# Requires: openssl, and htpasswd or docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTH_DIR="${SCRIPT_DIR}/auth"
USERNAME="pallet-builder"
PASSWORD_FILE="${AUTH_DIR}/password"
HTPASSWD_FILE="${AUTH_DIR}/htpasswd"
DOCKERCONFIG_FILE="${AUTH_DIR}/dockerconfig.json"
HTPASSWD_IMAGE="httpd:2.4.69-alpine"

umask 077
mkdir -p "${AUTH_DIR}"

if [[ ! -s "${PASSWORD_FILE}" ]]; then
  openssl rand -hex 24 > "${PASSWORD_FILE}"
  rm -f "${HTPASSWD_FILE}" "${DOCKERCONFIG_FILE}"
fi
password="$(cat "${PASSWORD_FILE}")"

if [[ ! -s "${HTPASSWD_FILE}" ]]; then
  if command -v htpasswd >/dev/null 2>&1; then
    htpasswd -Bbn "${USERNAME}" "${password}" > "${HTPASSWD_FILE}"
  else
    docker run --rm --entrypoint htpasswd "${HTPASSWD_IMAGE}" -Bbn "${USERNAME}" "${password}" > "${HTPASSWD_FILE}"
  fi
  # The registry container reads the file as its own user, so it has to be world-readable.
  chmod 0644 "${HTPASSWD_FILE}"
fi
chmod 0755 "${AUTH_DIR}"

if [[ ! -s "${DOCKERCONFIG_FILE}" ]]; then
  auth="$(printf '%s:%s' "${USERNAME}" "${password}" | base64 | tr -d '\n')"
  cat > "${DOCKERCONFIG_FILE}" <<EOF
{
  "auths": {
    "pallet-registry:5000": {"auth": "${auth}"},
    "localhost:5001": {"auth": "${auth}"}
  }
}
EOF
fi

echo "Registry user '${USERNAME}' ready. Push credential: ${DOCKERCONFIG_FILE}"
echo "Log in from the host with:"
echo "  docker login localhost:5001 -u ${USERNAME} --password-stdin < ${PASSWORD_FILE}"
