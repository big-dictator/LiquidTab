package io.github.offlineglass.ui

import io.github.offlineglass.ui.ManagerFeedbackMaterialSwitch as Switch

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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import io.github.offlineglass.ui.ManagerMaterialCard as Card
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
import io.github.offlineglass.ui.ManagerMaterialText as Text
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
import io.github.offlineglass.config.DouyinTopChannels
import io.github.offlineglass.config.TikTokTopChannels
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
        ?.let { key -> AppCatalog.uiTargets.firstOrNull { it.key == key.substringBefore('/') } }
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
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                backdrop = glassBackdrop,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        label = "manager-home-subpage",
                    ) { route ->
                        if (route == ManagerHomeSubpage.NONE) {
                            MaterialHome(settings, innerPadding, actions) { homeSubpageName = it.name }
                        } else {
                            MaterialHomeSubpage(route, innerPadding, settings, actions)
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
                    containerColor = managerStatusCardColor(),
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    // The 48dp status vector has a 4dp internal circle inset.
                    // Its visible edge starts at the other cards' 16dp line.
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        if (activeRecently) Icons.Filled.CheckCircle else Icons.Filled.OfflineBolt,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = ManagerStatusIconGreen,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LiquidTab ${io.github.offlineglass.BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+')}",
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                        )
                    }
                }
            }
        }
        item {
            Card(onClick = { onOpenSubpage(ManagerHomeSubpage.LANGUAGE) }, shape = MaterialTheme.shapes.extraLarge) {
                ListItem(headlineContent = { Text("语言/Language") },
                    supportingContent = { Text("选择模块界面与底栏的语言") },
                    leadingContent = { ManagerHomeLeadingIcon(androidx.compose.material.icons.Icons.Default.Language) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) })
            }
        }
        if (settings.lastActiveTime > 0L) {
            item {
                Text(
                    "最近活动：${DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, if (settings.language == io.github.offlineglass.config.ManagerLanguage.ENGLISH) java.util.Locale.ENGLISH else java.util.Locale.SIMPLIFIED_CHINESE).format(Date(settings.lastActiveTime))}",
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
                    headlineContent = { Text("赞助本项目") },
                    supportingContent = { Text("如果 LiquidTab 对你有帮助，欢迎赞助本项目") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.Coffee, tone = HomeIconTone.ORANGE) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                )
            }
        }
        item {
            Card(
                onClick = { onOpenSubpage(ManagerHomeSubpage.AUTHOR) },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                ListItem(
                    headlineContent = { Text("作者") },
                    supportingContent = { Text("了解作者与 LiquidTab 项目") },
                    leadingContent = { ManagerHomeLeadingIcon(androidx.compose.material.icons.Icons.Filled.Person, tone = HomeIconTone.PINK) },
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
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.LocalFlorist, tone = HomeIconTone.PINK) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
                )
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text("离线与隐私") },
                    supportingContent = { Text("模块没有网络权限；所有设置、状态和渲染数据都留在设备本地。") },
                    leadingContent = { ManagerHomeLeadingIcon(Icons.Filled.CloudOff, tone = HomeIconTone.TEAL) },
                )
            }
        }
    }
}

@Composable
private fun MaterialHomeSubpage(page: ManagerHomeSubpage, padding: PaddingValues, settings: ManagerSettings, actions: UiActions) {
    when (page) {
        ManagerHomeSubpage.AUTHOR -> AuthorInfoPage(padding)
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
            item { TopBarLayoutContributorCard() }
            item { Text("感谢以下开源项目为 LiquidTab 提供基础能力。", modifier = Modifier.padding(horizontal = 8.dp)) }
            item { AcknowledgementCard("AndroidX Jetpack Compose", "BOM 2026.06.01；Activity Compose 1.13.0、Animation、Foundation、Material Icons Extended、Material 3 1.5.0-alpha24、UI 与 Tooling Preview") }
            item { AcknowledgementCard("AndroidX Navigation", "Navigation 3 Runtime 1.1.4 与 NavigationEvent Compose 1.1.2") }
            item { AcknowledgementCard("AndroidX Graphics Shapes", "连续曲线与几何形状支持（1.1.0）") }
            item { AcknowledgementCard("Miuix KMP", "UI、图标、Preference、Blur、Squircle 与 Navigation 3 UI（均为 0.9.3）") }
            item { AcknowledgementCard("kyant0 / AndroidLiquidGlass", "感谢 Kyant 的开源液态玻璃实现。本模块参考 LiquidButton，并改编其折射着色器用于原生 View 控件渲染。许可证：Apache-2.0。点击查看开源项目。", "https://github.com/Kyant0/AndroidLiquidGlass") }
            item { AcknowledgementCard("LSPosed HiddenApiBypass", "隐藏 API 访问兼容层（6.1）") }
            item { AcknowledgementCard("LSPosed API", "模块接口由项目内的编译期桩提供，运行时由 LSPosed 环境承载") }
        }
        ManagerHomeSubpage.LANGUAGE -> ManagerLanguagePage(settings, padding, actions)
        ManagerHomeSubpage.NONE -> Unit
    }
}

@Composable
private fun AcknowledgementCard(title: String, summary: String, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = if (url != null) Modifier.clickable { uriHandler.openUri(url) } else Modifier,
        shape = MaterialTheme.shapes.extraLarge) {
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
    BackHandler(enabled = entryTargetKey != null) {
        onEntryTargetChange(entryTargetKey?.substringBefore('/')?.takeIf { entryTargetKey?.contains('/') == true })
    }
    // Ordinary transitions for both toolbar and system back. Navigation's
    // predictive scene previously consumed system pop with a None transition.
    AnimatedContent(
        targetState = entryTargetKey,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).clipToBounds(),
        transitionSpec = {
            val pushing = (targetState?.count { it == '/' } ?: -1) > (initialState?.count { it == '/' } ?: -1)
            (slideInHorizontally { if (pushing) it else -it } + fadeIn()) togetherWith
                (slideOutHorizontally { if (pushing) -it else it } + fadeOut())
        },
        label = "material-app-detail",
    ) { key ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            if (key == null) {
                MaterialAppList(settings, padding, actions, query, { query = it }, onEntryTargetChange)
            } else {
                val target = AppCatalog.uiTargets.firstOrNull { it.key == key.substringBefore('/') }
                if (target != null) {
                    if (key == DouyinTopChannels.SETTINGS_ROUTE) MaterialDouyinTopChannels(target, settings, padding, actions)
                    else if (key == TikTokTopChannels.ROUTE) MaterialTikTokTopChannels(target, settings, padding, actions)
                    else MaterialAppOverride(target, settings, padding, actions) {
                        onEntryTargetChange(if (target.key == "tiktok") TikTokTopChannels.ROUTE else DouyinTopChannels.SETTINGS_ROUTE)
                    }
                }
            }
        }
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
        AppCatalog.uiTargets.filter {
            (it.alwaysShowInManager || it.packageName in settings.installedPackages) &&
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
    val labels = if (target.key == "spotify") emptyList() else entryLabels(target.key, settings)
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val toggleIndices = labels.indices.filter { labels[it].isNotBlank() }
    val enabledCount = toggleIndices.count { it !in target.automaticEntryIndices && config.hiddenMask and (1 shl it) == 0 }
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
        items(toggleIndices) { index ->
            val enabled = config.hiddenMask and (1 shl index) == 0
            Card(shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text(labels[index]) },
                    supportingContent = { Text(if (target.key == "spotify" && index == 3 && !config.spotifyPremiumAvailable)
                        "Spotify 已根据帐号状态隐藏此入口。" else if (enabled) "显示在底栏" else "已关闭入口") },
                    trailingContent = {
                        Switch(
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
                    },
                )
            }
        }
    }
}

@Composable
private fun MaterialDouyinTopChannels(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val pins = DouyinTopChannels.pins(DouyinTopChannels.DEFAULT_PINS)
    val channels = DouyinTopChannels.ordered(settings.douyinTopChannels, DouyinTopChannels.DEFAULT_PINS)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding()+12.dp, bottom = padding.calculateBottomPadding()+16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(if (channels.isEmpty()) "正在读取已安装抖音的频道列表…"
                else "关注、直播、推荐常驻；其余标签仅在显示该页面时出现。标签始终按原生页面顺序排列，切换时连续过渡。",
                modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        items(channels, key = { it.id }) { channel ->
            val checked = channel.id in pins
            Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text(channel.label) },
                    supportingContent = { Text(if (checked) "常驻顶栏 · 按原生页面顺序排列" else "仅在显示该页面时出现") },
                    trailingContent = {
                        Switch(checked = checked, enabled = false, onCheckedChange = { })
                    },
                )
            }
        }
    }
}

@Composable
private fun MaterialTikTokTopChannels(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions) {
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val pins = TikTokTopChannels.pins(config.tiktokPinnedTabs)
    val channels = TikTokTopChannels.ordered(settings.tiktokTopChannels, config.tiktokPinnedTabs)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding()+12.dp, bottom = padding.calculateBottomPadding()+16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(if (channels.isEmpty()) "正在读取已安装 TikTok的频道列表…"
                else "已选择 ${pins.size}/3。先开启的页面在最右侧，后开启的依次向左排列；其余位置跟随当前页面。",
                modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        items(channels, key = { it.id }) { channel ->
            val checked = channel.id in pins
            Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.extraLarge) {
                ListItem(
                    headlineContent = { Text(channel.label) },
                    supportingContent = { Text(if (checked) "常驻顶栏 · 从右第 ${pins.indexOf(channel.id)+1} 位"
                        else if (pins.size >= TikTokTopChannels.MAX_PINS) "已选满三个，关闭一个后可继续选择" else "不常驻") },
                    trailingContent = {
                        Switch(checked = checked, enabled = config.tiktokTopBar && (checked || pins.size < TikTokTopChannels.MAX_PINS),
                            onCheckedChange = { enabled -> actions.setAppConfig(target.packageName,
                                config.copy(tiktokPinnedTabs = TikTokTopChannels.toggle(config.tiktokPinnedTabs, channel.id, enabled))) })
                    },
                )
            }
        }
    }
}

@Composable
private fun MaterialAppOverride(target: TargetSpec, settings: ManagerSettings, padding: PaddingValues, actions: UiActions,
    onTopChannels: () -> Unit) {
    val context = LocalContext.current
    val config = settings.appConfigs[target.packageName] ?: settings.glass
    val labels = if (target.key == "spotify") emptyList() else entryLabels(target.key, settings)
    val toggleIndices = labels.indices.filter { labels[it].isNotBlank() }
    val enabledCount = toggleIndices.count { it !in target.automaticEntryIndices && config.hiddenMask and (1 shl it) == 0 }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (target.key == "wechat") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("重构顶栏", "关闭后恢复微信原生顶栏，底栏不受影响。", config.wechatTopBarReconstruction) {
                        actions.setAppConfig(target.packageName, config.copy(wechatTopBarReconstruction = it))
                    }
                }
            }

              item {
                  Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                      MaterialSwitchRow("顶部模糊渐变", "开启后模糊顶部背景；关闭使用半透明渐变。", config.wechatTopBlurGradient) {
                          actions.setAppConfig(target.packageName, config.copy(wechatTopBlurGradient = it))
                      }
                  }
              }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("方案 B（调试）", "开启后折叠置顶聊天使用右侧绿色圆形按钮；关闭使用方案 A。", config.wechatFoldPlanB) {
                        actions.setAppConfig(target.packageName, config.copy(wechatFoldPlanB = it))
                    }
                }
            }
        }
        if (target.key == "mi_health") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("使用 iOS 底栏图标", "心形、跑步、手表、人物；关闭后恢复原图标。", config.miHealthIosIcons) {
                        actions.setAppConfig(target.packageName, config.copy(miHealthIosIcons = it))
                    }
                    MaterialSwitchRow("精简我的页面", "隐藏会员、小习惯、运动健康周报、活力竞赛和健康问诊卡片。", config.miHealthSimplifyMine) {
                        actions.setAppConfig(target.packageName, config.copy(miHealthSimplifyMine = it))
                    }
                }
            }
        }
        if (target.key == "x") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("自动隐藏底栏", "开启：信息流上滑收起、下滑展开，跟随原生节奏；关闭：滚动时底栏与发布键始终显示。帖子详情页仍会隐藏。", config.xAutoHideBar) {
                        actions.setAppConfig(target.packageName, config.copy(xAutoHideBar = it))
                    }
                }
            }
        }
        if (target.key == "tiktok") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("液态玻璃顶栏", "保留原生频道和点击操作，使用胶囊顶栏与完整频道菜单。", config.tiktokTopBar) {
                        actions.setAppConfig(target.packageName, config.copy(tiktokTopBar = it))
                    }
                    if (config.hiddenMask and (1 shl 2) == 0) {
                        MaterialSwitchRow("发布键独立到右侧", "四个页面在左，无底色的液态玻璃发布键独立在右；左右屏幕边距不变。", config.detachedPublish) {
                            actions.setAppConfig(target.packageName, config.copy(detachedPublish = it))
                        }
                    }
                    MaterialSwitchRow("竖屏视频上下铺满（实验）", "对 9:16 及更修长的视频等比缩放至屏幕上下边界，可能裁切左右；关闭恢复完整居中。切换后重新进入视频生效。", config.tiktokTallVideoFill) {
                        actions.setAppConfig(target.packageName, config.copy(tiktokTallVideoFill = it))
                    }
                }
            }
        }
        if (target.key == "douyin") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("液态玻璃顶栏", "四个频道使用胶囊顶栏，其他频道从更多菜单进入；关闭恢复原生顶栏。", config.douyinTopBar) {
                        actions.setAppConfig(target.packageName, config.copy(douyinTopBar = it))
                    }
                    if (config.hiddenMask and (1 shl 2) == 0) {
                        MaterialSwitchRow("发布键独立到右侧", "关闭使用原布局；开启后四个页面在左，发布键独立在右，整体左右边距不变。", config.detachedPublish) {
                            actions.setAppConfig(target.packageName, config.copy(detachedPublish = it))
                        }
                    }
                }
            }
        }
        if (target.key == "bilibili" || target.key == "bilibili_in") {
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("重构顶栏", "关闭后恢复 Bilibili 原生首页顶栏；底栏仍由应用列表中的开关控制。", config.bilibiliTopBar) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliTopBar = it))
                    }
                    MaterialSwitchRow("取消首页信息流左右边距", "让双列卡片和通栏视频卡片贴近屏幕两侧，保留两列之间的间距。", config.bilibiliEdgeToEdgeFeed) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliEdgeToEdgeFeed = it))
                    }
                }
            }
        }
        if (target.key == "bilibili_in") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("播放页状态栏氛围背景", "实验性：以低频画面取样生成柔和模糊背景，保留系统状态栏图标；默认关闭。", config.bilibiliInVideoAmbient) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliInVideoAmbient = it))
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("底栏右侧独立搜索键", "开启后搜索栏变为底栏右侧的独立液态搜索键；关闭后恢复底栏上方的搜索栏。", config.bilibiliInDetachedSearch) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliInDetachedSearch = it))
                    }
                }
            }
        }
        if (target.key == "bilibili") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    val searchBlocked = config.hiddenMask and 31 == 0
                    val notifySearchBlocked = {
                        android.widget.Toast.makeText(context, "请至少关闭一个页面开关", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    Box(Modifier.fillMaxWidth()) {
                        MaterialSwitchRow("独立搜索键", "将首页搜索栏改为底栏右侧的独立液态搜索键，开启时自动关闭独立发布键。",
                            config.bilibiliDetachedSearch, enabled = !searchBlocked) { checked ->
                            if (checked && searchBlocked) notifySearchBlocked()
                            else actions.setAppConfig(target.packageName, config.copy(
                                    bilibiliDetachedSearch = checked,
                                    detachedPublish = if (checked) false else config.detachedPublish,
                                ))
                        }
                        if (searchBlocked) Box(Modifier.matchParentSize().clickable(
                            onClickLabel = "请至少关闭一个页面开关", onClick = notifySearchBlocked))
                    }
                    MaterialSwitchRow("播放页状态栏氛围背景", "以低频画面取样生成柔和模糊背景，保留系统状态栏图标；默认关闭。", config.bilibiliVideoAmbient) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliVideoAmbient = it))
                    }
                }
            }
        }
        if (target.key == "xhs") {
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("重构顶栏", "关闭后恢复小红书原生首页顶栏；底栏仍由应用列表中的开关控制。", config.xhsTopBar) {
                        actions.setAppConfig(target.packageName, config.copy(xhsTopBar = it))
                    }
                    MaterialSwitchRow("取消首页信息流左右边距", "让关注、发现、同城的信息流贴近屏幕两侧，保留列间距。", config.xhsEdgeToEdgeFeed) {
                        actions.setAppConfig(target.packageName, config.copy(xhsEdgeToEdgeFeed = it))
                    }
                }
            }
        }
        if (target.key == "weibo") {
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("重构首页顶栏", "仅改造首页推荐、关注与三个原生操作入口；关闭后恢复原首页布局。", config.weiboHomeTopBar) {
                        actions.setAppConfig(target.packageName, config.copy(weiboHomeTopBar = it))
                    }
                }
            }
        }
        if (target.key == "youtube" && config.isYouTubeCreateVisible()) {
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("独立创作键", "开启后将创作入口放在底栏右侧的独立圆形按钮。", config.detachedPublish) {
                        actions.setAppConfig(target.packageName, config.copy(detachedPublish = it))
                    }
                }
            }
        }
        if (((target.key == "bilibili" || target.key == "xhs" || target.key == "zhihu") &&
            config.hiddenMask and (1 shl 2) == 0) ||
            (target.key == "xianyu" && config.hiddenMask and (1 shl 2) == 0) ||
            (target.key == "tieba" && config.isTiebaPublishVisible())) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow(if (target.key == "xianyu") "独立拍照键" else "独立发布键",
                        if (target.key == "xianyu") "开启后四个页面在左，黄色液态玻璃拍照键独立在右，底色跟随底栏透明度设置。"
                        else if (target.key == "zhihu") "开启后四个页面在左，半透明蓝色发布键独立在右，两部分分别绘制阴影。"
                        else if (target.key == "xhs") "开启后四个页面在左，红色发布键独立在右，两部分分别绘制阴影。"
                        else if (target.key == "tieba") "开启后四个页面在左，蓝色发布键独立在右，整体左右边距不变。"
                        else "开启后四个页面在左，粉色发布键独立在右，整体左右边距不变。", config.detachedPublish) { checked ->
                        actions.setAppConfig(target.packageName, config.copy(
                            detachedPublish = checked,
                            bilibiliDetachedSearch = if (target.key == "bilibili" && checked) false else config.bilibiliDetachedSearch,
                        ))
                    }
                }
            }
        }
        if (target.key == "bilibili" || target.key == "bilibili_in") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("顶部使用纯色渐变", "默认开启：半透明白色或深灰色渐变，降低功耗；关闭后使用模糊渐变。", config.bilibiliSolidTopGradient) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliSolidTopGradient = it))
                    }
                    MaterialSwitchRow("底部使用纯色渐变", "独立控制底部半透明白色或深灰色渐变。", config.bilibiliBottomColorGradient) {
                        actions.setAppConfig(target.packageName, config.copy(bilibiliBottomColorGradient = it))
                    }
                }
            }
        }
        if (target.key == "tiktok" || target.key == "douyin") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        MaterialEffectSlider("顶栏和底栏底色不透明度", config.shortVideoBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                            actions.setAppConfig(target.packageName, config.copy(shortVideoBarOpacity = it / 100f))
                        }
                    }
                }
            }
        }
        if (target.key == "spotify") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("主题色播放进度", "歌曲主题色随播放进度从左向右铺满迷你播放器。", config.spotifyProgressFill) {
                        actions.setAppConfig(target.packageName, config.copy(spotifyProgressFill = it))
                    }
                    MaterialSwitchRow("创建键独立到右侧", "创建键独立为绿色圆形按钮，整体屏幕左右边距不变。", config.detachedPublish) {
                        actions.setAppConfig(target.packageName, config.copy(detachedPublish = it))
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        MaterialEffectSlider("底栏底色透明度系数", config.spotifyBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                            actions.setAppConfig(target.packageName, config.copy(spotifyBarOpacity = it / 100f))
                        }
                    }
                }
            }
        }
        if (target.key == "netease" || target.key == "qqmusic") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        MaterialEffectSlider("底栏与迷你播放器底色透明度系数", config.spotifyBarOpacity * 100f, 0f..100f, valueSuffix = "%") {
                            actions.setAppConfig(target.packageName, config.copy(spotifyBarOpacity = it / 100f))
                        }
                    }
                }
            }
        }
        if (target.key == "meituan_main") {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    MaterialSwitchRow("小团按键独立到右侧", "开启后三个页面在左，小团图标居中显示在右侧圆形液态玻璃按键内；关闭后回到底栏。", config.meituanDetachedXiaotuan) {
                        actions.setAppConfig(target.packageName, config.copy(meituanDetachedXiaotuan = it))
                    }
                    Text("视频入口固定隐藏，不受美团自身设置影响。", modifier = Modifier.padding(20.dp))
                }
            }
        }
        if (target.key == "mi_market") {
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
        } else if (labels.isNotEmpty() && target.key != "meituan_main") {
            item { Text("底栏入口", style = MaterialTheme.typography.titleLargeEmphasized) }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    toggleIndices.filterNot { it in target.automaticEntryIndices }.forEach { index ->
                    val enabled = config.hiddenMask and (1 shl index) == 0
                    ListItem(
                        headlineContent = { Text(labels[index]) },
                        supportingContent = { Text(if (target.key == "spotify" && index == 3 && !config.spotifyPremiumAvailable)
                            "Spotify 已根据帐号状态隐藏此入口。" else if (enabled) "显示图标、文字和触控入口" else "入口已关闭") },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                enabled = (!enabled || enabledCount > 1) &&
                                    (target.key != "spotify" || index != 3 || config.spotifyPremiumAvailable),
                                onCheckedChange = { checked ->
                                    val mask = if (checked) config.hiddenMask and (1 shl index).inv()
                                    else config.hiddenMask or (1 shl index)
                                    actions.setAppConfig(target.packageName, config.withEntryVisibilityMask(target.key, mask))
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
                    MaterialEffectSlider("底栏高度", config.barHeight, GlassConfig.MIN_BAR_HEIGHT..GlassConfig.MAX_BAR_HEIGHT, "${config.barHeight.toInt()} dp") {
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
private fun MaterialSwitchRow(title: String, summary: String, checked: Boolean,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked, onChecked, enabled = enabled) },
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
    detents: List<SliderDetent> = emptyList(),
    onPreview: (Float) -> Unit = {},
    valueFormatter: ((Float) -> String)? = null,
    onChange: (Float) -> Unit,
) {
    ExcludeControlCardTilt()
    val view = LocalView.current
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var pendingCommit by remember { mutableStateOf<Float?>(null) }
    var lastCommittedValue by remember { mutableFloatStateOf(value) }
    val lastPrev = remember { mutableFloatStateOf(value) }
    val detentController = remember(detents, range, value) { SliderDetentController(detents, range, value) }
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
                valueFormatter?.invoke(localValue) ?: if (valueSuffix.isEmpty()) "${"%.1f".format(localValue)}" else "${localValue.toInt()}$valueSuffix",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
            modifier = Modifier.managerSliderGesture().sliderDetentTicks(detents, range, localValue, 10f),
        )
        SliderDetentLabels(detents, range, 10f)
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
                    MaterialSwitchRow(
                        title = "纯色模式",
                        summary = "使用不透明纯色底栏；开启时暂时关闭液态玻璃。",
                        checked = settings.solidBarEnabled,
                        onChecked = actions.setSolidBarEnabled,
                    )
                    MaterialSwitchRow(
                        title = "液态玻璃",
                        summary = if (settings.liquidGlassEnabled) {
                        "底栏与滑块启用液态玻璃；模块控件使用 iOS 样式。"
                        } else {
                        "关闭折射与按压放大，底栏保留模糊；模块控件使用 MIUIX 样式。"
                        },
                        checked = settings.liquidGlassEnabled,
                        onChecked = actions.setLiquidGlassEnabled,
                    )
                    MaterialSwitchRow(
                        title = "磨砂玻璃",
                        summary = "仅使用模糊，不使用液态折射或不透明底色。",
                        checked = settings.frostedGlassEnabled,
                        onChecked = actions.setFrostedGlassEnabled,
                    )
                    MaterialSwitchRow(
                        title = "姿态动态效果",
                        summary = "随握持角度改变液态折射和阴影；关闭后使用静态效果。",
                        checked = settings.glass.postureEffectsEnabled,
                        onChecked = { actions.setGlassConfig(settings.glass.copy(postureEffectsEnabled = it)) },
                    )
                    MaterialSwitchRow(
                        title = "简化切页动画",
                        summary = "开启时滑块保持原尺寸平移并保留折射；关闭后恢复放大。冷切页等待不变。",
                        checked = settings.glass.simplifyTabSwitch,
                        onChecked = { actions.setGlassConfig(settings.glass.copy(simplifyTabSwitch = it)) },
                    )
                    MaterialSwitchRow(
                        title = "滑块照亮效果",
                        summary = "关闭后取消底栏滑块交互时的照亮和阴影。",
                        checked = settings.glass.indicatorInteractionLightEnabled,
                        onChecked = { actions.setGlassConfig(settings.glass.copy(indicatorInteractionLightEnabled = it)) },
                    )
                    MaterialSwitchRow(
                        title = "描边",
                        summary = "控制纯模糊/纯色底栏的反色描边，以及液态底栏的高光描边。",
                        checked = settings.outlineEnabled,
                        onChecked = actions.setOutlineEnabled,
                    )
                    MaterialSwitchRow(
                        title = "底栏无字模式",
                        summary = "有图标和文字的底栏只显示图标，并垂直居中。",
                        checked = settings.glass.bottomBarIconOnly,
                        onChecked = { actions.setGlassConfig(settings.glass.copy(bottomBarIconOnly = it)) },
                    )
                    MaterialSwitchRow(
                        title = "使用经典导航键",
                        summary = "开启后，底栏与屏幕底部的间距增至默认值的两倍。",
                        checked = settings.glass.classicNavigation,
                        onChecked = { actions.setGlassConfig(settings.glass.copy(classicNavigation = it)) },
                    )
                    MaterialSwitchRow("动态配色", "从系统壁纸提取颜色。", settings.dynamicColor, onChecked = actions.setDynamicColor)
                }
            }
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp)) {
                    if (settings.glass.postureEffectsEnabled) {
                    MaterialEffectSlider(
                        title = "粘滞感\n低值轻快回荡，高值缓慢流动 · 默认 25%",
                        value = settings.glass.liquidViscosity,
                        range = 0f..100f,
                        valueSuffix = "%",
                        detents = viscosityDetents,
                        onChange = { actions.setGlassConfig(settings.glass.copy(liquidViscosity = it)) },
                    )
                    MaterialEffectSlider(
                        title = "左右流动强度\n0% 关闭左右方向 · 100% 当前效果",
                        value = settings.glass.flowHorizontalStrength,
                        range = 0f..100f, valueSuffix = "%", hapticTriggerValue = 0f,
                        onChange = { actions.setGlassConfig(settings.glass.copy(flowHorizontalStrength = it)) },
                    )
                    MaterialEffectSlider(
                        title = "上下流动强度\n0% 关闭上下方向 · 100% 当前效果",
                        value = settings.glass.flowVerticalStrength,
                        range = 0f..100f, valueSuffix = "%", hapticTriggerValue = 0f,
                        onChange = { actions.setGlassConfig(settings.glass.copy(flowVerticalStrength = it)) },
                    )
                    }
                    if (settings.liquidGlassEnabled && !settings.solidBarEnabled) {
                        MaterialEffectSlider(
                            title = "折射强度\n默认 120% · 100% 原有强度 · 最高 200% · 不影响桌面",
                            value = settings.glass.refractionStrength,
                            range = 0f..200f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_REFRACTION_STRENGTH,
                            onChange = { actions.setGlassConfig(settings.glass.copy(refractionStrength = it)) },
                        )
                    }
                    if (!settings.solidBarEnabled) {
                        MaterialEffectSlider(
                            title = "底色透明度\n深浅色统一 · 100% 为完全透明",
                            value = (1f - settings.glass.lightAlpha) * 100f,
                            range = 20f..100f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_BAR_TRANSPARENCY_PERCENT,
                            onChange = { actions.setGlassConfig(settings.glass.copy(
                                lightAlpha = 1f - it / 100f, darkAlpha = 1f - it / 100f)) },
                        )
                        MaterialEffectSlider(
                            title = "模糊半径\n默认 0.0",
                            value = settings.glass.blurRadius,
                            range = 0f..16f,
                            hapticTriggerValue = GlassConfig.DEFAULT_BLUR_RADIUS,
                            onChange = {
                                actions.setGlassConfig(settings.glass.copy(blurRadius = it))
                            },
                        )
                    }
                    MaterialEffectSlider(
                        title = "底栏高度\n50–64 dp",
                        value = settings.glass.barHeight,
                        range = GlassConfig.MIN_BAR_HEIGHT..GlassConfig.MAX_BAR_HEIGHT,
                        hapticTriggerValue = GlassConfig.DEFAULT_BAR_HEIGHT,
                        onChange = {
                            actions.setGlassConfig(settings.glass.copy(barHeight = it))
                        },
                    )
                    if (!settings.solidBarEnabled) {
                        MaterialEffectSlider(
                            title = "高光描边强度\n适用于深浅色液态玻璃控件 · 默认 60%",
                            value = settings.glass.darkBarHighlightStrength * 100f,
                            range = 0f..170f,
                            hapticTriggerValue = GlassConfig.DEFAULT_HIGHLIGHT_STRENGTH_PERCENT,
                            onChange = {
                                actions.setGlassConfig(settings.glass.copy(darkBarHighlightStrength = it / 100f))
                            },
                        )
                        if (settings.liquidGlassEnabled) MaterialEffectSlider(
                            title = "高光透明度\n深浅色同时生效 · 默认 45% · 100% 隐藏高光",
                            value = settings.glass.highlightTransparency * 100f,
                            range = 0f..100f, valueSuffix = "%", hapticTriggerValue = GlassConfig.DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT,
                            onChange = { actions.setGlassConfig(settings.glass.copy(highlightTransparency = it / 100f)) },
                        )
                    }
                }
            }
        }
    }
}


