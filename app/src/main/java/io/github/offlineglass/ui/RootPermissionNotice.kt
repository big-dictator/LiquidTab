/* Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * Dialog UI adapted from Kyant0/AndroidLiquidGlass Catalog DialogContent.kt.
 * Root probing and same-window integration added by LiquidTab.
 */
package io.github.offlineglass.ui


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import io.github.offlineglass.ui.ManagerMaterialText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/** Only probes identity; never changes USB, root configuration or services. */
private suspend fun rootStatus(): String? = withContext(Dispatchers.IO) {
    var process: Process? = null
    try {
        process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            "尚未确认 Root 授权。请在 Root 管理器中允许 LiquidTab，然后重新检测。"
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0 && Regex("\\buid=0\\b").containsMatchIn(output)) null
            else "LiquidTab 尚未获得 Root 权限。请在 KernelSU、Magisk 等 Root 管理器中允许本模块，然后重新检测。"
        }
    } catch (_: Exception) {
        "未能获得 Root 权限。请确认设备已 Root，并在 Root 管理器中允许 LiquidTab；普通设备无法仅通过授权启用 Root。"
    } finally {
        process?.destroy()
    }
}

@Composable
internal fun RootPermissionNotice(backdrop: Backdrop, dark: Boolean, blurRadius: Float,
    suppressed: Boolean = false,
    highlightStrength: Float = .5f,
    onVisibilityChange: (Boolean) -> Unit = {}) {
    var attempt by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    var denied by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            val resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (resumed && !active) denied = false
            active = resumed
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(attempt, active) {
        if (!active) return@LaunchedEffect
        do {
            checking = true
            val result = rootStatus()
            checking = false
            if (result == null) {
                denied = false
                message = null
            } else if (!denied || attempt > 0 && message != null) {
                message = result
                denied = true
            }
            delay(5000)
        } while (active)
    }
    val visibilityCallback by rememberUpdatedState(onVisibilityChange)
    val visible = message != null && !suppressed
    LaunchedEffect(visible) { visibilityCallback(visible) }
    if (!visible) return
    val body = message ?: return
    val dismiss = { message = null }
    ManagerGlassDialog(backdrop, dark, blurRadius, "请授予 Root 权限", body,
        "稍后", if (checking) "检测中…" else "重新检测",
        dismiss, { attempt++ }, confirmEnabled = !checking, accessibilityTitle = "Root 授权提醒",
        highlightStrength = highlightStrength)
}

@Composable
internal fun ManagerGlassDialog(
    backdrop: Backdrop, dark: Boolean, blurRadius: Float, title: String, body: String,
    cancelLabel: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true, accessibilityTitle: String = title,
    highlightStrength: Float = .5f,
) {
    BackHandler(onBack = onDismiss)
    val localizedTitle = managerText(accessibilityTitle)
    val ink = if (dark) Color.White else Color.Black
    val shape = BottomBarSquircleShape(CornerSize(48.dp), -1f)
    val dialogEdge = remember { ManagerGlassEdge() }
    val container = if (dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.6f)
    val accent = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    // Same-window modal: avoids the cross-window backdrop coordinate mismatch.
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF121212).copy(alpha = 0.56f) else Color(0xFF29293A).copy(alpha = 0.23f))
        .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(40.dp).widthIn(max = 400.dp).fillMaxWidth()
            .semantics { paneTitle = localizedTitle }
            .drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
                val blurPx = blurRadius.coerceAtLeast(0f).dp.toPx()
                renderEffect = io.github.offlineglass.rendering.NativeBackdropBlur.effect(blurPx).asComposeRenderEffect()
                downscaleFactor = 1
                padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(blurPx, padding)
                if (isRuntimeShaderSupported()) runtimeShaderEffect(
                    key = "RootDialogColorControls", shaderString = DIALOG_COLOR_SHADER, uniformShaderName = "content"
                ) { setFloatUniform("brightness", if (dark) 0f else 0.2f) }
                managerLens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
            }, highlight = { iosIndicatorSpecular.copy(alpha = 0f) }, onDrawSurface = {
                drawRect(container)
                dialogEdge.draw(this, shape, dark, (if (dark) .30f else .40f) * 2f * highlightStrength)
            })
            .clickable {}.padding(24.dp)) {
            Text(title, color = ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            Text(body, color = ink.copy(alpha = 0.68f), fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RootGlassButton(cancelLabel, backdrop, blurRadius, Color(0xFFE5E5E5), Color(0xFF242424),
                    Modifier.weight(1f), highlightStrength = highlightStrength, onClick = onDismiss)
                RootGlassButton(confirmLabel, backdrop, blurRadius,
                    accent, Color.White, Modifier.weight(1f), enabled = confirmEnabled,
                    highlightStrength = highlightStrength, onClick = onConfirm)
            }
        }
    }
}

/** Catalog LiquidButton's 48dp lens surface and spring press enlargement. */
@Composable
private fun RootGlassButton(
    label: String, backdrop: Backdrop, blurRadius: Float, tint: Color, ink: Color,
    modifier: Modifier = Modifier, enabled: Boolean = true, highlightStrength: Float, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val progress by animateFloatAsState(if (enabled && pressed) 1f else 0f,
        spring(dampingRatio = 0.65f, stiffness = 420f), label = "rootDialogButtonPress")
    val shape = BottomBarSquircleShape(CornerSize(24.dp), LocalIosSmoothing.current)
    val view = LocalView.current
    val buttonEdge = remember { ManagerGlassEdge() }
    val dark = LocalIosDark.current
    Box(modifier.height(48.dp).drawBackdrop(
        backdrop = backdrop, shape = { shape }, effects = {
            val px = blurRadius.coerceAtLeast(0f).dp.toPx()
            renderEffect = io.github.offlineglass.rendering.NativeBackdropBlur.effect(px).asComposeRenderEffect()
            downscaleFactor = 1
            padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(px, padding)
            managerLens(12.dp.toPx(), 24.dp.toPx())
        }, highlight = { iosIndicatorSpecular.copy(alpha = 0f) },
        layerBlock = {
            val scale = 1f + (4.dp.toPx() / size.height) * progress
            scaleX = scale
            scaleY = scale
        }, onDrawSurface = {
            drawRect(tint.copy(alpha = 0.72f))
            if (progress > 0.001f) drawRect(Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.22f * progress.coerceIn(0f, 1f)), Color.Transparent),
                Offset(size.width / 2f, size.height / 2f), size.maxDimension * 0.7f))
            buttonEdge.draw(this, shape, dark, (.06f + .10f * progress) * 2f * highlightStrength)
        }
    ).clip(shape).clickable(interactionSource = source, indication = null,
        enabled = enabled, role = Role.Button) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        onClick()
    }, contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = 16.sp)
    }
}

private const val DIALOG_COLOR_SHADER = """
uniform shader content;
uniform float brightness;
half4 main(float2 p) {
    half4 c = content.eval(p);
    float l = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));
    return half4(clamp(mix(float3(l), float3(c.rgb), 1.5) + brightness * c.a, 0.0, c.a), c.a);
}
"""
