#!/usr/bin/env bash
# Native keepalive E2E: live connection and remote-close disconnect detection.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! python3 -c 'import asyncssh' >/dev/null 2>&1; then
    echo "[ssh-native] keepalive E2E skipped (asyncssh not installed)"
    exit 0
fi

binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-keepalive.XXXXXX")"
live_port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-keepalive-live-port.XXXXXX")"
die_port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-keepalive-die-port.XXXXXX")"
live_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-keepalive-live-err.XXXXXX")"
die_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-keepalive-die-err.XXXXXX")"
live_pid=""
die_pid=""
trap 'kill "$live_pid" "$die_pid" 2>/dev/null || true; rm -f "$binary" "$live_port_file" "$die_port_file" "$live_err" "$die_err"' EXIT

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
    "$project_dir/app/src/main/cpp/ssh/ssh_keepalive_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_persistent_session.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_shell_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_keepalive_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$binary"

python3 "$project_dir/scripts/ssh-native-keepalive-test-server.py" \
    >"$live_port_file" 2>"$live_err" &
live_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$live_port_file" ]]; then break; fi
    sleep 0.1
done
if [[ ! -s "$live_port_file" ]]; then echo "live keepalive server failed" >&2; cat "$live_err" >&2; exit 1; fi
read -r live_port < "$live_port_file"
"$binary" "$live_port" 0
echo "[ssh-native] keepalive live E2E passed"

python3 "$project_dir/scripts/ssh-native-keepalive-test-server.py" 1 \
    >"$die_port_file" 2>"$die_err" &
die_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$die_port_file" ]]; then break; fi
    sleep 0.1
done
if [[ ! -s "$die_port_file" ]]; then echo "die keepalive server failed" >&2; cat "$die_err" >&2; exit 1; fi
read -r die_port < "$die_port_file"
"$binary" "$die_port" 1
echo "[ssh-native] keepalive remote-close E2E passed"
