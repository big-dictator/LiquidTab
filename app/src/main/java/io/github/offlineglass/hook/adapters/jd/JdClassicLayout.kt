package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD 16.1.0 la0.k.w/T: detach page sizing from the invisible native nav,
 * rather than repeatedly collapsing the native nav which JD restores itself. */
internal object JdClassicLayout {
    private data class Binding(val host: WeakReference<GlassHostLayout>, val content: View,
        val shell: View, val above: Int, val margin: Int, var active: Boolean = true)
    private val bindings = WeakHashMap<ViewGroup, Binding>()

    fun install(loader: ClassLoader) {
        // Native holder writes child LayoutParams before requesting a measure.
        // Correct the verified page before RelativeLayout builds its dependency
        // graph, rather than intercepting every View setter in the JD process.
        // Mutating the existing params here must NOT request another layout.
        val measure = runCatching { RelativeLayout::class.java.getDeclaredMethod(
            "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrElse {
            XposedBridge.log("LiquidTab JD parent-measure contract unavailable: $it")
            return
        }
        XposedBridge.framework().hook(measure)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            // Most calls belong to native cards, not the owned root. Avoid
            // allocating compatibility parameters/argument arrays for them.
            val binding = if (bindings.isEmpty()) null else bindings[chain.thisObject as ViewGroup]
            if (binding != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                binding.active && binding.host.get()?.isAttachedToWindow == true &&
                binding.content.parent === chain.thisObject && binding.shell.parent === chain.thisObject) {
                (binding.content.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
                    lp.removeRule(RelativeLayout.ABOVE)
                    lp.bottomMargin = 0
                }
            }
            chain.proceed()
        }
    }

    fun content(root: View): View? {
        if (root.javaClass.name != "com.jingdong.app.mall.MainFrameLayout" || root !is ViewGroup) return null
        // Reuse the verified content/shell pair across draws. Rewalking the
        // navigation descendants twice per frame adds work to every tab mount.
        bindings[root]?.let { binding ->
            val lp = binding.content.layoutParams as? RelativeLayout.LayoutParams
            if (binding.content.parent === root && binding.shell.parent === root &&
                lp != null && (lp.getRule(RelativeLayout.ABOVE) == 0 ||
                    lp.getRule(RelativeLayout.ABOVE) == binding.shell.id)) return binding.content
        }
        // Resource-holder class/field names change between JD releases. The
        // native ABI is structural: a direct vertical page container ABOVE
        // the direct shell that owns the five-button NavigationGroup.
        for (i in 0 until root.childCount) {
            val page = root.getChildAt(i) as? LinearLayout ?: continue
            val lp = page.layoutParams as? RelativeLayout.LayoutParams ?: continue
            val above = lp.getRule(RelativeLayout.ABOVE)
            if (above == 0) continue
            val shell = (0 until root.childCount).map(root::getChildAt).firstOrNull { it.id == above } ?: continue
            if (JdNavigationBinding.findInShell(shell) != null) return page
        }
        // Once detached from ABOVE, retain the verified identity via binding.
        bindings[root]?.content?.takeIf { it.parent === root }?.let { return it }
        return null
    }

    fun adjust(host: GlassHostLayout, nav: View, enabled: Boolean): Boolean {
        var ancestor = nav.parent as? View
        while (ancestor != null && ancestor.javaClass.name != "com.jingdong.app.mall.MainFrameLayout")
            ancestor = ancestor.parent as? View
        val root = ancestor as? ViewGroup ?: return false
        val page = content(root) ?: return false
        var shell = nav
        while (shell.parent !== root) shell = shell.parent as? View ?: return false
        val params = page.layoutParams as? RelativeLayout.LayoutParams ?: return false
        var binding = bindings[root]
        if (binding == null || binding.content !== page || binding.shell !== shell || binding.host.get() !== host) {
            if (params.getRule(RelativeLayout.ABOVE) != shell.id) return false
            binding = Binding(WeakReference(host), page, shell,
                params.getRule(RelativeLayout.ABOVE), params.bottomMargin)
            bindings[root] = binding
        }
        binding.active = enabled
        if (enabled) {
            apply(binding)
            // la0.k.w creates this anonymous 20dp NinePatch, ALIGN_BOTTOM to
            // the content, OUTSIDE the nav shell. It is not an icon or badge.
            val shadowHeight = (20f * root.resources.displayMetrics.density).toInt()
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val lp = child.layoutParams as? RelativeLayout.LayoutParams ?: continue
                if (child.javaClass === View::class.java && lp.getRule(RelativeLayout.ALIGN_BOTTOM) == page.id &&
                    kotlin.math.abs(lp.height - shadowHeight) <= 1 &&
                    child.background is android.graphics.drawable.NinePatchDrawable && child.alpha != 0f) child.alpha = 0f
            }
        } else restore(binding)
        return true
    }

    private fun apply(binding: Binding) {
        val page = binding.content
        val lp = page.layoutParams as? RelativeLayout.LayoutParams ?: return
        if (lp.getRule(RelativeLayout.ABOVE) != 0 || lp.bottomMargin != 0) {
            lp.removeRule(RelativeLayout.ABOVE)
            lp.bottomMargin = 0
            page.layoutParams = lp
        }
    }

    private fun restore(binding: Binding) {
        val lp = binding.content.layoutParams as? RelativeLayout.LayoutParams ?: return
        if (lp.getRule(RelativeLayout.ABOVE) != binding.above || lp.bottomMargin != binding.margin) {
            lp.addRule(RelativeLayout.ABOVE, binding.above)
            lp.bottomMargin = binding.margin
            binding.content.layoutParams = lp
        }
    }

    fun release(host: GlassHostLayout) {
        val iterator = bindings.entries.iterator()
        while (iterator.hasNext()) {
            val binding = iterator.next().value
            if (binding.host.get() === host || binding.host.get() == null) {
                binding.active = false
                restore(binding); iterator.remove()
            }
        }
    }
}
