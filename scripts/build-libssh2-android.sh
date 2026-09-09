#!/usr/bin/env bash
# Build pinned OpenSSL + libssh2 static libraries for Android.
#
# Reads all versions from toolchains/ssh-native.lock. Outputs are written under
# app/build/ssh-native/ (Git-ignored) so no third-party source, object file or
# archive can accidentally enter the repository.
#
# Required environment:
#   ANDROID_NDK_HOME   Android NDK root (or ANDROID_HOME with ndk/<version>)
#
# This script is reproducible: it removes per-ABI build directories before
# configuring, verifies OpenSSL archive hashes, checks out the exact libssh2
# commit, and never depends on CMake caches left by a previous invocation.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="$PROJECT_DIR/toolchains/ssh-native.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/ssh-native"
SOURCES_DIR="$OUTPUT_DIR/sources"
DOWNLOADS_DIR="$OUTPUT_DIR/downloads"
PREFIX_ROOT="$OUTPUT_DIR/prefix"
BUILD_ROOT="$OUTPUT_DIR/build"

show_help() {
    cat <<'EOF'
用法：scripts/build-libssh2-android.sh [--jobs N]

从 toolchains/ssh-native.lock 锁定版本构建 OpenSSL 与 libssh2 的 Android 静态库。

环境变量：
  ANDROID_NDK_HOME   Android NDK 根目录（优先）
  ANDROID_HOME       Android SDK 根目录（自动查找 ndk/<version>）

输出：
  app/build/ssh-native/
  ├── downloads/
  ├── sources/
  ├── build/
  ├── prefix/
  │   ├── arm64-v8a/
  │   └── x86_64/
  ├── SHA256SUMS
  └── BUILD_INFO.txt
EOF
}

jobs="$(nproc 2>/dev/null || echo 4)"
while (($# > 0)); do
    case "$1" in
        --jobs)
            shift
            jobs="$1"
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

openssl_version="$(read_lock openssl_version)"
openssl_sha256="$(read_lock openssl_sha256)"
libssh2_commit="$(read_lock libssh2_commit)"
ndk_version="$(read_lock android_ndk_version)"
android_api="$(read_lock android_api)"
abis="$(read_lock abis)"
crypto_backend="$(read_lock crypto_backend)"
legacy_algorithms="$(read_lock legacy_algorithms)"

if [[ -z "$openssl_version" || -z "$openssl_sha256" || -z "$libssh2_commit" || \
      -z "$ndk_version" || -z "$android_api" || -z "$abis" || -z "$crypto_backend" ]]; then
    echo "错误：$LOCK_FILE 缺少必要字段" >&2
    exit 1
fi

if [[ "$crypto_backend" != "openssl" ]]; then
    echo "错误：本脚本只支持 crypto_backend=openssl" >&2
    exit 1
fi

if [[ "$legacy_algorithms" != "false" ]]; then
    echo "错误：本迁移禁止 legacy_algorithms=true" >&2
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
        ndk_home="$sdk_dir/ndk/$ndk_version"
    fi
fi
if [[ ! -d "$ndk_home" ]]; then
    echo "错误：找不到 NDK $ndk_version：$ndk_home" >&2
    echo "请设置 ANDROID_NDK_HOME 或 ANDROID_HOME" >&2
    exit 1
fi

abi_list=()
IFS=',' read -ra abi_list <<< "$abis"
if [[ ${#abi_list[@]} -ne 2 ]] || [[ " ${abi_list[*]} " != *" arm64-v8a "* ]] || [[ " ${abi_list[*]} " != *" x86_64 "* ]]; then
    echo "错误：abis 必须是 arm64-v8a,x86_64" >&2
    exit 1
fi

download() {
    local url="$1"
    local output="$2"
    echo "[download] $url"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --connect-timeout 20 "$url" -o "$output"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$output" "$url"
    else
        echo "错误：未找到 curl 或 wget" >&2
        exit 1
    fi
}

verify_sha256() {
    local file="$1"
    local expected="$2"
    local actual
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [[ "$actual" != "$expected" ]]; then
        echo "错误：SHA-256 校验失败：$(basename "$file")" >&2
        echo "  期望: $expected" >&2
        echo "  实际: $actual" >&2
        exit 1
    fi
}

prepare_openssl() {
    local version="$1"
    local sha256="$2"
    local src_dir="$SOURCES_DIR/openssl-$version"
    local archive="$DOWNLOADS_DIR/openssl-$version.tar.gz"
    local url="https://www.openssl.org/source/openssl-$version.tar.gz"

    mkdir -p "$DOWNLOADS_DIR" "$SOURCES_DIR"

    if [[ -d "$src_dir" ]]; then
        echo "[openssl] 使用已有源码目录 $src_dir"
    else
        if [[ ! -f "$archive" ]]; then
            download "$url" "$archive"
        fi
        verify_sha256 "$archive" "$sha256"
        echo "[openssl] 解压 $archive"
        tar -xzf "$archive" -C "$SOURCES_DIR"
    fi

    if [[ -f "$archive" ]]; then
        verify_sha256 "$archive" "$sha256"
    fi

    if [[ ! -f "$src_dir/Configure" || ! -f "$src_dir/VERSION.dat" ]]; then
        echo "错误：OpenSSL 源码目录不完整：$src_dir" >&2
        exit 1
    fi
    local major minor patch
    major="$(awk -F= '$1=="MAJOR" {print $2}' "$src_dir/VERSION.dat")"
    minor="$(awk -F= '$1=="MINOR" {print $2}' "$src_dir/VERSION.dat")"
    patch="$(awk -F= '$1=="PATCH" {print $2}' "$src_dir/VERSION.dat")"
    if [[ "$major.$minor.$patch" != "$version" ]]; then
        echo "错误：OpenSSL 源码版本不匹配：$major.$minor.$patch != $version" >&2
        exit 1
    fi
}

prepare_libssh2() {
    local commit="$1"
    local src_dir="$SOURCES_DIR/libssh2"

    mkdir -p "$SOURCES_DIR"

    if [[ -d "$src_dir" ]]; then
        if [[ ! -d "$src_dir/.git" ]]; then
            echo "错误：libssh2 源码目录存在但不是 Git 仓库：$src_dir" >&2
            echo "请删除该目录后重试" >&2
            exit 1
        fi
        if ! git -C "$src_dir" diff --quiet; then
            echo "错误：libssh2 源码目录有未提交修改，拒绝覆盖" >&2
            echo "请删除 $src_dir 后重试" >&2
            exit 1
        fi
        actual="$(git -C "$src_dir" rev-parse HEAD)"
        if [[ "$actual" != "$commit" ]]; then
            echo "[libssh2] 源码 HEAD 与 lock 不一致，checkout 到锁定 commit"
            git -C "$src_dir" fetch --all --tags
            git -C "$src_dir" checkout --detach "$commit"
        fi
        actual="$(git -C "$src_dir" rev-parse HEAD)"
        if [[ "$actual" != "$commit" ]]; then
            echo "错误：libssh2 无法 checkout 到锁定 commit $commit" >&2
            exit 1
        fi
    else
        echo "[libssh2] 克隆 https://github.com/libssh2/libssh2.git"
        git clone https://github.com/libssh2/libssh2.git "$src_dir"
        git -C "$src_dir" checkout --detach "$commit"
    fi
}

map_abi_openssl_target() {
    # OpenSSL uses Android-specific configuration names, not the NDK triple.
    case "$1" in
        arm64-v8a) echo "android-arm64" ;;
        x86_64)    echo "android-x86_64" ;;
        *)
            echo "错误：不支持的 ABI：$1" >&2
            exit 1
            ;;
    esac
}

verify_archive_arch() {
    local archive="$1"
    local expected_abi="$2"
    local tmpdir
    tmpdir="$(mktemp -d)"
    (
        cd "$tmpdir"
        ar t "$archive" >/dev/null 2>&1 || true
    )
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

openssl_src="$SOURCES_DIR/openssl-$openssl_version"
libssh2_src="$SOURCES_DIR/libssh2"

prepare_openssl "$openssl_version" "$openssl_sha256"
prepare_libssh2 "$libssh2_commit"

toolchain_bin="$ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$toolchain_bin" ]]; then
    # Try common host layout used by macOS and Linux NDK packages.
    toolchain_bin="$(find "$ndk_home/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name bin | head -1)"
fi
if [[ -z "$toolchain_bin" || ! -d "$toolchain_bin" ]]; then
    echo "错误：找不到 NDK LLVM prebuilt bin 目录" >&2
    exit 1
fi
export PATH="$toolchain_bin:$PATH"
export ANDROID_NDK_ROOT="$ndk_home"

for abi in "${abi_list[@]}"; do
    target="$(map_abi_openssl_target "$abi")"
    prefix="$PREFIX_ROOT/$abi"
    openssl_build="$BUILD_ROOT/openssl/$abi"
    libssh2_build="$BUILD_ROOT/libssh2/$abi"

    echo ""
    echo "[ssh-native] 构建 $abi"
    echo "[openssl] 配置 $abi ($target)"

    rm -rf "$openssl_build" "$prefix"
    mkdir -p "$openssl_build" "$prefix"

    (
        cd "$openssl_build"
        "$openssl_src/Configure" "$target" \
            -D__ANDROID_API__="$android_api" \
            --prefix="$prefix" \
            --openssldir="$prefix/ssl" \
            no-shared \
            no-tests \
            no-apps \
            no-docs \
            no-module \
            no-dynamic-engine \
            no-weak-ssl-ciphers
        make -j"$jobs"
        make install_sw
    )

    if [[ ! -f "$prefix/lib/libcrypto.a" ]]; then
        echo "错误：$abi OpenSSL 构建缺少 libcrypto.a" >&2
        exit 1
    fi
    if ! verify_archive_arch "$prefix/lib/libcrypto.a" "$abi"; then
        echo "错误：$abi libcrypto.a 架构校验失败" >&2
        exit 1
    fi

    echo "[libssh2] 配置 $abi"
    rm -rf "$libssh2_build"
    cmake -S "$libssh2_src" -B "$libssh2_build" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_home/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$android_api" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DCMAKE_PREFIX_PATH="$prefix" \
        -DOPENSSL_ROOT_DIR="$prefix" \
        -DOPENSSL_USE_STATIC_LIBS=TRUE \
        -DOPENSSL_INCLUDE_DIR="$prefix/include" \
        -DOPENSSL_CRYPTO_LIBRARY="$prefix/lib/libcrypto.a" \
        -DOPENSSL_SSL_LIBRARY="$prefix/lib/libssl.a" \
        -DBUILD_SHARED_LIBS=OFF \
        -DBUILD_STATIC_LIBS=ON \
        -DBUILD_TESTING=OFF \
        -DBUILD_EXAMPLES=OFF \
        -DLIBSSH2_BUILD_DOCS=OFF \
        -DCRYPTO_BACKEND=OpenSSL \
        -DENABLE_ZLIB_COMPRESSION=OFF
    cmake --build "$libssh2_build" --parallel "$jobs"
    cmake --install "$libssh2_build"

    if [[ ! -f "$prefix/lib/libssh2.a" ]]; then
        echo "错误：$abi libssh2 构建缺少 libssh2.a" >&2
        exit 1
    fi
    if ! verify_archive_arch "$prefix/lib/libssh2.a" "$abi"; then
        echo "错误：$abi libssh2.a 架构校验失败" >&2
        exit 1
    fi
    if ! nm -g --defined-only "$prefix/lib/libssh2.a" 2>/dev/null | grep " libssh2_init$" >/dev/null; then
        echo "错误：$abi libssh2.a 缺少 libssh2_init 符号" >&2
        exit 1
    fi
done

# Record the exact build inputs and host that produced this tree.
build_info_tmp="$(mktemp "${TMPDIR:-/tmp}/ssh-native-info.XXXXXX")"
trap 'rm -f "$build_info_tmp"' EXIT
cat > "$build_info_tmp" <<EOF
openssl_version=$openssl_version
openssl_sha256=$openssl_sha256
libssh2_commit=$libssh2_commit
android_ndk_version=$ndk_version
android_api=$android_api
abis=$abis
crypto_backend=$crypto_backend
legacy_algorithms=$legacy_algorithms
build_date=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
mv "$build_info_tmp" "$OUTPUT_DIR/BUILD_INFO.txt"

# SHA-256 over installed headers and static libraries. The manifest is written
# outside the scanned tree so it never hashes itself.
sha_tmp="$(mktemp "${TMPDIR:-/tmp}/ssh-native-sha.XXXXXX")"
trap 'rm -f "$sha_tmp"' EXIT
(
    cd "$PREFIX_ROOT"
    find . -type f -print0 | sort -z | while IFS= read -r -d '' file; do
        sha256sum "$file"
    done
) > "$sha_tmp"
mv "$sha_tmp" "$OUTPUT_DIR/SHA256SUMS"
trap - EXIT

echo ""
echo "[ssh-native] 完成：$OUTPUT_DIR"
cat "$OUTPUT_DIR/BUILD_INFO.txt"
echo ""
echo "[ssh-native] SHA256SUMS 条目数：$(wc -l < "$OUTPUT_DIR/SHA256SUMS")"
