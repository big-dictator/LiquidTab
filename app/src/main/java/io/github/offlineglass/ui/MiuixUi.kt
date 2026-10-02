package io.github.offlineglass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.targets.TargetSpec
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
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
        ?.let { key -> AppCatalog.targets.firstOrNull { it.key == key } }
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
                    selectedPage == ManagerPage.APPS -> entryTarget?.displayName ?: selectedPage.title
                    else -> selectedPage.title
                },
                onBack = when {
                    selectedPage == ManagerPage.HOME && homeSubpage != ManagerHomeSubpage.NONE -> ({ homeSubpageName = ManagerHomeSubpage.NONE.name })
                    selectedPage == ManagerPage.APPS && entryTarget != null -> ({ entryTargetKey = null })
                    else -> null
                },
            )
        },
        bottomBar = {
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
                outlineEnabled = settings.outlineEnabled,
                blurRadius = previewGlass.blurRadius,
                cornerRadiusPercent = previewGlass.cornerRadiusPercent,
                cornerSmoothing = previewGlass.cornerSmoothing,
                barHeight = previewGlass.barHeight,
                darkBarHighlightStrength = previewGlass.darkBarHighlightStrength,
                lightAlpha = previewGlass.lightAlpha,
                darkAlpha = previewGlass.darkAlpha,
                classicNavigation = previewGlass.classicNavigation,
            )
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
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        renderEffect = topBlurEffect
                    },
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
                            MiuixHomeSubpage(route, innerPadding)
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
    val installedTargets = AppCatalog.targets.filter { it.packageName in settings.installedPackages }
    val enabledCount = installedTargets.count { settings.appEnabled[it.packageName] != false }
    MiuixList(padding) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = if (activeRecently) MiuixTheme.colorScheme.primaryContainer
                    else MiuixTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        if (activeRecently) Icons.Rounded.CheckCircle else Icons.Rounded.OfflineBolt,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (activeRecently) "模块正在工作" else "模块已就绪",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (activeRecently) "最近作用于 ${settings.lastActivePackage}"
                            else "在 LSPosed 中启用作用域后生效",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiuixMetricCard("适配应用", installedTargets.size.toString(), Modifier.weight(1f))
                MiuixMetricCard("已启用", enabledCount.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "请作者喝杯咖啡",
                    summary = "如果 LiquidTab 对你有帮助，欢迎请作者喝杯咖啡",
                    startAction = { ManagerHomeLeadingIcon(Icons.Filled.Coffee) },
                    onClick = { onOpenSubpage(ManagerHomeSubpage.DONATION) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "致谢",
                    summary = "查看本模块使用的开源项目与依赖",
                    startAction = { ManagerHomeLeadingIcon(Icons.Filled.LocalFlorist) },
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
                    ManagerHomeLeadingIcon(Icons.Outlined.Info)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LiquidTab 2.0", style = MiuixTheme.textStyles.body1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "LSPosed API 82+ · Android 12+",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ManagerHomeLeadingIcon(Icons.Rounded.CloudOff)
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
private fun MiuixHomeSubpage(page: ManagerHomeSubpage, padding: PaddingValues) {
    val uriHandler = LocalUriHandler.current
    MiuixList(padding) {
        when (page) {
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
                item {
                    Card(Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "跳转作者酷安主页",
                            summary = "在酷安查看作者主页与项目动态",
                            onClick = { uriHandler.openUri(AUTHOR_COOLAPK_URL) },
                        )
                    }
                }
            }
            ManagerHomeSubpage.ACKNOWLEDGEMENTS -> {
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
                item { MiuixAcknowledgementCard("LSPosed HiddenApiBypass", "隐藏 API 访问兼容层（6.1）") }
                item { MiuixAcknowledgementCard("LSPosed API", "模块接口由项目内的编译期桩提供，运行时由 LSPosed 环境承载") }
            }
            ManagerHomeSubpage.NONE -> Unit
        }
    }
}

@Composable
private fun MiuixAcknowledgementCard(title: String, summary: String) {
    Card(Modifier.fillMaxWidth()) {
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
    val backStack = remember { mutableStateListOf<NavKey>(ManagerAppRoute.List) }

    LaunchedEffect(entryTargetKey) {
        when {
            entryTargetKey == null && backStack.lastOrNull() !is ManagerAppRoute.List -> {
                backStack.clear()
                backStack.add(ManagerAppRoute.List)
            }
            entryTargetKey != null &&
                (backStack.lastOrNull() as? ManagerAppRoute.Detail)?.targetKey != entryTargetKey -> {
                backStack.add(ManagerAppRoute.Detail(entryTargetKey))
            }
        }
    }

    // App-list details use a normal pop transition; predictive-back preview is disabled here.
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .clipToBounds(),
    ) {
        NavDisplay(
            backStack = backStack,
            popTransitionSpec = {
                (slideInHorizontally { -it } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            onBack = {
                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                onEntryTargetChange(null)
            },
            entryProvider = entryProvider {
                entry<ManagerAppRoute.List> {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        MiuixAppList(settings, padding, actions, query, { query = it }) { key ->
                            if (key != null) {
                                backStack.add(ManagerAppRoute.Detail(key))
                                onEntryTargetChange(key)
                            }
                        }
                    }
                }
                entry<ManagerAppRoute.Detail> { route ->
                    val target = AppCatalog.targets.firstOrNull { it.key == route.targetKey }
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        if (target != null) MiuixAppOverride(target, settings, padding, actions)
                    }
                }
            },
        )
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
        AppCatalog.targets.filter {
            it.packageName in settings.installedPackages &&
                (query.isBlank() || it.displayName.contains(query, true) || it.packageName.contains(query, true))
        }.sortedBy { settings.appEnabled[it.packageName] == false }
    }
    MiuixList(padding) {
        item { MiuixSearchBox(query, onQueryChange) }
        items(targets, key = { it.packageName }) { target ->
            Card(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = target.displayName,
                        summary = "${target.packageName} · 底栏入口设置",
                        startAction = { AppIcon(target.packageName, target.displayName, Modifier.padding(end = 8.dp)) },
                        endActions = {
                            top.yukonga.miuix.kmp.basic.Switch(
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

@Composable
private fun MiuixAppEntries(
    target: TargetSpec,
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
) {
    val labels = entryLabels(target.key, settings)
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val enabledCount = labels.indices.count { config.hiddenMask and (1 shl it) == 0 }
    MiuixList(padding) {
        items(labels.indices.toList()) { index ->
            val enabled = config.hiddenMask and (1 shl index) == 0
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = labels[index],
                    summary = if (enabled) "显示图标、文字和触控入口" else "入口已关闭",
                    checked = enabled,
                    enabled = !enabled || enabledCount > 1,
                    onCheckedChange = { checked ->
                        val mask = if (checked) {
                            config.hiddenMask and (1 shl index).inv()
                        } else {
                            config.hiddenMask or (1 shl index)
                        }
                        actions.setAppConfig(target.packageName, config.copy(hiddenMask = mask))
                    },
                )
            }
        }
    }
}

@Composable
private fun MiuixAppOverride(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val labels = entryLabels(target.key, settings)
    val enabledCount = labels.indices.count { config.hiddenMask and (1 shl it) == 0 }
    MiuixList(padding) {
        if (target.key == "mi_market" || target.key == "meituan_main") {
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
        } else if (labels.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    labels.indices.forEach { index ->
                    val enabled = config.hiddenMask and (1 shl index) == 0
                    SwitchPreference(
                        title = labels[index],
                        summary = if (enabled) "显示图标、文字和触控入口" else "入口已关闭",
                        checked = enabled,
                        enabled = !enabled || enabledCount > 1,
                        onCheckedChange = { checked ->
                            val mask = if (checked) config.hiddenMask and (1 shl index).inv()
                            else config.hiddenMask or (1 shl index)
                            actions.setAppConfig(target.packageName, config.copy(hiddenMask = mask))
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
private fun MiuixSearchBox(value: String, onChange: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
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
            SwitchPreference(title = "使用经典导航键", summary = "开启后底栏与屏幕底部的默认边距加倍", checked = config.classicNavigation,
                onCheckedChange = { actions.setGlassConfig(config.copy(classicNavigation = it)) })
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
                    title = "底栏明暗模式",
                    summary = "跟随系统、浅色或深色。",
                    items = listOf("跟随系统", "浅色", "深色"),
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
            Card(Modifier.fillMaxWidth()) {
                MiuixEffectSlider("单个栏目宽度", config.tabWidth, 64f..92f, "${config.tabWidth.toInt()} dp") {
                    actions.setGlassConfig(config.copy(tabWidth = it))
                }
                MiuixEffectSlider("底部间距", config.bottomPadding, 4f..28f, "${config.bottomPadding.toInt()} dp") {
                    actions.setGlassConfig(config.copy(bottomPadding = it))
                }
                MiuixEffectSlider("模糊半径", config.blurRadius, 0f..16f, "${"%.1f".format(config.blurRadius)}") {
                    actions.setGlassConfig(config.copy(blurRadius = it))
                }
                MiuixEffectSlider("底栏高度", config.barHeight, 56f..64f, "${config.barHeight.toInt()} dp") {
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
    onPreview: (Float) -> Unit = {},
    onChange: (Float) -> Unit,
) {
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var pendingCommit by remember { mutableStateOf<Float?>(null) }
    var lastCommittedValue by remember { mutableFloatStateOf(value) }
    val lastPrev = remember { mutableFloatStateOf(value) }
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
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                if (valueSuffix.isEmpty()) "${"%.1f".format(localValue)}" else "${localValue.toInt()}$valueSuffix",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = localValue,
            onValueChange = { newValue ->
                if (hapticTriggerValue != null) {
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
        )
    }
}

@Composable
private fun MiuixSettings(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    MiuixList(padding) {
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(title = "使用经典导航键", summary = "开启后底栏与屏幕底部的默认边距加倍", checked = settings.glass.classicNavigation,
                    onCheckedChange = { actions.setGlassConfig(settings.glass.copy(classicNavigation = it)) })
                SwitchPreference(
                    title = "纯色底栏",
                    summary = "使用不透明纯色底栏；开启时暂时关闭液态玻璃。",
                    checked = settings.solidBarEnabled,
                    onCheckedChange = actions.setSolidBarEnabled,
                )
                SwitchPreference(
                    title = "液态玻璃",
                    summary = if (settings.liquidGlassEnabled) {
                        "底栏与滑块启用液态折射和按压放大效果。"
                    } else {
                        "已关闭折射与按压放大，底栏仍保留模糊效果。"
                    },
                    checked = settings.liquidGlassEnabled,
                    onCheckedChange = { if (it) { actions.setSolidBarEnabled(false); actions.setLiquidGlassEnabled(true) } else { actions.setLiquidGlassEnabled(false) } },
                )
                SwitchPreference(
                    title = "描边",
                    summary = "控制纯模糊/纯色底栏的反色描边，以及液态底栏的高光描边。",
                    checked = settings.outlineEnabled,
                    onCheckedChange = actions.setOutlineEnabled,
                )
                SwitchPreference(
                    title = "动态配色",
                    summary = "从系统壁纸提取颜色。",
                    startAction = { Icon(Icons.Rounded.Palette, null, tint = MiuixTheme.colorScheme.onBackground) },
                    checked = settings.dynamicColor,
                    onCheckedChange = actions.setDynamicColor,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                MiuixEffectSlider(
                    title = "底栏模糊半径\n默认 2.0",
                    value = settings.glass.blurRadius,
                    range = 0f..16f,
                    hapticTriggerValue = 2f,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(blurRadius = it))
                    },
                )
                MiuixEffectSlider(
                    title = "底栏高度\n56–64 dp",
                    value = settings.glass.barHeight,
                    range = 56f..64f,
                    hapticTriggerValue = 56f,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(barHeight = it))
                    },
                )
                
                
                
                MiuixEffectSlider(
                    title = "深色模式高光描边强度\n仅调整底栏外框 · 默认 50%",
                    value = settings.glass.darkBarHighlightStrength * 100f,
                    range = 50f..170f,
                    hapticTriggerValue = 50f,
                    onChange = {
                        actions.setGlassConfig(settings.glass.copy(darkBarHighlightStrength = it / 100f))
                    },
                )
            }
        }
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
