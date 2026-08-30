package com.yang136.sshhelper.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端资源回归测试：直接读取仓库中的资产源文件（Gradle JVM 单测工作目录固定为
 * app/ 模块目录），锁定以下行为，防止终端背景修复回归：
 * - 自定义 CSS（terminal.css）在第三方 xterm.css 之后加载；
 * - viewport 与内边距区域背景由 --terminal-background 驱动，不再使用固定黑色；
 * - setAppearance() 同步 CSS 背景变量，且构建产物由源文件重新生成（非手改 bundle）。
 */
class TerminalAssetRegressionTest {
    private val assetsDir = File("src/main/assets/terminal")

    @Test
    fun customCssLoadsAfterXtermCss() {
        val html = File(assetsDir, "index.html").readText()
        val xtermIndex = html.indexOf("xterm.css")
        val terminalIndex = html.indexOf("terminal.css")
        assertTrue("index.html 必须引用 xterm.css", xtermIndex >= 0)
        assertTrue("index.html 必须引用 terminal.css", terminalIndex >= 0)
        assertTrue("terminal.css 必须在 xterm.css 之后加载", xtermIndex < terminalIndex)
    }

    @Test
    fun viewportBackgroundIsDrivenByCssVariableNotFixedBlack() {
        val css = File(assetsDir, "terminal.css").readText()
        val viewportRule = css.substringAfter(".xterm .xterm-viewport", "")
        assertTrue("terminal.css 必须覆盖 .xterm .xterm-viewport", viewportRule.isNotEmpty())
        assertTrue("viewport 背景必须由 --terminal-background 驱动", viewportRule.contains("var(--terminal-background"))
        assertTrue("terminal.css 必须覆盖 #terminal > .xterm（内边距区域）", css.contains("#terminal > .xterm"))
    }

    @Test
    fun pageAndTerminalBackgroundsUseCssVariable() {
        val css = File(assetsDir, "terminal.css").readText()
        assertTrue("页面与终端背景必须使用 --terminal-background 变量", css.contains("var(--terminal-background"))
        assertFalse("不再硬编码固定深蓝背景", css.contains("background: #07131f"))
        assertFalse("不再硬编码固定黑色背景", css.contains("background-color: #000"))
    }

    @Test
    fun setAppearanceSyncsCssBackgroundVariableInSourceAndBundle() {
        val source = File("../terminal-web/src/index.js").readText()
        val syncsVariable = source.contains("setProperty('--terminal-background'") ||
            source.contains("setProperty(\"--terminal-background\"")
        assertTrue("setAppearance 必须在源文件中同步 --terminal-background", syncsVariable)

        val bundle = File(assetsDir, "terminal.js").readText()
        assertTrue(
            "构建产物 terminal.js 必须包含 --terminal-background（证明由 npm run build 从源重新生成）",
            bundle.contains("--terminal-background"),
        )
    }

    @Test
    fun touchInputRequiresCursorCellAndDoesNotHideImeWhileScrolling() {
        val source = File("../terminal-web/src/index.js").readText()
        val touchMove = source.substringAfter("function handleTouchMove", "").substringBefore("function handleTouchEnd", "")
        val touchEnd = source.substringAfter("function handleTouchEnd", "").substringBefore("terminal.element?.addEventListener('touchstart'", "")

        assertFalse("滚动终端不应主动关闭 Android IME", touchMove.contains("onHideKeyboard"))
        assertTrue("点击输入必须校验当前光标行", touchEnd.contains("point.absoluteRow === cursorAbsoluteRow"))
        assertTrue("点击输入必须校验当前光标列", touchEnd.contains("point.column === cursorColumn"))
        assertTrue("点击光标单元格应请求 Android 软键盘", touchEnd.contains("onRequestKeyboard"))
    }
}
