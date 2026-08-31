#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

device="${SSH_HELPER_ADB_TARGET:-192.168.1.100:5555}"
apk_path="${SSH_HELPER_APK_PATH:-$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
launch_app=true
package_name="com.yang136.sshhelper"
activity_name="com.yang136.sshhelper/.MainActivity"

show_help() {
    cat <<'EOF'
用法：scripts/install-debug.sh [选项] [ADB设备]

将已有 Debug APK 安装到 Android 设备并启动应用。

选项：
  -d, --device TARGET   ADB 地址或设备序列号
  -a, --apk PATH        要安装的 APK 路径
  --no-launch           安装后不启动应用
  -h, --help            显示帮助

默认设备：192.168.1.100:5555
环境变量：SSH_HELPER_ADB_TARGET、SSH_HELPER_APK_PATH
EOF
}

while (($# > 0)); do
    case "$1" in
        -d|--device)
            [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }
            device="$2"
            shift
            ;;
        -a|--apk)
            [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }
            apk_path="$2"
            shift
            ;;
        --no-launch)
            launch_app=false
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        -*)
            echo "未知参数：$1" >&2
            show_help >&2
            exit 2
            ;;
        *)
            device="$1"
            ;;
    esac
    shift
done

command -v adb >/dev/null 2>&1 || {
    echo "未找到 adb，请先安装 Android SDK Platform-Tools 并加入 PATH。" >&2
    exit 1
}

if [[ ! -f "$apk_path" ]]; then
    echo "未找到 APK：$apk_path" >&2
    echo "请先运行 scripts/build-debug.sh。" >&2
    exit 1
fi

adb start-server >/dev/null
if [[ "$device" == *:* ]]; then
    echo "[install] 连接设备：$device"
    connect_output="$(adb connect "$device" 2>&1)" || {
        echo "$connect_output" >&2
        exit 1
    }
    echo "[install] $connect_output"
fi

device_state="$(adb -s "$device" get-state 2>/dev/null || true)"
if [[ "$device_state" != "device" ]]; then
    echo "设备不可用：$device（状态：${device_state:-未连接}）" >&2
    echo "若显示 unauthorized，请在手机上确认无线调试授权。" >&2
    exit 1
fi

echo "[install] 安装：$apk_path"
adb -s "$device" install -r "$apk_path"

if [[ "$launch_app" == true ]]; then
    echo "[install] 启动：$package_name"
    adb -s "$device" shell am force-stop "$package_name"
    adb -s "$device" shell am start -W -n "$activity_name"
fi

echo "[install] 完成：$device"

