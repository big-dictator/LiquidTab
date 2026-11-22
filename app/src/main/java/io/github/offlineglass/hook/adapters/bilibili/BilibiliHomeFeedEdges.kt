package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Only the physical outside edges of the native Bilibili home grid. */
internal object BilibiliHomeFeedEdges {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binding: Binding? = null

    fun install(loader: ClassLoader, packageName: String) {
        val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
        val manager = recycler.getMethod("getLayoutManager")
        val insets = recycler.getDeclaredMethod("getItemDecorInsetsForChild", View::class.java)
        check(insets.returnType == Rect::class.java)
        val grid = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
        val state = Binding(packageName, manager, recycler.getMethod("invalidateItemDecorations"), grid)
        state.applyConfig(HookConfigReader.startupSnapshot(packageName))
        XposedBridge.framework().deoptimize(manager.returnType.getDeclaredMethod(
            "calculateItemDecorationsForChild", View::class.java, Rect::class.java))
        XposedBridge.hookMethod(insets, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.thisObject as ViewGroup
                val child = param.args[0] as View
                val offsets = param.result as? Rect ?: return
                state.trim(list, child, offsets)
            }
        })
        XposedBridge.hookMethod(recycler.getDeclaredMethod("onAttachedToWindow"), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                state.attach(param.thisObject as ViewGroup)
            }
        })
        XposedBridge.hookMethod(recycler.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    state.reconcilePadding(param.thisObject as ViewGroup)
                }
            })
        binding = state
    }

    fun configChanged(packageName: String, config: GlassConfig) {
        val state = binding?.takeIf { it.packageName == packageName } ?: return
        main.post { state.applyConfig(config) }
    }

    private class Binding(
        val packageName: String,
        val layoutManager: Method,
        val invalidateDecorations: Method,
        private val grid: Class<*>,
    ) {
        private data class FeedPadding(var left: Int, var right: Int, var applied: Boolean = false)
        private val count = grid.getMethod("getSpanCount")
        private val orientation = grid.getMethod("getOrientation")
        private val paramsAccess = HashMap<Class<*>, Pair<Method, Method>?>()
        private val feeds = WeakHashMap<ViewGroup, FeedPadding>()
        private var inlineComponentId = 0
        private var enabled = false
        private var reported = false

        fun attach(list: ViewGroup) {
            if (list.context.packageName != packageName) return
            val resources = list.resources
            val feedId = resources.getIdentifier("recycler_view", "id", packageName)
            if (feedId == View.NO_ID || feedId == 0 || list.id != feedId) return
            val pageId = resources.getIdentifier("primary_multi_page_layout", "id", packageName)
            val home = pageId != 0 && generateSequence(list.parent as? View) { it.parent as? View }.any { it.id == pageId }
            if (home) {
                inlineComponentId = resources.getIdentifier("inline_component", "id", packageName)
                val padding = feeds.getOrPut(list) { FeedPadding(list.paddingLeft, list.paddingRight) }
                if (enabled || padding.applied) requestInsets(list)
            } else {
                feeds.remove(list)?.let { restorePadding(list, it); requestInsets(list) }
            }
        }

        fun applyConfig(config: GlassConfig?) {
            val wanted = config?.let { it.enabled && it.bilibiliEdgeToEdgeFeed } == true
            if (enabled == wanted) return
            enabled = wanted
            feeds.keys.toList().forEach(::requestInsets)
        }

        private fun requestInsets(list: ViewGroup) {
            list.post {
                if (list.isAttachedToWindow) runCatching {
                    reconcilePadding(list)
                    invalidateDecorations.invoke(list)
                }
                    .onFailure { XposedBridge.log("LiquidTab Bilibili home feed insets: ${it.cause ?: it}") }
            }
        }

        fun reconcilePadding(list: ViewGroup) {
            val state = feeds[list] ?: return
            val useOuterEdges = enabled && runCatching { verticalGridSpanCount(list) == 2 }.getOrDefault(false)
            if (useOuterEdges) {
                if (!state.applied || list.paddingLeft != 0 || list.paddingRight != 0) {
                    if (!state.applied || list.paddingLeft != 0) state.left = list.paddingLeft
                    if (!state.applied || list.paddingRight != 0) state.right = list.paddingRight
                    state.applied = true
                    if (list.paddingLeft != 0 || list.paddingRight != 0)
                        list.setPadding(0, list.paddingTop, 0, list.paddingBottom)
                }
            } else {
                restorePadding(list, state)
            }
        }

        private fun restorePadding(list: ViewGroup, state: FeedPadding) {
            if (!state.applied) return
            state.applied = false
            val left = list.paddingLeft.takeIf { it != 0 } ?: state.left
            val right = list.paddingRight.takeIf { it != 0 } ?: state.right
            if (list.paddingLeft != left || list.paddingRight != right)
                list.setPadding(left, list.paddingTop, right, list.paddingBottom)
        }

        fun trim(list: ViewGroup, child: View, offsets: Rect) {
            if (!enabled || feeds[list]?.applied != true) return
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (offsets.left <= 0 && offsets.right <= 0 && params.leftMargin <= 0 && params.rightMargin <= 0) return
            val access = paramsAccess.getOrPut(params.javaClass) {
                runCatching {
                    params.javaClass.getMethod("getSpanIndex") to params.javaClass.getMethod("getSpanSize")
                }.getOrNull()
            } ?: return
            val index = runCatching { access.first.invoke(params) as Int }.getOrNull() ?: return
            val size = runCatching { access.second.invoke(params) as Int }.getOrNull() ?: return
            val oldLeft = offsets.left
            val oldRight = offsets.right
            if (size == 1 && index in 0..1) {
                val rtl = list.layoutDirection == View.LAYOUT_DIRECTION_RTL
                if ((index == 0) != rtl) offsets.left = minOf(0, oldLeft)
                else offsets.right = minOf(0, oldRight)
            } else if (size == 2 && index == 0 && inlineComponentId != 0 &&
                child is ViewGroup && (0 until child.childCount).any { child.getChildAt(it).id == inlineComponentId }) {
                // The wide inline card can also have margins on its item layout params.
                // Compensate in the decoration rect so recycled params stay native.
                offsets.left = minOf(oldLeft, -params.leftMargin.coerceAtLeast(0))
                offsets.right = minOf(oldRight, -params.rightMargin.coerceAtLeast(0))
            }
            if (!reported && (offsets.left != oldLeft || offsets.right != oldRight)) {
                reported = true
                android.util.Log.i("LiquidTabBiliFeed", "$packageName home grid outer offsets active")
            }
        }

        private fun verticalGridSpanCount(list: ViewGroup): Int? {
            val manager = layoutManager.invoke(list) ?: return null
            if (!grid.isInstance(manager) || orientation.invoke(manager) != 1) return null
            return count.invoke(manager) as Int
        }

    }
}
