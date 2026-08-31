package com.yang136.sshhelper.ui.adaptive

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SshWindowWidthClass { COMPACT, MEDIUM, EXPANDED }
enum class SshWindowHeightClass { COMPACT, MEDIUM, EXPANDED }

/**
 * 基于当前可用窗口而非物理设备类型的自适应信息。分屏、折叠和自由窗口缩放时会自动重算。
 */
data class SshAdaptiveInfo(
    val width: Dp,
    val height: Dp,
    val widthClass: SshWindowWidthClass,
    val heightClass: SshWindowHeightClass,
) {
    val isLandscape: Boolean get() = width > height
    val isPhoneLandscape: Boolean get() = isLandscape && heightClass == SshWindowHeightClass.COMPACT
    val isLargeScreen: Boolean
        get() = widthClass != SshWindowWidthClass.COMPACT && heightClass != SshWindowHeightClass.COMPACT

    /** 手机横屏与平板都使用顶层导航轨。 */
    val useNavigationRail: Boolean get() = isPhoneLandscape || isLargeScreen

    /** 平板导航轨在详情页保持可见；手机横屏沿用原有详情全屏行为。 */
    val usePersistentNavigationRail: Boolean get() = isLargeScreen

    /** 840dp 以上且高度充足时启用 canonical list-detail。 */
    val useTwoPane: Boolean
        get() = widthClass == SshWindowWidthClass.EXPANDED && heightClass != SshWindowHeightClass.COMPACT

    /** 主机页保留原有手机横屏 master-detail，并在 Expanded 窗口启用平板双栏。 */
    val useHostListDetail: Boolean get() = isPhoneLandscape || useTwoPane

    /** 终端在所有横屏窗口及平板横竖屏使用侧边工具轨。 */
    val useTerminalSideRail: Boolean get() = isLandscape || isLargeScreen

    /** SFTP 的 GNOME 双面板用于手机横屏和 Expanded 平板。 */
    val useDesktopWorkspace: Boolean get() = isPhoneLandscape || useTwoPane

    val maxContentWidth: Dp get() = when (widthClass) {
        SshWindowWidthClass.COMPACT -> width
        SshWindowWidthClass.MEDIUM -> 720.dp
        SshWindowWidthClass.EXPANDED -> 960.dp
    }
}

fun adaptiveInfo(width: Dp, height: Dp): SshAdaptiveInfo = SshAdaptiveInfo(
    width = width,
    height = height,
    widthClass = when {
        width < 600.dp -> SshWindowWidthClass.COMPACT
        width < 840.dp -> SshWindowWidthClass.MEDIUM
        else -> SshWindowWidthClass.EXPANDED
    },
    heightClass = when {
        height < 480.dp -> SshWindowHeightClass.COMPACT
        height < 900.dp -> SshWindowHeightClass.MEDIUM
        else -> SshWindowHeightClass.EXPANDED
    },
)

fun BoxWithConstraintsScope.adaptiveInfo(): SshAdaptiveInfo = adaptiveInfo(maxWidth, maxHeight)

@Composable
fun currentAdaptiveInfo(): SshAdaptiveInfo {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        adaptiveInfo(containerSize.width.toDp(), containerSize.height.toDp())
    }
}

/** 旧的横竖屏兼容接口；新页面应优先使用 [SshAdaptiveInfo]。 */
enum class SshLayoutMode { PORTRAIT, LANDSCAPE }

/** 纯函数：根据可用宽高判断布局模式，便于单元测试。 */
fun layoutMode(width: Dp, height: Dp): SshLayoutMode =
    if (width > height) SshLayoutMode.LANDSCAPE else SshLayoutMode.PORTRAIT

/** 在 [BoxWithConstraints] 作用域内使用实际布局约束判断模式（最贴近真实可用空间）。 */
fun BoxWithConstraintsScope.layoutMode(): SshLayoutMode = layoutMode(maxWidth, maxHeight)

/** 在任意 Composable 中用系统配置判断模式（适用于非布局上下文）。 */
@Composable
fun currentLayoutMode(): SshLayoutMode {
    val info = currentAdaptiveInfo()
    return layoutMode(info.width, info.height)
}

/**
 * 纯函数：是否连接了可见的物理键盘（QWERTY）。
 * 12 键/其他外接键盘不计入，保持简单；软键盘不改变这两个字段。
 */
fun hasHardwareKeyboard(keyboard: Int, hardKeyboardHidden: Int): Boolean =
    keyboard == Configuration.KEYBOARD_QWERTY &&
        hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

/** 当前是否有可见的物理键盘（QWERTY 且未合盖/未收起）。 */
@Composable
fun hasHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current
    return hasHardwareKeyboard(configuration.keyboard, configuration.hardKeyboardHidden)
}
