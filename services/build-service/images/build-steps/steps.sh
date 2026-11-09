# shellcheck shell=busybox
# Shared by the step scripts. Every value written to the termination message is either a fixed
# vocabulary word, a digest or a number, so nothing a tenant or GitHub produced can reach it.

TERMINATION_LOG="${PALLET_TERMINATION_LOG:-/dev/termination-log}"

# report <json>: the single line build-service reads back from the container status.
report() {
  printf '%s\n' "$1" > "${TERMINATION_LOG}" 2>/dev/null || true
}

# finish <step> <exit code> <error word>
finish() {
  report "{\"step\":\"$1\",\"error\":\"$3\"}"
  echo "${1}: ${3}" >&2
  exit "$2"
}

is_digest() {
  printf '%s' "$1" | grep -Eq '^sha256:[0-9a-f]{64}$'
}

is_image_ref() {
  printf '%s' "$1" | grep -Eq '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?/[a-z0-9]+([._/-][a-z0-9]+)*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'
}
