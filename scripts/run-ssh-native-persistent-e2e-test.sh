#!/usr/bin/env bash
# Persistent-session E2E against a temporary OpenSSH server.
#
# AsyncSSH currently fails when libssh2 opens a second channel after closing the
# first (see docs), while OpenSSH handles sequential channels correctly. This
# test therefore uses a temporary sshd instance with public-key auth.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! command -v sshd >/dev/null 2>&1 || ! command -v ssh-keygen >/dev/null 2>&1; then
    echo "[ssh-native] persistent e2e skipped (sshd/ssh-keygen not available)"
    exit 0
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ssh-native-openssh.XXXXXX")"
binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-persistent.XXXXXX")"
openssh_pid=""
cleanup() {
    if [[ -n "$openssh_pid" ]]; then
        kill "$openssh_pid" 2>/dev/null || true
        wait "$openssh_pid" 2>/dev/null || true
    fi
    rm -rf "$tmp_dir" "$binary"
}
trap cleanup EXIT

port="$(python3 - <<'PY_INNER'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY_INNER
)"
username="$(id -un)"

ssh-keygen -q -t ed25519 -N '' -f "$tmp_dir/hostkey"
ssh-keygen -q -t ed25519 -N '' -f "$tmp_dir/clientkey"
cp "$tmp_dir/clientkey.pub" "$tmp_dir/authorized_keys"
chmod 600 "$tmp_dir/authorized_keys"
cat > "$tmp_dir/sshd_config" <<SSHD_EOF
Port $port
ListenAddress 127.0.0.1
HostKey $tmp_dir/hostkey
PidFile $tmp_dir/sshd.pid
AuthorizedKeysFile $tmp_dir/authorized_keys
PasswordAuthentication no
PubkeyAuthentication yes
StrictModes no
UsePAM no
LogLevel ERROR
SSHD_EOF

/usr/sbin/sshd -D -f "$tmp_dir/sshd_config" -E "$tmp_dir/sshd.log" &
openssh_pid=$!
# Wait for the port to accept connections.
for _ in $(seq 1 50); do
    if python3 - "$port" <<'PY_INNER2'
import socket, sys
port = int(sys.argv[1])
s = socket.socket()
try:
    s.settimeout(0.2)
    s.connect(('127.0.0.1', port))
except OSError:
    raise SystemExit(1)
else:
    s.close()
    raise SystemExit(0)
PY_INNER2
    then
        break
    fi
    sleep 0.1
done

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
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_persistent_session.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_persistent_session_e2e_test.cpp" \
    -lssh2 \
    -lcrypto \
    -o "$binary"
"$binary" "$port" "$username" "$tmp_dir/clientkey"
echo "[ssh-native] persistent e2e passed"
