package io.github.offlineglass.hook.adapters.qqmusic

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** An independent panel beneath the original player; the original views retain all input. */
internal object QqMusicPlayerGlass {
    private val panels = WeakHashMap<ViewGroup, WeakReference<GlassHostLayout>>()

    /**
     * Native content is the visible player control surface (the minibar root and any
     * inflated content slot). The wide skin plate holder and the tiny promo strip are
     * not content. All checks are structural: no obfuscated slot ids are consulted.
     */
    fun hasVisibleNativeContent(player: View): Boolean {
        if (player !is ViewGroup) return false
        val density = player.resources.displayMetrics.density
        for (index in 0 until player.childCount) {
            val child = player.getChildAt(index)
            if (child is GlassHostLayout) continue
            if (isSkinHolder(child)) continue
            val w = child.width
            val h = child.height
            // The promo strip is a shallow fixed-height float, not the control surface.
            if (w > 0 && h > 0 && h <= 24f * density && w <= 160f * density) continue
            if (child.isShown && child.alpha > 0.001f && w > 0 && h > 0) return true
        }
        return false
    }

    private fun isSkinHolder(view: View): Boolean {
        if (view.javaClass.name == QqMusicBinding.SKIN_PLATE_CLASS) return true
        if (view !is ViewGroup) return false
        for (i in 0 until view.childCount) {
            if (view.getChildAt(i).javaClass.name == QqMusicBinding.SKIN_PLATE_CLASS) return true
        }
        return false
    }

    fun attach(shell: ViewGroup) {
        val player = QqMusicNativeChrome.player(shell) as? FrameLayout ?: return
        if (!player.isAttachedToWindow || !player.isShown || player.width <= 0 || player.height <= 0) return
        releaseShadowClipping(player, shell)
        panels[player]?.get()?.takeIf { it.parent === player }?.let { return }
        // A detach/re-attach can dispose a renderer without replacing its player root.
        for (index in player.childCount - 1 downTo 0) {
            val child = player.getChildAt(index)
            if (child is GlassHostLayout) player.removeViewAt(index)
        }
        val config = HookConfigReader.read(player.context, targetSpec.packageName)
        if (!config.enabled) return
        val panel = GlassHostLayout(
            player.context, config, 1, nativeSelectionReliable = false,
            navigationSource = player, sliderEnabled = false,
        ).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 0f
            translationZ = 0f
        }
        player.clipChildren = false
        player.clipToPadding = false
        // The background holder leads; original controls follow it. Insert before
        // them, never above the native controls or their touch targets.
        player.addView(panel, 0, parameters(player))
        panels[player] = WeakReference(panel)
        player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                panels.remove(player)
                player.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun releaseShadowClipping(player: ViewGroup, shell: ViewGroup) {
        // The outer shell frame also clips the player's overflow.
        // Reassert before the cached-panel return: native layout may reset flags.
        fun release(group: ViewGroup) {
            if (group.clipChildren) group.clipChildren = false
            if (group.clipToPadding) group.clipToPadding = false
            if (group.clipToOutline) group.clipToOutline = false
        }
        release(player)
        release(shell)
        val outer = shell.parent as? ViewGroup ?: return
        if (outer.javaClass.name == QqMusicBinding.SHELL_CLASS) {
            release(outer)
        }
    }

    fun parameters(player: View): FrameLayout.LayoutParams {
        // Match the extracted skin plate geometry (height and bottom offset).
        val metrics = QqMusicBinding.plateMetrics(player)
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, metrics.heightPx).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = metrics.bottomMarginPx
        }
    }

    fun updateGeometry(panel: GlassHostLayout) {
        val player = panel.adapterNavigationSource ?: return
        if (panel.parent !== player) return
        // Changing visibility discards the retained glass drawing immediately;
        // a draw-time early return alone can leave the previous RenderNode frame.
        if (!hasVisibleNativeContent(player)) {
            if (panel.visibility != View.INVISIBLE) panel.visibility = View.INVISIBLE
        } else if (panel.visibility == View.INVISIBLE) {
            panel.visibility = View.VISIBLE
        }
        val expected = parameters(player)
        val actual = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (actual.width != expected.width || actual.height != expected.height ||
            actual.bottomMargin != expected.bottomMargin || actual.gravity != expected.gravity) {
            panel.layoutParams = expected
        }
    }
}
