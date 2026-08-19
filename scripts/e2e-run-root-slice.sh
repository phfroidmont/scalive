#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
app_root="${repo_root}/rootSliceApp"
config_file="${app_root}/playwright.config.js"

if [[ -z "${PLAYWRIGHT_TEST_NODE_PATH:-}" ]]; then
	echo "PLAYWRIGHT_TEST_NODE_PATH is not set. Enter via nix develop." >&2
	exit 1
fi

playwright_bin="$(command -v playwright || true)"
if [[ -z "${playwright_bin}" ]]; then
	echo "Missing playwright CLI in PATH. Enter via nix develop." >&2
	exit 1
fi

mill --ticker false rootSliceApp.bundle

mkdir -p "${app_root}/node_modules"
for package in @playwright playwright playwright-core; do
	target="${app_root}/node_modules/${package}"
	if [[ ! -e "${target}" && ! -L "${target}" ]]; then
		ln -s "${PLAYWRIGHT_TEST_NODE_PATH}/${package}" "${target}"
	fi
done

export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS="true"
"${playwright_bin}" test --config "${config_file}" "$@"
