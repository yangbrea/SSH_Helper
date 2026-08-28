package com.yang136.sshhelper.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活引导：检测应用是否被系统电池优化限制网络，并提供跳转入口。
 *
 * 背景：即使前台服务 + WakeLock 保住了进程，Android 的 Doze/App Standby（以及
 * 各厂商 ROM 的省电策略）仍会挂起"没有可见窗口"的后台应用的网络访问，导致
 * SSH 连接在息屏/切走期间掉线、切回应用时才自动重连。让应用进入电池优化
 * 白名单（"不受限制"）可以降低 Doze/App Standby 的干扰，但厂商系统仍可能回收进程。
 */
enum class OemFamily(val label: String) {
    XIAOMI("小米/红米 (HyperOS/MIUI)"),
    OPPO("OPPO/一加/真我 (ColorOS)"),
    VIVO("vivo/iQOO (OriginOS)"),
    HUAWEI("华为/荣耀 (EMUI/HarmonyOS)"),
    SAMSUNG("三星 (One UI)"),
    STOCK("Android 原生/其他"),
}

/** 按品牌/厂商识别 ROM 家族，决定引导文案与跳转目标。 */
fun detectOemFamily(): OemFamily = detectOemFamily(Build.BRAND, Build.MANUFACTURER)

internal fun detectOemFamily(brandValue: String, manufacturerValue: String): OemFamily {
    val brand = brandValue.lowercase()
    val manufacturer = manufacturerValue.lowercase()
    return when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
            manufacturer.contains("xiaomi") -> OemFamily.XIAOMI

        brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ||
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") ||
            manufacturer.contains("realme") -> OemFamily.OPPO

        brand.contains("vivo") || brand.contains("iqoo") ||
            manufacturer.contains("vivo") -> OemFamily.VIVO

        brand.contains("huawei") || brand.contains("honor") ||
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> OemFamily.HUAWEI

        brand.contains("samsung") || manufacturer.contains("samsung") -> OemFamily.SAMSUNG

        else -> OemFamily.STOCK
    }
}

/** 应用是否已进入电池优化白名单。API 26+ 恒可用（本项目 minSdk=26）；低版本视为已豁免。 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun systemBatteryOptimizationIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

fun applicationDetailsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:${context.packageName}"),
)

/**
 * 手动设置跳转：优先打开厂商自启动/后台管理页（需对应系统应用存在），
 * 失败时回退到本应用的应用详情页（其中一般也有"电池/后台"入口）。
 */
internal fun oemBatterySettingsComponents(family: OemFamily): List<Pair<String, String>> = when (family) {
        OemFamily.XIAOMI -> listOf(
            "com.miui.securitycenter" to "com.miui.powercenter.PowerCenterActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.AutoStartActivity",
        )

        OemFamily.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )

        OemFamily.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        )

        OemFamily.HUAWEI -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )

        OemFamily.SAMSUNG -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )

        OemFamily.STOCK -> emptyList()
    }

fun oemBatterySettingsCandidates(family: OemFamily = detectOemFamily()): List<Intent> =
    oemBatterySettingsComponents(family).map { (packageName, className) ->
        Intent().setClassName(packageName, className)
    }

/**
 * 从用户交互中安全打开系统或厂商设置。厂商组件会逐个直接尝试，避免包可见性导致
 * resolveActivity 误判；所有候选失败时统一回退到应用详情页。
 */
fun launchBatterySettings(context: Context, preferOem: Boolean): Boolean {
    val candidates = if (preferOem) oemBatterySettingsCandidates() else listOf(systemBatteryOptimizationIntent())
    val flags = if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
    candidates.forEach { candidate ->
        if (runCatching { context.startActivity(Intent(candidate).addFlags(flags)) }.isSuccess) return true
    }
    return runCatching { context.startActivity(applicationDetailsIntent(context).addFlags(flags)) }.isSuccess
}

/** 各厂商 ROM 的"允许无限制后台"操作指引。 */
fun batteryGuidanceText(family: OemFamily = detectOemFamily()): String = when (family) {
    OemFamily.XIAOMI ->
        "小米/红米：设置 → 应用设置 → 应用管理 → SSH Helper → 省电策略改为「无限制」，并打开「自启动」"

    OemFamily.OPPO ->
        "OPPO/一加：设置 → 应用管理 → SSH Helper → 打开「允许后台运行」，并在电池里关闭「耗电优化」"

    OemFamily.VIVO ->
        "vivo/iQOO：设置 → 电池 → 后台耗电管理 → 允许 SSH Helper 后台高耗电"

    OemFamily.HUAWEI ->
        "华为/荣耀：设置 → 应用 → 应用启动管理 → SSH Helper → 手动管理，允许「自启动」「关联启动」「后台活动」"

    OemFamily.SAMSUNG ->
        "三星：设置 → 电池 → 后台使用限制 → SSH Helper → 选「不受限制」"

    OemFamily.STOCK ->
        "系统设置 → 应用 → SSH Helper → 电池 → 选「不受限制」"
}
