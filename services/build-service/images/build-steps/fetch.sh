#!/bin/sh
# shellcheck shell=busybox
# fetch <repoId> <commitSha> <rootDirectory> [dockerfilePath]
#
# Downloads one commit of one repository by id, extracts it under /workspace/src and checks that the
# root directory (and the Dockerfile, when given) exist inside it. The source token, when mounted, is
# revoked on every exit. Exit 10 means the source couldn't be fetched, 11 that it doesn't exist.
set -euo pipefail
set +x

# shellcheck source=SCRIPTDIR/steps.sh
. /usr/local/lib/pallet/steps.sh

STEP=FETCH
EXIT_UNAVAILABLE=10
EXIT_NOT_FOUND=11
EXIT_USAGE=2

API_URL="${PALLET_GITHUB_API_URL:-https://api.github.com}"
TOKEN_FILE="${PALLET_SOURCE_TOKEN_FILE:-/var/run/pallet/source/token}"
WORKSPACE="${PALLET_WORKSPACE:-/workspace}"
MAX_ARCHIVE_BYTES="${PALLET_FETCH_MAX_BYTES:-1073741824}"
SRC="${WORKSPACE}/src"
SCRATCH="$(mktemp -d)"
AUTH_CONFIG="${SCRATCH}/auth.conf"
ARCHIVE="${SCRATCH}/source.tar.gz"

[ "$#" -ge 3 ] && [ "$#" -le 4 ] || finish "${STEP}" "${EXIT_USAGE}" "USAGE"
repo_id="$1"
sha="$2"
root_directory="$3"
dockerfile_path="${4:-}"

printf '%s' "${repo_id}" | grep -Eq '^[1-9][0-9]{0,19}$' || finish "${STEP}" "${EXIT_USAGE}" "USAGE"
printf '%s' "${sha}" | grep -Eq '^[0-9a-f]{40}$' || finish "${STEP}" "${EXIT_USAGE}" "USAGE"

has_token=false
if [ -s "${TOKEN_FILE}" ]; then
  has_token=true
  # The header lives in a curl config file so the token never appears in an argument list.
  ( umask 077 && printf 'header = "Authorization: Bearer %s"\n' "$(cat "${TOKEN_FILE}")" > "${AUTH_CONFIG}" )
fi

revoke() {
  if [ "${has_token}" = true ]; then
    curl --silent --output /dev/null --max-time 10 --config "${AUTH_CONFIG}" \
      --header 'Accept: application/vnd.github+json' \
      --request DELETE "${API_URL}/installation/token" || true
    has_token=false
  fi
  rm -f "${AUTH_CONFIG}"
}

cleanup() {
  revoke
  rm -rf "${SCRATCH}"
}
trap cleanup EXIT
trap 'exit 143' TERM INT

auth_args=""
[ "${has_token}" = true ] && auth_args="--config ${AUTH_CONFIG}"

set +e
# Redirects to codeload.github.com are followed; curl never forwards the Authorization header to a
# different host.
# shellcheck disable=SC2086
status="$(curl --silent --show-error --location --max-redirs 3 \
  --connect-timeout 10 --max-time 900 \
  --retry 3 --retry-delay 2 --retry-connrefused \
  --max-filesize "${MAX_ARCHIVE_BYTES}" \
  --header 'Accept: application/vnd.github+json' \
  --header 'X-GitHub-Api-Version: 2022-11-28' \
  ${auth_args} \
  --output "${ARCHIVE}" --write-out '%{http_code}' \
  "${API_URL}/repositories/${repo_id}/tarball/${sha}" 2>/dev/null)"
curl_exit=$?
set -e
revoke

case "${curl_exit}" in
  0) ;;
  63) finish "${STEP}" "${EXIT_UNAVAILABLE}" "ARCHIVE_TOO_LARGE" ;;
  *) finish "${STEP}" "${EXIT_UNAVAILABLE}" "SOURCE_UNREACHABLE" ;;
esac
case "${status}" in
  200) ;;
  404 | 422) finish "${STEP}" "${EXIT_NOT_FOUND}" "COMMIT_NOT_FOUND" ;;
  401 | 403) finish "${STEP}" "${EXIT_UNAVAILABLE}" "SOURCE_REFUSED" ;;
  429) finish "${STEP}" "${EXIT_UNAVAILABLE}" "SOURCE_RATE_LIMITED" ;;
  *) finish "${STEP}" "${EXIT_UNAVAILABLE}" "SOURCE_UNREACHABLE" ;;
esac

bytes="$(wc -c < "${ARCHIVE}" | tr -d ' ')"

members="$(tar --list --gzip --file "${ARCHIVE}" 2>/dev/null)" || finish "${STEP}" "${EXIT_UNAVAILABLE}" "ARCHIVE_INVALID"
if printf '%s\n' "${members}" | grep -Eq '^/|(^|/)\.\.(/|$)'; then
  finish "${STEP}" "${EXIT_UNAVAILABLE}" "ARCHIVE_INVALID"
fi

umask 0002
mkdir -p "${SRC}" "${WORKSPACE}/out" "${WORKSPACE}/layers" "${WORKSPACE}/platform"
tar --extract --gzip --file "${ARCHIVE}" --directory "${SRC}" \
  --strip-components=1 --no-same-owner --no-same-permissions --no-overwrite-dir \
  --warning=no-timestamp 2>/dev/null || finish "${STEP}" "${EXIT_UNAVAILABLE}" "ARCHIVE_INVALID"
rm -f "${ARCHIVE}"
# The build step runs as a different user that shares the pod's fsGroup, and buildpacks write into
# the app directory. find never follows symlinks, so a link can't widen access outside the tree.
find "${SRC}" \( -type f -o -type d \) -exec chmod g+rwX {} +

src_real="$(readlink -f "${SRC}")"

# inside <path>: true when path resolves, through any symlinks, to src_real or below it.
inside() {
  case "$1" in
    "${src_real}" | "${src_real}"/*) return 0 ;;
    *) return 1 ;;
  esac
}

case "${root_directory}" in
  /* | ../* | */../* | */.. | ..) finish "${STEP}" "${EXIT_NOT_FOUND}" "ROOT_DIRECTORY_NOT_FOUND" ;;
esac
root_real="$(readlink -f "${SRC}/${root_directory}" 2>/dev/null || true)"
if [ -z "${root_real}" ] || ! inside "${root_real}" || [ ! -d "${root_real}" ]; then
  finish "${STEP}" "${EXIT_NOT_FOUND}" "ROOT_DIRECTORY_NOT_FOUND"
fi

if [ -n "${dockerfile_path}" ]; then
  case "${dockerfile_path}" in
    /* | ../* | */../* | */.. | ..) finish "${STEP}" "${EXIT_NOT_FOUND}" "DOCKERFILE_NOT_FOUND" ;;
  esac
  dockerfile_real="$(readlink -f "${root_real}/${dockerfile_path}" 2>/dev/null || true)"
  if [ -z "${dockerfile_real}" ] || ! inside "${dockerfile_real}" || [ ! -f "${dockerfile_real}" ]; then
    finish "${STEP}" "${EXIT_NOT_FOUND}" "DOCKERFILE_NOT_FOUND"
  fi
fi

report "{\"step\":\"${STEP}\",\"bytes\":${bytes}}"
echo "${STEP}: fetched ${bytes} bytes"
