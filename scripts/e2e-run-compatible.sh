#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
allowlist_file="${repo_root}/test/e2e-allowlist.txt"
config_file="${repo_root}/test/playwright.upstream.config.js"

kill_process_tree() {
	local pid="$1"
	local children

	children="$(pgrep -P "${pid}" || true)"
	if [[ -n "${children}" ]]; then
		for child in ${children}; do
			kill_process_tree "${child}"
		done
	fi

	kill "${pid}" 2>/dev/null || true
	sleep 0.1
	kill -9 "${pid}" 2>/dev/null || true
}

cleanup_stale_e2e_run() {
	local active_file process_dir ppid pid

	active_file="${repo_root}/out/mill-active.json"
	if [[ ! -f "${active_file}" ]]; then
		return
	fi

	process_dir="$(sed -n 's/.*"command":"e2eApp.run","processDir":"\([^"]*\)".*/\1/p' "${active_file}")"
	if [[ -z "${process_dir}" ]]; then
		return
	fi

	for pid in $(pgrep -f "${process_dir}" || true); do
		if [[ "${pid}" == "$$" ]]; then
			continue
		fi

		ppid="$(ps -o ppid= -p "${pid}" 2>/dev/null | tr -d ' ')"
		if [[ "${ppid}" != "1" ]]; then
			continue
		fi

		kill_process_tree "${pid}"
	done
}

playwright_pid=""

cleanup_on_exit() {
	local status="$?"

	trap - EXIT INT TERM

	if [[ -n "${playwright_pid}" ]]; then
		kill "${playwright_pid}" 2>/dev/null || true
		for pid in $(pgrep -P "${playwright_pid}" || true); do
			kill_process_tree "${pid}"
		done
	fi

	cleanup_stale_e2e_run
	exit "${status}"
}

trap cleanup_on_exit EXIT INT TERM

cleanup_stale_e2e_run

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
	"$@" &

playwright_pid="$!"
wait "${playwright_pid}"
