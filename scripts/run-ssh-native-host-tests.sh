#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
test_binary="$(mktemp "${TMPDIR:-/tmp}/ssh-native-host-test.XXXXXX")"
trap 'rm -f "$test_binary"' EXIT

"${CXX:-c++}" \
    -std=c++17 \
    -pthread \
    -Wall \
    -Wextra \
    -Werror \
    -I"$project_dir/app/src/main/cpp" \
    "$project_dir/app/src/main/cpp/ssh/ssh_runtime.cpp" \
    "$project_dir/app/src/test/cpp/ssh_runtime_test.cpp" \
    -o "$test_binary"
"$test_binary"
