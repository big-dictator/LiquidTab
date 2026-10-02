package io.github.offlineglass.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.UiMode
import io.github.offlineglass.targets.TargetSpecRegistry

enum class ManagerPage(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME("主页", Icons.Filled.Home, Icons.Outlined.Home),
    APPS("应用", Icons.Filled.Apps, Icons.Outlined.Apps),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

internal enum class ManagerHomeSubpage(val title: String) {
    NONE("主页"),
    DONATION("打赏作者"),
    ACKNOWLEDGEMENTS("致谢"),
}

data class UiActions(
    val setUiMode: (UiMode) -> Unit,
    val setColorMode: (ColorMode) -> Unit,
    val setDynamicColor: (Boolean) -> Unit,
    val setLiquidGlassEnabled: (Boolean) -> Unit,

    val setSolidBarEnabled: (Boolean) -> Unit,
    val setOutlineEnabled: (Boolean) -> Unit,
    val setAppEnabled: (String, Boolean) -> Unit,
    val setMiMarketTabEnabled: (String, Boolean) -> Unit,
    val setGlassConfig: (GlassConfig) -> Unit,
    val previewGlassConfig: (GlassConfig) -> Unit,
    val setAppConfig: (String, GlassConfig) -> Unit,
    val clearAppConfig: (String) -> Unit,
    val applyChanges: () -> Unit,
)

internal fun entryLabels(targetKey: String, settings: io.github.offlineglass.config.ManagerSettings? = null): List<String> {
    val spec = TargetSpecRegistry.all.firstOrNull { it.key == targetKey }
    settings?.appEntryLabels?.get(spec?.packageName)?.takeIf { it.isNotEmpty() }?.let { return it }
    return when (targetKey) {
    "qq" -> listOf("消息", "联系人", "动态")
    else -> TargetSpecRegistry.all.firstOrNull { it.key == targetKey }?.entryLabels ?: emptyList()
}
}

internal data class MiMarketTabToggle(
    val key: String,
    val label: String,
    val summary: String,
)

internal val miMarketTabToggles = listOf(
    MiMarketTabToggle(
        key = "pref_key_short_play",
        label = "短剧",
        summary = "同步应用商店「底栏页面管理」中的短剧开关",
    ),
    MiMarketTabToggle(
        key = "pref_key_mini_game",
        label = "小游戏",
        summary = "同步应用商店「底栏页面管理」中的小游戏开关",
    ),
)
