#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
if [[ $# -gt 0 ]]; then
    apk="$1"
elif [[ -f "$project_dir/app/build/outputs/apk/release/app-release-unsigned.apk" ]]; then
    apk="$project_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
else
    apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
fi
ndk_version="$(awk -F= '$1 == "android_ndk_version" { print $2 }' "$project_dir/toolchains/ghostty.lock")"
# local.properties 是本机文件（.gitignore 里），CI 上不存在。必须先判存在再读：在
# `set -o pipefail` 下，缺文件时 sed 的退出码 2 会让整条管道返回 2，`set -e` 随即
# 终止脚本——而 2>/dev/null 又把原因吞掉了，最终表现为"零输出 + exit 2"。
sdk_dir=""
if [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_dir="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_dir="$ANDROID_SDK_ROOT"
elif [[ -f "$project_dir/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$project_dir/local.properties" | head -1)"
fi
toolchain="$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin"
readelf_bin="${READELF:-$toolchain/llvm-readelf}"

[[ -n "$sdk_dir" ]] || { echo "无法确定 Android SDK 路径（ANDROID_HOME/ANDROID_SDK_ROOT/local.properties 均不可用）" >&2; exit 1; }

[[ -f "$apk" ]] || { echo "APK 不存在：$apk" >&2; exit 1; }
[[ -x "$readelf_bin" ]] || { echo "找不到 llvm-readelf：$readelf_bin" >&2; exit 1; }

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ghostty-apk-check.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT

for abi in arm64-v8a x86_64; do
    entry="lib/$abi/libsshhelper_terminal.so"
    unzip -p "$apk" "$entry" > "$temp_dir/$abi.so"
    [[ -s "$temp_dir/$abi.so" ]] || { echo "APK 缺少 $entry" >&2; exit 1; }

    while read -r alignment; do
        [[ "$alignment" == "0x4000" ]] || {
            echo "$entry 的 LOAD 对齐不是 0x4000：$alignment" >&2
            exit 1
        }
    done < <("$readelf_bin" -lW "$temp_dir/$abi.so" | awk '$1 == "LOAD" { print $NF }')

    file "$temp_dir/$abi.so" | grep -q 'stripped' || {
        echo "$entry 未 strip" >&2
        exit 1
    }

    unexpected="$("$readelf_bin" -dW "$temp_dir/$abi.so" | awk '/NEEDED/ { gsub(/[][]/, "", $5); print $5 }' | grep -Ev '^(libc|libdl|liblog|libm)\.so$' || true)"
    [[ -z "$unexpected" ]] || { echo "$entry 存在非预期依赖：$unexpected" >&2; exit 1; }
done

echo "Ghostty JNI APK 校验通过：$apk"
