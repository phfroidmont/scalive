#!/usr/bin/env bash
set -euo pipefail

jar=${1:-out/documentation/site/assembly.dest/out.jar}
revision=${2:-$(git rev-parse HEAD)}
port=${SCALIVE_SMOKE_PORT:-}

if [[ ! -f "$jar" ]]; then
  printf 'Documentation assembly not found: %s\n' "$jar" >&2
  exit 64
fi
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'Expected a full lowercase Git revision, got: %s\n' "$revision" >&2
  exit 64
fi
if [[ -z "$port" ]]; then
  port=$(node -e '
    const server = require("node:net").createServer()
    server.listen(0, "127.0.0.1", () => {
      console.log(server.address().port)
      server.close()
    })
  ')
fi

workdir=$(mktemp -d)
pid=
# Invoked through the EXIT trap.
# shellcheck disable=SC2329
cleanup() {
  if [[ -n "$pid" ]]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf "$workdir"
}
trap cleanup EXIT

SCALIVE_SERVER_PORT="$port" \
SCALIVE_PUBLIC_ORIGIN=https://scalive.dev \
SCALIVE_TOKEN_SECRET=documentation-assembly-smoke-secret \
  java -jar "$jar" >"$workdir/server.log" 2>&1 &
pid=$!

for _ in {1..30}; do
  if ! kill -0 "$pid" 2>/dev/null; then
    cat "$workdir/server.log" >&2
    exit 1
  fi
  if response=$(curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:$port/health" 2>/dev/null); then
    if [[ "$response" == "$revision" ]] && kill -0 "$pid" 2>/dev/null; then
      exit 0
    fi
    printf 'Expected revision %s, got %s\n' "$revision" "$response" >&2
    exit 1
  fi
  sleep 1
done

cat "$workdir/server.log" >&2
printf 'Documentation assembly did not become healthy\n' >&2
exit 1
