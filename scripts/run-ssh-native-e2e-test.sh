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
key.write_private_key(sys.argv[1], passphrase="secret")
PY
"$test_binary" "$port" "$key_file"

gate_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-gate.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_blocking_connection.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_blocking_connection_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$gate_binary"
"$gate_binary" "$port"

runtime_handshake_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-handshake.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_operations.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_handshake_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_handshake_binary"
"$runtime_handshake_binary" "$port"

runtime_tcp_handshake_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-tcp-handshake.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_handshake_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_tcp_handshake_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_tcp_handshake_binary"
"$runtime_tcp_handshake_binary" "$port"

runtime_password_auth_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-password-auth.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_operations.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_password_auth_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_password_auth_binary"
"$runtime_password_auth_binary" "$port"

runtime_private_key_auth_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-private-key-auth.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_operations.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_private_key_auth_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_private_key_auth_binary"
"$runtime_private_key_auth_binary" "$port" "$key_file"

runtime_password_exec_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-password-exec.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_operations.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_password_exec_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_password_exec_binary"
"$runtime_password_exec_binary" "$port"

runtime_direct_password_exec_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-direct-password-exec.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_direct_password_exec_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_direct_password_exec_binary"
"$runtime_direct_password_exec_binary" "$port"

runtime_direct_private_key_exec_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-direct-private-key-exec.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_direct_private_key_exec_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_direct_private_key_exec_binary"
"$runtime_direct_private_key_exec_binary" "$port" "$key_file"

runtime_direct_stderr_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-direct-stderr.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_direct_stderr_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_direct_stderr_binary"
"$runtime_direct_stderr_binary" "$port"

runtime_direct_output_limit_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-direct-output-limit.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_direct_output_limit_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_direct_output_limit_binary"
"$runtime_direct_output_limit_binary" "$port"

runtime_hostkey_mismatch_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-hostkey-mismatch.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_hostkey_mismatch_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_hostkey_mismatch_binary"
"$runtime_hostkey_mismatch_binary" "$port"

runtime_hostkey_match_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-hostkey-match.XXXXXX")"
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$runtime_hostkey_match_binary" "$port_file" "$server_err" "$key_file"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_operations.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_hostkey_match_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_hostkey_match_binary"
"$runtime_hostkey_match_binary" "$port"

runtime_transport_handoff_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-transport-handoff.XXXXXX")"
trap 'kill "$server_pid" "$kbdint_server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$runtime_hostkey_match_binary" "$runtime_transport_handoff_binary" "$port_file" "$server_err" "$key_file" "$kbdint_port_file" "$kbdint_server_err" "$kbdint_binary"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_connect_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_transport_handoff_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_transport_handoff_binary"
"$runtime_transport_handoff_binary" "$port"

http_proxy_port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-http-proxy-port.XXXXXX")"
http_proxy_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-http-proxy-err.XXXXXX")"
socks_proxy_port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-socks-proxy-port.XXXXXX")"
socks_proxy_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-socks-proxy-err.XXXXXX")"
runtime_proxy_exec_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-proxy-exec.XXXXXX")"
trap 'kill "$server_pid" "$http_proxy_pid" "$socks_proxy_pid" "$kbdint_server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$runtime_hostkey_match_binary" "$runtime_transport_handoff_binary" "$runtime_proxy_exec_binary" "$port_file" "$server_err" "$key_file" "$http_proxy_port_file" "$http_proxy_err" "$socks_proxy_port_file" "$socks_proxy_err" "$kbdint_port_file" "$kbdint_server_err" "$kbdint_binary"' EXIT

python3 "$project_dir/scripts/ssh-native-test-proxy.py" http "$port" >"$http_proxy_port_file" 2>"$http_proxy_err" &
http_proxy_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$http_proxy_port_file" ]]; then
        break
    fi
    sleep 0.1
done
if [[ ! -s "$http_proxy_port_file" ]]; then
    echo "[ssh-native] e2e failed to start http proxy" >&2
    cat "$http_proxy_err" >&2 || true
    exit 1
fi
http_proxy_port="$(head -1 "$http_proxy_port_file")"

python3 "$project_dir/scripts/ssh-native-test-proxy.py" socks5 "$port" >"$socks_proxy_port_file" 2>"$socks_proxy_err" &
socks_proxy_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$socks_proxy_port_file" ]]; then
        break
    fi
    sleep 0.1
done
if [[ ! -s "$socks_proxy_port_file" ]]; then
    echo "[ssh-native] e2e failed to start socks proxy" >&2
    cat "$socks_proxy_err" >&2 || true
    exit 1
fi
socks_proxy_port="$(head -1 "$socks_proxy_port_file")"

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_proxy_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socks5_operation.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_proxy_exec_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_proxy_exec_binary"
"$runtime_proxy_exec_binary" "$port" http "$http_proxy_port" "$key_file"
"$runtime_proxy_exec_binary" "$port" socks5 "$socks_proxy_port" "$key_file"
kill "$http_proxy_pid" "$socks_proxy_pid" 2>/dev/null || true

kbdint_port_file="$(mktemp "${TMPDIR:-/tmp}/ssh-native-kbdint-port.XXXXXX")"
kbdint_server_err="$(mktemp "${TMPDIR:-/tmp}/ssh-native-kbdint-err.XXXXXX")"
kbdint_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-kbdint.XXXXXX")"
trap 'kill "$server_pid" "$kbdint_server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$runtime_hostkey_match_binary" "$kbdint_binary" "$port_file" "$server_err" "$key_file" "$kbdint_port_file" "$kbdint_server_err"' EXIT

python3 "$project_dir/scripts/ssh-native-test-server.py" kbdint >"$kbdint_port_file" 2>"$kbdint_server_err" &
kbdint_server_pid=$!
for _ in $(seq 1 50); do
    if [[ -s "$kbdint_port_file" ]]; then
        break
    fi
    sleep 0.1
done
if [[ ! -s "$kbdint_port_file" ]]; then
    echo "[ssh-native] e2e failed to start kbdint test server" >&2
    cat "$kbdint_server_err" >&2 || true
    exit 1
fi
kbdint_port="$(head -1 "$kbdint_port_file")"

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_blocking_connection.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_keyboard_interactive_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$kbdint_binary"
"$kbdint_binary" "$kbdint_port"

runtime_direct_kbdint_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-runtime-direct-kbdint.XXXXXX")"
trap 'kill "$server_pid" "$kbdint_server_pid" 2>/dev/null || true; rm -f "$test_binary" "$gate_binary" "$runtime_handshake_binary" "$runtime_tcp_handshake_binary" "$runtime_password_auth_binary" "$runtime_private_key_auth_binary" "$runtime_password_exec_binary" "$runtime_direct_password_exec_binary" "$runtime_direct_private_key_exec_binary" "$runtime_direct_stderr_binary" "$runtime_direct_output_limit_binary" "$runtime_hostkey_mismatch_binary" "$runtime_hostkey_match_binary" "$kbdint_binary" "$runtime_direct_kbdint_binary" "$port_file" "$server_err" "$key_file" "$kbdint_port_file" "$kbdint_server_err"' EXIT
"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_direct_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_direct_keyboard_interactive_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$runtime_direct_kbdint_binary"
"$runtime_direct_kbdint_binary" "$kbdint_port"
echo "[ssh-native] e2e passed"
