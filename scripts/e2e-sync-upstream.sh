#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${LV_UPSTREAM_SRC:-}" ]]; then
	echo "LV_UPSTREAM_SRC is not set. Enter via nix develop." >&2
	exit 1
fi

if [[ -z "${LV_UPSTREAM_REV:-}" ]]; then
	echo "LV_UPSTREAM_REV is not set. Enter via nix develop." >&2
	exit 1
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
dest_dir="${repo_root}/.e2e-upstream/phoenix_live_view/${LV_UPSTREAM_REV}"

mkdir -p "${dest_dir}"

rsync \
	--archive \
	--delete \
	--chmod="Du+rwx,Fu+rw" \
	--exclude ".git" \
	--exclude "node_modules" \
	--exclude ".playwright-browsers" \
	"${LV_UPSTREAM_SRC}/" \
	"${dest_dir}/"

patch_file="${repo_root}/test/upstream-patches/${LV_UPSTREAM_REV}.patch"
if [[ ! -f "${patch_file}" ]]; then
	echo "Missing upstream synchronization patch: ${patch_file}" >&2
	exit 1
fi

relative_dest="${dest_dir#"${repo_root}/"}"
git -C "${repo_root}" apply --check --directory="${relative_dest}" "${patch_file}"
git -C "${repo_root}" apply --directory="${relative_dest}" "${patch_file}"

printf '%s\n' "${dest_dir}"
