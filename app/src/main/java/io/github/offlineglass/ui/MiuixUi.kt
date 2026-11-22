package io.github.offlineglass.ui


import io.github.offlineglass.rendering.postureDropShadow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.DouyinTopChannels
import io.github.offlineglass.config.TikTokTopChannels
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.targets.TargetSpec
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.offlineglass.ui.ManagerStyleCard as Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import io.github.offlineglass.ui.ManagerStyleSlider as Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import io.github.offlineglass.ui.ManagerMiuixText as Text
import io.github.offlineglass.ui.ManagerArrowPreference as ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import io.github.offlineglass.ui.ManagerStyleSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
fun MiuixManager(
    settings: ManagerSettings,
    selectedPage: ManagerPage,
    onSelectedPage: (ManagerPage) -> Unit,
    actions: UiActions,
) {
    var previewGlass by remember { mutableStateOf(settings.glass) }
    LaunchedEffect(settings.glass) { previewGlass = settings.glass }
    val previewActions = actions.copy(previewGlassConfig = { previewGlass = it.normalized() })
    var homeSubpageName by rememberSaveable { mutableStateOf(ManagerHomeSubpage.NONE.name) }
    val homeSubpage = ManagerHomeSubpage.entries.firstOrNull { it.name == homeSubpageName }
        ?: ManagerHomeSubpage.NONE
    BackHandler(enabled = selectedPage == ManagerPage.HOME && homeSubpage != ManagerHomeSubpage.NONE) {
        homeSubpageName = ManagerHomeSubpage.NONE.name
    }
    var entryTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    val entryTarget = entryTargetKey
        ?.let { key -> AppCatalog.uiTargets.firstOrNull { it.key == key.substringBefore('/') } }
    val scrollBehavior = MiuixScrollBehavior()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val glassBackdrop = rememberLayerBackdrop {
        // Keep the scene opaque before recording it.  Without the explicit
        // surface the transparent Scaffold layer is composited as black by
        // RenderEffect, which is what produced the dead-black manager bar.
        drawRect(surfaceColor)
        drawContent()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ManagerCompactTopBar(
                title = when {
                    selectedPage == ManagerPage.HOME && homeSubpage != ManagerHomeSubpage.NONE -> homeSubpage.title
                    selectedPage == ManagerPage.APPS && entryTargetKey in setOf(DouyinTopChannels.SETTINGS_ROUTE, TikTokTopChannels.ROUTE) -> "选择常驻顶栏页面"
                    selectedPage == ManagerPage.APPS -> entryTarget?.displayName ?: selectedPage.title
                    else -> selectedPage.title
                },
                onBack = when {
                    selectedPage == ManagerPage.HOME && homeSubpage != ManagerHomeSubpage.NONE -> ({ homeSubpageName = ManagerHomeSubpage.NONE.name })
                    selectedPage == ManagerPage.APPS && entryTarget != null -> ({
                        entryTargetKey = entryTargetKey?.substringBefore('/')?.takeIf { entryTargetKey?.contains('/') == true }
                    })
                    else -> null
                },
            )
        },
        bottomBar = {
            ManagerBottomBarVisibility(
                visible = !(selectedPage == ManagerPage.APPS && entryTarget != null),
                heightDp = managerBottomBarSlotHeight(previewGlass),
            ) {
            if (selectedPage != ManagerPage.HOME || homeSubpage == ManagerHomeSubpage.NONE) ManagerGlassBottomBar(
                selectedPage = selectedPage,
                onSelectedPage = { page ->
                    if (page != ManagerPage.HOME) homeSubpageName = ManagerHomeSubpage.NONE.name
                    if (page != ManagerPage.APPS) entryTargetKey = null
                    onSelectedPage(page)
                },
                selectedColor = MiuixTheme.colorScheme.primary,
                unselectedColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                backdrop = glassBackdrop,
                containerColor = MiuixTheme.colorScheme.surfaceContainer,
                tabWidthDp = previewGlass.tabWidth,
                liquidGlassEnabled = settings.liquidGlassEnabled,
                solidBarEnabled = settings.solidBarEnabled,
                postureEffectsEnabled = settings.glass.postureEffectsEnabled,
                indicatorInteractionLightEnabled = settings.glass.indicatorInteractionLightEnabled,
                simplifyTabSwitch = settings.glass.simplifyTabSwitch,
                outlineEnabled = settings.outlineEnabled,
                blurRadius = previewGlass.blurRadius,
                cornerRadiusPercent = previewGlass.cornerRadiusPercent,
                cornerSmoothing = previewGlass.cornerSmoothing,
                barHeight = previewGlass.barHeight,
                darkBarHighlightStrength = previewGlass.darkBarHighlightStrength,
                lightAlpha = previewGlass.lightAlpha,
                darkAlpha = previewGlass.darkAlpha,
                classicNavigation = previewGlass.classicNavigation,
                iconOnly = previewGlass.bottomBarIconOnly,
            )
            }
        },
        popupHost = {},
    ) { innerPadding ->
        // The top veil now rides on the page content itself as a RenderEffect
        // tree (six masked platform Gaussians, MIRROR edges) instead of a
        // backdrop-sampling overlay, so it stays in sync with every scroll
        // frame — the same architecture as the verified WeChat bottom veil.
        val density = LocalDensity.current
        val topVeilHeightPx = with(density) { (innerPadding.calculateTopPadding() + 12.dp).toPx() }
        val topBlurEffect = rememberManagerGradientTopBlurEffect(
            enabled = true,
            veilHeightPx = topVeilHeightPx,
            density = density.density,
        )
        Box(Modifier.fillMaxSize()) {
            // Record only the page scene.  The bottom glass bar samples this
            // backdrop from the bottom of the screen, far below the veiled top
            // band, so the top blur riding inside the recorded layer never
            // feeds the bar's sampling region.
            Box(Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                ManagerPagePager(
                    selectedPage = selectedPage,
                    onSelectedPage = {
                        if (it != ManagerPage.HOME) homeSubpageName = ManagerHomeSubpage.NONE.name
                        if (it != ManagerPage.APPS) entryTargetKey = null
                        onSelectedPage(it)
                    },
                    modifier = Modifier.fillMaxSize().managerGradientTopBlur(topBlurEffect, topVeilHeightPx, density.density),
                ) { page ->
                    when (page) {
                    ManagerPage.HOME -> AnimatedContent(
                        targetState = homeSubpage,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val pushing = initialState == ManagerHomeSubpage.NONE && targetState != ManagerHomeSubpage.NONE
                            (slideInHorizontally { width -> if (pushing) width else -width } + fadeIn()) togetherWith
                                (slideOutHorizontally { width -> if (pushing) -width else width } + fadeOut())
                        },
                        label = "miuix-home-subpage",
                    ) { route ->
                        if (route == ManagerHomeSubpage.NONE) {
                            MiuixHome(settings, innerPadding, actions) { homeSubpageName = it.name }
                        } else {
                            MiuixHomeSubpage(route, innerPadding, settings, actions)
                        }
                    }
                    ManagerPage.APPS -> MiuixApps(
                        settings = settings,
                        padding = innerPadding,
                    actions = previewActions,
                        entryTargetKey = entryTargetKey,
                        onEntryTargetChange = { entryTargetKey = it },
                    )
                    ManagerPage.SETTINGS -> MiuixSettings(settings, innerPadding, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixHome(
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
    onOpenSubpage: (ManagerHomeSubpage) -> Unit,
) {
    val activeRecently = System.currentTimeMillis() - settings.lastActiveTime < 10 * 60_000L
    MiuixList(padding) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = managerStatusCardColor(),
                ),
            ) {
                Row(
                    // The 48dp status vector has a 4dp internal circle inset.
                    // Its visible edge starts at the other cards' 16dp line.
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        if (activeRecently) Icons.Rounded.CheckCircle else Icons.Rounded.OfflineBolt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = ManagerStatusIconGreen,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LiquidTab ${io.github.offlineglass.BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+')}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = "语言/Language", summary = "选择模块界面与底栏的语言",
                    startAction = { ManagerHomeLeadingIcon(androidx.compose.material.icons.Icons.Default.Language) },
                    onClick = { onOpenSubpage(ManagerHomeSubpage.LANGUAGE) })
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "赞助本项目",
                    summary = "如果 LiquidTab 对你有帮助，欢迎赞助本项目",
                    startAction = { ManagerHomeLeadingIcon(Icons.Filled.Coffee, tone = HomeIconTone.ORANGE) },
                    onClick = { onOpenSubpage(ManagerHomeSubpage.DONATION) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "作者",
                    summary = "了解作者与 LiquidTab 项目",
                    startAction = { ManagerHomeLeadingIcon(androidx.compose.material.icons.Icons.Filled.Person, tone = HomeIconTone.PINK) },
                    onClick = { onOpenSubpage(ManagerHomeSubpage.AUTHOR) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "致谢",
                    summary = "查看本模块使用的开源项目与依赖",
                    startAction = { ManagerHomeLeadingIcon(Icons.Filled.LocalFlorist, tone = HomeIconTone.PINK) },
                    onClick = { onOpenSubpage(ManagerHomeSubpage.ACKNOWLEDGEMENTS) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ManagerHomeLeadingIcon(Icons.Rounded.CloudOff, tone = HomeIconTone.TEAL)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("离线与隐私", style = MiuixTheme.textStyles.body1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "模块没有网络权限；所有设置、状态和渲染数据都留在设备本地。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixHomeSubpage(page: ManagerHomeSubpage, padding: PaddingValues, settings: ManagerSettings, actions: UiActions) {
    if (page == ManagerHomeSubpage.LANGUAGE) {
        ManagerLanguagePage(settings, padding, actions)
        return
    }
    val uriHandler = LocalUriHandler.current
    MiuixList(padding) {
        when (page) {
            ManagerHomeSubpage.AUTHOR -> { item { AuthorInfoContent(miuix = true) } }
            ManagerHomeSubpage.DONATION -> {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            DonationQrImage()
                            Spacer(Modifier.height(10.dp))
                            Text("感谢你的支持", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }

            }
            ManagerHomeSubpage.ACKNOWLEDGEMENTS -> {
                item { TopBarLayoutContributorCard(miuix = true) }
                item {
                    Text(
                        "感谢以下开源项目为 LiquidTab 提供基础能力。",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                item { MiuixAcknowledgementCard("AndroidX Jetpack Compose", "BOM 2026.06.01；Activity Compose 1.13.0、Animation、Foundation、Material Icons Extended、Material 3 1.5.0-alpha24、UI 与 Tooling Preview") }
                item { MiuixAcknowledgementCard("AndroidX Navigation", "Navigation 3 Runtime 1.1.4 与 NavigationEvent Compose 1.1.2") }
                item { MiuixAcknowledgementCard("AndroidX Graphics Shapes", "连续曲线与几何形状支持（1.1.0）") }
                item { MiuixAcknowledgementCard("Miuix KMP", "UI、图标、Preference、Blur、Squircle 与 Navigation 3 UI（均为 0.9.3）") }
                item { MiuixAcknowledgementCard("kyant0 / AndroidLiquidGlass", "感谢 Kyant 的开源液态玻璃实现。本模块参考 LiquidButton，改编折射着色器，并使用 LiquidToggle、LiquidSlider 示例实现可选的 iOS 风格控件。许可证：Apache-2.0。点击查看开源项目。", "https://github.com/Kyant0/AndroidLiquidGlass") }
                item { MiuixAcknowledgementCard("LSPosed HiddenApiBypass", "隐藏 API 访问兼容层（6.1）") }
                item { MiuixAcknowledgementCard("LSPosed API", "模块接口由项目内的编译期桩提供，运行时由 LSPosed 环境承载") }
            }
            ManagerHomeSubpage.NONE, ManagerHomeSubpage.LANGUAGE -> Unit
        }
    }
}

@Composable
private fun MiuixAcknowledgementCard(title: String, summary: String, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().then(if (url != null) Modifier.clickable { uriHandler.openUri(url) } else Modifier)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(3.dp))
            Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun MiuixMetricCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(18.dp)) {
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun MiuixApps(
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
    entryTargetKey: String?,
    onEntryTargetChange: (String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = entryTargetKey != null) {
        onEntryTargetChange(entryTargetKey?.substringBefore('/')?.takeIf { entryTargetKey?.contains('/') == true })
    }
    // Ordinary transitions for both toolbar and system back. Navigation's
    // predictive scene previously consumed system pop with a None transition.
    AnimatedContent(
        targetState = entryTargetKey,
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).clipToBounds(),
        transitionSpec = {
            val pushing = (targetState?.count { it == '/' } ?: -1) > (initialState?.count { it == '/' } ?: -1)
            (slideInHorizontally { if (pushing) it else -it } + fadeIn()) togetherWith
                (slideOutHorizontally { if (pushing) -it else it } + fadeOut())
        },
        label = "miuix-app-detail",
    ) { key ->
        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
            if (key == null) {
                MiuixAppList(settings, padding, actions, query, { query = it }, onEntryTargetChange)
            } else {
                val target = AppCatalog.uiTargets.firstOrNull { it.key == key.substringBefore('/') }
                if (target != null) {
                    if (key == DouyinTopChannels.SETTINGS_ROUTE) MiuixDouyinTopChannels(target, settings, padding, actions)
                    else if (key == TikTokTopChannels.ROUTE) MiuixTikTokTopChannels(target, settings, padding, actions)
                    else MiuixAppOverride(target, settings, padding, actions) {
                        onEntryTargetChange(if (target.key == "tiktok") TikTokTopChannels.ROUTE else DouyinTopChannels.SETTINGS_ROUTE)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixAppList(
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
    query: String,
    onQueryChange: (String) -> Unit,
    onEntryTargetChange: (String?) -> Unit,
) {
    val targets = remember(query, settings.appEnabled, settings.installedPackages) {
        AppCatalog.uiTargets.filter {
            (it.alwaysShowInManager || it.packageName in settings.installedPackages) &&
                (query.isBlank() || it.displayName.contains(query, true) || it.packageName.contains(query, true))
        }.sortedBy { settings.appEnabled[it.packageName] == false }
    }
    val surface = MiuixTheme.colorScheme.surface
    val listBackdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().layerBackdrop(listBackdrop)) {
    MiuixList(PaddingValues(top = padding.calculateTopPadding() + 64.dp,
        bottom = padding.calculateBottomPadding())) {
        items(targets, key = { it.packageName }) { target ->
            Card(Modifier.fillMaxWidth(), allowTilt = false) {
                    ArrowPreference(
                        title = target.displayName,
                        summary = when (target.key) {
                            else -> "${target.packageName} · 底栏入口设置"
                        },
                        startAction = { AppIcon(target.packageName, target.displayName, Modifier.padding(end = 8.dp)) },
                        endActions = {
                            ManagerStyleSwitch(
                                checked = settings.appEnabled[target.packageName] != false,
                                onCheckedChange = { actions.setAppEnabled(target.packageName, it) },
                            )
                        },
                        onClick = { onEntryTargetChange(target.key) },
                    )
            }
        }
    }
    }
    MiuixSearchBox(query, onQueryChange, settings, listBackdrop,
        Modifier.padding(top = padding.calculateTopPadding() + 4.dp, start = 12.dp, end = 12.dp))
    }
}

@Composable
private fun MiuixAppEntries(
    target: TargetSpec,
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
) {
    val labels = if (target.key == "spotify") emptyList() else entryLabels(target.key, settings)
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val toggleIndices = labels.indices.filter { labels[it].isNotBlank() }
    val enabledCount = toggleIndices.count { it !in target.automaticEntryIndices && config.hiddenMask and (1 shl it) == 0 }
    MiuixList(padding) {
        items(toggleIndices) { index ->
            val enabled = config.hiddenMask and (1 shl index) == 0
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = labels[index],
                    summary = if (target.key == "spotify" && index == 3 && !config.spotifyPremiumAvailable)
                        "Spotify 已根据帐号状态隐藏此入口。" else if (enabled) "显示图标、文字和触控入口" else "入口已关闭",
                    checked = enabled,
                    enabled = (!enabled || enabledCount > 1) &&
                        (target.key != "spotify" || index != 3 || config.spotifyPremiumAvailable),
                    onCheckedChange = { checked ->
                        val mask = if (checked) {
                            config.hiddenMask and (1 shl index).inv()
                        } else {
                            config.hiddenMask or (1 shl index)
                        }
                        actions.setAppConfig(target.packageName, if (target.key == "youtube") config.withEntryVisibilityMask(target.key, mask)
                                    else config.copy(hiddenMask = mask))
                    },
                )
            }
        }
    }
}

@Composable
private fun MiuixDouyinTopChannels(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val pins = DouyinTopChannels.pins(DouyinTopChannels.DEFAULT_PINS)
    val channels = DouyinTopChannels.ordered(settings.douyinTopChannels, DouyinTopChannels.DEFAULT_PINS)
    MiuixList(padding) {
        item {
            Text(if (channels.isEmpty()) "正在读取已安装抖音的频道列表…"
                else "关注、直播、推荐常驻；其余标签仅在显示该页面时出现。标签始终按原生页面顺序排列，切换时连续过渡。",
                modifier = Modifier.padding(16.dp))
        }
        items(channels, key = { it.id }) { channel ->
            val checked = channel.id in pins
            Card(Modifier.fillMaxWidth().animateItem()) {
                SwitchPreference(
                    title = channel.label,
                    summary = if (checked) "常驻顶栏 · 按原生页面顺序排列" else "仅在显示该页面时出现",
                    checked = checked,
                    enabled = false,
                    onCheckedChange = {},
                )
            }
        }
    }
}

@Composable
private fun MiuixTikTokTopChannels(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val pins = TikTokTopChannels.pins(config.tiktokPinnedTabs)
    val channels = TikTokTopChannels.ordered(settings.tiktokTopChannels, config.tiktokPinnedTabs)
    MiuixList(padding) {
        item {
            Text(if (channels.isEmpty()) "正在读取已安装 TikTok的频道列表…"
                else "已选择 ${pins.size}/3。先开启的页面在最右侧，后开启的依次向左排列；其余位置跟随当前页面。",
                modifier = Modifier.padding(16.dp))
        }
        items(channels, key = { it.id }) { channel ->
            val checked = channel.id in pins
            Card(Modifier.fillMaxWidth().animateItem()) {
                SwitchPreference(
                    title = channel.label,
                    summary = if (checked) "常驻顶栏 · 从右第 ${pins.indexOf(channel.id)+1} 位"
                        else if (pins.size >= TikTokTopChannels.MAX_PINS) "已选满三个，关闭一个后可继续选择" else "不常驻",
                    checked = checked,
                    enabled = config.tiktokTopBar && (checked || pins.size < TikTokTopChannels.MAX_PINS),
                    onCheckedChange = { enabled -> actions.setAppConfig(target.packageName,
                        config.copy(tiktokPinnedTabs = TikTokTopChannels.toggle(config.tiktokPinnedTabs, channel.id, enabled))) },
                )
            }
        }
    }
}

@Composable
private fun MiuixAppOverride(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions,
    onTopChannels: () -> Unit) {
    val context = LocalContext.current
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val labels = if (target.key == "spotify") emptyList() else entryLabels(target.key, settings)
    val toggleIndices = labels.indices.filter { labels[it].isNotBlank() }
    val enabledCount = toggleIndices.count { it !in target.automaticEntryIndices && config.hiddenMask and (1 shl it) == 0 }
    MiuixList(padding) {
        if (target.key == "wechat") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "重构顶栏",
                        summary = "关闭后恢复微信原生顶栏，底栏不受影响。",
                        checked = config.wechatTopBarReconstruction,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(wechatTopBarReconstruction = it)) },
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "顶部模糊渐变",
                        summary = "开启后模糊顶部背景；关闭使用半透明渐变。",
                        checked = config.wechatTopBlurGradient,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(wechatTopBlurGradient = it)) },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "方案 B（调试）",
                        summary = "开启后折叠置顶聊天使用右侧绿色圆形按钮；关闭使用方案 A。",
                        checked = config.wechatFoldPlanB,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(wechatFoldPlanB = it)) },
                    )
                }
            }
        }
        if (target.key == "mi_health") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "使用 iOS 底栏图标",
                        summary = "心形、跑步、手表、人物；关闭后恢复原图标。",
                        checked = config.miHealthIosIcons,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(miHealthIosIcons = it)) },
                    )
                    SwitchPreference(
                        title = "精简我的页面",
                        summary = "隐藏会员、小习惯、运动健康周报、活力竞赛和健康问诊卡片。",
                        checked = config.miHealthSimplifyMine,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(miHealthSimplifyMine = it)) },
                    )
                }
            }
        }
        if (target.key == "x") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "自动隐藏底栏",
                        summary = "开启：信息流上滑收起、下滑展开，跟随原生节奏；关闭：滚动时底栏与发布键始终显示。帖子详情页仍会隐藏。",
                        checked = config.xAutoHideBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(xAutoHideBar = it)) },
                    )
                }
            }
        }
        if (target.key == "tiktok") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(title = "液态玻璃顶栏", summary = "保留原生频道和点击操作，使用胶囊顶栏与完整频道菜单。",
                        checked = config.tiktokTopBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(tiktokTopBar = it)) })
                    if (config.hiddenMask and (1 shl 2) == 0) {
                        SwitchPreference(
                            title = "发布键独立到右侧",
                            summary = "四个页面在左，无底色的液态玻璃发布键独立在右；左右屏幕边距不变。",
                            checked = config.detachedPublish,
                            onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(detachedPublish = it)) },
                        )
                    }
                    SwitchPreference(
                        title = "竖屏视频上下铺满（实验）",
                        summary = "对 9:16 及更修长的视频等比缩放至屏幕上下边界，可能裁切左右；关闭恢复完整居中。切换后重新进入视频生效。",
                        checked = config.tiktokTallVideoFill,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(tiktokTallVideoFill = it)) },
                    )
                }
            }
        }
        if (target.key == "douyin") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "液态玻璃顶栏",
                        summary = "四个频道使用胶囊顶栏，其他频道从更多菜单进入；关闭恢复原生顶栏。",
                        checked = config.douyinTopBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(douyinTopBar = it)) },
                    )
                    if (config.hiddenMask and (1 shl 2) == 0) {
                        SwitchPreference(
                            title = "发布键独立到右侧",
                            summary = "关闭使用原布局；开启后四个页面在左，发布键独立在右，整体左右边距不变。",
                            checked = config.detachedPublish,
                            onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(detachedPublish = it)) },
                        )
                    }
                }
            }
        }
        if (target.key == "bilibili" || target.key == "bilibili_in") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "重构顶栏",
                        summary = "关闭后恢复 Bilibili 原生首页顶栏；底栏仍由应用列表中的开关控制。",
                        checked = config.bilibiliTopBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliTopBar = it)) },
                    )
                    SwitchPreference(
                        title = "取消首页信息流左右边距",
                        summary = "让双列卡片和通栏视频卡片贴近屏幕两侧，保留两列之间的间距。",
                        checked = config.bilibiliEdgeToEdgeFeed,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliEdgeToEdgeFeed = it)) },
                    )
                }
            }
        }
        if (target.key == "bilibili_in") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(title = "播放页状态栏氛围背景",
                        summary = "实验性：以低频画面取样生成柔和模糊背景，保留系统状态栏图标；默认关闭。",
                        checked = config.bilibiliInVideoAmbient,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliInVideoAmbient = it)) })
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(title = "底栏右侧独立搜索键",
                        summary = "开启后搜索栏变为底栏右侧的独立液态搜索键；关闭后恢复底栏上方的搜索栏。",
                        checked = config.bilibiliInDetachedSearch,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliInDetachedSearch = it)) })
                }
            }
        }
        if (target.key == "bilibili") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    val searchBlocked = config.hiddenMask and 31 == 0
                    val notifySearchBlocked = {
                        android.widget.Toast.makeText(context, "请至少关闭一个页面开关", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    Box(Modifier.fillMaxWidth()) {
                        SwitchPreference(title = "独立搜索键",
                            summary = "将首页搜索栏改为底栏右侧的独立液态搜索键，开启时自动关闭独立发布键。",
                            checked = config.bilibiliDetachedSearch, enabled = !searchBlocked,
                            onCheckedChange = { checked ->
                                if (checked && searchBlocked) notifySearchBlocked()
                                else actions.setAppConfig(target.packageName, config.copy(
                                    bilibiliDetachedSearch = checked,
                                    detachedPublish = if (checked) false else config.detachedPublish,
                                ))
                            })
                        if (searchBlocked) Box(Modifier.matchParentSize().clickable(
                            onClickLabel = "请至少关闭一个页面开关", onClick = notifySearchBlocked))
                    }
                    SwitchPreference(title = "播放页状态栏氛围背景",
                        summary = "以低频画面取样生成柔和模糊背景，保留系统状态栏图标；默认关闭。",
                        checked = config.bilibiliVideoAmbient,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliVideoAmbient = it)) })
                }
            }
        }
        if (target.key == "xhs") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "重构顶栏",
                        summary = "关闭后恢复小红书原生首页顶栏；底栏仍由应用列表中的开关控制。",
                        checked = config.xhsTopBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(xhsTopBar = it)) },
                    )
                    SwitchPreference(
                        title = "取消首页信息流左右边距",
                        summary = "让关注、发现、同城的信息流贴近屏幕两侧，保留列间距。",
                        checked = config.xhsEdgeToEdgeFeed,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(xhsEdgeToEdgeFeed = it)) },
                    )
                }
            }
        }
        if (target.key == "weibo") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "重构首页顶栏",
                        summary = "仅改造首页推荐、关注与三个原生操作入口；关闭后恢复原首页布局。",
                        checked = config.weiboHomeTopBar,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(weiboHomeTopBar = it)) },
                    )
                }
            }
        }
        if (target.key == "youtube" && config.isYouTubeCreateVisible()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "独立创作键",
                        summary = "开启后将创作入口放在底栏右侧的独立圆形按钮。",
                        checked = config.detachedPublish,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(detachedPublish = it)) },
                    )
                }
            }
        }
        if (((target.key == "bilibili" || target.key == "xhs" || target.key == "zhihu") &&
            config.hiddenMask and (1 shl 2) == 0) ||
            (target.key == "xianyu" && config.hiddenMask and (1 shl 2) == 0) ||
            (target.key == "tieba" && config.isTiebaPublishVisible())) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = if (target.key == "xianyu") "独立拍照键" else "独立发布键",
                        summary = if (target.key == "xianyu") "开启后四个页面在左，黄色液态玻璃拍照键独立在右，底色跟随底栏透明度设置。"
                            else if (target.key == "zhihu") "开启后四个页面在左，半透明蓝色发布键独立在右，两部分分别绘制阴影。"
                            else if (target.key == "xhs") "开启后四个页面在左，红色发布键独立在右，两部分分别绘制阴影。"
                            else if (target.key == "tieba") "开启后四个页面在左，蓝色发布键独立在右，整体左右边距不变。"
                            else "开启后四个页面在左，粉色发布键独立在右，整体左右边距不变。",
                        checked = config.detachedPublish,
                        onCheckedChange = { checked -> actions.setAppConfig(target.packageName, config.copy(
                            detachedPublish = checked,
                            bilibiliDetachedSearch = if (target.key == "bilibili" && checked) false else config.bilibiliDetachedSearch,
                        )) },
                    )
                }
            }
        }
        if (target.key == "bilibili" || target.key == "bilibili_in") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "顶部使用纯色渐变",
                        summary = "默认开启：半透明白色或深灰色渐变，降低功耗；关闭后使用模糊渐变。",
                        checked = config.bilibiliSolidTopGradient,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliSolidTopGradient = it)) },
                    )
                    SwitchPreference(
                        title = "底部使用纯色渐变",
                        summary = "独立控制底部半透明白色或深灰色渐变。",
                        checked = config.bilibiliBottomColorGradient,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(bilibiliBottomColorGradient = it)) },
                    )
                }
            }
        }
        if (target.key == "tiktok" || target.key == "douyin") {
            item {
                ManagerSliderGroup {
                    MiuixEffectSlider("顶栏和底栏底色不透明度", config.shortVideoBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                        actions.setAppConfig(target.packageName, config.copy(shortVideoBarOpacity = it / 100f))
                    }
                }
            }
        }
        if (target.key == "spotify") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "主题色播放进度",
                        summary = "歌曲主题色随播放进度从左向右铺满迷你播放器。",
                        checked = config.spotifyProgressFill,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(spotifyProgressFill = it)) },
                    )
                    SwitchPreference(
                        title = "创建键独立到右侧",
                        summary = "创建键独立为绿色圆形按钮，整体屏幕左右边距不变。",
                        checked = config.detachedPublish,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(detachedPublish = it)) },
                    )
                }
            }
            item {
                ManagerSliderGroup {
                    MiuixEffectSlider("底栏底色透明度系数", config.spotifyBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                        actions.setAppConfig(target.packageName, config.copy(spotifyBarOpacity = it / 100f))
                    }
                }
            }
        }
        if (target.key == "netease" || target.key == "qqmusic") {
            item {
                ManagerSliderGroup {
                    MiuixEffectSlider("底栏与迷你播放器底色透明度系数", config.spotifyBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                        actions.setAppConfig(target.packageName, config.copy(spotifyBarOpacity = it / 100f))
                    }
                }
            }
        }
        if (target.key == "meituan_main") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "小团按键独立到右侧",
                        summary = "开启后三个页面在左，小团图标居中显示在右侧圆形液态玻璃按键内；关闭后回到底栏。",
                        checked = config.meituanDetachedXiaotuan,
                        onCheckedChange = { actions.setAppConfig(target.packageName, config.copy(meituanDetachedXiaotuan = it)) },
                    )
                    Text("视频入口固定隐藏，不受美团自身设置影响。", modifier = Modifier.padding(20.dp))
                }
            }
        }
        if (target.key == "mi_market") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(if (target.key == "mi_market") "请在应用商店设置中关闭视频广告页面" else "请在美团设置中关闭视频广告页面", modifier = Modifier.padding(20.dp))
                }
            }
        } else if (target.key == "wechat") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("为了保障正常功能使用，不支持关闭页面", modifier = Modifier.padding(20.dp))
                }
            }
        } else if (target.key == "zhihu" && labels.isEmpty()) {
            item {
                Text("请先打开知乎主页，读取实际底栏后再设置入口开关。", modifier = Modifier.padding(20.dp))
            }
        } else if (labels.isNotEmpty() && target.key != "meituan_main") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    toggleIndices.filterNot { it in target.automaticEntryIndices }.forEach { index ->
                    val enabled = config.hiddenMask and (1 shl index) == 0
                    SwitchPreference(
                        title = labels[index],
                        summary = if (target.key == "spotify" && index == 3 && !config.spotifyPremiumAvailable)
                            "Spotify 已根据帐号状态隐藏此入口。" else if (enabled) "显示图标、文字和触控入口" else "入口已关闭",
                        checked = enabled,
                        enabled = (!enabled || enabledCount > 1) &&
                            (target.key != "spotify" || index != 3 || config.spotifyPremiumAvailable),
                        onCheckedChange = { checked ->
                            val mask = if (checked) config.hiddenMask and (1 shl index).inv()
                            else config.hiddenMask or (1 shl index)
                            actions.setAppConfig(target.packageName, config.withEntryVisibilityMask(target.key, mask))
                        },
                    )
                    }
                }
            }
        }
    }
}



@Composable
private fun MiuixColorSliders(color: Int, onColor: (Int) -> Unit) {
    val red = android.graphics.Color.red(color).toFloat()
    val green = android.graphics.Color.green(color).toFloat()
    val blue = android.graphics.Color.blue(color).toFloat()
    MiuixEffectSlider("红色", red, 0f..255f, red.toInt().toString()) {
        onColor(android.graphics.Color.rgb(it.toInt(), green.toInt(), blue.toInt()))
    }
    MiuixEffectSlider("绿色", green, 0f..255f, green.toInt().toString()) {
        onColor(android.graphics.Color.rgb(red.toInt(), it.toInt(), blue.toInt()))
    }
    MiuixEffectSlider("蓝色", blue, 0f..255f, blue.toInt().toString()) {
        onColor(android.graphics.Color.rgb(red.toInt(), green.toInt(), it.toInt()))
    }
}

@Composable
private fun MiuixSearchBox(value: String, onChange: (String) -> Unit, settings: ManagerSettings,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    var pressSerial by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var pulse by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(pressSerial) {
        if (pressSerial > 0) { pulse = true; kotlinx.coroutines.delay(180); pulse = false }
    }
    val press by animateFloatAsState(if (pressed || pulse) 1f else 0f,
        spring(dampingRatio = 0.65f, stiffness = 420f), label = "search-glass-press")
    val shape = remember(settings.glass.cornerSmoothing) {
        BottomBarSquircleShape(androidx.compose.foundation.shape.CornerSize(50), settings.glass.cornerSmoothing)
    }
    val fill = CardDefaults.defaultColors().color
    val dark = LocalIosDark.current
    val optical = settings.liquidGlassEnabled && !settings.solidBarEnabled
    val searchEdge = remember { ManagerGlassEdge() }
    val dynamicHighlight = if (optical) rememberGravityRotatedHighlight(
        iosIndicatorSpecular, -45f, settings.glass.postureEffectsEnabled,
    ) else iosIndicatorSpecular
    Box(modifier.fillMaxWidth().postureDropShadow(shape,
        androidx.compose.ui.graphics.shadow.Shadow(
            radius = if (dark) 10.dp else 8.dp,
            spread = if (dark) 1.dp else 2.dp,
            color = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.16f),
        )).pointerInput(Unit) {
        // Observe, do not consume: the text field must retain focus and cursor gestures.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false,
                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            touch = down.position
            pressSerial++
            pressed = true
            try {
                do { val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            } finally { pressed = false }
        }
    }.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (!settings.solidBarEnabled) {
                blur(settings.glass.blurRadius.dp.toPx())
                if (settings.liquidGlassEnabled) managerLens((12 + 4 * press).dp.toPx(), (12 + 4 * press).dp.toPx())
            }
        },
        highlight = { iosIndicatorSpecular.copy(alpha = 0f) },
        layerBlock = {
            val scale = if (settings.liquidGlassEnabled && !settings.solidBarEnabled) 1f + 0.035f * press else 1f
            scaleX = scale; scaleY = scale
        },
        onDrawSurface = {
            drawRect(fill.copy(alpha = if (settings.solidBarEnabled) 1f else 0.4f))
            if (optical && press > 0.001f) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.20f * press.coerceIn(0f, 1f)), Color.Transparent),
                center = touch, radius = 140.dp.toPx()))
            if (optical && settings.outlineEnabled) {
                val light = (dynamicHighlight.style as top.yukonga.miuix.kmp.blur.highlight.BloomStroke).primaryLight.position
                searchEdge.draw(this, shape, dark, (.22f + .18f * press) * 2f * settings.glass.darkBarHighlightStrength, light.x, light.y)
            }
        },
    )) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = MiuixTheme.colorScheme.onBackground),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("搜索应用或包名", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    inner()
                },
            )
        }
    }
}

@Composable
private fun MiuixEffects(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.glass
    MiuixList(padding) {
        item { Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "HyperOS 原生模糊",
                    summary = "使用系统提供的原生模糊路径。",
                    startAction = { Icon(Icons.Rounded.BlurOn, null, tint = MiuixTheme.colorScheme.onBackground) },
                    checked = config.nativeBlur,
                    onCheckedChange = { actions.setGlassConfig(config.copy(nativeBlur = it)) },
                )
                SwitchPreference(
                    title = "背景取景玻璃",
                    summary = "本地捕获、模糊、折射和色散，不进行联网。",
                    startAction = { Icon(Icons.Rounded.Tune, null, tint = MiuixTheme.colorScheme.onBackground) },
                    checked = config.backdropCapture,
                    onCheckedChange = { actions.setGlassConfig(config.copy(backdropCapture = it)) },
                )
                SwitchPreference(
                    title = "选中项强调色",
                    summary = "使用应用强调色显示当前栏目。",
                    startAction = { Icon(Icons.Rounded.Speed, null, tint = MiuixTheme.colorScheme.onBackground) },
                    checked = config.selectedAccent,
                    onCheckedChange = { actions.setGlassConfig(config.copy(selectedAccent = it)) },
                )
                OverlayDropdownPreference(
                    title = managerText("底栏明暗模式"),
                    summary = managerText("跟随系统、浅色或深色。"),
                    items = listOf(managerText("跟随系统"), managerText("浅色"), managerText("深色")),
                    selectedIndex = config.themeMode,
                    onSelectedIndexChange = { actions.setGlassConfig(config.copy(themeMode = it.coerceIn(0, 2))) },
                )
                SwitchPreference(
                    title = "仅显示图标",
                    summary = "隐藏文字标签。",
                    checked = config.iconOnly,
                    onCheckedChange = { actions.setGlassConfig(config.copy(iconOnly = it)) },
                )
        } }
        item {
            ManagerSliderGroup {
                MiuixEffectSlider("单个栏目宽度", config.tabWidth, 64f..92f, "${config.tabWidth.toInt()} dp") {
                    actions.setGlassConfig(config.copy(tabWidth = it))
                }
                MiuixEffectSlider("底部间距", config.bottomPadding, 4f..28f, "${config.bottomPadding.toInt()} dp") {
                    actions.setGlassConfig(config.copy(bottomPadding = it))
                }
                MiuixEffectSlider("模糊半径", config.blurRadius, 0f..16f, "${"%.1f".format(config.blurRadius)}") {
                    actions.setGlassConfig(config.copy(blurRadius = it))
                }
                MiuixEffectSlider("底栏高度", config.barHeight, GlassConfig.MIN_BAR_HEIGHT..GlassConfig.MAX_BAR_HEIGHT, "${config.barHeight.toInt()} dp") {
                    actions.setGlassConfig(config.copy(barHeight = it))
                }
                MiuixEffectSlider("图标比例", config.iconScale, 0.80f..1.20f, "${(config.iconScale * 100).toInt()}%") {
                    actions.setGlassConfig(config.copy(iconScale = it))
                }
                MiuixEffectSlider("文字大小", config.textSize, 9f..14f, "${"%.1f".format(config.textSize)} sp") {
                    actions.setGlassConfig(config.copy(textSize = it))
                }
                ArrowPreference(
                    title = "生效方式",
                    summary = "保存后关闭并重新打开目标应用，无需重启手机。",
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun MiuixEffectSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String = "",
    hapticTriggerValue: Float? = null,
    snapToTrigger: Boolean = true,
    valueSuffix: String = "",
    detents: List<SliderDetent> = emptyList(),
    onPreview: (Float) -> Unit = {},
    valueFormatter: ((Float) -> String)? = null,
    onChange: (Float) -> Unit,
) {
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var pendingCommit by remember { mutableStateOf<Float?>(null) }
    var lastCommittedValue by remember { mutableFloatStateOf(value) }
    val lastPrev = remember { mutableFloatStateOf(value) }
    val detentController = remember(detents, range, value) { SliderDetentController(detents, range, value) }
    LaunchedEffect(value) { localValue = value }
    // Keep drag frames local and cheap. Commit once on release; the inactivity
    // fallback covers MIUIX versions that occasionally omit the finish callback.
    LaunchedEffect(pendingCommit) {
        val candidate = pendingCommit ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (pendingCommit == candidate && candidate != lastCommittedValue) {
            lastCommittedValue = candidate
            pendingCommit = null
            onChange(candidate)
        }
    }
    val view = LocalView.current
    ManagerSliderFrame {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                val localizedTitle = managerText(title)
                ManagerCardTitle(localizedTitle.substringBefore('\n'))
                if ('\n' in localizedTitle) ManagerCardDescription(localizedTitle.substringAfter('\n'))
            }
            Text(
                valueFormatter?.invoke(localValue) ?: if (valueSuffix.isEmpty()) "${"%.1f".format(localValue)}" else "${localValue.toInt()}$valueSuffix",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = localValue,
            onValueChange = { newValue ->
                if (detents.isNotEmpty()) {
                    val adjusted = detentController.move(newValue)
                    localValue = adjusted.value
                    if (adjusted.haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                } else if (hapticTriggerValue != null) {
                    val crossed = (lastPrev.floatValue < hapticTriggerValue && newValue >= hapticTriggerValue) ||
                        (lastPrev.floatValue > hapticTriggerValue && newValue <= hapticTriggerValue)
                    if (crossed) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    }
                    // Snap to default if within 0.35 units
                    if (snapToTrigger && kotlin.math.abs(newValue - hapticTriggerValue) <= 0.35f && newValue != hapticTriggerValue) {
                        localValue = hapticTriggerValue
                    } else {
                        localValue = newValue
                    }
                } else {
                    localValue = newValue
                }
                pendingCommit = localValue
                onPreview(localValue)
                lastPrev.floatValue = newValue
            },
            onValueChangeFinished = {
                pendingCommit = null
                if (localValue != lastCommittedValue) {
                    lastCommittedValue = localValue
                    onChange(localValue)
                }
            },
            valueRange = range,
            showKeyPoints = false,
            detents = detents,
        )
        SliderDetentLabels(detents, range, if (LocalIosStyle.current) 20f else 10f)
    }
    }
}

@Composable
private fun MiuixSettings(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    MiuixList(padding) {
        item {
            Card(Modifier.fillMaxWidth(), allowTilt = false) {
                SwitchPreference(
                    title = "纯色模式",
                    summary = "使用不透明纯色底栏；开启时暂时关闭液态玻璃。",
                    checked = settings.solidBarEnabled,
                    onCheckedChange = actions.setSolidBarEnabled,
                )
                SwitchPreference(
                    title = "液态玻璃",
                    summary = if (settings.liquidGlassEnabled) {
                        "底栏与滑块启用液态玻璃；模块控件使用 iOS 样式。"
                    } else {
                        "关闭折射与按压放大，底栏保留模糊；模块控件使用 MIUIX 样式。"
                    },
                    checked = settings.liquidGlassEnabled,
                    onCheckedChange = actions.setLiquidGlassEnabled,
                )
                SwitchPreference(
                    title = "磨砂玻璃",
                    summary = "仅使用模糊，不使用液态折射或不透明底色。",
                    checked = settings.frostedGlassEnabled,
                    onCheckedChange = actions.setFrostedGlassEnabled,
                )
                SwitchPreference(
                    title = "姿态动态效果",
                    summary = "随握持角度改变液态折射和阴影；关闭后使用静态效果。",
                    checked = settings.glass.postureEffectsEnabled,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(postureEffectsEnabled = it)) },
                )
                SwitchPreference(
                    title = "简化切页动画",
                    summary = "开启时滑块保持原尺寸平移并保留折射；关闭后恢复放大。冷切页等待不变。",
                    checked = settings.glass.simplifyTabSwitch,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(simplifyTabSwitch = it)) },
                )
                SwitchPreference(
                    title = "滑块照亮效果",
                    summary = "关闭后取消底栏滑块交互时的照亮和阴影。",
                    checked = settings.glass.indicatorInteractionLightEnabled,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(indicatorInteractionLightEnabled = it)) },
                )
                SwitchPreference(
                    title = "描边",
                    summary = "控制纯模糊/纯色底栏的反色描边，以及液态底栏的高光描边。",
                    checked = settings.outlineEnabled,
                    onCheckedChange = actions.setOutlineEnabled,
                )
                SwitchPreference(
                    title = "底栏无字模式",
                    summary = "有图标和文字的底栏只显示图标，并垂直居中。",
                    checked = settings.glass.bottomBarIconOnly,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(bottomBarIconOnly = it)) },
                )
                SwitchPreference(
                    title = "使用经典导航键",
                    summary = "开启后，底栏与屏幕底部的间距增至默认值的两倍。",
                    checked = settings.glass.classicNavigation,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(classicNavigation = it)) },
                )
                SwitchPreference(
                    title = "动态配色",
                    summary = "从系统壁纸提取颜色。",
                    checked = settings.dynamicColor,
                    onCheckedChange = actions.setDynamicColor,
                )
            }
        }
        // Hide only the posture controls; preserve their saved values and stable item keys.
        val firstSliderIndex = if (settings.glass.postureEffectsEnabled) 0 else 3
        val sliderIndices = (firstSliderIndex..6).filterNot { settings.solidBarEnabled && (it == 3 || it == 4 || it == 6) } +
            (if (settings.liquidGlassEnabled && !settings.solidBarEnabled) listOf(8, 7) else emptyList())
        if (settings.iosStyle) {
            // Separate cards must be separate lazy items, not one eager column.
            items(sliderIndices.size, key = { "settings-slider-${sliderIndices[it]}" }, contentType = { "settings-slider" }) { index ->
                MiuixSettingsSlider(sliderIndices[index], settings, actions)
            }
        } else {
            item {
                ManagerSliderGroup {
                    sliderIndices.forEach { index ->
                        androidx.compose.runtime.key(index) {
                            MiuixSettingsSlider(index, settings, actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixSettingsSlider(index: Int, settings: ManagerSettings, actions: UiActions) {
    when (index) {
        0 -> MiuixEffectSlider(
                    title = "粘滞感\n低值轻快回荡，高值缓慢流动 · 默认 25%",
                    value = settings.glass.liquidViscosity,
                    range = 0f..100f,
                    valueSuffix = "%",
                    detents = viscosityDetents,
                    onChange = { actions.setGlassConfig(settings.glass.copy(liquidViscosity = it)) },
                )
        1 -> MiuixEffectSlider(
                    title = "左右流动强度\n0% 关闭左右方向 · 100% 当前效果",
                    value = settings.glass.flowHorizontalStrength,
                    range = 0f..100f, valueSuffix = "%", hapticTriggerValue = 0f,
                    onChange = { actions.setGlassConfig(settings.glass.copy(flowHorizontalStrength = it)) },
                )
        2 -> MiuixEffectSlider(
                    title = "上下流动强度\n0% 关闭上下方向 · 100% 当前效果",
                    value = settings.glass.flowVerticalStrength,
                    range = 0f..100f, valueSuffix = "%", hapticTriggerValue = 0f,
                    onChange = { actions.setGlassConfig(settings.glass.copy(flowVerticalStrength = it)) },
                )
        3 -> MiuixEffectSlider(
                    title = "底色透明度\n深浅色统一 · 100% 为完全透明",
                    value = (1f - settings.glass.lightAlpha) * 100f,
                    range = 20f..100f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_BAR_TRANSPARENCY_PERCENT,
                    onChange = { actions.setGlassConfig(settings.glass.copy(
                        lightAlpha = 1f - it / 100f, darkAlpha = 1f - it / 100f)) },
                )
        4 -> MiuixEffectSlider(
                    title = "模糊半径\n默认 0.0",
                    value = settings.glass.blurRadius,
                    range = 0f..16f,
                    hapticTriggerValue = GlassConfig.DEFAULT_BLUR_RADIUS,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(blurRadius = it))
                    },
                )
        5 -> MiuixEffectSlider(
                    title = "底栏高度\n50–64 dp",
                    value = settings.glass.barHeight,
                    range = GlassConfig.MIN_BAR_HEIGHT..GlassConfig.MAX_BAR_HEIGHT,
                    hapticTriggerValue = GlassConfig.DEFAULT_BAR_HEIGHT,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(barHeight = it))
                    },
                )
        8 -> MiuixEffectSlider(
            title = "高光透明度\n深浅色同时生效 · 默认 45% · 100% 隐藏高光",
            value = settings.glass.highlightTransparency * 100f,
            range = 0f..100f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT,
            onChange = { actions.setGlassConfig(settings.glass.copy(highlightTransparency = it / 100f)) },
        )
        7 -> MiuixEffectSlider(
            title = "折射强度\n默认 120% · 100% 原有强度 · 最高 200% · 不影响桌面",
            value = settings.glass.refractionStrength,
            range = 0f..200f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_REFRACTION_STRENGTH,
            onChange = { actions.setGlassConfig(settings.glass.copy(refractionStrength = it)) },
        )
        6 -> MiuixEffectSlider(
                    title = "高光描边强度\n适用于深浅色液态玻璃控件 · 默认 60%",
                    value = settings.glass.darkBarHighlightStrength * 100f,
                    range = 0f..170f,
                    hapticTriggerValue = GlassConfig.DEFAULT_HIGHLIGHT_STRENGTH_PERCENT,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(darkBarHighlightStrength = it / 100f))
                    },
                )
    }
}


@Composable
private fun MiuixList(
    padding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}


