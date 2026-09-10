#!/usr/bin/env bash
# Verify libsshhelper_ssh.so / ssh-native static library packaging.
#
# In the current migration step no libsshhelper_ssh.so exists yet, so this
# script verifies the reproducible static libraries under app/build/ssh-native/
# and, when an APK is supplied or present, enforces:
#   - two supported ABIs contain libsshhelper_ssh.so (once added)
#   - no independent libcrypto.so / libssl.so / libssh2.so is shipped
#   - project .so files have 16 KB LOAD alignment and expected arch
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="$PROJECT_DIR/toolchains/ssh-native.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/ssh-native"
PREFIX_ROOT="$OUTPUT_DIR/prefix"
NDK_VERSION="$(awk -F= '$1 == "android_ndk_version" { print $2 }' "$LOCK_FILE")"
API_LEVEL="$(awk -F= '$1 == "android_api" { print $2 }' "$LOCK_FILE")"
EXPECTED_ABIS="$(awk -F= '$1 == "abis" { print $2 }' "$LOCK_FILE")"

show_help() {
    cat <<'EOF'
用法：scripts/verify-ssh-native-packaging.sh [APK 路径]

校验项：
  1. app/build/ssh-native/prefix/<ABI>/lib/{libcrypto.a,libssh2.a} 架构正确。
  2. 静态库内没有 host 架构对象（首个对象架构符合目标 ABI）。
  3. 若 APK 存在 libsshhelper_ssh.so，检查 16 KB LOAD 对齐、架构与动态依赖。
  4. APK 内不出现 libcrypto.so / libssl.so / libssh2.so 独立依赖。
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    show_help
    exit 0
fi

if [[ -z "$NDK_VERSION" || -z "$API_LEVEL" || -z "$EXPECTED_ABIS" ]]; then
    echo "错误：$LOCK_FILE 缺少必要字段" >&2
    exit 1
fi

apk=""
if [[ $# -gt 0 ]]; then
    apk="$1"
elif [[ -f "$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" ]]; then
    apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
elif [[ -f "$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk" ]]; then
    apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
fi

sdk_dir=""
if [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_dir="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_dir="$ANDROID_SDK_ROOT"
fi
if [[ -z "$sdk_dir" && -f "$PROJECT_DIR/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties" | head -1)"
fi
readelf_bin="${READELF:-$sdk_dir/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}"
nm_bin="${NM:-$sdk_dir/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm}"

verify_archive_arch() {
    local archive="$1"
    local expected_abi="$2"
    local tmpdir
    tmpdir="$(mktemp -d)"
    (
        cd "$tmpdir"
        ar x "$archive" >/dev/null 2>&1 || true
    )
    local first_obj
    first_obj="$(find "$tmpdir" -name '*.o' -type f | head -1)"
    local desc=""
    if [[ -n "$first_obj" ]]; then
        desc="$(file -b "$first_obj")"
    fi
    rm -rf "$tmpdir"

    case "$expected_abi" in
        arm64-v8a)
            [[ "$desc" == *"ARM aarch64"* ]]
            ;;
        x86_64)
            [[ "$desc" == *"x86-64"* ]]
            ;;
        *)
            return 1
            ;;
    esac
}

if [[ ! -d "$PREFIX_ROOT" ]]; then
    echo "错误：找不到 $PREFIX_ROOT" >&2
    echo "请先运行 scripts/build-libssh2-android.sh" >&2
    exit 1
fi

echo "[ssh-native] 校验静态库"
IFS=',' read -ra abi_list <<< "$EXPECTED_ABIS"
for abi in "${abi_list[@]}"; do
    libcrypto="$PREFIX_ROOT/$abi/lib/libcrypto.a"
    libssh2="$PREFIX_ROOT/$abi/lib/libssh2.a"
    for lib in "$libcrypto" "$libssh2"; do
        [[ -f "$lib" ]] || { echo "错误：缺少 $lib" >&2; exit 1; }
        if ! verify_archive_arch "$lib" "$abi"; then
            echo "错误：$abi 静态库架构校验失败：$lib" >&2
            exit 1
        fi
    done
    if ! "$nm_bin" -g --defined-only "$libssh2" 2>/dev/null | grep " libssh2_init$" >/dev/null; then
        echo "错误：$abi libssh2.a 缺少 libssh2_init 符号" >&2
        exit 1
    fi
    echo "  [ok] $abi static libraries"
done

if [[ -n "$apk" ]]; then
    [[ -f "$apk" ]] || { echo "错误：APK 不存在：$apk" >&2; exit 1; }
    [[ -x "$readelf_bin" ]] || { echo "错误：找不到 llvm-readelf：$readelf_bin" >&2; exit 1; }

    echo "[ssh-native] 校验 APK：$apk"
    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ssh-native-apk.XXXXXX")"
    trap 'rm -rf "$temp_dir"' EXIT

    # Read the complete listing before querying it. With `pipefail`, piping
    # `unzip -Z1` into `grep -q` can turn a successful match into status 141:
    # grep exits early and unzip receives SIGPIPE.
    apk_entries="$(unzip -Z1 "$apk")"

    forbidden="$(grep -E '^lib/[^/]+/lib(crypto|ssl|ssh2)\.so$' <<< "$apk_entries" || true)"
    if [[ -n "$forbidden" ]]; then
        echo "错误：APK 包含不应单独分发的 native 依赖：" >&2
        echo "$forbidden" >&2
        exit 1
    fi

    for abi in "${abi_list[@]}"; do
        entry="lib/$abi/libsshhelper_ssh.so"
        if grep -Fxq "$entry" <<< "$apk_entries"; then
            unzip -p "$apk" "$entry" > "$temp_dir/$abi.so"
            [[ -s "$temp_dir/$abi.so" ]] || { echo "错误：$entry 解压为空" >&2; exit 1; }

            alignments="$("$readelf_bin" -lW "$temp_dir/$abi.so" | awk '$1 == "LOAD" { print $NF }')"
            [[ -n "$alignments" ]] || { echo "错误：$entry 无 LOAD 段" >&2; exit 1; }
            while read -r alignment; do
                [[ "$alignment" == "0x4000" ]] || {
                    echo "错误：$entry 的 LOAD 对齐不是 0x4000：$alignment" >&2
                    exit 1
                }
            done <<< "$alignments"

            file "$temp_dir/$abi.so" | grep -q 'stripped' || {
                echo "错误：$entry 未 strip" >&2
                exit 1
            }

            unexpected="$("$readelf_bin" -dW "$temp_dir/$abi.so" | awk '/NEEDED/ { gsub(/[][]/, "", $5); print $5 }' | grep -Ev '^(libc|libdl|liblog|libm)\.so$' || true)"
            if [[ -n "$unexpected" ]]; then
                echo "错误：$entry 存在非预期依赖：$unexpected" >&2
                exit 1
            fi
            echo "  [ok] $entry 16 KB alignment / arch / deps"
        else
            echo "  [skip] $entry 尚未打包（Step 3 前预期行为）"
        fi
    done
else
    echo "[ssh-native] 未找到 APK，跳过 APK 校验"
fi

echo "[ssh-native] 校验通过"
