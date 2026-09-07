#!/usr/bin/env bash
# Host-only POC for the libssh2 custom send/recv transport used by jump host
# native support. Requires Python asyncssh; skipped when unavailable.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! python3 -c 'import asyncssh' >/dev/null 2>&1; then
    echo "[ssh-native] jump POC skipped (asyncssh not installed)"
    exit 0
fi

test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-poc.XXXXXX")"
port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-poc-port.XXXXXX")"
server_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-poc-err.XXXXXX")"
server_pid=""
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$port_file" "$server_err"' EXIT

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/test/cpp/ssh_jump_custom_transport_poc_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$test_binary"

python3 "$project_dir/scripts/ssh-native-jump-test-server.py" \
    >"$port_file" 2>"$server_err" &
server_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$port_file" ]]; then
        break
    fi
    sleep 0.1
done
if [[ ! -s "$port_file" ]]; then
    echo "[ssh-native] jump POC failed to start test server" >&2
    cat "$server_err" >&2 || true
    exit 1
fi

read -r target_port jump_port < "$port_file"
"$test_binary" "$target_port" "$jump_port"
echo "[ssh-native] jump POC passed"
