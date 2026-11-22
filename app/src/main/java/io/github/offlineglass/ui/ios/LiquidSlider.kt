/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Copyright 2025 Kyant).
 * Apache License 2.0; see licenses/Apache-2.0.txt and NOTICE.md.
 * Miuix Backdrop bridge, lifecycle-safe callbacks and accessibility by LiquidTab.
 */
package io.github.offlineglass.ui.ios

import io.github.offlineglass.rendering.postureDropShadow

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
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
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    val capture = LocalIosCaptureState.current
    val isLightTheme = !LocalIosDark.current
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackSurface = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainer
    val trackBackdrop = rememberLayerBackdrop { drawRect(trackSurface); drawContent() }

    BoxWithConstraints(
        modifier.fillMaxWidth().height(48.dp).semantics {
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value(), valueRange)
            setProgress { requested -> currentOnChange(requested.coerceIn(valueRange)); currentFinished(); true }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val cardBackdrop = LocalIosCardBackdrop.current ?: trackBackdrop
        val thumbWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
        val travelWidth = (trackWidth - thumbWidth).coerceAtLeast(1f)

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        // Accumulate raw motion independently of default-value snapping.
        var rawDragValue by remember { mutableFloatStateOf(value()) }
        val dampedDragAnimation = remember(animationScope, valueRange, trackWidth, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = { didDrag = false; rawDragValue = currentValue() },
                onDragStopped = {
                    if (didDrag) currentOnChange(currentValue())
                    didDrag = false
                    currentFinished()
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / travelWidth)
                    rawDragValue = (rawDragValue + if (isLtr) delta else -delta).coerceIn(valueRange)
                    currentOnChange(rawDragValue)
                }
            )
        }
        LocalControlOpticsProbe.current?.let { register -> androidx.compose.runtime.SideEffect { register(dampedDragAnimation) } }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { value ->
                    if (dampedDragAnimation.targetValue != value) {
                        dampedDragAnimation.updateValue(value)
                    }
                }
        }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(trackColor)
                    .pointerInput(animationScope) {
                        detectTapGestures { position ->
                            val delta = (valueRange.endInclusive - valueRange.start) * ((position.x - thumbWidth / 2f) / travelWidth)
                            val targetValue =
                                (if (isLtr) valueRange.start + delta
                                else valueRange.endInclusive - delta)
                                    .coerceIn(valueRange)
                            dampedDragAnimation.animateToValue(targetValue)
                            currentOnChange(targetValue)
                            currentFinished()
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .graphicsLayer {
                        // Only the fill's clip changes. The track already owns
                        // the full width, so an animated child measurement did
                        // not change any visible layout, but dirtied the card
                        // and its backdrop coordinates on every spring frame.
                        // Keep the original integer width and round-rect clip;
                        // do not scale a recorded texture (including its caps).
                        val filledWidth = SliderTrackGeometry.widthPx(trackWidth, dampedDragAnimation.progress)
                        shape = SliderTrackFillShape(filledWidth)
                        // An empty Android Outline can mean "no clipping".
                        // The former zero-width child painted nothing.
                        alpha = if (filledWidth == 0) 0f else 1f
                        clip = true
                    }
                    .background(accentColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )
        }

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
                    translationX = travelWidth * dampedDragAnimation.progress.coerceIn(0f, 1f) * if (isLtr) 1f else -1f
                }
                .then(dampedDragAnimation.modifier)
                .dropShadow(RoundedCornerShape(50)) {
                    radius = 5.dp.toPx()
                    spread = 1.dp.toPx()
                    color = Color.Black
                    alpha = lerp(0.16f, 0.05f, dampedDragAnimation.pressProgress.coerceIn(0f, 1f))
                    offset = androidx.compose.ui.geometry.Offset.Zero
                }
                .then(if (opticalActive) Modifier.drawBackdrop(
                    backdrop = cardBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        managerLens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
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
                        val velocity = dampedDragAnimation.velocity / 10f
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

/** Same rounded clip as the old width-measured fill, in a stable full-track layer. */
private data class SliderTrackFillShape(val filledWidthPx: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = SliderTrackGeometry.leftPx(size.width, filledWidthPx, layoutDirection == LayoutDirection.Rtl)
        val radius = SliderTrackGeometry.radiusPx(filledWidthPx, size.height)
        return Outline.Rounded(RoundRect(
            left = left, top = 0f, right = left + filledWidthPx, bottom = size.height,
            cornerRadius = CornerRadius(radius, radius),
        ))
    }
}



