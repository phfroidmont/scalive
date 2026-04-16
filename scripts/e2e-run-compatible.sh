#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
allowlist_file="${repo_root}/test/e2e-allowlist.txt"
config_file="${repo_root}/test/playwright.upstream.config.js"

if [[ ! -f "${allowlist_file}" ]]; then
	echo "Missing allowlist: ${allowlist_file}" >&2
	exit 1
fi

if [[ ! -f "${config_file}" ]]; then
	echo "Missing Playwright config: ${config_file}" >&2
	exit 1
fi

upstream_root="$(${repo_root}/scripts/e2e-sync-upstream.sh)"

if [[ ! -f "${upstream_root}/package.json" ]]; then
	echo "Upstream cache missing package.json: ${upstream_root}" >&2
	exit 1
fi

if [[ ! -d "${upstream_root}/node_modules" ]]; then
	echo "Installing upstream npm dependencies in ${upstream_root}"
	npm ci --prefix "${upstream_root}"
fi

playwright_bin="$(command -v playwright || true)"
if [[ -z "${playwright_bin}" ]]; then
	echo "Missing playwright CLI in PATH. Enter via nix develop." >&2
	exit 1
fi

export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS="true"
if [[ -z "${PLAYWRIGHT_TEST_NODE_PATH:-}" ]]; then
	echo "PLAYWRIGHT_TEST_NODE_PATH is not set. Enter via nix develop." >&2
	exit 1
fi

rm -rf \
	"${upstream_root}/node_modules/@playwright" \
	"${upstream_root}/node_modules/playwright" \
	"${upstream_root}/node_modules/playwright-core"

ln -s "${PLAYWRIGHT_TEST_NODE_PATH}/@playwright" "${upstream_root}/node_modules/@playwright"
ln -s "${PLAYWRIGHT_TEST_NODE_PATH}/playwright" "${upstream_root}/node_modules/playwright"
ln -s "${PLAYWRIGHT_TEST_NODE_PATH}/playwright-core" "${upstream_root}/node_modules/playwright-core"

test_files=()
while IFS= read -r line || [[ -n "${line}" ]]; do
	trimmed="${line## }"
	trimmed="${trimmed%% }"
	if [[ -z "${trimmed}" ]] || [[ "${trimmed:0:1}" == "#" ]]; then
		continue
	fi
	test_files+=("${trimmed}")
done <"${allowlist_file}"

if [[ ${#test_files[@]} -eq 0 ]]; then
	echo "Allowlist is empty: ${allowlist_file}" >&2
	exit 1
fi

export SCALIVE_REPO_ROOT="${repo_root}"
export LV_LOCAL_E2E_DIR="${upstream_root}"

"${playwright_bin}" test \
	--config "${config_file}" \
	"${test_files[@]}" \
	"$@"
