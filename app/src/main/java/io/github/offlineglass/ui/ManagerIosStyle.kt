package io.github.offlineglass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import io.github.offlineglass.ui.ios.LiquidSlider
import io.github.offlineglass.ui.ios.LiquidToggle
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import io.github.offlineglass.ui.ManagerMiuixText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

// Manager-only state: never included in target-app rendering configuration.
internal val LocalIosStyle = staticCompositionLocalOf { false }
internal val LocalIosDark = staticCompositionLocalOf { false }
internal val LocalIosSmoothing = staticCompositionLocalOf { -1f }
internal val LocalIosCardBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal class IosCaptureState {
    var recording = false
    var activeOpticalConsumers by mutableIntStateOf(0)
}
internal val LocalIosCaptureState = staticCompositionLocalOf<IosCaptureState?> { null }

// Match Miuix BasicComponent (the application-list ArrowPreference):
// headline1 size / Medium title, body2 size / normal secondary description.
@Composable
internal fun ManagerCardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
}

@Composable
internal fun ManagerCardDescription(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = MiuixTheme.textStyles.body2.fontSize,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun ManagerBottomBarVisibility(visible: Boolean, heightDp: Float, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        ) { content() }
    }
}

@Composable
internal fun ManagerFeedbackMaterialSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ExcludeControlCardTilt()
    val view = LocalView.current
    androidx.compose.material3.Switch(checked = checked, enabled = enabled, onCheckedChange = { next ->
        if (enabled && next != checked) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onCheckedChange(next)
        }
    })
}

internal fun Modifier.excludeIosGlassFromCapture(state: IosCaptureState?): Modifier =
    drawWithContent { if (state?.recording != true) drawContent() }

@Composable
internal fun ManagerStyleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ExcludeControlCardTilt()
    val view = LocalView.current
    val onChange: (Boolean) -> Unit = { next ->
        if (next != checked) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onCheckedChange(next)
        }
    }
    if (LocalIosStyle.current) {
        LiquidToggle(selected = { checked }, onSelect = onChange, modifier = Modifier.size(64.dp, 44.dp))
    } else {
        top.yukonga.miuix.kmp.basic.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ManagerStyleCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.defaultColors(),
    allowTilt: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val policy = remember { CardFeedbackPolicy() }
    val parents = LocalCardFeedbackPolicies.current
    val policies = remember(parents, policy) { parents + policy }
    val feedbackModifier = if (allowTilt && policy.controls == 0) modifier.managerCardFeedback() else modifier
    val cardContent: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalCardFeedbackPolicies provides policies) { content() }
    }
    if (!LocalIosStyle.current) {
        top.yukonga.miuix.kmp.basic.Card(modifier = feedbackModifier, colors = colors, content = cardContent)
    } else {
        // Exactly the shared bar's continuous path, not merely a round-rect.
        val smoothing = LocalIosSmoothing.current
        val shape = remember(smoothing) { BottomBarSquircleShape(CornerSize(22.dp), smoothing) }
        // Capture the full opaque card background separately from the controls.
        // Sampling a 6dp track leaves the enlarged glass thumb mostly outside
        // its source texture, producing black/transparent regions on press.
        val capture = remember { IosCaptureState() }
        // One owner records the source and registers its coordinates. Do not
        // attach another layerBackdrop or manually overwrite its GraphicsLayer.
        val cardBackdrop = rememberLayerBackdrop {
            drawRect(colors.color)
            if (capture.activeOpticalConsumers > 0) {
                capture.recording = true
                try { drawContent() } finally { capture.recording = false }
            }
        }
        // Retain the complete card output while LazyColumn only translates it.
        // Control/content invalidations still re-record both live optical passes;
        // scrolling alone must not traverse every card twice again.
        Box(feedbackModifier.graphicsLayer().clip(shape)) {
            // Background is ordinary paint; only the content Column below owns
            // the backdrop coordinates and glass-free source display list.
            Box(Modifier.matchParentSize().background(colors.color))
            CompositionLocalProvider(LocalIosCardBackdrop provides cardBackdrop, LocalIosCaptureState provides capture) {
                Column(Modifier.layerBackdrop(cardBackdrop), content = cardContent)
            }
        }
    }
}

@Composable
internal fun ManagerStyleSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    ExcludeControlCardTilt()
    val view = LocalView.current
    val onChange: (Boolean) -> Unit = { next ->
        if (enabled && next != checked) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onCheckedChange(next)
        }
    }
    if (!LocalIosStyle.current) {
        top.yukonga.miuix.kmp.preference.SwitchPreference(
            title = managerText(title), summary = summary?.let { managerText(it) }, checked = checked,
            onCheckedChange = onChange, enabled = enabled, startAction = startAction,
        )
    } else {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 14.dp).alpha(if (enabled) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            startAction?.invoke()
            Column(Modifier.weight(1f)) {
                ManagerCardTitle(title)
                if (!summary.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ManagerCardDescription(summary)
                }
            }
            if (enabled) LiquidToggle(selected = { checked }, onSelect = onChange,
                modifier = Modifier.size(64.dp, 44.dp))
            else LiquidToggle(selected = { checked }, onSelect = {},
                modifier = Modifier.size(64.dp, 44.dp).disabledIosControl())
        }
    }
}

@Composable
internal fun ManagerSliderGroup(content: @Composable ColumnScope.() -> Unit) {
    if (LocalIosStyle.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else {
        ManagerStyleCard(Modifier.fillMaxWidth(), allowTilt = false, content = content)
    }
}

@Composable
internal fun ManagerSliderFrame(content: @Composable ColumnScope.() -> Unit) {
    if (LocalIosStyle.current) ManagerStyleCard(Modifier.fillMaxWidth(), allowTilt = false) { ExcludeControlCardTilt(); content() }
    else Column(content = content)
}

private fun Modifier.disabledIosControl() = this.pointerInputDisabled()

// Consume disabled thumb events, while leaving vertical list scrolling intact.
private fun Modifier.pointerInputDisabled(): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
)

@Composable
internal fun ManagerStyleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    showKeyPoints: Boolean = false,
    detents: List<SliderDetent> = emptyList(),
) {
    ExcludeControlCardTilt()
    if (!LocalIosStyle.current) {
        top.yukonga.miuix.kmp.basic.Slider(
            value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange, showKeyPoints = showKeyPoints,
            modifier = Modifier.managerSliderGesture().sliderDetentTicks(detents, valueRange, value, 10f),
        )
    } else {
        LiquidSlider(value = { value }, onValueChange = onValueChange, valueRange = valueRange,
            visibilityThreshold = (valueRange.endInclusive - valueRange.start) * 0.001f,
            modifier = Modifier.managerSliderGesture().sliderDetentTicks(detents, valueRange, value, 20f),
            onValueChangeFinished = onValueChangeFinished)
    }
}
