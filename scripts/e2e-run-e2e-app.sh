#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
app_root="${repo_root}/e2eApp"

if [[ -z "${PLAYWRIGHT_TEST_NODE_PATH:-}" ]]; then
	echo "PLAYWRIGHT_TEST_NODE_PATH is not set. Enter via nix develop." >&2
	exit 1
fi

playwright_bin="$(command -v playwright || true)"
if [[ -z "${playwright_bin}" ]]; then
	echo "Missing playwright CLI in PATH. Enter via nix develop." >&2
	exit 1
fi

mill --ticker false e2eApp.compile

# The app's node_modules is owned and recreated by Mill's asset build.
node_modules="${app_root}/test/node_modules"
mkdir -p "${node_modules}"
for package in @playwright playwright playwright-core; do
	source="${PLAYWRIGHT_TEST_NODE_PATH}/${package}"
	target="${node_modules}/${package}"
	if [[ ! -e "${source}" ]]; then
		echo "Missing Playwright package: ${source}" >&2
		exit 1
	fi
	if [[ -L "${target}" || ! -e "${target}" ]]; then
		ln -sfn "${source}" "${target}"
	fi
done

export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS="true"
"${playwright_bin}" test --config "${app_root}/playwright.config.js" "$@"
