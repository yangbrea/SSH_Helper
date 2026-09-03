#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

show_help() {
    cat <<'EOF'
用法：scripts/build-debug.sh [选项]

构建 SSH Helper Debug APK，并默认执行单元测试。

选项：
  --offline       使用 Gradle 离线模式
  --clean         构建前执行 :app:clean
  --skip-tests    跳过单元测试
  --skip-native   跳过 libghostty-vt Native 构建（仅当产物已存在时使用）
  -h, --help      显示帮助

也可设置 SSH_HELPER_OFFLINE=1 开启离线模式。
EOF
}

run_clean=false
run_tests=true
run_native=true
gradle_options=(--no-daemon)

if [[ "${SSH_HELPER_OFFLINE:-0}" == "1" ]]; then
    gradle_options+=(--offline)
fi

while (($# > 0)); do
    case "$1" in
        --offline)
            gradle_options+=(--offline)
            ;;
        --clean)
            run_clean=true
            ;;
        --skip-tests)
            run_tests=false
            ;;
        --skip-native)
            run_native=false
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

gradle_tasks=()
if [[ "$run_clean" == true ]]; then
    gradle_tasks+=(:app:clean)
fi
if [[ "$run_tests" == true ]]; then
    gradle_tasks+=(:app:testDebugUnitTest)
fi
gradle_tasks+=(:app:assembleDebug)

cd "$PROJECT_DIR"
echo "[build] 项目：$PROJECT_DIR"
if [[ "$run_native" == true ]]; then
    echo "[build] 构建 libghostty-vt Native 库"
    "$SCRIPT_DIR/build-libghostty-android.sh"
else
    echo "[build] 跳过 libghostty-vt Native 构建（--skip-native）"
fi
echo "[build] 任务：${gradle_tasks[*]}"
./gradlew "${gradle_tasks[@]}" "${gradle_options[@]}"

if [[ ! -f "$APK_PATH" ]]; then
    echo "构建完成，但未找到 APK：$APK_PATH" >&2
    exit 1
fi

echo "[build] APK：$APK_PATH"
if command -v sha256sum >/dev/null 2>&1; then
    echo "[build] SHA-256：$(sha256sum "$APK_PATH" | awk '{print $1}')"
fi

