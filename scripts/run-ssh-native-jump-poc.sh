#!/usr/bin/env bash
# Host-only jump-host tests for the libssh2 custom send/recv transport.
#
# Runs:
#   1. a raw libssh2 POC over a direct-tcpip channel;
#   2. a production-runtime jump route test (jump session + nested target session).
#
# Requires Python asyncssh; skipped when unavailable.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! python3 -c 'import asyncssh' >/dev/null 2>&1; then
    echo "[ssh-native] jump tests skipped (asyncssh not installed)"
    exit 0
fi

poc_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-poc.XXXXXX")"
runtime_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-jump.XXXXXX")"
port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-port.XXXXXX")"
server_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-jump-err.XXXXXX")"
server_pid=""
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$poc_binary" "$runtime_binary" "$port_file" "$server_err"' EXIT

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
    -o "$poc_binary"

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_connect_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_jump_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_persistent_session.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_jump_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_binary"

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
    echo "[ssh-native] jump tests failed to start test server" >&2
    cat "$server_err" >&2 || true
    exit 1
fi

read -r target_port jump_port < "$port_file"
"$poc_binary" "$target_port" "$jump_port"
echo "[ssh-native] jump custom transport POC passed"
"$runtime_binary" "$target_port" "$jump_port"
echo "[ssh-native] jump runtime E2E passed"
