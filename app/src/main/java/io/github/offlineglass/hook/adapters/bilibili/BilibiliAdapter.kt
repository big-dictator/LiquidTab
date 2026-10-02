package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Domestic Bilibili's app-owned bottom navigation policy and artwork. */
internal object BilibiliAdapter : TargetAdapter {
    override val key = "bilibili"
    override val ownsNavigationDrawing = true
    override val usesContentDarkMode = true
    override val sceneVisibilityAnimation = true
    override val drawsNavigationInOptics = true
    override val accentColor: Int = 0xFFFB7299.toInt()
    override val sourceIconScale = 1.15f
    override val sourceTextTranslationDp = 4f
    override val sourceIconTranslationDp = 0f
    override val snapIndicatorToSelectionOnRebind = true
    override val refreshHostGeometryOnRebind = true
    override fun screenReferenceHeight(rootHeight: Int?, displayHeight: Int): Int =
        maxOf(rootHeight ?: 0, displayHeight)

    const val PUBLISH_INDEX = 2
    const val SHOP_INDEX = 3
    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) =
        BilibiliRenderer.draw(canvas, frame)
    override fun hookSignals() = io.github.offlineglass.hook.adapters.bilibili.hookSignals()
}
