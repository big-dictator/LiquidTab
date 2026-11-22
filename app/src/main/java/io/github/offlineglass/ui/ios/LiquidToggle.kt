/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Copyright 2025 Kyant).
 * Apache License 2.0; see licenses/Apache-2.0.txt and NOTICE.md.
 * Miuix Backdrop bridge, lifecycle-safe callbacks and accessibility by LiquidTab.
 */
package io.github.offlineglass.ui.ios

import androidx.compose.foundation.background

import io.github.offlineglass.rendering.postureDropShadow

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import io.github.offlineglass.ui.managerLens
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import io.github.offlineglass.ui.LocalIosDark
import io.github.offlineglass.ui.LocalIosCardBackdrop
import io.github.offlineglass.ui.LocalIosCaptureState
import io.github.offlineglass.ui.excludeIosGlassFromCapture
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val capture = LocalIosCaptureState.current
    val isLightTheme = !LocalIosDark.current
    val accentColor =
        if (isLightTheme) Color(0xFF34C759)
        else Color(0xFF30D158)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (currentSelected()) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope, dragWidth, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (fraction >= 0.5f) 1f else 0f
                    currentOnSelect(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (currentSelected()) 0f else 1f
                    currentOnSelect(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentSelected() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    // The fraction collector alone owns translation. Starting
                    // animateToValue here raced it and restarted the same spring.
                    dampedDragAnimation.press()
                    dampedDragAnimation.release()
                }
            }
    }

    LocalControlOpticsProbe.current?.let { register -> androidx.compose.runtime.SideEffect { register(dampedDragAnimation) } }
    val trackSurface = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainer
    val trackBackdrop = rememberLayerBackdrop { drawRect(trackSurface); drawContent() }
    val cardBackdrop = LocalIosCardBackdrop.current ?: trackBackdrop

    Box(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(RoundedCornerShape(50))
                .drawBehind {
                    val fraction = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
                .size(64f.dp, 28f.dp)
        )

        // At exact rest the surface is opaque white: sampling/blur underneath
        // contributes no pixels. Keep all optical work for the entire gesture
        // and spring return, not merely while the finger is down.
        val opticalActive by remember(dampedDragAnimation) {
            androidx.compose.runtime.derivedStateOf {
                dampedDragAnimation.pressProgress != 0f ||
                    dampedDragAnimation.scaleX != 1f || dampedDragAnimation.scaleY != 1f ||
                    dampedDragAnimation.velocity != 0f
            }
        }
        androidx.compose.runtime.DisposableEffect(capture, opticalActive) {
            // Capture a plain Boolean for THIS registration. Reading the
            // delegated opticalActive in onDispose observes the NEXT state.
            val registered = opticalActive
            if (registered) capture?.let { it.activeOpticalConsumers++ }
            onDispose { if (registered) capture?.let { it.activeOpticalConsumers-- } }
        }
        Box(
            Modifier
                .excludeIosGlassFromCapture(capture)
                .graphicsLayer {
                    val fraction = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, fraction)
                        else lerp(-padding, -(padding + dragWidth), fraction)
                }
                .semantics {
                    role = Role.Switch
                    stateDescription = if (currentSelected()) "开启" else "关闭"
                    toggleableState = if (currentSelected()) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                    onClick { currentOnSelect(!currentSelected()); true }
                }
                .then(dampedDragAnimation.modifier)
                .postureDropShadow(RoundedCornerShape(50), Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)))
                .then(if (opticalActive) Modifier.drawBackdrop(
                    backdrop = cardBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        managerLens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = 1f
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(
                            width = Highlight.Default.width / 1.5f,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                else Modifier.background(Color.White, RoundedCornerShape(50)))
                .size(40f.dp, 24f.dp)
        )
    }
}



