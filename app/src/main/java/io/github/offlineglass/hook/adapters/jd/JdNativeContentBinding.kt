package io.github.offlineglass.hook.adapters.jd

import android.graphics.RenderNode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD's newer holder mounts a full-window page inside a native blur source. */
internal object JdNativeContentBinding {
    private const val BLUR_PACKAGE = "com.jingdong.common.unification.jdBlur"
    private const val NAV_BOX = "com.jingdong.app.mall.navigationbar.refactor.view.NavBoxContainer"
    private data class Binding(
        val frame: WeakReference<ViewGroup>, val target: WeakReference<ViewGroup>,
        val page: WeakReference<LinearLayout>, val rowParent: WeakReference<ViewGroup>,
        val box: WeakReference<ViewGroup>, val shell: WeakReference<ViewGroup>, val frameChildren: Int,
    )
    private var targetType: Class<*>? = null
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val retryAt = WeakHashMap<ViewGroup, Long>()

    fun install(loader: ClassLoader) {
        targetType = runCatching {
            val target = Class.forName("$BLUR_PACKAGE.JdBlurTarget", false, loader)
            check(FrameLayout::class.java.isAssignableFrom(target) &&
                target.getMethod("getBlurRenderNode").returnType == RenderNode::class.java)
            target
        }.onFailure { XposedBridge.log("LiquidTab JD native content contract unavailable: $it") }.getOrNull()
    }

    fun content(row: ViewGroup): LinearLayout? {
        val type = targetType ?: return null
        bindings[row]?.let { binding ->
            val frame = binding.frame.get()
            val target = binding.target.get()
            val page = binding.page.get()
            val box = binding.box.get()
            val shell = binding.shell.get()
            if (frame != null && target != null && page != null && row.isAttachedToWindow &&
                box != null && shell != null && row.parent === binding.rowParent.get() &&
                box.parent === shell && shell.parent === frame &&
                frame.childCount == binding.frameChildren && target.parent === frame &&
                target.childCount == 1 && page.parent === target) return page
            bindings.remove(row)
        }
        val now = SystemClock.uptimeMillis()
        if (now < (retryAt[row] ?: 0L)) return null
        val result = discover(row, type)
        if (result == null) retryAt[row] = now + 350L else {
            retryAt.remove(row)
            bindings[row] = result
        }
        return result?.page?.get()
    }

    private fun discover(row: ViewGroup, type: Class<*>): Binding? {
        if (JdNavigationBinding.resolve(row) == null) return rejected(2)
        val frame = JdNavigationBinding.mainFrame(row) ?: return rejected(3)
        val rowParent = row.parent as? ViewGroup ?: return null
        var ancestor = rowParent
        var box: ViewGroup? = null
        while (ancestor !== frame) {
            if (ancestor.javaClass.name == NAV_BOX) {
                if (box != null) return rejected(4)
                box = ancestor
            }
            ancestor = ancestor.parent as? ViewGroup ?: return null
        }
        val shell = box?.parent as? ViewGroup ?: return rejected(4)
        if (shell.parent !== frame || frame.childCount > 32) return rejected(4)
        var target: ViewGroup? = null
        for (index in 0 until frame.childCount) {
            val child = frame.getChildAt(index) as? ViewGroup ?: continue
            if (!type.isInstance(child)) continue
            if (target != null) return rejected(5)
            target = child
        }
        target ?: return rejected(6)
        val targetParams = target.layoutParams as? RelativeLayout.LayoutParams ?: return rejected(7)
        if (!fillsParent(targetParams) || targetParams.getRule(RelativeLayout.ABOVE) != 0 ||
            target.childCount != 1) return rejected(8)
        val page = target.getChildAt(0) as? LinearLayout ?: return rejected(9)
        val pageParams = page.layoutParams as? FrameLayout.LayoutParams ?: return rejected(10)
        if (page.orientation != LinearLayout.VERTICAL || !fillsParent(pageParams)) return rejected(11)
        if (android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.contentBinding", 0)
        return Binding(WeakReference(frame), WeakReference(target), WeakReference(page), WeakReference(rowParent),
            WeakReference(box), WeakReference(shell), frame.childCount)
    }

    private fun fillsParent(params: ViewGroup.MarginLayoutParams): Boolean =
        params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            params.leftMargin == 0 && params.rightMargin == 0 && params.topMargin == 0 && params.bottomMargin == 0

    fun scene(row: ViewGroup): View? {
        val page = content(row) ?: return null
        val target = page.parent as? ViewGroup ?: return null
        return page.takeIf {
            target.background == null && target.foreground == null && target.alpha == 1f &&
                target.matrix.isIdentity && target.clipBounds == null && !target.clipToOutline &&
                target.scrollX == 0 && target.scrollY == 0 && it.isShown && it.alpha == 1f &&
                it.matrix.isIdentity && it.left == 0 && it.top == 0 && it.width == target.width &&
                it.height == target.height && it.width > 0 && it.height > 0
        } ?: rejected(12)
    }

    private fun rejected(reason: Long): Nothing? {
        if (android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.contentBinding", reason)
        return null
    }
}
