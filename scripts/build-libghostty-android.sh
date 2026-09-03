#!/usr/bin/env bash
# Build libghostty-vt static libraries for Android.
#
# Reads versions from toolchains/ghostty.lock. The output is intentionally
# written under app/build/ghostty/ so it never enters Git history.
#
# Required environment:
#   ANDROID_NDK_HOME   Android NDK root (or ANDROID_HOME with ndk/<version>)
#   ZIG                Zig executable path (defaults to `zig` on PATH)
#
# The script uses a writable Zig global cache under the project root because
# some developer machines mount $HOME read-only.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
GHOSTTY_DIR="$PROJECT_DIR/ghostty"
LOCK_FILE="$PROJECT_DIR/toolchains/ghostty.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/ghostty"
WORK_DIR="$PROJECT_DIR/.zig-cache/ghostty-android"

show_help() {
    cat <<'EOF'
用法：scripts/build-libghostty-android.sh [--skip-checks]

从锁定的 Ghostty submodule 构建 Android libghostty-vt 静态库。

环境变量：
  ANDROID_NDK_HOME   Android NDK 根目录（优先）
  ANDROID_HOME       Android SDK 根目录（自动查找 ndk/<version>）
  ZIG                Zig 可执行文件路径（默认使用 PATH 中的 zig）

输出：
  app/build/ghostty/
  ├── include/ghostty/
  ├── arm64-v8a/libghostty-vt.a
  ├── x86_64/libghostty-vt.a
  └── SHA256SUMS
EOF
}

skip_checks=false
while (($# > 0)); do
    case "$1" in
        --skip-checks)
            skip_checks=true
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            show_help >&2
            exit 2
            ;;
    esac
    shift
done

read_lock() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK_FILE"
}

expected_commit="$(read_lock ghostty_commit)"
expected_zig="$(read_lock zig_version)"
expected_ndk="$(read_lock android_ndk_version)"
expected_simd="$(read_lock simd)"
expected_abis="$(read_lock abis)"

if [[ -z "$expected_commit" || -z "$expected_zig" || -z "$expected_ndk" || -z "$expected_abis" ]]; then
    echo "错误：$LOCK_FILE 缺少必要字段" >&2
    exit 1
fi

if [[ ! -d "$GHOSTTY_DIR/.git" ]]; then
    echo "错误：Ghostty submodule 不存在：$GHOSTTY_DIR" >&2
    echo "请先运行 git submodule update --init --recursive" >&2
    exit 1
fi

if [[ "$skip_checks" != "true" ]]; then
    actual_commit="$(git -C "$GHOSTTY_DIR" rev-parse HEAD)"
    if [[ "$actual_commit" != "$expected_commit" ]]; then
        echo "错误：Ghostty submodule SHA 与 lock 不一致" >&2
        echo "  lock:  $expected_commit" >&2
        echo "  actual: $actual_commit" >&2
        exit 1
    fi

    # Resolve Zig.
    if [[ -n "${ZIG:-}" ]]; then
        zig_exe="$ZIG"
    elif command -v zig >/dev/null 2>&1; then
        zig_exe="$(command -v zig)"
    elif [[ -x "$HOME/.local/bin/zig" ]]; then
        zig_exe="$HOME/.local/bin/zig"
    else
        echo "错误：未找到 Zig，请设置 ZIG 或加入 PATH" >&2
        exit 1
    fi
    zig_version_output="$("$zig_exe" version)"
    if [[ "$zig_version_output" != "$expected_zig" ]]; then
        echo "错误：Zig 版本不匹配" >&2
        echo "  lock:  $expected_zig" >&2
        echo "  actual: $zig_version_output" >&2
        exit 1
    fi

    # Resolve NDK.
    ndk_home=""
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        ndk_home="$ANDROID_NDK_HOME"
    else
        local_properties="$PROJECT_DIR/local.properties"
        sdk_dir=""
        if [[ -f "$local_properties" ]]; then
            sdk_dir="$(sed -n 's/^sdk.dir=//p' "$local_properties" | head -1)"
        fi
        if [[ -z "$sdk_dir" && -n "${ANDROID_HOME:-}" ]]; then
            sdk_dir="$ANDROID_HOME"
        fi
        if [[ -z "$sdk_dir" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
            sdk_dir="$ANDROID_SDK_ROOT"
        fi
        if [[ -n "$sdk_dir" ]]; then
            ndk_home="$sdk_dir/ndk/$expected_ndk"
        fi
    fi
    if [[ ! -d "$ndk_home" ]]; then
        echo "错误：找不到 NDK $expected_ndk：$ndk_home" >&2
        echo "请设置 ANDROID_NDK_HOME 或 ANDROID_HOME" >&2
        exit 1
    fi
else
    zig_exe="${ZIG:-$(command -v zig || true)}"
    ndk_home="${ANDROID_NDK_HOME:-}"
fi

if [[ -z "$zig_exe" ]]; then
    echo "错误：未找到 Zig（--skip-checks 时仍需要 ZIG/PATH）" >&2
    exit 1
fi
if [[ -z "$ndk_home" ]]; then
    echo "错误：未设置 ANDROID_NDK_HOME（--skip-checks 时仍需要）" >&2
    exit 1
fi

if [[ "$expected_simd" != "false" ]]; then
    echo "错误：本脚本只支持 lock 中 simd=false" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

abi_list=()
IFS=',' read -ra abi_list <<< "$expected_abis"

# Merge headers from the first successful build. Headers are target-independent.
headers_copied=false

for abi in "${abi_list[@]}"; do
    case "$abi" in
        arm64-v8a) target="aarch64-linux-android" ;;
        x86_64)    target="x86_64-linux-android" ;;
        *)
            echo "错误：不支持的 ABI：$abi" >&2
            exit 1
            ;;
    esac

    echo "[ghostty] 构建 $abi ($target)"
    (
        cd "$GHOSTTY_DIR"
        ZIG_GLOBAL_CACHE_DIR="$PROJECT_DIR/.zig-cache-global" \
        ZIG_LOCAL_CACHE_DIR="$PROJECT_DIR/.zig-cache/ghostty-local" \
        ANDROID_NDK_HOME="$ndk_home" \
        "$zig_exe" build \
            -Demit-lib-vt \
            -Dtarget="$target" \
            -Doptimize=ReleaseFast \
            -Dsimd=false \
            -p "$WORK_DIR/$abi"
    )

    mkdir -p "$OUTPUT_DIR/$abi"
    cp "$WORK_DIR/$abi/lib/libghostty-vt.a" "$OUTPUT_DIR/$abi/libghostty-vt.a"

    if [[ "$headers_copied" != "true" ]]; then
        rm -rf "$OUTPUT_DIR/include"
        mkdir -p "$OUTPUT_DIR/include"
        cp -R "$WORK_DIR/$abi/include/ghostty" "$OUTPUT_DIR/include/"
        headers_copied=true
    fi
done

# Write checksums for every produced file.
(
    cd "$OUTPUT_DIR"
    find . -type f | sort | while read -r file; do
        sha256sum "$file"
    done > SHA256SUMS
)

echo "[ghostty] 输出目录：$OUTPUT_DIR"
cat "$OUTPUT_DIR/SHA256SUMS"
