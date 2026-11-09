#!/bin/sh
# shellcheck shell=busybox
# report <imageRef>
#
# Turns the buildpacks lifecycle's export report into the same termination message the push step
# writes, after checking the lifecycle exported the reference it was asked to.
set -euo pipefail
set +x

# shellcheck source=SCRIPTDIR/steps.sh
. /usr/local/lib/pallet/steps.sh

STEP=PUSH
EXIT_FAILED=20
EXIT_USAGE=2

REPORT_FILE="${PALLET_WORKSPACE:-/workspace}/layers/report.toml"

[ "$#" -eq 1 ] || finish "${STEP}" "${EXIT_USAGE}" "USAGE"
image_ref="$1"
is_image_ref "${image_ref}" || finish "${STEP}" "${EXIT_USAGE}" "USAGE"

[ -s "${REPORT_FILE}" ] || finish "${STEP}" "${EXIT_FAILED}" "REPORT_MISSING"

digest="$(sed -n 's/^[[:space:]]*digest[[:space:]]*=[[:space:]]*"\(sha256:[0-9a-f]\{64\}\)"[[:space:]]*$/\1/p' "${REPORT_FILE}" | head -n 1)"
is_digest "${digest}" || finish "${STEP}" "${EXIT_FAILED}" "DIGEST_INVALID"
grep -Fq "\"${image_ref}\"" "${REPORT_FILE}" || finish "${STEP}" "${EXIT_FAILED}" "REFERENCE_MISMATCH"

report "{\"step\":\"${STEP}\",\"digest\":\"${digest}\",\"strategy\":\"BUILDPACKS\"}"
echo "${STEP}: exported ${digest}"
