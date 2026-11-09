#!/usr/bin/env bash
# render.sh <job.yaml> KEY=value...
#
# Fills a Job template's ${KEY} placeholders the way build-service will, and refuses to print a
# manifest with any placeholder left unfilled.
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <job.yaml> KEY=value..." >&2; exit 2; }
manifest="$(cat "$1")"
shift

for pair in "$@"; do
  key="${pair%%=*}"
  value="${pair#*=}"
  [[ "${key}" =~ ^[A-Z_]+$ ]] || { echo "bad key: ${key}" >&2; exit 2; }
  manifest="${manifest//\$\{${key}\}/${value}}"
done

if left="$(grep -oE '\$\{[A-Z_]+\}' <<<"${manifest}" | sort -u)" && [[ -n "${left}" ]]; then
  echo "unfilled placeholders: $(tr '\n' ' ' <<<"${left}")" >&2
  exit 1
fi
printf '%s\n' "${manifest}"
