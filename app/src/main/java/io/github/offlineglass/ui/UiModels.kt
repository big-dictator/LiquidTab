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
    private val baseTitle: String,
    private val baseSelectedIcon: ImageVector,
    private val baseUnselectedIcon: ImageVector,
) {
    HOME("主页", Icons.Filled.Home, Icons.Outlined.Home),
    APPS("应用", Icons.Filled.Apps, Icons.Outlined.Apps),
    SETTINGS("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
    ;

    val title: String get() = baseTitle
    val selectedIcon: ImageVector get() = baseSelectedIcon
    val unselectedIcon: ImageVector get() = baseUnselectedIcon

    companion object {
        val available: List<ManagerPage> get() = entries
    }
}

internal enum class ManagerHomeSubpage(val title: String) {
    NONE("主页"),
    DONATION("赞助本项目"),
    ACKNOWLEDGEMENTS("致谢"),
    AUTHOR("作者"),
    LANGUAGE("语言/Language"),
}

data class UiActions(
    val setLanguage: (io.github.offlineglass.config.ManagerLanguage) -> Unit,
    val setUiMode: (UiMode) -> Unit,
    val setColorMode: (ColorMode) -> Unit,
    val setDynamicColor: (Boolean) -> Unit,
    val setLiquidGlassEnabled: (Boolean) -> Unit,
    val setFrostedGlassEnabled: (Boolean) -> Unit,

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
    // YouTube controls retain semantic bit positions, with unavailable roles omitted.
    if (targetKey == "youtube") return spec?.entryLabels.orEmpty()
    settings?.appEntryLabels?.get(spec?.packageName)?.takeIf { it.isNotEmpty() }?.let { labels ->
        return if (targetKey == "zhihu")
            labels.mapIndexed { index, label -> if (index == 2) "发布" else label }
        else labels
    }
    return when (targetKey) {
    "qq" -> listOf("消息", "联系人", "动态")
    else -> TargetSpecRegistry.all.firstOrNull { it.key == targetKey }?.entryLabels ?: emptyList()
}
}

internal fun GlassConfig.withEntryVisibilityMask(targetKey: String, mask: Int): GlassConfig =
    copy(
        hiddenMask = mask,
        detachedPublish = detachedPublish &&
            !((targetKey == "zhihu" || targetKey == "xianyu" || targetKey == "tiktok" || targetKey == "youtube") &&
                ((hiddenMask or mask) and (1 shl 2)) != 0),
    )

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
