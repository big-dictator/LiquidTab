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

    fun hasVisibleNativeContent(player: View): Boolean = listOf("g64", "fd7", "nbz", "g9t").any { entry ->
        val id = QqMusicNativeChrome.id(player, entry)
        val controls = player.findViewById<View>(id)
        controls != null && controls.isShown && controls.alpha > 0.001f &&
            controls.width > 0 && controls.height > 0
    }

    fun attach(shell: ViewGroup) {
        val player = QqMusicNativeChrome.player(shell) as? FrameLayout ?: return
        if (!player.isAttachedToWindow || !player.isShown || player.width <= 0 || player.height <= 0) return
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
        // d7: g5t is the background holder; g64 and other original controls follow it.
        // Insert before them, never above the native controls or their touch targets.
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

    fun parameters(player: View): FrameLayout.LayoutParams {
        // Match the extracted 46dp skin plate, with its original 14dp bottom offset.
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, QqMusicNativeChrome.dimension(player, "aiv", 46f)).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = QqMusicNativeChrome.dimension(player, "aim", 14f)
        }
    }

    fun updateGeometry(panel: GlassHostLayout) {
        val player = panel.adapterNavigationSource ?: return
        if (panel.parent !== player) return
        // Comments retain g5x while setting the actual controls GONE. Changing
        // visibility also discards the retained glass drawing immediately;
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
