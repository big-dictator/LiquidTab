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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.targets.TargetSpec
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import android.graphics.Color
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialManager(
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
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val glassBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                backdrop = glassBackdrop,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        label = "manager-home-subpage",
                    ) { route ->
                        if (route == ManagerHomeSubpage.NONE) {
                            MaterialHome(settings, innerPadding, actions) { homeSubpageName = it.name }
                        } else {
                            MaterialHomeSubpage(route, innerPadding)
                        }
                    }
                    ManagerPage.APPS -> MaterialApps(
                        settings = settings,
                        padding = innerPadding,
                    actions = previewActions,
                        entryTargetKey = entryTargetKey,
                        onEntryTargetChange = { entryTargetKey = it },
                    )
                    ManagerPage.SETTINGS -> MaterialSettings(settings, innerPadding, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialHome(
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
    onOpenSubpage: (ManagerHomeSubpage) -> Unit,
) {
    val installedTargets = AppCatalog.targets.filter { it.packageName in settings.installedPackages }
    val enabledCount = installedTargets.count { settings.appEnabled[it.packageName] != false }
    val activeRecently = System.currentTimeMillis() - settings.lastActiveTime < 10 * 60_000L
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (activeRecently) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        if (activeRecently) Icons.Filled.CheckCircle else Icons.Filled.OfflineBolt,
                        null,
                        modifier = Modifier.size(44.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (activeRecently) "模块正在工作" else "模块已就绪",
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                        )
                        Text(
                            if (activeRecently) "最近作用于 ${settings.lastActivePackage}"
                            else "在 LSPosed 中启用作用域后生效",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("适配应用", installedTargets.size.toString(), Modifier.weight(1f))
                MetricCard("已启用", enabledCount.toString(), Modifier.weight(1f))
            }
        }
        if (settings.lastActiveTime > 0L) {
            item {
                Text(
                    "最近活动：${DateFormat.getDateTimeInstance().format(Date(settings.lastActiveTime))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Card(
                onClick = { onOpenSubpage(ManagerHomeSubpage.DONATION) },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                ListItem(
                    headlineContent = { Text("请作者喝杯咖啡") },
                    supportingContent = { Text("如果 LiquidTab 对你有帮助，欢迎请作者喝杯咖啡") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.Coffee) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                )
            }
        }
        item {
            Card(
                onClick = { onOpenSubpage(ManagerHomeSubpage.ACKNOWLEDGEMENTS) },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                ListItem(
                    headlineContent = { Text("致谢") },
                    supportingContent = { Text("查看本模块使用的开源项目与依赖") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.LocalFlorist) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                )
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text("LiquidTab 2.0") },
                    supportingContent = { Text("LSPosed API 82+ · Android 12+") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Outlined.Info) },
                )
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text("离线与隐私") },
                    supportingContent = { Text("模块没有网络权限；所有设置、状态和渲染数据都留在设备本地。") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.CloudOff) },
                )
            }
        }
    }
}

@Composable
private fun MaterialHomeSubpage(page: ManagerHomeSubpage, padding: PaddingValues) {
    when (page) {
        ManagerHomeSubpage.DONATION -> {
            val uriHandler = LocalUriHandler.current
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { DonationQrImage() }
                item {
                    Card(onClick = { uriHandler.openUri(AUTHOR_COOLAPK_URL) }, shape = MaterialTheme.shapes.extraLarge) {
                        ListItem(
                            headlineContent = { Text("跳转作者酷安主页") },
                            supportingContent = { Text("在酷安查看作者主页与项目动态") },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                        )
                    }
                }
            }
        }
        ManagerHomeSubpage.ACKNOWLEDGEMENTS -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("感谢以下开源项目为 LiquidTab 提供基础能力。", modifier = Modifier.padding(horizontal = 8.dp)) }
            item { AcknowledgementCard("AndroidX Jetpack Compose", "BOM 2026.06.01；Activity Compose 1.13.0、Animation、Foundation、Material Icons Extended、Material 3 1.5.0-alpha24、UI 与 Tooling Preview") }
            item { AcknowledgementCard("AndroidX Navigation", "Navigation 3 Runtime 1.1.4 与 NavigationEvent Compose 1.1.2") }
            item { AcknowledgementCard("AndroidX Graphics Shapes", "连续曲线与几何形状支持（1.1.0）") }
            item { AcknowledgementCard("Miuix KMP", "UI、图标、Preference、Blur、Squircle 与 Navigation 3 UI（均为 0.9.3）") }
            item { AcknowledgementCard("LSPosed HiddenApiBypass", "隐藏 API 访问兼容层（6.1）") }
            item { AcknowledgementCard("LSPosed API", "模块接口由项目内的编译期桩提供，运行时由 LSPosed 环境承载") }
        }
        ManagerHomeSubpage.NONE -> Unit
    }
}

@Composable
private fun AcknowledgementCard(title: String, summary: String) {
    Card(shape = MaterialTheme.shapes.extraLarge) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp)) {
            Text(value, style = MaterialTheme.typography.displaySmallEmphasized)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MaterialApps(
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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
                        MaterialAppList(settings, padding, actions, query, { query = it }) { key ->
                            if (key != null) {
                                backStack.add(ManagerAppRoute.Detail(key))
                                onEntryTargetChange(key)
                            }
                        }
                    }
                }
                entry<ManagerAppRoute.Detail> { route ->
                    val target = AppCatalog.targets.firstOrNull { it.key == route.targetKey }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
                        if (target != null) MaterialAppOverride(target, settings, padding, actions)
                    }
                }
            },
        )
    }
}

@Composable
private fun MaterialAppList(
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("搜索应用或包名") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        items(targets, key = { it.packageName }) { target ->
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(target.packageName, target.displayName)
                        Column(
                            Modifier.weight(1f).then(
                                Modifier.clickable { onEntryTargetChange(target.key) },
                            ),
                        ) {
                            Text(target.displayName, style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(
                                target.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                            Icon(
                                Icons.Filled.ChevronRight,
                                "打开底栏入口设置",
                                modifier = Modifier.clickable { onEntryTargetChange(target.key) },
                            )
                        Switch(
                            checked = settings.appEnabled[target.packageName] != false,
                            onCheckedChange = { actions.setAppEnabled(target.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialAppEntries(
    target: TargetSpec,
    settings: ManagerSettings,
    padding: PaddingValues,
    actions: UiActions,
) {
    val labels = entryLabels(target.key, settings)
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val enabledCount = labels.indices.count { config.hiddenMask and (1 shl it) == 0 }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "关闭后将同时移除该入口的图标、说明文字和触控按钮；重新打开应用后生效。",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(labels.indices.toList()) { index ->
            val enabled = config.hiddenMask and (1 shl index) == 0
            Card(shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text(labels[index]) },
                    supportingContent = { Text(if (enabled) "显示在底栏" else "已关闭入口") },
                    trailingContent = {
                        Switch(
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
                    },
                )
            }
        }
    }
}

@Composable
private fun MaterialAppOverride(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val labels = entryLabels(target.key, settings)
    val enabledCount = labels.indices.count { config.hiddenMask and (1 shl it) == 0 }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (target.key == "mi_market" || target.key == "meituan_main") {
            item { Card(shape = MaterialTheme.shapes.extraLarge) {
                Text(if (target.key == "mi_market") "请在应用商店设置中关闭视频广告页面" else "请在美团设置中关闭视频广告页面", modifier = Modifier.padding(20.dp))
            } }
        } else if (target.key == "wechat") {
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    Text("为了保障正常功能使用，不支持关闭页面", modifier = Modifier.padding(20.dp))
                }
            }
        } else if (target.key == "zhihu" && labels.isEmpty()) {
            item {
                Text("请先打开知乎主页，读取实际底栏后再设置入口开关。", modifier = Modifier.padding(20.dp))
            }
        } else if (labels.isNotEmpty()) {
            item { Text("底栏入口", style = MaterialTheme.typography.titleLargeEmphasized) }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    labels.indices.forEach { index ->
                    val enabled = config.hiddenMask and (1 shl index) == 0
                    ListItem(
                        headlineContent = { Text(labels[index]) },
                        supportingContent = { Text(if (enabled) "显示图标、文字和触控入口" else "入口已关闭") },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                enabled = !enabled || enabledCount > 1,
                                onCheckedChange = { checked ->
                                    val mask = if (checked) config.hiddenMask and (1 shl index).inv()
                                    else config.hiddenMask or (1 shl index)
                                    actions.setAppConfig(target.packageName, config.copy(hiddenMask = mask))
                                },
                            )
                        },
                    )
                    }
                }
            }
        }
        }
}



@Composable
private fun MaterialInlineSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MaterialColorSliders(color: Int, onColor: (Int) -> Unit) {
    val red = Color.red(color).toFloat()
    val green = Color.green(color).toFloat()
    val blue = Color.blue(color).toFloat()
    MaterialEffectSlider("红色", red, 0f..255f, red.toInt().toString()) { onColor(Color.rgb(it.toInt(), green.toInt(), blue.toInt())) }
    MaterialEffectSlider("绿色", green, 0f..255f, green.toInt().toString()) { onColor(Color.rgb(red.toInt(), it.toInt(), blue.toInt())) }
    MaterialEffectSlider("蓝色", blue, 0f..255f, blue.toInt().toString()) { onColor(Color.rgb(red.toInt(), green.toInt(), it.toInt())) }
}

@Composable
private fun MaterialEffects(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.glass
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    MaterialSwitchRow("使用经典导航键", "开启后底栏与屏幕底部的默认边距加倍", config.classicNavigation) {
                        actions.setGlassConfig(config.copy(classicNavigation = it))
                    }
                    MaterialSwitchRow("选中项强调色", "使用应用强调色显示当前选中的栏目。", config.selectedAccent) {
                        actions.setGlassConfig(config.copy(selectedAccent = it))
                    }
                    MaterialSwitchRow("仅显示图标", "隐藏文字标签。", config.iconOnly) {
                        actions.setGlassConfig(config.copy(iconOnly = it))
                    }
                }
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(18.dp)) {
                    Text("通用底栏模糊", style = MaterialTheme.typography.titleMedium)
                    Text("使用 Android 的背景取景与 RenderEffect，不依赖小米专有模糊接口。")
                }
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("底栏明暗模式", style = MaterialTheme.typography.titleLargeEmphasized)
                    val modes = listOf(GlassConfig.THEME_SYSTEM to "跟随系统", GlassConfig.THEME_LIGHT to "浅色", GlassConfig.THEME_DARK to "深色")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                selected = config.themeMode == mode,
                                onClick = { actions.setGlassConfig(config.copy(themeMode = mode)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    MaterialEffectSlider("单个栏目宽度", config.tabWidth, 64f..92f, "${config.tabWidth.toInt()} dp") {
                        actions.setGlassConfig(config.copy(tabWidth = it))
                    }
                    MaterialEffectSlider("底部间距", config.bottomPadding, 4f..28f, "${config.bottomPadding.toInt()} dp") {
                        actions.setGlassConfig(config.copy(bottomPadding = it))
                    }
                    MaterialEffectSlider("模糊半径", config.blurRadius, 0f..16f, "${"%.1f".format(config.blurRadius)}") {
                        actions.setGlassConfig(config.copy(blurRadius = it))
                    }
                    MaterialEffectSlider("底栏高度", config.barHeight, 56f..64f, "${config.barHeight.toInt()} dp") {
                        actions.setGlassConfig(config.copy(barHeight = it))
                    }
                    MaterialEffectSlider("图标比例", config.iconScale, 0.80f..1.20f, "${(config.iconScale * 100).toInt()}%") {
                        actions.setGlassConfig(config.copy(iconScale = it))
                    }
                    MaterialEffectSlider("文字大小", config.textSize, 9f..14f, "${"%.1f".format(config.textSize)} sp") {
                        actions.setGlassConfig(config.copy(textSize = it))
                    }
                }
            }
        }
        item {
            Text(
                "保存后关闭并重新打开目标应用即可读取新配置，无需重启手机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun MaterialSwitchCard(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(shape = MaterialTheme.shapes.extraLarge) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            trailingContent = { Switch(checked, onChecked) },
        )
    }
}

@Composable
private fun MaterialSwitchRow(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked, onChecked) },
    )
}

@Composable
private fun MaterialEffectSlider(
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
    val view = LocalView.current
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var pendingCommit by remember { mutableStateOf<Float?>(null) }
    var lastCommittedValue by remember { mutableFloatStateOf(value) }
    val lastPrev = remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { localValue = value }
    // Dragging only changes this lightweight local value. Commit once after
    // release, with a short inactivity fallback for devices whose Slider
    // occasionally misses onValueChangeFinished.
    LaunchedEffect(pendingCommit) {
        val candidate = pendingCommit ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (pendingCommit == candidate && candidate != lastCommittedValue) {
            lastCommittedValue = candidate
            pendingCommit = null
            onChange(candidate)
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (valueSuffix.isEmpty()) "${"%.1f".format(localValue)}" else "${localValue.toInt()}$valueSuffix",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
        )
    }
}

@Composable
private fun MaterialSettings(settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    MaterialSwitchRow("使用经典导航键", "开启后底栏与屏幕底部的默认边距加倍", settings.glass.classicNavigation) {
                        actions.setGlassConfig(settings.glass.copy(classicNavigation = it))
                    }
                    MaterialSwitchRow(
                        title = "纯色底栏",
                        summary = "使用不透明纯色底栏；开启时暂时关闭液态玻璃。",
                        checked = settings.solidBarEnabled,
                        onChecked = actions.setSolidBarEnabled,
                    )
                    MaterialSwitchRow(
                        title = "液态玻璃",
                        summary = if (settings.liquidGlassEnabled) {
                            "底栏与滑块启用液态折射和按压放大效果。"
                        } else {
                            "已关闭折射与按压放大，底栏仍保留模糊效果。"
                        },
                        checked = settings.liquidGlassEnabled,
                        onChecked = { if (it) { actions.setSolidBarEnabled(false); actions.setLiquidGlassEnabled(true) } else { actions.setLiquidGlassEnabled(false) } },
                    )
                    MaterialSwitchRow(
                        title = "描边",
                        summary = "控制纯模糊/纯色底栏的反色描边，以及液态底栏的高光描边。",
                        checked = settings.outlineEnabled,
                        onChecked = actions.setOutlineEnabled,
                    )
                    MaterialSwitchRow("动态配色", "从系统壁纸提取颜色。", settings.dynamicColor, actions.setDynamicColor)
                }
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp)) {
                    MaterialEffectSlider(
                        title = "底栏模糊半径\n默认 2.0",
                        value = settings.glass.blurRadius,
                        range = 0f..16f,
                        hapticTriggerValue = 2f,
                        onChange = {
                            actions.setGlassConfig(settings.glass.copy(blurRadius = it))
                        },
                    )
                    MaterialEffectSlider(
                        title = "底栏高度\n56–64 dp",
                        value = settings.glass.barHeight,
                        range = 56f..64f,
                        hapticTriggerValue = 56f,
                        onChange = {
                            actions.setGlassConfig(settings.glass.copy(barHeight = it))
                        },
                    )
                    
                    
                    
                    MaterialEffectSlider(
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
}
