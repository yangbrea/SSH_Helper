#!/usr/bin/env bash
# End-to-end native SSH smoke test against an AsyncSSH server.
# Requires Python asyncssh on the host; skipped when unavailable.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! python3 -c 'import asyncssh' >/dev/null 2>&1; then
    echo "[ssh-native] e2e skipped (asyncssh not installed)"
    exit 0
fi

test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-e2e.XXXXXX")"
port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-port.XXXXXX")"
server_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-server-err.XXXXXX")"
key_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-key.XXXXXX")"
trap 'rm -f "$test_binary" "$port_file" "$server_err" "$key_file"' EXIT

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_libssh2_e2e_test.cpp" \
    -lssh2 \
    -o "$test_binary"

python3 "$project_dir/scripts/ssh-native-test-server.py" >"$port_file" 2>"$server_err" &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$port_file" "$server_err" "$key_file"' EXIT

for _ in $(seq 1 50); do
    if [[ -s "$port_file" ]]; then
        break
    fi
    sleep 0.1
done

if [[ ! -s "$port_file" ]]; then
    echo "[ssh-native] e2e failed to start test server" >&2
    cat "$server_err" >&2 || true
    exit 1
fi

port="$(head -1 "$port_file")"
python3 - "$key_file" <<'PY'
import sys
import asyncssh
key = asyncssh.generate_private_key("ssh-ed25519")
key.write_private_key(sys.argv[1])
PY
"$test_binary" "$port" "$key_file"
echo "[ssh-native] e2e passed"
