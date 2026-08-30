package com.yang136.sshhelper.ui.adaptive

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 布局模式。当前仅区分手机竖屏与手机横屏；
 * 后续平板/折叠屏支持只需在此枚举增加更细的宽度档位（如 EXPANDED），现有调用点不受影响。
 */
enum class SshLayoutMode { PORTRAIT, LANDSCAPE }

/** 纯函数：根据可用宽高判断布局模式，便于单元测试。 */
fun layoutMode(width: Dp, height: Dp): SshLayoutMode =
    if (width > height) SshLayoutMode.LANDSCAPE else SshLayoutMode.PORTRAIT

/** 在 [BoxWithConstraints] 作用域内使用实际布局约束判断模式（最贴近真实可用空间）。 */
fun BoxWithConstraintsScope.layoutMode(): SshLayoutMode = layoutMode(maxWidth, maxHeight)

/** 在任意 Composable 中用系统配置判断模式（适用于非布局上下文）。 */
@Composable
fun currentLayoutMode(): SshLayoutMode {
    val configuration = LocalConfiguration.current
    return layoutMode(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
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
