#!/usr/bin/env bash
#
# 为**宿主机**编译 lock 中固定的 libssh2，供 run-ssh-native-host-tests.sh 使用。
#
# 为什么需要它：宿主测试通过 `-lssh2` 链接，默认会链到发行版的 libssh2。而 app 的
# C++ 直接使用了 libssh2 1.11.1 才引入的 libssh2_session_callback_set2 /
# libssh2_cb_generic，Ubuntu 24.04 只提供 1.11.0，于是编译失败——CI 上就是这样挂的。
# 用同一份 lock 源码编出宿主库，宿主测试才真正跑在 APK 所带的那个 libssh2 上。
#
# 用法：
#   ./scripts/build-libssh2-host.sh
#   SSH_NATIVE_HOST_LIBSSH2_PREFIX=/tmp/prefix ./scripts/build-libssh2-host.sh
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LOCK_FILE="$PROJECT_DIR/toolchains/ssh-native.lock"
OUTPUT_DIR="$PROJECT_DIR/app/build/ssh-native"
SOURCES_DIR="$OUTPUT_DIR/sources"
BUILD_DIR="$OUTPUT_DIR/host-build"

read_lock() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK_FILE"
}

libssh2_commit="$(read_lock libssh2_commit)"
if [[ -z "$libssh2_commit" ]]; then
    echo "错误：$LOCK_FILE 中缺少 libssh2_commit" >&2
    exit 1
fi

# 与 build-libssh2-android.sh 共用同一份源码检出，避免重复下载、也保证版本一致。
prepare_libssh2() {
    local commit="$1"
    local src_dir="$SOURCES_DIR/libssh2"

    mkdir -p "$SOURCES_DIR"

    if [[ -d "$src_dir" ]]; then
        if [[ ! -d "$src_dir/.git" ]]; then
            echo "错误：libssh2 源码目录存在但不是 Git 仓库：$src_dir" >&2
            exit 1
        fi
        local actual
        actual="$(git -C "$src_dir" rev-parse HEAD)"
        if [[ "$actual" != "$commit" ]]; then
            echo "[libssh2-host] 源码 HEAD 与 lock 不一致，checkout 到锁定 commit"
            git -C "$src_dir" fetch --all --tags
            git -C "$src_dir" checkout --detach "$commit"
        fi
    else
        echo "[libssh2-host] 克隆 https://github.com/libssh2/libssh2.git"
        git clone https://github.com/libssh2/libssh2.git "$src_dir"
        git -C "$src_dir" checkout --detach "$commit"
    fi

    local actual
    actual="$(git -C "$src_dir" rev-parse HEAD)"
    if [[ "$actual" != "$commit" ]]; then
        echo "错误：libssh2 无法 checkout 到锁定 commit $commit" >&2
        exit 1
    fi
}

prepare_libssh2 "$libssh2_commit"

PREFIX="${SSH_NATIVE_HOST_LIBSSH2_PREFIX:-$OUTPUT_DIR/host-prefix}"
mkdir -p "$PREFIX"
PREFIX="$(cd "$PREFIX" && pwd)"

rm -rf "$BUILD_DIR"

echo "[libssh2-host] 配置（静态库，OpenSSL 后端）"
cmake -S "$SOURCES_DIR/libssh2" -B "$BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC_LIBS=ON \
    -DCRYPTO_BACKEND=OpenSSL \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_TESTING=OFF

echo "[libssh2-host] 编译"
cmake --build "$BUILD_DIR" --parallel "$(nproc 2>/dev/null || echo 4)"

echo "[libssh2-host] 安装到 $PREFIX"
cmake --install "$BUILD_DIR"

# 宿主测试编译失败正是因为这个符号在发行版 libssh2 上缺失，所以这里显式断言，
# 避免"编好了但装错库"这种情况又变成几层之后的编译报错。
if ! grep -q "libssh2_cb_generic" "$PREFIX/include/libssh2.h"; then
    echo "错误：$PREFIX/include/libssh2.h 缺少 libssh2_cb_generic，装到的不是锁定版本" >&2
    exit 1
fi

echo "[libssh2-host] 完成：$PREFIX"
