#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

sanitizer_flags=()
case "${SSH_NATIVE_SANITIZER:-}" in
    "") ;;
    address) sanitizer_flags=(-fsanitize=address -fno-omit-frame-pointer) ;;
    undefined) sanitizer_flags=(-fsanitize=undefined -fno-omit-frame-pointer) ;;
    thread) sanitizer_flags=(-fsanitize=thread -fno-omit-frame-pointer) ;;
    address,undefined) sanitizer_flags=(-fsanitize=address,undefined -fno-omit-frame-pointer) ;;
    *) echo "unsupported SSH_NATIVE_SANITIZER: $SSH_NATIVE_SANITIZER" >&2; exit 2 ;;
esac

# libssh2-backed cases link `-lssh2`. Point that at the lock-pinned build from
# build-libssh2-host.sh when it is available: the sources use
# libssh2_session_callback_set2, which only exists from libssh2 1.11.1, while e.g.
# Ubuntu 24.04 ships 1.11.0 and fails to compile. Without this variable the system
# library is used, which is what a developer with a new-enough distro package gets.
libssh2_flags=()
if [[ -n "${SSH_NATIVE_HOST_LIBSSH2_PREFIX:-}" ]]; then
    libssh2_prefix="$(cd -- "${SSH_NATIVE_HOST_LIBSSH2_PREFIX}" && pwd)"
    libssh2_flags+=(-I"$libssh2_prefix/include" -L"$libssh2_prefix/lib")
    echo "[ssh-native] libssh2: $libssh2_prefix"
else
    echo "[ssh-native] libssh2: 系统库（未设置 SSH_NATIVE_HOST_LIBSSH2_PREFIX）"
fi

compile_and_run() {
    local name="$1"
    shift
    local test_binary
    test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-$name.XXXXXX")"
    trap 'rm -f "$test_binary"' RETURN

    "${CXX:-c++}" \
        -std=c++17 \
        -pthread \
        -Wall \
        -Wextra \
        -Werror \
        "${sanitizer_flags[@]}" \
        -I"$project_dir/app/src/main/cpp" \
        "$@" \
        -o "$test_binary"
    "$test_binary"
    echo "[ssh-native] $name passed"
}

compile_and_run_libssh2() {
    local name="$1"
    shift
    local test_binary
    test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-$name.XXXXXX")"
    trap 'rm -f "$test_binary"' RETURN

    "${CXX:-c++}" \
        -std=c++17 \
        -pthread \
        -Wall \
        -Wextra \
        -Werror \
        "${sanitizer_flags[@]}" \
        -I"$project_dir/app/src/main/cpp" \
        "${libssh2_flags[@]}" \
        "$@" \
        -lssh2 \
        -lcrypto \
        -o "$test_binary"
    "$test_binary"
    echo "[ssh-native] $name passed"
}

compile_and_run_hostkey() {
    local name="$1"
    shift
    local test_binary
    test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-$name.XXXXXX")"
    trap 'rm -f "$test_binary"' RETURN

    "${CXX:-c++}" \
        -std=c++17 \
        -pthread \
        -Wall \
        -Wextra \
        -Werror \
        "${sanitizer_flags[@]}" \
        -I"$project_dir/app/src/main/cpp" \
        "$@" \
        -lcrypto \
        -o "$test_binary"
    "$test_binary"
    echo "[ssh-native] $name passed"
}

compile_and_run algorithm_policy \
    "$project_dir/app/src/main/cpp/ssh/ssh_algorithm_policy.cpp" \
    "$project_dir/app/src/test/cpp/ssh_algorithm_policy_test.cpp"

compile_and_run_hostkey hostkey \
    "$project_dir/app/src/main/cpp/ssh/ssh_hostkey.cpp" \
    "$project_dir/app/src/test/cpp/ssh_hostkey_test.cpp"

compile_and_run_libssh2 libssh2_lifecycle \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/test/cpp/ssh_libssh2_test.cpp"

compile_and_run_libssh2 libssh2_handshake \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_libssh2_handshake_test.cpp"

compile_and_run runtime \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_test.cpp"

compile_and_run connect_operation \
    "$project_dir/app/src/main/cpp/ssh/ssh_connect_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_connect_operation_test.cpp"

compile_and_run proxy_operation \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_proxy_operation.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_proxy_operation_test.cpp"

compile_and_run socks5_operation \
    "$project_dir/app/src/main/cpp/ssh/ssh_error.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socks5_operation.cpp" \
    "$project_dir/app/src/test/cpp/ssh_socks5_operation_test.cpp"

compile_and_run_libssh2 libssh2_nonblocking \
    "$project_dir/app/src/main/cpp/ssh/ssh_libssh2_nonblocking.cpp" \
    "$project_dir/app/src/test/cpp/ssh_libssh2_nonblocking_test.cpp"

compile_and_run socket \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_socket_test.cpp"

compile_and_run http_proxy \
    "$project_dir/app/src/main/cpp/ssh/ssh_http_proxy.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_http_proxy_test.cpp"

compile_and_run socks5_proxy \
    "$project_dir/app/src/main/cpp/ssh/ssh_socks5_proxy.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_socks5_proxy_test.cpp"

compile_and_run transport \
    "$project_dir/app/src/main/cpp/ssh/ssh_transport.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_http_proxy.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socks5_proxy.cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_socket.cpp" \
    "$project_dir/app/src/test/cpp/ssh_transport_test.cpp"

"$project_dir/scripts/run-ssh-native-keepalive-test.sh"
"$project_dir/scripts/run-ssh-native-jump-poc.sh"
"$project_dir/scripts/run-ssh-native-e2e-test.sh"
