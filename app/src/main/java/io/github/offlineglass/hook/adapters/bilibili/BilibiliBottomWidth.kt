package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.TargetAdapter
import kotlin.math.roundToInt

internal fun TargetAdapter.twoTabCompoundWidth(
    config: GlassConfig, nativeSlots: Int, density: Float, height: Int, tabRowWidth: (Int) -> Int,
): Int? {
    val referenceWidth = tabRowWidth(4)
    val layout = barLayout(config, referenceWidth, height, density, nativeSlots) ?: return null
    if (layout.navigationIndices.size != 2) return null
    val actionAndGap = (referenceWidth - layout.mainWidth).roundToInt()
    return (tabRowWidth(2) + actionAndGap).coerceAtMost(referenceWidth)
}
