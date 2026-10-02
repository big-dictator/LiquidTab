package io.github.offlineglass.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
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
private fun Path.addBottomBarSquircle(
    width: Float,
    height: Float,
    radius: Float,
    smoothing: Float,
) {
    val strength = smoothing.coerceIn(-1f, 1f)
    val cornerRadius = radius.coerceAtLeast(0f).coerceAtMost(min(width, height) * 0.5f)
    if (cornerRadius <= 0f) {
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, width, height))
        return
    }
    if (strength < 0f) {
        val androidPath = RoundedPolygon(
            vertices = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height),
            rounding = CornerRounding(cornerRadius, -strength),
            centerX = width * 0.5f,
            centerY = height * 0.5f,
        ).toPath()
        addPath(androidPath.asComposePath())
        return
    }
    val extent = (radius * (1f + 0.2f * strength)).coerceAtMost(min(width, height) * 0.5f)
    if (extent <= 0f) {
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, width, height))
        return
    }
    val controlRatio = 0.448f - 0.182f * strength
    val control = extent * controlRatio
    moveTo(extent, 0f)
    lineTo(width - extent, 0f)
    cubicTo(width - control, 0f, width, control, width, extent)
    lineTo(width, height - extent)
    cubicTo(width, height - control, width - control, height, width - extent, height)
    lineTo(extent, height)
    cubicTo(control, height, 0f, height - control, 0f, height - extent)
    lineTo(0f, extent)
    cubicTo(0f, control, control, 0f, extent, 0f)
    close()
}

private class BottomBarSquircleShape(
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
private val iosIndicatorSpecular: Highlight = Highlight(
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
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
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

/**
 * The manager uses the same Backdrop -> vibrancy -> blur -> lens pipeline as the
 * injected application bar.  It is intentionally not a separately painted mock.
 */
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
    outlineEnabled: Boolean = true,
    blurRadius: Float = 2f,
    cornerRadiusPercent: Float = 50f,
    cornerSmoothing: Float = -1f,
    barHeight: Float = 56f,
    darkBarHighlightStrength: Float = 0.5f,
    lightAlpha: Float = 0.40f,
    darkAlpha: Float = 0.40f,
    classicNavigation: Boolean = false,
) {
    val density = LocalDensity.current
    val managerBlurPx = with(density) { blurRadius.dp.toPx() }
    // Keep sampling in source pixel coordinates. The custom blur DSL snaps its
    // downscaled input to a coarse grid, which shimmers on slowly moving text.
    // HWUI performs its own GPU blur optimization without that manual grid.
    val managerBlurEffect = remember(managerBlurPx) {
        io.github.offlineglass.rendering.NativeBackdropBlur.effect(managerBlurPx).asComposeRenderEffect()
    }
    val view = LocalView.current
    val screenHeightPx = (view.rootView.height.takeIf { it > 0 }
        ?: view.resources.displayMetrics.heightPixels).coerceAtLeast(1)
    val bottomGap = with(density) { (screenHeightPx * 68f / 2656f).toDp() + 10.dp } *
        if (classicNavigation) 2 else 1
    val pages = ManagerPage.entries
    val isDark = containerColor.luminance() < 0.5f
    val configuredFill = if (isDark) Color(0xFF211F26) else Color.White
    val configuredAlpha = (if (isDark) darkAlpha else lightAlpha).coerceIn(0f, 0.50f)
    val barWidth = (pages.size * tabWidthDp.coerceIn(64f, 92f)).dp
    val selectedIndex = pages.indexOf(selectedPage).coerceAtLeast(0)
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = remember(backdrop, tabsBackdrop) {
        ManagerCombinedBackdrop(backdrop, tabsBackdrop)
    }
    val normalizedHeight = barHeight.coerceIn(56f, 64f)
    val heightScale = normalizedHeight / 64f
    val indicatorHeight = (normalizedHeight - 8f).dp
    val barCornerDp = normalizedHeight * cornerRadiusPercent.coerceIn(15f, 50f) / 100f
    val barShape = remember(barCornerDp, cornerSmoothing) {
        BottomBarSquircleShape(
            CornerSize(barCornerDp.dp),
            cornerSmoothing.coerceIn(-1f, 1f),
        )
    }

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    val pressProgress by animateFloatAsState(
        targetValue = if (liquidGlassEnabled && (dragging || pressedIndex != null)) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "managerGlassPress",
    )
    val settledIndex by animateFloatAsState(
        targetValue = if (dragging) dragIndex else (pressedIndex ?: selectedIndex).toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "managerGlassSelection",
    )
    LaunchedEffect(selectedIndex) {
        if (!dragging) dragIndex = selectedIndex.toFloat()
    }

    // Gravity-rotated speculars (KernelSU port): only subscribe to the tilt
    // sensor while liquid glass is on — plain/solid modes draw no stroke.
    val baseHighlight = if (liquidGlassEnabled) {
        rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    } else {
        iosIndicatorSpecular
    }
    val pillHighlight = if (liquidGlassEnabled) {
        rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)
    } else {
        iosIndicatorSpecular
    }
    val outerLight = (baseHighlight.style as BloomStroke).primaryLight.position

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
                .widthIn(max = 380.dp)
                .height(normalizedHeight.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        tabWidthPx = (it.size.width - with(density) { 8.dp.roundToPx() }) /
                            pages.size.toFloat()
                    }
                    .dropShadow(
                        shape = barShape,
                        shadow = Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = if (isDark) 0.14f else 0.19f,
                        ),
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { barShape },
                        effects = {
                            if (!solidBarEnabled) {
                                renderEffect = managerBlurEffect
                                downscaleFactor = 1
                                padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(managerBlurPx, padding)
                            }
                            if (liquidGlassEnabled && !solidBarEnabled) {
                                managerLens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            }
                        },
                        highlight = {
                            // The manager draws its outer specular below using the
                            // actual squircle path. miuix-blur's generic highlight
                            // renderer reduces Generic outlines to a rounded box.
                            baseHighlight.copy(alpha = 0f)
                        },
                        onDrawSurface = {
                            drawRect(if (solidBarEnabled) containerColor else configuredFill.copy(alpha = configuredAlpha))
                        },
                    )
                    .drawWithCache {
                        val path = Path().apply {
                            addBottomBarSquircle(
                                width = size.width,
                                height = size.height,
                                radius = size.minDimension *
                                    (cornerRadiusPercent.coerceIn(15f, 50f) / 100f),
                                smoothing = cornerSmoothing,
                            )
                        }
                        val dx = outerLight.x - LIGHT_REF_X
                        val dy = outerLight.y - LIGHT_REF_Y
                        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                        val ux = dx / length
                        val uy = dy / length
                        val reach = size.maxDimension * 0.58f
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (isDark) 0.07f * darkBarHighlightStrength else 0.10f),
                                Color.White.copy(alpha = if (isDark) 0.50f * darkBarHighlightStrength else 0.68f),
                                Color.White.copy(alpha = if (isDark) 0.11f * darkBarHighlightStrength else 0.16f),
                                Color.Transparent,
                            ),
                            start = center - Offset(ux * reach, uy * reach),
                            end = center + Offset(ux * reach, uy * reach),
                        )
                        val stroke = Stroke(width = 1.dp.toPx())
                        onDrawWithContent {
                            drawContent()
                            if (liquidGlassEnabled && outlineEnabled && !solidBarEnabled) {
                                drawPath(path = path, brush = brush, style = stroke)
                            }
                        }
                    }
                    .then(
                        if (!liquidGlassEnabled && outlineEnabled) Modifier.border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.32f)
                            else Color.Black.copy(alpha = 0.24f),
                            shape = barShape,
                        ) else Modifier,
                    )
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerTabs(
                    pages = pages,
                    selectedIndex = selectedIndex,
                    selectedColor = selectedColor,
                    unselectedColor = unselectedColor,
                    onSelected = { onSelectedPage(pages[it]) },
                    onPressedChange = { index, pressed ->
                        if (pressed) pressedIndex = index
                        else if (pressedIndex == index) pressedIndex = null
                    },
                    interactive = true,
                    tabScale = heightScale,
                )
            }

            // The invisible tab layer is sampled together with the page backdrop,
            // matching the injected bar's combined-backdrop indicator.
            Row(
                modifier = Modifier
                    .alpha(0f)
                    .fillMaxWidth()
                    .layerBackdrop(tabsBackdrop)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { barShape },
                        effects = {
                            renderEffect = managerBlurEffect
                            downscaleFactor = 1
                            padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(managerBlurPx, padding)
                            if (liquidGlassEnabled) managerLens(24.dp.toPx(), 24.dp.toPx())
                        },
                        onDrawSurface = { drawRect(configuredFill.copy(alpha = configuredAlpha)) },
                    )
                    .height(indicatorHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerTabs(
                    pages = pages,
                    selectedIndex = -1,
                    selectedColor = selectedColor,
                    unselectedColor = selectedColor,
                    onSelected = {},
                    interactive = false,
                    tabScale = heightScale * (1f + 0.20f * pressProgress),
                )
            }

            if (tabWidthPx > 0f) {
                val widthDp = with(density) { tabWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            translationX = settledIndex * tabWidthPx
                        }
                        .pointerInput(tabWidthPx, pages.size) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragCancel = {
                                    dragging = false
                                    dragIndex = selectedIndex.toFloat()
                                },
                                onDragEnd = {
                                    val target = dragIndex.roundToInt().coerceIn(0, pages.lastIndex)
                                    dragging = false
                                    dragIndex = target.toFloat()
                                    onSelectedPage(pages[target])
                                },
                            ) { change, amount ->
                                change.consume()
                                dragIndex = (dragIndex + amount.x / tabWidthPx)
                                    .fastCoerceIn(0f, pages.lastIndex.toFloat())
                            }
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { barShape },
                            effects = {
                                if (solidBarEnabled) {
                                    // Opaque mode uses only the selected-state fill.
                                } else if (liquidGlassEnabled) {
                                    managerLens(
                                        refractionHeight = 10.dp.toPx() * pressProgress,
                                        refractionAmount = 14.dp.toPx() * pressProgress,
                                        depthEffect = true,
                                        chromaticAberration = 0.5f,
                                    )
                                } else {
                                    // Plain pill, no backdrop blur — the
                                    // navigation text and icons are drawn above
                                    // this layer and must never blur through
                                    // the pill's backdrop sampling.
                                }
                            },
                            highlight = {
                                pillHighlight.copy(
                                    alpha = if (outlineEnabled) {
                                        pressProgress * if (isDark) 0.48f else 1f
                                    } else {
                                        0f
                                    },
                                )
                            },
                            layerBlock = {
                                val scale = 1f + (78f / 56f - 1f) * pressProgress
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = {
                                if (solidBarEnabled) drawRect(containerColor)
                                drawRect(
                                    color = if (isDark) Color.White.copy(alpha = 0.10f)
                                    else Color.Black.copy(alpha = 0.10f),
                                    alpha = 1f - pressProgress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * pressProgress))
                            },
                        )
                        .height(indicatorHeight)
                        .width(widthDp),
                )
            }
            // In plain-blur mode the moving pill must sit below the artwork.
            // Redraw the static navigation once above it so selected labels and
            // icons never become part of the pill's blurred backdrop.
            if (!liquidGlassEnabled) {
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
                        tabScale = heightScale,
                    )
                }
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
    tabScale: Float = 1f,
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
                .graphicsLayer {
                    scaleX = tabScale
                    scaleY = tabScale
                }
                .then(
                    if (interactive) Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onSelected(index) } else Modifier,
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (selected) page.selectedIcon else page.unselectedIcon,
                contentDescription = page.title,
                tint = if (selected) selectedColor else unselectedColor,
            )
            Text(
                text = page.title,
                color = if (selected) selectedColor else unselectedColor,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
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
