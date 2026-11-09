#!/bin/sh
# shellcheck shell=busybox
# push <imageRef> <strategy>
#
# Pushes the OCI archive the build step left in the workspace and reports the pushed digest.
set -euo pipefail
set +x

# shellcheck source=SCRIPTDIR/steps.sh
. /usr/local/lib/pallet/steps.sh

STEP=PUSH
EXIT_FAILED=20
EXIT_USAGE=2

ARCHIVE="${PALLET_WORKSPACE:-/workspace}/out/image.tar"
AUTH_FILE="${PALLET_REGISTRY_AUTH_FILE:-/var/run/pallet/registry/.dockerconfigjson}"
POLICY_FILE=/etc/pallet/policy.json

[ "$#" -eq 2 ] || finish "${STEP}" "${EXIT_USAGE}" "USAGE"
image_ref="$1"
strategy="$2"
is_image_ref "${image_ref}" || finish "${STEP}" "${EXIT_USAGE}" "USAGE"
case "${strategy}" in
  DOCKERFILE | BUILDPACKS) ;;
  *) finish "${STEP}" "${EXIT_USAGE}" "USAGE" ;;
esac

[ -s "${ARCHIVE}" ] || finish "${STEP}" "${EXIT_FAILED}" "ARCHIVE_MISSING"
[ -s "${AUTH_FILE}" ] || finish "${STEP}" "${EXIT_FAILED}" "CREDENTIAL_MISSING"

tls_verify=true
[ "${PALLET_REGISTRY_INSECURE:-false}" = true ] && tls_verify=false

digest_file="$(mktemp)"
trap 'rm -f "${digest_file}"' EXIT

skopeo --policy "${POLICY_FILE}" --tmpdir "${TMPDIR:-/tmp}" copy \
  --retry-times 3 \
  --authfile "${AUTH_FILE}" \
  --dest-tls-verify="${tls_verify}" \
  --digestfile "${digest_file}" \
  "oci-archive:${ARCHIVE}" "docker://${image_ref}" || finish "${STEP}" "${EXIT_FAILED}" "PUSH_ERROR"

digest="$(cat "${digest_file}")"
is_digest "${digest}" || finish "${STEP}" "${EXIT_FAILED}" "DIGEST_INVALID"

report "{\"step\":\"${STEP}\",\"digest\":\"${digest}\",\"strategy\":\"${strategy}\"}"
echo "${STEP}: pushed ${digest}"
