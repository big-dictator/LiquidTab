package io.github.offlineglass.ui

import io.github.offlineglass.rendering.postureDropShadow
import io.github.offlineglass.hook.setBottomBarSquircle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Icon
import io.github.offlineglass.ui.ManagerMaterialText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.ui.graphics.asComposeRenderEffect
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈6° tilt

/** The same continuous MIUIX squircle used by injected View/Canvas bars. */
private data class ManagerContourKey(val width: Float, val height: Float, val radius: Float, val smoothing: Float)
// Exact geometry only: no quantization, bitmap caching or changed optical shape.
// A bounded manager-only cache shares the same canonical path between outlines,
// clip and highlight passes, including the common -1 curve not cached upstream.
private val managerContourPaths = android.util.LruCache<ManagerContourKey, android.graphics.Path>(64)

private fun Path.addBottomBarSquircle(
    width: Float,
    height: Float,
    radius: Float,
    smoothing: Float,
) {
    // One canonical path implementation for the manager and injected bars.
    val key = ManagerContourKey(width, height, radius, smoothing)
    val path = managerContourPaths.get(key) ?: android.graphics.Path().also {
        it.setBottomBarSquircle(android.graphics.RectF(0f, 0f, width, height), radius, smoothing)
        managerContourPaths.put(key, it)
    }
    addPath(path.asComposePath())
}

internal class BottomBarSquircleShape(
    corner: CornerSize,
    private val smoothing: Float,
) : CornerBasedShape(
    topStart = corner,
    topEnd = corner,
    bottomEnd = corner,
    bottomStart = corner,
) {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    ): Outline = Outline.Generic(
        Path().apply {
            addBottomBarSquircle(
                width = size.width,
                height = size.height,
                radius = minOf(topStart, topEnd, bottomEnd, bottomStart),
                smoothing = smoothing,
            )
        },
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = BottomBarSquircleShape(topStart, smoothing)

    // CornerBasedShape compares only its four CornerSize values. Include the
    // continuous-curve parameter so Compose invalidates cached outlines when
    // the strength slider moves without changing the radius.
    override fun equals(other: Any?): Boolean =
        other is BottomBarSquircleShape &&
            topStart == other.topStart && smoothing == other.smoothing

    override fun hashCode(): Int = 31 * topStart.hashCode() + smoothing.hashCode()
}

// Same specular stroke as the injected bars (BLOOM_STROKE_DUAL_SHADER
// uniforms): White 0.12 stroke, 2 dp inner blur, primary light at the gravity
// reference (0.5, -0.3, -0.05), dual peak. Adapted from KernelSU's
// FloatingBottomBar (Apache 2.0).
internal val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
internal fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
    postureEnabled: Boolean = true,
): Highlight {
    val baseStyle = base.style as BloomStroke
    // Refraction/ripple ticks do not change the light direction. Observe only
    // gravity here instead of recomposing the whole bar for every spring tick.
    val gravity by remember(postureEnabled) {
        androidx.compose.runtime.derivedStateOf {
            if (postureEnabled) io.github.offlineglass.rendering.PostureCompose.current.let {
                it.gravityX to it.gravityY
            } else 0f to 0f
        }
    }
    val rotatedPrimary = remember(gravity, baseStyle.primaryLight, extraDegrees, postureEnabled) {
        val basePrimary = baseStyle.primaryLight
        val gx = gravity.first
        val gy = gravity.second
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

/** Read pose in the optical draw callback, never in the page composition. */
private fun currentManagerHighlight(extraDegrees: Float, enabled: Boolean): Highlight {
    val pose = if (enabled) io.github.offlineglass.rendering.PostureCompose.current else null
    val gx = pose?.gravityX ?: 0f
    val gy = pose?.gravityY ?: 0f
    val mag = sqrt(gx * gx + gy * gy)
    val x = if (mag * mag > GRAVITY_DIR_THRESHOLD_SQ) gx / mag else 0f
    val y = if (mag * mag > GRAVITY_DIR_THRESHOLD_SQ) gy / mag else -1f
    val rad = extraDegrees * PI / 180.0
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val style = iosIndicatorSpecular.style as BloomStroke
    return iosIndicatorSpecular.copy(style = style.copy(primaryLight = style.primaryLight.copy(
        position = LightPosition(LIGHT_REF_X + c * x - s * y,
            LIGHT_REF_Y + s * x + c * y, style.primaryLight.position.z))))
}

/**
 * The manager uses the same Backdrop -> vibrancy -> blur -> lens pipeline as the
 * injected application bar.  It is intentionally not a separately painted mock.
 */
@Composable
internal fun managerBottomBarSlotHeight(config: io.github.offlineglass.config.GlassConfig): Float {
    val view = LocalView.current
    val density = LocalDensity.current
    return with(density) {
        val gap = (io.github.offlineglass.hook.globalGlassBottomGapPx(view) *
            if (config.classicNavigation) 2 else 1).toDp().value
        io.github.offlineglass.rendering.BottomBarIndicatorStyle.compactBarHeightPx(
            config.barHeight.coerceIn(io.github.offlineglass.config.GlassConfig.MIN_BAR_HEIGHT, io.github.offlineglass.config.GlassConfig.MAX_BAR_HEIGHT),
            density.density,
        ) / density.density + gap + 16f
    }
}

@Composable
fun ManagerGlassBottomBar(
    selectedPage: ManagerPage,
    onSelectedPage: (ManagerPage) -> Unit,
    selectedColor: Color,
    unselectedColor: Color,
    backdrop: Backdrop,
    containerColor: Color,
    tabWidthDp: Float = 76f,
    liquidGlassEnabled: Boolean = true,
    solidBarEnabled: Boolean = false,
    postureEffectsEnabled: Boolean = true,
    indicatorInteractionLightEnabled: Boolean = true,
    simplifyTabSwitch: Boolean = true,
    outlineEnabled: Boolean = true,
    blurRadius: Float = 0f,
    cornerRadiusPercent: Float = 50f,
    cornerSmoothing: Float = -1f,
    barHeight: Float = 56f,
    darkBarHighlightStrength: Float = 0.5f,
    lightAlpha: Float = 0.40f,
    darkAlpha: Float = 0.40f,
    classicNavigation: Boolean = false,
    iconOnly: Boolean = false,
) {
    val liquidOptics = liquidGlassEnabled && !solidBarEnabled
    val density = LocalDensity.current
    val managerBlurPx = with(density) { blurRadius.dp.toPx() }
    // Keep sampling in source pixel coordinates. The custom blur DSL snaps its
    // downscaled input to a coarse grid, which shimmers on slowly moving text.
    // HWUI performs its own GPU blur optimization without that manual grid.
    val managerBlurEffect = remember(managerBlurPx) {
        io.github.offlineglass.rendering.NativeBackdropBlur.effect(managerBlurPx).asComposeRenderEffect()
    }
    val view = LocalView.current
    val pages = ManagerPage.available
    val isDark = containerColor.luminance() < 0.5f
    val configuredFill = if (isDark) Color(0xFF211F26) else Color.White
    val configuredAlpha = lightAlpha.coerceIn(0f, 0.80f)
    // Same standard four-tab width policy as GlassInstaller: 12dp minimum
    // screen-side clearance, otherwise configured per-tab width, centred.
    val screenWidthPx = view.rootView.width.takeIf { it > 0 }
        ?: view.resources.displayMetrics.widthPixels
    val barWidth = with(density) {
        min((screenWidthPx - 24.dp.toPx()).coerceAtLeast(128.dp.toPx()),
            pages.size * tabWidthDp.coerceIn(64f, 92f).dp.toPx()).toDp()
    }
    // Reuse the injected bars' screen-height reference, independently of width.
    val bottomGap = with(density) {
        (io.github.offlineglass.hook.globalGlassBottomGapPx(view) *
            if (classicNavigation) 2 else 1).toDp()
    }
    val selectedIndex = pages.indexOf(selectedPage).coerceAtLeast(0)
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = remember(backdrop, tabsBackdrop) {
        ManagerCombinedBackdrop(backdrop, tabsBackdrop)
    }
    val normalizedHeight = io.github.offlineglass.rendering.BottomBarIndicatorStyle.compactBarHeightPx(
        barHeight.coerceIn(io.github.offlineglass.config.GlassConfig.MIN_BAR_HEIGHT, io.github.offlineglass.config.GlassConfig.MAX_BAR_HEIGHT),
        density.density,
    ) / density.density
    // One inset defines both the slot bounds and the nested outline.
    val indicatorHeight = (normalizedHeight - 8f).dp
    val hiddenRefractionHeight = min(24f, (normalizedHeight - 8f) * 0.5f - 1f).dp
    val hiddenShape = remember(normalizedHeight, cornerRadiusPercent, cornerSmoothing) {
        BottomBarSquircleShape(
            CornerSize(((normalizedHeight - 8f) * cornerRadiusPercent.coerceIn(15f, 50f) / 100f).dp),
            cornerSmoothing.coerceIn(-1f, 1f),
        )
    }
    val contentScale = ManagerBottomBarGeometry(
        with(density) { barWidth.toPx() }, with(density) { normalizedHeight.dp.toPx() },
        density.density, pages.size, selectedIndex.toFloat(), 1f, density.fontScale,
    ).contentScale
    val tabIconSize = (28f * contentScale).dp
    val barCornerDp = normalizedHeight * cornerRadiusPercent.coerceIn(15f, 50f) / 100f
    val barShape = remember(barCornerDp, cornerSmoothing) {
        BottomBarSquircleShape(
            CornerSize(barCornerDp.dp),
            cornerSmoothing.coerceIn(-1f, 1f),
        )
    }

    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val tabWidthPx = (panelSize.width - with(density) { 4.dp.toPx() } * 2) / pages.size.toFloat()
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    val motion = remember(pages.size, liquidOptics) { ManagerBottomBarMotion(selectedIndex.toFloat(), pages.size, liquidOptics) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    val selectWithPress: (Int) -> Unit = { index ->
        motion.selectByTap(index.toFloat(), liquidOptics && !simplifyTabSwitch)
        onSelectedPage(pages[index])
    }
    val pressProgress = { motion.pressProgress }
    val insetIndicator = remember(panelSize, barCornerDp, cornerSmoothing, density.density, pages.size) {
        if (tabWidthPx <= 0f || panelSize.height <= 0) null else with(density) {
            ManagerInsetIndicatorShape(
                panelSize.width.toFloat(), panelSize.height.toFloat(), barCornerDp.dp.toPx(),
                cornerSmoothing.coerceIn(-1f, 1f), 4.dp.toPx(), pages.size,
            )
        }
    }
    // Finger position is authoritative during a drag. Restarting animateTo on
    // every MOVE can cancel it before its first frame and pin the lens in place.
    val settledIndex = { if (dragging) dragIndex else motion.position }
    // Same bounded velocity stretch as GlassHostLayout; it vanishes at rest.
    // Shape inertia is independent from finger-owned translation. It should
    // lag and rebound; only the lens centre follows the pointer immediately.
    val latestSelection by rememberUpdatedState(selectedIndex)
    val latestOnSelected by rememberUpdatedState(onSelectedPage)
    val latestRichTap by rememberUpdatedState(liquidOptics && !simplifyTabSwitch)
    val restingTintProgress = { io.github.offlineglass.rendering.IndicatorReturnPolicy.mix(
        motion.restingTintProgress, dragging || pressedIndex != null, true, true) }
    LaunchedEffect(selectedIndex) {
        if (!dragging) {
            dragIndex = selectedIndex.toFloat()
            motion.followSelection(selectedIndex.toFloat())
        }
    }

    // Gravity-rotated speculars (KernelSU port): only subscribe to the tilt
    // sensor while liquid glass is on — plain/solid modes draw no stroke.
    // Defer pose reads until drawing. Otherwise every sensor tick recomposes
    // the entire bar, its layouts, three icon rows and pointer-input modifiers.
    val panelEdge = remember { ManagerGlassEdge() }
    val hiddenEdge = remember { ManagerGlassEdge() }
    val sliderEdge = remember { ManagerGlassEdge() }
    val baseHighlight = { if (liquidOptics) currentManagerHighlight(-45f, postureEffectsEnabled) else iosIndicatorSpecular }
    val pillHighlight = { if (liquidOptics) currentManagerHighlight(90f, postureEffectsEnabled) else iosIndicatorSpecular }

    Box(
        modifier = Modifier.fillMaxWidth().height(normalizedHeight.dp + bottomGap + 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = bottomGap)
                // Match injected bars: host width is slot count multiplied by
                // the configured single-slot width, never a screen fraction.
                .width(barWidth)
                .height(normalizedHeight.dp)
                .graphicsLayer {
                    translationX = io.github.offlineglass.rendering.BottomBarMotionSprings.panelTranslation(
                        motion.panelDragOffset, size.width, density.density)
                }
                .onSizeChanged {
                    // Measure the host, not a Row with inner padding modifiers.
                    panelSize = it
                }
                .drawWithCache {
                    val illuminationClip = Path().apply {
                        addBottomBarSquircle(size.width, size.height,
                            size.height * cornerRadiusPercent.coerceIn(15f, 50f) / 100f, cornerSmoothing)
                    }
                    onDrawBehind {
                        if (indicatorInteractionLightEnabled && pressProgress() > 0.001f && !solidBarEnabled) {
                            val center = Offset(4.dp.toPx() + (settledIndex() + 0.5f) * tabWidthPx, size.height / 2f)
                            val radius = 96.dp.toPx()
                            clipPath(illuminationClip) {
                                drawCircle(Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.12f * pressProgress().coerceIn(0f, 1f)), Color.Transparent),
                                    center, radius), radius, center)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Only the panel grows. Tabs, hit targets and the independently
                        // measured thumb stay in unscaled host coordinates.
                        val panelPress = if (motion.sliderOnlyPress) 0f else pressProgress()
                        val grow = 16.dp.toPx() / size.width.coerceAtLeast(1f) * panelPress * 0.8f
                        scaleX = 1f + grow * 0.65f
                        scaleY = 1f + grow
                    }
                    .dropShadow(
                        shape = barShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = if (isDark) 0.10f else 0.13f,
                            offset = androidx.compose.ui.unit.DpOffset(0.dp, 1.dp),
                        ),
                    )
                    .then(if (isDark) Modifier.dropShadow(
                        shape = barShape,
                        shadow = Shadow(radius = 10.dp, spread = 1.dp,
                            color = Color.White, alpha = 0.08f),
                    ) else Modifier)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { barShape },
                        effects = {
                            if (!solidBarEnabled) {
                                renderEffect = managerBlurEffect
                                downscaleFactor = 1
                                padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(managerBlurPx, padding)
                            }
                            if (liquidOptics && !solidBarEnabled) {
                                managerLens(
                                    refractionHeight = ManagerBottomBarGeometry.refractionHeight(size.height, density.density),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            }
                        },
                        highlight = {
                            // The manager draws its outer specular below using the
                            // actual squircle path. miuix-blur's generic highlight
                            // renderer reduces Generic outlines to a rounded box.
                            iosIndicatorSpecular.copy(alpha = 0f)
                        },
                        onDrawSurface = {
                            drawRect(if (solidBarEnabled) containerColor else configuredFill.copy(alpha = configuredAlpha))
                            if (outlineEnabled && liquidOptics && !solidBarEnabled) {
                                val light = (baseHighlight().style as BloomStroke).primaryLight.position
                                panelEdge.draw(this, barShape, isDark,
                                    (if (isDark) .58f else .75f) * darkBarHighlightStrength, light.x, light.y)
                            }
                        },
                    )
                    .then(
                        if (!liquidOptics && outlineEnabled) Modifier.border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.32f)
                            else Color.Black.copy(alpha = 0.24f),
                            shape = barShape,
                        ) else Modifier,
                    )
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerTabs(
                    pages = pages,
                    selectedIndex = selectedIndex,
                    selectedColor = selectedColor,
                    unselectedColor = unselectedColor,
                    onSelected = selectWithPress,
                    onPressedChange = { index, pressed ->
                        if (pressed) pressedIndex = index
                        else if (pressedIndex == index) pressedIndex = null
                    },
                    interactive = true,
                    iconSize = tabIconSize,
                    contentScale = contentScale,
                    iconOnly = iconOnly,
                )
            }

            // The invisible tab layer is sampled together with the page backdrop,
            // matching the injected bar's combined-backdrop indicator.
            Row(
                modifier = Modifier
                    .alpha(0f)
                    .padding(horizontal = 4.dp)
                    .fillMaxWidth()
                    .height(indicatorHeight)
                    .layerBackdrop(tabsBackdrop)
                    // Keep the optical backdrop in the source layer. This is
                    // the part that lets the enlarged indicator refract the
                    // bar's full interior, not just the labels and icons.
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { hiddenShape },
                        effects = {
                            // The hidden source stays blurred; the moving lens adds only refraction.
                            renderEffect = managerBlurEffect
                            downscaleFactor = 1
                            padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(managerBlurPx, padding)
                            if (liquidOptics) managerLens(
                                hiddenRefractionHeight.toPx(), 24.dp.toPx(),
                                postureBlend = if (dragging || pressedIndex != null) 0f else restingTintProgress(),
                            )
                        },
                        highlight = {
                            iosIndicatorSpecular.copy(alpha = 0f)
                        },
                        onDrawSurface = {
                            // Only the hidden source carries its material fill.
                            drawRect(configuredFill.copy(alpha = configuredAlpha))
                            if (liquidOptics && !solidBarEnabled) {
                                val strength = io.github.offlineglass.rendering.BottomBarIndicatorStyle.SPECULAR_MIN_ALPHA *
                                    io.github.offlineglass.rendering.BottomBarIndicatorStyle.HIDDEN_SPECULAR_GAIN *
                                    pressProgress().coerceIn(0f, 1f)
                                if (outlineEnabled && pressProgress() > 0f) {
                                    val light = (pillHighlight().style as BloomStroke).primaryLight.position
                                    hiddenEdge.draw(this, hiddenShape, isDark, strength * 2f * darkBarHighlightStrength, light.x, light.y)
                                }
                            }
                        },
                    )
                    // Match GlassHostLayout.drawHiddenTabsBackdrop: a centred
                    // source row inset 4dp from the panel, not a full-size panel.
                    // Bound the lens depth separately so its bands cannot overlap.
                    ,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerTabs(
                    pages = pages,
                    selectedIndex = -1,
                    selectedColor = selectedColor,
                    unselectedColor = selectedColor,
                    onSelected = {},
                    interactive = false,
                    // This row is the invisible optical sampling layer. Its
                    // artwork must stay registered to the visible tabs while
                    // the indicator grows; only the sampled backdrop refracts.
                    iconSize = tabIconSize,
                    contentScale = contentScale,
                    iconOnly = iconOnly,
                )
            }

            if (insetIndicator != null) {
                // The child is measured with the REAL enlarged lens dimensions.
                // A requiredSize after drawBackdrop reports a height constrained
                // by the bar to the shader, then centres a larger child inside it.
                // That mismatch clips sampled artwork and exposes hard seams.
                ManagerIndicatorLayout(
                    position = settledIndex, verticalScale = { motion.scaleY },
                    tabs = pages.size, density = density.density,
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val rim = android.graphics.Path()
                            val transform = android.graphics.Matrix()
                            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                style = android.graphics.Paint.Style.STROKE
                                color = android.graphics.Color.BLACK
                                strokeWidth = io.github.offlineglass.rendering.BottomBarIndicatorStyle
                                    .expandedRimStrokeDp(isDark).dp.toPx()
                            }
                            onDrawWithContent {
                                drawContent()
                                val progress = pressProgress()
                                if (liquidOptics && outlineEnabled && progress > 0.001f) {
                                    insetIndicator.copyStretchedOutline(size,
                                        ManagerBottomBarGeometry.stretchX(motion.velocity),
                                        ManagerBottomBarGeometry.stretchY(motion.velocity), rim, transform)
                                    pen.alpha = io.github.offlineglass.rendering.BottomBarIndicatorStyle
                                        .expandedRimAlpha(isDark, progress)
                                    drawContext.canvas.nativeCanvas.drawPath(rim, pen)
                                }
                            }
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { insetIndicator },
                            effects = {
                                if (solidBarEnabled) {
                                    // Opaque mode uses only the selected-state fill.
                                } else if (liquidOptics) {
                                    val lensProgress = maxOf(pressProgress(),
                                        if (simplifyTabSwitch && pressedIndex == null && !dragging &&
                                            kotlin.math.abs(motion.position - selectedIndex) > 0.001f) 0.35f else 0f)
                                    managerLens(
                                        refractionHeight = 10.dp.toPx() * lensProgress,
                                        refractionAmount = 14.dp.toPx() * lensProgress,
                                        depthEffect = false,
                                        chromaticAberration = io.github.offlineglass.rendering.indicatorChromaticAberration(pressProgress()),
                                        insetIndicator = insetIndicator,
                                        postureBlend = if (dragging || pressedIndex != null) 0f
                                            else restingTintProgress() * pressProgress(),
                                    )
                                } else {
                                    // Plain pill, no backdrop blur — the
                                    // navigation text and icons are drawn above
                                    // this layer and must never blur through
                                    // the pill's backdrop sampling.
                                }
                            },
                            highlight = { iosIndicatorSpecular.copy(alpha = 0f) },
                            layerBlock = {
                                scaleX = ManagerBottomBarGeometry.stretchX(motion.velocity)
                                scaleY = ManagerBottomBarGeometry.stretchY(motion.velocity)
                            },
                            onDrawSurface = {
                                // Apply selection tint to the final clipped output, not to
                                // the smaller hidden sample (whose edge may refract).
                                if (liquidOptics) {
                                    val alpha = 0.10f * restingTintProgress()
                                    if (alpha > 0f) drawRect(if (isDark) Color.White else Color.Black, alpha = alpha)
                                }
                                if (outlineEnabled && liquidOptics && !solidBarEnabled && pressProgress() > 0.001f) {
                                    val light = (pillHighlight().style as BloomStroke).primaryLight.position
                                    val strength = io.github.offlineglass.rendering.indicatorHighlightAlpha(pressProgress(), isDark)
                                    // Light mode uses one black hairline on the final transformed outline.
                                    sliderEdge.draw(this, insetIndicator, isDark, strength * 2f * darkBarHighlightStrength,
                                        light.x, light.y, blackOutline = isDark)
                                }
                                if (solidBarEnabled) drawRect(containerColor)
                                if (!liquidOptics) drawRect(
                                    color = if (isDark) Color.White.copy(alpha = 0.10f)
                                    else Color.Black.copy(alpha = 0.10f),
                                )
                                if (indicatorInteractionLightEnabled && isDark && pressProgress() > 0.001f) drawRect(Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.14f * pressProgress().coerceIn(0f, 1f)), Color.Transparent),
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = size.maxDimension * 0.65f,
                                ))
                            },
                        )
                        ,
                )
                }
            }
            // In plain-blur mode the moving pill must sit below the artwork.
            // Redraw the static navigation once above it so selected labels and
            // icons never become part of the pill's blurred backdrop.
            if (!liquidOptics) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ManagerTabs(
                        pages = pages,
                        selectedIndex = selectedIndex,
                        selectedColor = selectedColor,
                        unselectedColor = unselectedColor,
                        onSelected = {},
                        interactive = false,
                        iconSize = tabIconSize,
                        contentScale = contentScale,
                        iconOnly = iconOnly,
                    )
                }
            }
            // One gesture owner for every slot; dragging must not be limited to
            // whichever moving lens happened to receive ACTION_DOWN.
            if (tabWidthPx > 0f) {
                Box(Modifier.fillMaxSize().managerSliderGesture()
                    .pointerInput(tabWidthPx, motion) {
                        val inset = with(density) { 4.dp.toPx() }
                        fun slot(x: Float) = ((x - inset) / tabWidthPx).toInt().coerceIn(0, pages.lastIndex)
                        awaitEachGesture {
                            // A single down/move/up owner, not two competing tap/drag
                            // detectors. Claim before the page-swipe observer's Main pass.
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val startIndex = slot(down.position.x)
                            val startPosition = motion.position
                            val startGeometry = ManagerBottomBarGeometry(
                                size.width.toFloat(), size.height.toFloat(), density.density, pages.size,
                                startPosition, motion.scaleY, density.fontScale,
                            )
                            val centre = startGeometry.left + startGeometry.lensWidth * 0.5f
                            val halfWidth = startGeometry.lensWidth * ManagerBottomBarGeometry.stretchX(motion.velocity) * 0.5f
                            val startedOnThumb = down.position.x in (centre - halfWidth)..(centre + halfWidth)
                            pressedIndex = startIndex.takeIf { startedOnThumb }
                            if (startedOnThumb) motion.press(liquidOptics)
                            dragIndex = if (startedOnThumb) startPosition else startIndex.toFloat()
                            down.consume()
                            var moved = false
                            var released = false
                            var upIndex = startIndex
                            var previousX = down.position.x
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = change.position.x - down.position.x
                                    if (kotlin.math.abs(dx) > viewConfiguration.touchSlop) moved = true
                                    if (moved && startedOnThumb) {
                                        dragging = true
                                        dragIndex = (startPosition + dx / tabWidthPx)
                                            .fastCoerceIn(0f, pages.lastIndex.toFloat())
                                        motion.dragTo(dragIndex, change.position.x - previousX)
                                    }
                                    previousX = change.position.x
                                    change.consume()
                                    if (!change.pressed) { upIndex = slot(change.position.x); released = true; break }
                                }
                            } finally {
                                pressedIndex = null
                                dragging = false
                                if (!released && startedOnThumb) motion.releaseTo(latestSelection.toFloat(), dragIndex)
                            }
                            if (released) {
                                if (startedOnThumb) {
                                    val target = dragIndex.roundToInt().coerceIn(0, pages.lastIndex)
                                    motion.releaseTo(target.toFloat(), if (moved) dragIndex else null)
                                    latestOnSelected(pages[target])
                                } else if (!moved && upIndex == startIndex) {
                                    motion.selectByTap(startIndex.toFloat(), latestRichTap)
                                    latestOnSelected(pages[startIndex])
                                }
                            } else dragIndex = latestSelection.toFloat()
                        }
                    })
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ManagerTabs(
    pages: List<ManagerPage>,
    selectedIndex: Int,
    selectedColor: Color,
    unselectedColor: Color,
    onSelected: (Int) -> Unit,
    onPressedChange: (Int, Boolean) -> Unit = { _, _ -> },
    interactive: Boolean,
    iconSize: androidx.compose.ui.unit.Dp,
    contentScale: Float,
    iconOnly: Boolean,
) {
    pages.forEachIndexed { index, page ->
        val selected = index == selectedIndex
        val interactionSource = remember(page) { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        LaunchedEffect(interactive, pressed) {
            if (interactive) onPressedChange(index, pressed)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (interactive) Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onSelected(index) } else Modifier,
                ),
            verticalArrangement = Arrangement.spacedBy(contentScale.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (selected) page.selectedIcon else page.unselectedIcon,
                contentDescription = managerText(page.title),
                modifier = Modifier.width(iconSize).height(iconSize),
                tint = if (selected) selectedColor else unselectedColor,
            )
            if (!iconOnly) Text(
                text = page.title,
                color = if (selected) selectedColor else unselectedColor,
                fontSize = (11f * contentScale).sp,
                lineHeight = (12f * contentScale).sp,
                maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ManagerIndicatorLayout(
    position: () -> Float,
    verticalScale: () -> Float,
    tabs: Int,
    density: Float,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { children, constraints ->
        val geometry = ManagerBottomBarGeometry(
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density,
            tabs, 0f, verticalScale(), 1f,
        )
        val lens = children.single().measure(Constraints.fixed(
            geometry.lensWidth.roundToInt().coerceAtLeast(1),
            geometry.lensHeight.roundToInt().coerceAtLeast(1),
        ))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Pure translation belongs to placement, not measurement. The lens
            // keeps its live texture and exact dimensions while moving.
            val left = geometry.left + position().coerceIn(0f, (tabs - 1).toFloat()) * geometry.slotWidth
            lens.place(left.roundToInt(), geometry.top.roundToInt())
        }
    }
}

@Stable
private class ManagerCombinedBackdrop(
    private val first: Backdrop,
    private val second: Backdrop,
) : Backdrop {
    override val isCoordinatesDependent: Boolean =
        first.isCoordinatesDependent || second.isCoordinatesDependent
    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}
