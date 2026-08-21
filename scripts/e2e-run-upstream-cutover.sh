#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 0 ]]; then
	echo "The cutover gate always runs the complete upstream suite." >&2
	exit 2
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

for run in 1 2 3; do
	echo "Running upstream cutover suite ${run}/3"
	CI=1 "${repo_root}/scripts/e2e-run-upstream.sh" --retries=0
done
