#!/usr/bin/env bash
# deploy.sh — SSH Helper 通用构建 + 安装脚本
#
# 支持 debug / release 两种构建类型;ADB 目标可显式指定,也可通过 mDNS 自动发现
# (Android 无线调试端口每次重连会变,mDNS 是官方发现机制)。
#
# 用法示例:
#   scripts/deploy.sh -t debug                       # 构建 debug 并安装到 mDNS 发现的设备
#   scripts/deploy.sh -t release -d 192.168.1.100:41289
#   scripts/deploy.sh -t release -d 41289 --ip 192.168.1.100
#   scripts/deploy.sh -t release --no-build -d 192.168.1.100:41289   # 只装已有产物
#   scripts/deploy.sh -t release --apk /path/app-release.apk -d ... # 装指定 APK

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

build_type="debug"
device=""
ip=""
scan="auto"            # auto: 未显式给 device 时尝试扫描;force: 强制扫描;off: 不扫描
apk_path=""
no_build=false
launch_app=true
run_tests=true
clean=false
offline=false
force_reinstall=false
package_name="com.yang136.sshhelper"
activity_name="com.yang136.sshhelper/.MainActivity"
adb_timeout=6

show_help() {
    cat <<'EOF'
用法：scripts/deploy.sh [选项]

构建（可选，--apk/--no-build 时跳过）并安装 SSH Helper 到 Android 设备。

选项：
  -t, --type debug|release   构建类型（默认 debug）
  -d, --device TARGET        ADB 目标：ip:port / 纯端口 / 设备序列号
  --ip IP                    设备 IP；纯端口参数或 mDNS 扫描时用于匹配（默认 192.168.1.100）
  -s, --scan                 强制 mDNS 扫描发现设备（默认：未指定 device 时自动尝试）
      --no-scan              禁用自动扫描（未指定 device 时直接报错）
  -a, --apk PATH             使用指定 APK 并跳过构建
      --no-build             使用默认产物路径并跳过构建
      --no-launch            安装后不启动应用
      --skip-tests           构建时跳过单元测试
      --clean                构建前执行 :app:clean
      --offline              Gradle 离线模式
      --force-reinstall      签名冲突时卸载旧版重装（会清空应用数据！）
  -h, --help                 显示帮助

环境变量：SSH_HELPER_ADB_TARGET（默认设备 ip:port）
EOF
}

while (($# > 0)); do
    case "$1" in
        -t|--type)
            [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }
            case "$2" in
                debug|release) build_type="$2" ;;
                *) echo "未知构建类型：$2（应为 debug 或 release）" >&2; exit 2 ;;
            esac
            shift 2
            ;;
        -d|--device) [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }; device="$2"; shift 2 ;;
        --ip) [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }; ip="$2"; shift 2 ;;
        -s|--scan) scan="force"; shift ;;
        --no-scan) scan="off"; shift ;;
        -a|--apk) [[ $# -ge 2 ]] || { echo "$1 缺少参数" >&2; exit 2; }; apk_path="$2"; no_build=true; shift 2 ;;
        --no-build) no_build=true; shift ;;
        --no-launch) launch_app=false; shift ;;
        --skip-tests) run_tests=false; shift ;;
        --clean) clean=true; shift ;;
        --offline) offline=true; shift ;;
        --force-reinstall) force_reinstall=true; shift ;;
        -h|--help) show_help; exit 0 ;;
        *) echo "未知参数：$1" >&2; show_help >&2; exit 2 ;;
    esac
done

[[ -z "$ip" ]] && ip="${SSH_HELPER_IP:-192.168.1.100}"

# ---------- 解析 ADB 目标 ----------
resolve_target() {
    local raw="$1"
    if [[ "$raw" == *:* ]]; then
        # 已是 ip:port
        echo "$raw"
    elif [[ "$raw" =~ ^[0-9]+$ ]]; then
        # 纯端口 → 拼 IP
        echo "${ip}:${raw}"
    else
        # 视为设备序列号(USB)或主机名
        echo "$raw"
    fi
}

# mDNS 发现:输出 ip:port 列表(过滤 _adb-tls-connect 服务)
# 优先用 adb mdns;本机 adb 可能不带 mdns 支持,退化为 avahi-browse
mdns_discover() {
    local out
    out="$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $1}')"
    if [[ -n "$out" ]]; then
        echo "$out"
        return
    fi
    if command -v avahi-browse >/dev/null 2>&1; then
        timeout 4 avahi-browse -rt _adb-tls-connect._tcp 2>/dev/null \
            | grep -oE "[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+" | sort -u
    fi
}

discover_target() {
    local matches
    # 等 mDNS 广播:最多重试 3 次,每次间隔 2 秒
    for attempt in 1 2 3; do
        matches="$(mdns_discover)"
        if [[ -n "$matches" ]]; then
            # 若指定了 --ip,优先匹配;否则取第一个
            local picked
            if [[ -n "$ip" ]]; then
                picked="$(echo "$matches" | grep -F "$ip" | head -1 || true)"
            fi
            [[ -z "$picked" ]] && picked="$(echo "$matches" | head -1)"
            echo "$picked"
            return 0
        fi
        [[ $attempt -lt 3 ]] && sleep 2
    done
    return 1
}

# ---------- 构建 ----------
build_apk() {
    local type="$1"
    local apk="$2"
    if [[ -f "$apk" ]]; then
        # 有缓存产物但用户没显式 --no-build:仍按构建流程走(Gradle 自己判断 up-to-date)
        :
    fi
    local tasks=()
    if [[ "$clean" == true ]]; then
        tasks+=(:app:clean)
    fi
    if [[ "$run_tests" == true ]]; then
        tasks+=(:app:testDebugUnitTest)
    fi
    tasks+=(:app:assembleRelease)
    [[ "$type" == "debug" ]] && tasks[-1]=":app:assembleDebug"

    local options=(--console=plain)
    [[ "$offline" == true ]] && options+=(--offline)
    echo "[deploy] 构建任务：${tasks[*]}"
    (cd "$PROJECT_DIR" && ./gradlew "${tasks[@]}" "${options[@]}")
}

# ---------- 安装 ----------
install_apk() {
    local target="$1"
    local apk="$2"
    local state
    state="$(adb -s "$target" get-state 2>/dev/null || true)"
    if [[ "$state" != "device" ]]; then
        echo "[deploy] 连接设备：$target"
        local out
        out="$(adb connect "$target" 2>&1)" || { echo "$out" >&2; exit 1; }
        echo "[deploy] $out"
    fi
    state="$(adb -s "$target" get-state 2>/dev/null || true)"
    if [[ "$state" != "device" ]]; then
        echo "设备不可用：$target（状态：${state:-未连接}）；若显示 unauthorized 请在手机上确认授权" >&2
        exit 1
    fi

    echo "[deploy] 安装：$apk → $target"
    local out
    out="$(adb -s "$target" install -r "$apk" 2>&1)"
    if [[ "$out" == *"Success"* ]]; then
        echo "[deploy] $out" | tail -1
        return 0
    fi
    if echo "$out" | grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE|signatures do not match"; then
        if [[ "$force_reinstall" == true ]]; then
            echo "[deploy] 签名冲突，卸载旧版重装（应用数据将被清空）"
            adb -s "$target" uninstall "$package_name" >/dev/null 2>&1
            adb -s "$target" install "$apk" 2>&1 | tail -1
            return 0
        fi
        echo "签名冲突或版本不兼容（旧版为不同签名/更高版本）：$out" >&2
        echo "如需卸载重装（会清空应用数据）请加 --force-reinstall" >&2
        exit 1
    fi
    echo "$out" >&2
    exit 1
}

launch() {
    local target="$1"
    if [[ "$launch_app" == true ]]; then
        echo "[deploy] 启动：$package_name"
        adb -s "$target" shell am force-stop "$package_name" >/dev/null 2>&1
        adb -s "$target" shell am start -W -n "$activity_name" 2>&1 | grep -E "Status|TotalTime" || true
    fi
}

# ---------- 主流程 ----------
if [[ "$build_type" == "release" ]]; then
    default_apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
else
    default_apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi
[[ -z "$apk_path" ]] && apk_path="$default_apk"

# 1. 确定 ADB 目标
if [[ -z "$device" ]]; then
    if [[ "$scan" == "off" ]]; then
        echo "未指定设备，且已禁用扫描。请用 -d 指定 ip:port。" >&2
        exit 2
    fi
    echo "[deploy] 尝试 mDNS 扫描设备（IP 匹配：$ip）…"
    if ! device="$(discover_target)"; then
        echo "[deploy] mDNS 未发现设备。"
        if command -v avahi-browse >/dev/null 2>&1 && ! systemctl is-active avahi-daemon >/dev/null 2>&1; then
            echo "[deploy] 提示：本机 avahi-daemon 未运行，mDNS 无法工作。请先执行："
            echo "[deploy]   sudo systemctl enable --now avahi-daemon"
        else
            echo "[deploy] 可能原因：无线调试未开启 / 路由器 AP 隔离 / 网络不支持 mDNS。"
        fi
        echo "[deploy] 请用 -d 手动指定当前端口（手机设置 → 无线调试 查看 IP 和端口）。"
        exit 1
    fi
    echo "[deploy] 扫描到设备：$device"
fi
target="$(resolve_target "$device")"

# 2. 构建（除非跳过）
if [[ "$no_build" == true ]]; then
    echo "[deploy] 跳过构建，使用：$apk_path"
else
    build_apk "$build_type" "$apk_path"
fi
[[ -f "$apk_path" ]] || { echo "未找到 APK：$apk_path" >&2; exit 1; }

# 3. 安装
install_apk "$target" "$apk_path"

# 4. 启动
launch "$target"

echo "[deploy] 完成：$target ($build_type)"
