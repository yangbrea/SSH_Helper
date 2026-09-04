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
sdk_dir="$(sed -n 's/^sdk.dir=//p' "$project_dir/local.properties" 2>/dev/null | head -1)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$sdk_dir}}"
toolchain="$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin"
readelf_bin="${READELF:-$toolchain/llvm-readelf}"

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
