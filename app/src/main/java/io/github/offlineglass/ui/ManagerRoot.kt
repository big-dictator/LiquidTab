package io.github.offlineglass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.offlineglass.config.ColorMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
fun ManagerRoot(settings: ManagerSettings, actions: UiActions) {
    val postureView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { managerRefractionScale = settings.glass.refractionScale }
    androidx.compose.runtime.SideEffect {
        managerHighlightTransparency.floatValue = settings.glass.highlightTransparency
    }
    androidx.compose.runtime.LaunchedEffect(settings.glass.liquidViscosity,
        settings.glass.flowHorizontalStrength, settings.glass.flowVerticalStrength) {
        io.github.offlineglass.rendering.LiquidMotion.configure(postureView,
            settings.glass.flowHorizontalStrength, settings.glass.flowVerticalStrength)
        io.github.offlineglass.rendering.PostureCompose.viscosity = settings.glass.liquidViscosity
        io.github.offlineglass.rendering.PostureCompose.update()
    }
    androidx.compose.runtime.DisposableEffect(postureView, settings.liquidGlassEnabled, settings.solidBarEnabled, settings.glass.postureEffectsEnabled) {
        io.github.offlineglass.rendering.PostureCompose.view = java.lang.ref.WeakReference(postureView)
        io.github.offlineglass.rendering.PostureGlass.track(postureView, settings.liquidGlassEnabled && !settings.solidBarEnabled && settings.glass.postureEffectsEnabled)
        onDispose { io.github.offlineglass.rendering.PostureGlass.track(postureView, false); io.github.offlineglass.rendering.PostureCompose.reset() }
    }
    WarmAppIcons(settings.installedPackages)
    var selectedPageName by rememberSaveable { mutableStateOf(ManagerPage.HOME.name) }
    val selectedPage = ManagerPage.available.firstOrNull { it.name == selectedPageName } ?: ManagerPage.HOME
    val onSelected: (ManagerPage) -> Unit = { selectedPageName = it.name }

    val dark = when (settings.colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val sliderGestures = androidx.compose.runtime.remember { ManagerSliderGesture() }
    CompositionLocalProvider(LocalManagerSliderGesture provides sliderGestures,
        LocalManagerLanguage provides settings.language,
        LocalIosStyle provides settings.iosStyle,
        LocalIosDark provides dark, LocalIosSmoothing provides settings.glass.cornerSmoothing) {
    val rootBackdrop = rememberLayerBackdrop()
    var rootNoticeVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var frostConfirmation by rememberSaveable { mutableStateOf(false) }
    val settingsActions = actions.copy(setFrostedGlassEnabled = { enabled ->
        if (enabled && settings.glass.blurRadius == 0f) frostConfirmation = true
        else actions.setFrostedGlassEnabled(enabled)
    })
    Box(Modifier.fillMaxSize()) {
    // The dialog is the sole consumer; don't record a redundant full-screen
    // backdrop during ordinary scrolling when no dialog is visible.
    Box(Modifier.fillMaxSize().then(if (rootNoticeVisible || frostConfirmation) Modifier.layerBackdrop(rootBackdrop) else Modifier)) {
    when (settings.uiMode) {
        UiMode.MATERIAL -> MaterialManager(settings, selectedPage, onSelected, settingsActions)
        UiMode.MIUIX -> MiuixManager(settings, selectedPage, onSelected, settingsActions)
    }
    }
    RootPermissionNotice(rootBackdrop, dark, settings.glass.blurRadius, suppressed = frostConfirmation,
        highlightStrength = settings.glass.darkBarHighlightStrength) {
        rootNoticeVisible = it
    }
    if (frostConfirmation) {
        ManagerGlassDialog(rootBackdrop, dark, settings.glass.blurRadius, "开启磨砂玻璃",
            "当前模糊半径为 0。确认后将半径设为 4，并开启磨砂玻璃。",
            "取消", "确认", onDismiss = { frostConfirmation = false }, onConfirm = {
                actions.setFrostedGlassEnabled(true)
                frostConfirmation = false
            }, highlightStrength = settings.glass.darkBarHighlightStrength)
    }
    }
    }
}
