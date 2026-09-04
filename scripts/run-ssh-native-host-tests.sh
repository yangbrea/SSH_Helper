#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

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
        -I"$project_dir/app/src/main/cpp" \
        "$@" \
        -o "$test_binary"
    "$test_binary"
    echo "[ssh-native] $name passed"
}

compile_and_run runtime \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_test.cpp"

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
