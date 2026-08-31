#!/usr/bin/env bash
#
# 使用两个已启动的 Android 模拟器采集 SSH Helper 的手机/平板、横屏/竖屏布局。
# 本脚本不会构建或安装 APK；运行前请先把待测 APK 安装到模拟器。
# 执行顺序：平板（tablet）优先，然后手机（phone）。
#
# 推荐用法：
#   scripts/capture-adaptive-layouts.sh \
#       --phone emulator-5554 \
#       --tablet emulator-5556 \
#       --clear-data
#
# 复用同一个模拟器依次模拟手机和平板：
#   scripts/capture-adaptive-layouts.sh \
#       --phone emulator-5554 \
#       --tablet emulator-5554 \
#       --clear-data
#
# 如需采集连接后的终端、终端面板和 SFTP，请提供可访问的 SSH 测试机：
#   scripts/capture-adaptive-layouts.sh \
#       --phone emulator-5554 --tablet emulator-5556 \
#       --test-host 10.0.2.2 --test-user layout --test-password 'simpleAsciiPassword'
#
# 默认会在每个模拟器中创建一个名为 AdaptiveFixture 的测试主机，地址使用
# RFC 5737 保留地址 192.0.2.1。提供 --test-host 后改用指定地址。
# --clear-data 和测试主机创建都会修改模拟器中的应用数据，请勿对真机使用。

set -Eeuo pipefail

ADB="${ADB:-adb}"
PACKAGE="${SSH_HELPER_PACKAGE:-com.yang136.sshhelper}"
ACTIVITY="${SSH_HELPER_ACTIVITY:-com.yang136.sshhelper/.MainActivity}"
PHONE_SERIAL="${PHONE_SERIAL:-}"
TABLET_SERIAL="${TABLET_SERIAL:-}"
OUTPUT_ROOT="${OUTPUT_ROOT:-layout-captures/$(date +%Y%m%d-%H%M%S)}"
SETTLE_SECONDS="${SETTLE_SECONDS:-1}"
CLEAR_DATA=false
SEED_FIXTURE=true

FIXTURE_NAME="${FIXTURE_NAME:-AdaptiveFixture}"
TEST_HOST="${TEST_HOST:-192.0.2.1}"
TEST_PORT="${TEST_PORT:-22}"
TEST_USER="${TEST_USER:-layout}"
TEST_PASSWORD="${TEST_PASSWORD:-}"

EXTRA_OPEN_SETTINGS="com.yang136.sshhelper.OPEN_SETTINGS"
EXTRA_SETTINGS_SECTION="com.yang136.sshhelper.SETTINGS_SECTION"

# 1080 / 420 = 411dp；2400 / 420 = 914dp。
PHONE_SIZE="1080x2400"
PHONE_DENSITY="420"
# 1600 / 320 = 800dp；2560 / 320 = 1280dp。
# 平板竖屏覆盖 Medium，横屏覆盖 Expanded。
TABLET_SIZE="1600x2560"
TABLET_DENSITY="320"

CURRENT_SERIAL=""
CURRENT_PROFILE=""
CURRENT_ORIENTATION=""
CURRENT_RUN_DIR=""
UI_CACHE=""
UI_CACHE_VALID=false
SHOT_INDEX=0
TEMP_DIR=""

ORIGINAL_SIZE=""
ORIGINAL_DENSITY=""
ORIGINAL_ACCELEROMETER_ROTATION=""
ORIGINAL_USER_ROTATION=""
ORIGINAL_STAY_AWAKE=""
PROFILE_CONFIGURED=false

usage() {
    cat <<'EOF'
用法：scripts/capture-adaptive-layouts.sh --phone SERIAL --tablet SERIAL [选项]

必选：
  --phone SERIAL          手机模拟器 ADB serial
  --tablet SERIAL         平板模拟器 ADB serial；可以与手机 serial 相同

选项：
  -o, --output DIR        截图根目录（默认 layout-captures/时间戳）
  --package NAME          applicationId（默认 com.yang136.sshhelper）
  --activity COMPONENT    Activity component
  --settle SECONDS        页面/旋转后的等待时间（默认 1 秒）
  --clear-data            每个设备规格开始前清空应用数据
  --no-seed               不自动创建测试主机；依赖主机的页面会被标记为 SKIP
  --fixture-name NAME     测试主机显示名称，仅建议使用 ASCII 且不带空格
  --test-host HOST        测试 SSH 地址；默认 192.0.2.1
  --test-port PORT        测试 SSH 端口；默认 22
  --test-user USER        测试 SSH 用户；默认 layout
  --test-password PASS    可选。提供后尝试进入连接态终端与 SFTP
  -h, --help              显示帮助

输出结构：
  <output>/<phone|tablet>/<portrait|landscape>/NN-screen.png
  <output>/<phone|tablet>/<portrait|landscape>/NN-screen.xml
  <output>/manifest.tsv

采集范围：主页、活动、设置目录及全部设置详情、主机新增/编辑、快捷命令及
编辑器、局域网发现、网络诊断、主机工作区、端口转发及规则编辑器、终端及
侧面板、SFTP 和传输面板。系统文件选择器、生物识别弹窗、媒体预览等依赖
外部数据的系统界面不在布局截图范围内。
EOF
}

while (($# > 0)); do
    case "$1" in
        --phone) PHONE_SERIAL="${2:?--phone 缺少 serial}"; shift 2 ;;
        --tablet) TABLET_SERIAL="${2:?--tablet 缺少 serial}"; shift 2 ;;
        -o|--output) OUTPUT_ROOT="${2:?--output 缺少目录}"; shift 2 ;;
        --package) PACKAGE="${2:?--package 缺少名称}"; shift 2 ;;
        --activity) ACTIVITY="${2:?--activity 缺少 component}"; shift 2 ;;
        --settle) SETTLE_SECONDS="${2:?--settle 缺少秒数}"; shift 2 ;;
        --clear-data) CLEAR_DATA=true; shift ;;
        --no-seed) SEED_FIXTURE=false; shift ;;
        --fixture-name) FIXTURE_NAME="${2:?--fixture-name 缺少名称}"; shift 2 ;;
        --test-host) TEST_HOST="${2:?--test-host 缺少地址}"; shift 2 ;;
        --test-port) TEST_PORT="${2:?--test-port 缺少端口}"; shift 2 ;;
        --test-user) TEST_USER="${2:?--test-user 缺少用户}"; shift 2 ;;
        --test-password) TEST_PASSWORD="${2:?--test-password 缺少密码}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ -n "$PHONE_SERIAL" ]] || { echo "必须提供 --phone SERIAL" >&2; exit 2; }
[[ -n "$TABLET_SERIAL" ]] || { echo "必须提供 --tablet SERIAL" >&2; exit 2; }
[[ "$TEST_PORT" =~ ^[0-9]+$ ]] || { echo "--test-port 必须是数字" >&2; exit 2; }

mkdir -p "$OUTPUT_ROOT"
TEMP_DIR="$(mktemp -d)"
printf 'profile\torientation\tindex\tscreen\tstatus\tdetail\n' > "$OUTPUT_ROOT/manifest.tsv"

log() {
    printf '[%s/%s] %s\n' "${CURRENT_PROFILE:-setup}" "${CURRENT_ORIENTATION:--}" "$*"
}

adb_device() {
    "$ADB" -s "$CURRENT_SERIAL" "$@"
}

adb_shell() {
    adb_device shell "$@"
}

trim_cr() {
    tr -d '\r'
}

wait_for_ui() {
    sleep "$SETTLE_SECONDS"
}

validate_device() {
    local serial="$1"
    local state qemu
    state="$($ADB -s "$serial" get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] || {
        echo "ADB 设备不可用：$serial（状态：${state:-unknown}）" >&2
        exit 1
    }
    "$ADB" -s "$serial" shell pm path "$PACKAGE" >/dev/null 2>&1 || {
        echo "$serial 未安装 $PACKAGE" >&2
        exit 1
    }
    qemu="$($ADB -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | trim_cr)"
    if [[ "$qemu" != "1" ]]; then
        qemu="$($ADB -s "$serial" shell getprop ro.boot.qemu 2>/dev/null | trim_cr)"
    fi
    [[ "$qemu" == "1" ]] || {
        echo "$serial 不是 Android 模拟器；脚本拒绝修改其窗口和应用数据" >&2
        exit 1
    }
}

capture_original_window_state() {
    local wm_size wm_density
    wm_size="$(adb_shell wm size | trim_cr)"
    wm_density="$(adb_shell wm density | trim_cr)"
    ORIGINAL_SIZE="$(printf '%s\n' "$wm_size" | sed -n 's/^Override size: //p')"
    ORIGINAL_DENSITY="$(printf '%s\n' "$wm_density" | sed -n 's/^Override density: //p')"
    ORIGINAL_ACCELEROMETER_ROTATION="$(adb_shell settings get system accelerometer_rotation | trim_cr)"
    ORIGINAL_USER_ROTATION="$(adb_shell settings get system user_rotation | trim_cr)"
    ORIGINAL_STAY_AWAKE="$(adb_shell settings get global stay_on_while_plugged_in | trim_cr)"
}

restore_window_state() {
    $PROFILE_CONFIGURED || return 0
    log "恢复模拟器窗口配置"
    if [[ -n "$ORIGINAL_SIZE" ]]; then
        adb_shell wm size "$ORIGINAL_SIZE" >/dev/null
    else
        adb_shell wm size reset >/dev/null
    fi
    if [[ -n "$ORIGINAL_DENSITY" ]]; then
        adb_shell wm density "$ORIGINAL_DENSITY" >/dev/null
    else
        adb_shell wm density reset >/dev/null
    fi
    adb_shell settings put system accelerometer_rotation "${ORIGINAL_ACCELEROMETER_ROTATION:-1}" >/dev/null
    adb_shell settings put system user_rotation "${ORIGINAL_USER_ROTATION:-0}" >/dev/null
    if [[ "$ORIGINAL_ACCELEROMETER_ROTATION" == "1" ]]; then
        adb_shell wm user-rotation free >/dev/null 2>&1 || true
    else
        adb_shell wm user-rotation lock "${ORIGINAL_USER_ROTATION:-0}" >/dev/null 2>&1 || true
    fi
    if [[ -n "$ORIGINAL_STAY_AWAKE" && "$ORIGINAL_STAY_AWAKE" != "null" ]]; then
        adb_shell settings put global stay_on_while_plugged_in "$ORIGINAL_STAY_AWAKE" >/dev/null
    else
        adb_shell settings delete global stay_on_while_plugged_in >/dev/null
    fi
    PROFILE_CONFIGURED=false
}

cleanup() {
    local exit_code=$?
    set +e
    restore_window_state
    [[ -z "$TEMP_DIR" ]] || rm -rf -- "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

configure_profile() {
    local serial="$1" profile="$2" size="$3" density="$4"
    CURRENT_SERIAL="$serial"
    CURRENT_PROFILE="$profile"
    CURRENT_ORIENTATION=""
    capture_original_window_state
    PROFILE_CONFIGURED=true
    log "设置窗口：$size @ ${density}dpi"
    adb_shell wm size "$size" >/dev/null
    adb_shell wm density "$density" >/dev/null
    adb_shell settings put system accelerometer_rotation 0 >/dev/null
    adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb_shell svc power stayon true >/dev/null 2>&1 || true
    invalidate_ui_cache
}

set_orientation() {
    local orientation="$1" rotation
    CURRENT_ORIENTATION="$orientation"
    case "$orientation" in
        portrait) rotation=0 ;;
        landscape) rotation=1 ;;
        *) echo "未知方向：$orientation" >&2; return 1 ;;
    esac
    adb_shell settings put system accelerometer_rotation 0 >/dev/null
    adb_shell settings put system user_rotation "$rotation" >/dev/null
    # 新旧 Android 模拟器对该命令的支持不同，settings 是主路径。
    adb_shell wm user-rotation lock "$rotation" >/dev/null 2>&1 || true
    wait_for_ui
    CURRENT_RUN_DIR="$OUTPUT_ROOT/$CURRENT_PROFILE/$CURRENT_ORIENTATION"
    mkdir -p "$CURRENT_RUN_DIR"
    UI_CACHE="$TEMP_DIR/ui-${CURRENT_PROFILE}-${CURRENT_ORIENTATION}.xml"
    UI_CACHE_VALID=false
    SHOT_INDEX=0
    {
        echo "serial=$CURRENT_SERIAL"
        echo "profile=$CURRENT_PROFILE"
        echo "orientation=$CURRENT_ORIENTATION"
        adb_shell wm size | trim_cr
        adb_shell wm density | trim_cr
        echo "user_rotation=$(adb_shell settings get system user_rotation | trim_cr)"
    } > "$CURRENT_RUN_DIR/device.txt"
}

grant_runtime_permissions() {
    adb_shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

invalidate_ui_cache() { UI_CACHE_VALID=false; }

dump_ui() {
    # 界面未变化时复用已抓取的 dump，避免重复 uiautomator dump（每次约 1~2 秒）。
    if $UI_CACHE_VALID && [[ -s "$UI_CACHE" ]]; then
        return 0
    fi
    local remote="/sdcard/ssh-helper-layout-window.xml"
    if ! adb_shell uiautomator dump --compressed "$remote" >/dev/null 2>&1; then
        sleep 1
        adb_shell uiautomator dump "$remote" >/dev/null 2>&1 || return 1
    fi
    adb_device exec-out cat "$remote" > "$UI_CACHE"
    if [[ -s "$UI_CACHE" ]]; then
        UI_CACHE_VALID=true
        return 0
    fi
    return 1
}

xml_has_label() {
    local label="$1"
    dump_ui || return 1
    grep -Fq "text=\"$label\"" "$UI_CACHE" || grep -Fq "content-desc=\"$label\"" "$UI_CACHE"
}

node_for_label() {
    local label="$1" nodes node
    nodes="$(sed 's#/><#/>\n<#g' "$UI_CACHE")"
    # 操作时优先使用 content-desc，避免面板标题文字遮蔽同名工具按钮。
    node="$(printf '%s\n' "$nodes" | grep -F "content-desc=\"$label\"" | head -n 1 || true)"
    if [[ -z "$node" ]]; then
        node="$(printf '%s\n' "$nodes" | grep -F "text=\"$label\"" | head -n 1 || true)"
    fi
    printf '%s' "$node"
}

tap_node() {
    local node="$1" bounds x1 y1 x2 y2
    [[ -n "$node" ]] || return 1
    bounds="$(printf '%s' "$node" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    [[ -n "$bounds" ]] || return 1
    read -r x1 y1 x2 y2 <<< "$bounds"
    adb_shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))" >/dev/null
    invalidate_ui_cache
    wait_for_ui
}

tap_label() {
    local label="$1"
    dump_ui || return 1
    tap_node "$(node_for_label "$label")"
}

tap_any() {
    # 只 dump 一次，再遍历候选标签；避免每个候选标签各抓一次界面。
    local label node
    dump_ui || return 1
    for label in "$@"; do
        node="$(node_for_label "$label")"
        if [[ -n "$node" ]] && tap_node "$node"; then
            return 0
        fi
    done
    return 1
}

scroll_to_and_tap() {
    local label="$1" attempt
    for attempt in 1 2 3 4; do
        if tap_label "$label"; then
            return 0
        fi
        adb_shell input swipe 500 1500 500 500 350 >/dev/null
        invalidate_ui_cache
        sleep 1
    done
    return 1
}

record_status() {
    local screen="$1" status="$2" detail="${3:-}"
    printf '%s\t%s\t%02d\t%s\t%s\t%s\n' \
        "$CURRENT_PROFILE" "$CURRENT_ORIENTATION" "$SHOT_INDEX" "$screen" "$status" "$detail" \
        >> "$OUTPUT_ROOT/manifest.tsv"
}

capture_screen() {
    local screen="$1" base
    SHOT_INDEX=$((SHOT_INDEX + 1))
    base="$(printf '%s/%02d-%s' "$CURRENT_RUN_DIR" "$SHOT_INDEX" "$screen")"
    log "截图 $screen"
    adb_device exec-out screencap -p > "$base.png"
    if dump_ui; then
        cp "$UI_CACHE" "$base.xml"
        record_status "$screen" PASS "png+xml"
    else
        : > "$base.xml"
        record_status "$screen" WARN "uiautomator dump failed"
    fi
}

skip_screen() {
    local screen="$1" detail="$2"
    SHOT_INDEX=$((SHOT_INDEX + 1))
    log "跳过 $screen：$detail"
    record_status "$screen" SKIP "$detail"
}

force_stop() {
    adb_shell am force-stop "$PACKAGE" >/dev/null
    invalidate_ui_cache
}

launch_home() {
    force_stop
    adb_shell am start -W -n "$ACTIVITY" >/dev/null
    wait_for_ui
}

launch_settings() {
    local section="${1:-}"
    force_stop
    if [[ -n "$section" ]]; then
        adb_shell am start -W -n "$ACTIVITY" \
            --ez "$EXTRA_OPEN_SETTINGS" true \
            --es "$EXTRA_SETTINGS_SECTION" "$section" >/dev/null
    else
        adb_shell am start -W -n "$ACTIVITY" \
            --ez "$EXTRA_OPEN_SETTINGS" true >/dev/null
    fi
    wait_for_ui
}

press_back() {
    adb_shell input keyevent KEYCODE_BACK >/dev/null
    invalidate_ui_cache
    wait_for_ui
}

input_text() {
    local value="$1"
    # adb input text 用 %s 表示空格；建议测试字段只使用 ASCII。
    value="${value// /%s}"
    adb_shell input text "$value" >/dev/null
    invalidate_ui_cache
}

replace_field() {
    local label="$1" value="$2" i
    tap_label "$label" || return 1
    adb_shell input keyevent KEYCODE_MOVE_END >/dev/null
    for i in $(seq 1 64); do
        adb_shell input keyevent KEYCODE_DEL >/dev/null
    done
    input_text "$value"
    adb_shell input keyevent KEYCODE_BACK >/dev/null
    sleep 1
}

open_fixture_workspace() {
    launch_home
    tap_label "$FIXTURE_NAME"
}

seed_fixture_if_needed() {
    launch_home
    if xml_has_label "$FIXTURE_NAME"; then
        return 0
    fi
    if ! $SEED_FIXTURE; then
        return 1
    fi
    log "创建测试主机 $FIXTURE_NAME ($TEST_USER@$TEST_HOST:$TEST_PORT)"
    tap_any "添加主机" || return 1
    replace_field "连接名称" "$FIXTURE_NAME" || return 1
    replace_field "服务器地址" "$TEST_HOST" || return 1
    replace_field "端口" "$TEST_PORT" || return 1
    replace_field "用户名" "$TEST_USER" || return 1
    scroll_to_and_tap "创建主机" || return 1
    xml_has_label "$FIXTURE_NAME"
}

capture_shell_pages() {
    launch_home
    capture_screen "home-hosts"

    if tap_label "活动"; then
        capture_screen "home-activity"
    else
        skip_screen "home-activity" "navigation item not found"
    fi

    launch_settings
    capture_screen "settings-index"

    local section
    for section in appearance terminal ai connections security documents data about; do
        launch_settings "$section"
        capture_screen "settings-$section"
    done

    launch_home
    if tap_any "快捷命令"; then
        capture_screen "snippets"
        if tap_any "添加命令"; then
            capture_screen "snippet-editor"
        else
            skip_screen "snippet-editor" "add command action not found"
        fi
    else
        skip_screen "snippets" "shortcut command action not found"
        skip_screen "snippet-editor" "snippets page unavailable"
    fi

    launch_home
    if tap_any "扫描局域网"; then
        capture_screen "lan-discovery"
    else
        skip_screen "lan-discovery" "discovery action not found"
    fi

    launch_home
    if tap_any "网络诊断"; then
        capture_screen "network-diagnostics-global"
    else
        skip_screen "network-diagnostics-global" "diagnostics action not found"
    fi

    launch_home
    if tap_any "添加主机"; then
        capture_screen "host-editor-add"
    else
        skip_screen "host-editor-add" "add host action not found"
    fi
}

open_fixture_editor() {
    open_fixture_workspace || return 1
    if tap_any "编辑主机"; then
        return 0
    fi
    # Expanded/Medium 内联工作区不带独立详情页顶栏，从主机卡片菜单进入编辑。
    launch_home
    tap_any "更多操作" || return 1
    tap_label "编辑"
}

open_fixture_action() {
    local action="$1"
    open_fixture_workspace || return 1
    tap_label "$action"
}

complete_credential_if_possible() {
    local attempt
    [[ -n "$TEST_PASSWORD" ]] || return 1
    for attempt in 1 2 3 4; do
        if xml_has_label "密码"; then
            replace_field "密码" "$TEST_PASSWORD" || true
            tap_label "连接" || true
            continue
        fi
        if xml_has_label "信任并连接"; then
            tap_label "信任并连接" || true
            continue
        fi
        break
    done
    ! xml_has_label "密码"
}

capture_terminal_panels() {
    local label screen
    for label in "会话" "扩展键" "搜索" "快捷命令" "更多"; do
        case "$label" in
            会话) screen="terminal-sessions" ;;
            扩展键) screen="terminal-extra-keys" ;;
            搜索) screen="terminal-search" ;;
            快捷命令) screen="terminal-snippets" ;;
            更多) screen="terminal-more" ;;
        esac
        if tap_label "$label"; then
            capture_screen "$screen"
            # 手机竖屏的快捷命令是 BottomSheet，“更多”是 DropdownMenu；返回键只
            # 关闭浮层。其余布局再次点击同名工具按钮即可收起，不会退出终端。
            if [[ "$CURRENT_PROFILE" == "phone" && "$CURRENT_ORIENTATION" == "portrait" ]] &&
                [[ "$label" == "快捷命令" || "$label" == "更多" ]]; then
                press_back
            elif ! tap_label "$label"; then
                press_back
            fi
        else
            skip_screen "$screen" "terminal action not available in this layout/state"
        fi
    done

    if [[ "$CURRENT_PROFILE" == "tablet" && "$CURRENT_ORIENTATION" == "portrait" ]]; then
        # Medium：后打开扩展键后，上下文面板应自动关闭。
        if tap_label "会话" && tap_label "扩展键"; then
            capture_screen "terminal-medium-panel-exclusion"
            tap_label "扩展键" || true
        else
            skip_screen "terminal-medium-panel-exclusion" "required terminal controls not found"
        fi
    elif [[ "$CURRENT_PROFILE" == "tablet" && "$CURRENT_ORIENTATION" == "landscape" ]]; then
        # Expanded：上下文面板和右侧扩展键应允许同时显示。
        if tap_label "会话" && tap_label "扩展键"; then
            capture_screen "terminal-expanded-panels-combined"
            tap_label "扩展键" || true
            tap_label "会话" || true
        else
            skip_screen "terminal-expanded-panels-combined" "required terminal controls not found"
        fi
    fi
}

return_to_workspace_from_terminal() {
    if xml_has_label "取消" && xml_has_label "密码"; then
        tap_label "取消" || press_back
    else
        tap_any "返回" || press_back
    fi
}

capture_host_dependent_pages() {
    if ! seed_fixture_if_needed; then
        local missing
        for missing in host-workspace host-editor-edit forwards forward-editor network-diagnostics-host terminal-entry terminal-connected sftp; do
            skip_screen "$missing" "fixture host unavailable"
        done
        return
    fi

    if open_fixture_workspace; then
        capture_screen "host-workspace"
    else
        skip_screen "host-workspace" "fixture host card not found"
    fi

    if open_fixture_editor; then
        capture_screen "host-editor-edit"
    else
        skip_screen "host-editor-edit" "edit host action not found"
    fi

    if open_fixture_action "端口转发"; then
        capture_screen "forwards"
        if tap_label "添加规则"; then
            capture_screen "forward-editor"
        else
            skip_screen "forward-editor" "add forwarding rule action not found"
        fi
    else
        skip_screen "forwards" "forwarding action not found"
        skip_screen "forward-editor" "forwarding page unavailable"
    fi

    if open_fixture_action "连接诊断"; then
        capture_screen "network-diagnostics-host"
    else
        skip_screen "network-diagnostics-host" "host diagnostics action not found"
    fi

    if ! open_fixture_action "打开终端"; then
        skip_screen "terminal-entry" "open terminal action not found"
        skip_screen "terminal-connected" "terminal unavailable"
        skip_screen "sftp" "workspace unavailable"
        return
    fi

    capture_screen "terminal-entry"
    if xml_has_label "密码"; then
        if complete_credential_if_possible; then
            wait_for_ui
            capture_screen "terminal-connected"
            capture_terminal_panels
        else
            skip_screen "terminal-connected" "set --test-password to capture connected terminal"
            skip_screen "terminal-sessions" "terminal credential gate active"
            skip_screen "terminal-extra-keys" "terminal credential gate active"
            skip_screen "terminal-search" "terminal credential gate active"
            skip_screen "terminal-snippets" "terminal credential gate active"
            skip_screen "terminal-more" "terminal credential gate active"
        fi
    else
        capture_screen "terminal-connected"
        capture_terminal_panels
    fi

    return_to_workspace_from_terminal
    if tap_label "文件"; then
        capture_screen "sftp"
        if xml_has_label "密码"; then
            complete_credential_if_possible || true
            wait_for_ui
            capture_screen "sftp-after-credential"
        fi
        if tap_any "传输任务"; then
            capture_screen "sftp-transfers"
        else
            skip_screen "sftp-transfers" "transfer action not available in this layout/state"
        fi
    else
        skip_screen "sftp" "files action not found after terminal"
        skip_screen "sftp-transfers" "SFTP unavailable"
    fi
}

run_orientation() {
    local orientation="$1"
    set_orientation "$orientation"
    grant_runtime_permissions
    capture_shell_pages
    capture_host_dependent_pages
}

run_profile() {
    local serial="$1" profile="$2" size="$3" density="$4"
    configure_profile "$serial" "$profile" "$size" "$density"
    if $CLEAR_DATA; then
        log "清空 $PACKAGE 应用数据"
        adb_shell pm clear "$PACKAGE" >/dev/null
        invalidate_ui_cache
    fi
    grant_runtime_permissions
    run_orientation portrait
    run_orientation landscape
    force_stop
    restore_window_state
}

validate_device "$PHONE_SERIAL"
validate_device "$TABLET_SERIAL"

cat > "$OUTPUT_ROOT/run-info.txt" <<EOF
package=$PACKAGE
activity=$ACTIVITY
phone_serial=$PHONE_SERIAL
phone_window=$PHONE_SIZE@$PHONE_DENSITY
tablet_serial=$TABLET_SERIAL
tablet_window=$TABLET_SIZE@$TABLET_DENSITY
fixture_name=$FIXTURE_NAME
test_endpoint=$TEST_USER@$TEST_HOST:$TEST_PORT
connected_capture=$([[ -n "$TEST_PASSWORD" ]] && echo enabled || echo disabled)
clear_data=$CLEAR_DATA
EOF

# 执行顺序：平板 → 手机。
run_profile "$TABLET_SERIAL" tablet "$TABLET_SIZE" "$TABLET_DENSITY"
run_profile "$PHONE_SERIAL" phone "$PHONE_SIZE" "$PHONE_DENSITY"

CURRENT_PROFILE="done"
CURRENT_ORIENTATION=""
echo "截图完成：$OUTPUT_ROOT"
echo "场景清单：$OUTPUT_ROOT/manifest.tsv"
