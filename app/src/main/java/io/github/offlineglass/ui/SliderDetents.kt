package io.github.offlineglass.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.offlineglass.ui.ManagerMaterialText as Text
import androidx.compose.foundation.layout.fillMaxWidth
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SliderDetent(val value: Float, val label: String)
internal val viscosityDetents = listOf(
    SliderDetent(0f, "Alcohol"), SliderDetent(50f, "Oil"), SliderDetent(100f, "Honey"),
)

/** Raw pointer travel stays separate from snapping, so every detent can be escaped. */
internal class SliderDetentController(
    private val marks: List<SliderDetent>, range: ClosedFloatingPointRange<Float>, initial: Float,
) {
    data class Result(val value: Float, val haptic: Boolean)
    private val enter = (range.endInclusive - range.start) * 0.015f
    private val leave = (range.endInclusive - range.start) * 0.025f
    private var previous = initial
    private var held = marks.minByOrNull { abs(initial - it.value) }?.value?.takeIf { abs(initial - it) <= enter }
    private var signalled = held

    fun move(raw: Float): Result {
        if (signalled != null && abs(raw - signalled!!) > leave) signalled = null
        if (held != null && abs(raw - held!!) > leave) held = null
        if (held == null) held = marks.minByOrNull { abs(raw - it.value) }?.value?.takeIf { abs(raw - it) <= enter }
        val crossed = marks.firstOrNull {
            (previous < it.value && raw >= it.value) || (previous > it.value && raw <= it.value)
        }?.value
        val reached = held ?: crossed
        val haptic = reached != null && reached != signalled
        if (haptic) signalled = reached
        previous = raw
        return Result(held ?: raw, haptic)
    }
}

internal fun verifyViscosityDetents() {
    val drag = SliderDetentController(viscosityDetents, 0f..100f, 25f)
    val enter = drag.move(49f)
    check(enter.value == 50f && enter.haptic)
    check(drag.move(51f).let { it.value == 50f && !it.haptic })
    check(drag.move(53f).value == 53f)
    check(drag.move(51f).haptic)
    check(drag.move(0f).let { it.value == 0f && it.haptic })
    check(drag.move(1f).let { it.value == 0f && !it.haptic })
    check(drag.move(3f).value == 3f)
    check(drag.move(100f).let { it.value == 100f && it.haptic })
    check(!drag.move(99f).haptic)
    check(drag.move(96f).value == 96f)
}

@Composable
internal fun Modifier.sliderDetentTicks(
    marks: List<SliderDetent>, range: ClosedFloatingPointRange<Float>, value: Float, insetDp: Float,
): Modifier {
    if (marks.isEmpty()) return this
    val color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f)
    return drawWithContent {
        drawContent()
        val inset = insetDp.dp.toPx().coerceAtMost(size.width / 2f)
        val span = (range.endInclusive - range.start).coerceAtLeast(1f)
        for (mark in marks) {
            // Leave the liquid thumb itself untouched.
            if (abs(value - mark.value) <= span * 0.035f) continue
            val position = inset + (size.width - 2f * inset) * ((mark.value - range.start) / span)
            val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - position else position
            drawLine(color, Offset(x, size.height / 2f - 2.dp.toPx()),
                Offset(x, size.height / 2f + 2.dp.toPx()), 1.dp.toPx())
        }
    }
}

@Composable
internal fun SliderDetentLabels(marks: List<SliderDetent>, range: ClosedFloatingPointRange<Float>, insetDp: Float) {
    if (marks.isEmpty()) return
    val color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
    Layout(content = { marks.forEach { Text(it.label, color = color, fontSize = 12.sp, maxLines = 1) } },
        modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val height = (labels.maxOfOrNull { it.height } ?: 0) + 4.dp.roundToPx()
        val inset = insetDp.dp.toPx().coerceAtMost(width / 2f)
        val span = (range.endInclusive - range.start).coerceAtLeast(1f)
        layout(width, height) {
            labels.forEachIndexed { index, label ->
                val center = inset + (width - 2f * inset) * ((marks[index].value - range.start) / span)
                val x = (center - label.width / 2f).roundToInt().coerceIn(0, (width - label.width).coerceAtLeast(0))
                label.placeRelative(x, 2.dp.roundToPx())
            }
        }
    }
}
