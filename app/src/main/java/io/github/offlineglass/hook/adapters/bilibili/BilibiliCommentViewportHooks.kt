package io.github.offlineglass.hook.adapters.bilibili

import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Let the native list draw behind the now-transparent input, while retaining its touch layout. */
internal object BilibiliCommentViewportHooks {
    private val listDepth = ThreadLocal.withInitial { 0 }
    fun install(loader: ClassLoader) {
        val binding = BilibiliCommentBinding.current ?: return
        if (!BilibiliCommentGlass.available()) return
        XposedBridge.hookMethod(binding.list, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { listDepth.set((listDepth.get() ?: 0)+1) }
            override fun afterHookedMethod(param: MethodHookParam) {
                val depth=(listDepth.get() ?: 1)-1
                if (depth <= 0) listDepth.remove() else listDepth.set(depth)
            }
        })
        installLazyList(loader)
    }

    /** 9.15 replaced the pull-to-refresh wrapper with a directly composed LazyColumn. */
    private fun installLazyList(loader: ClassLoader) {
        runCatching {
            val size = loader.loadClass("androidx.compose.foundation.layout.SizeKt")
            val requiredHeight = size.declaredMethods.single {
                it.name.startsWith("requiredHeight-") && it.parameterTypes.size == 2
            }
            val wrapHeight = size.declaredMethods.single { it.name == "wrapContentHeight" && it.parameterTypes.size == 3 }
            val alignment = loader.loadClass("androidx.compose.ui.Alignment").getDeclaredField("Companion").get(null)
            val top = XposedHelpers.callMethod(alignment, "getTop")
            val localView = loader.loadClass("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
                .getDeclaredMethod("getLocalView").invoke(null)
            val paddingType = loader.loadClass("androidx.compose.foundation.layout.PaddingValues")
            val lazy = loader.loadClass("androidx.compose.foundation.lazy.LazyDslKt")
            XposedBridge.hookAllMethods(lazy, "LazyColumn", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size != 13) return
                    if ((listDepth.get() ?: 0) <= 0) return
                    runCatching {
                        val view = XposedHelpers.callMethod(param.args[10], "consume", localView) as? View ?: return
                        var ancestor: View? = view
                        var container: View? = null
                        while (ancestor != null) {
                            val current = ancestor
                            if (current.id != View.NO_ID && runCatching {
                                    current.resources.getResourceEntryName(current.id)
                                }.getOrNull() == "comment_container") { container = current; break }
                            ancestor = current.parent as? View
                        }
                        val density = view.resources.displayMetrics.density
                        val modifier = param.args[0] ?: return
                        val listKey = param.args[1] ?: param.args[9]
                        if (container != null) {
                            val height = BilibiliCommentGlass.viewportHeight(view, container)
                            if (height <= 0) return
                            val wrapped = wrapHeight.invoke(null, modifier, top, true)
                            val extended = requireNotNull(requiredHeight.invoke(null, wrapped, height / density))
                            param.args[0] = BilibiliCommentGlass.viewport(view, extended, listKey = listKey)
                        } else {
                            // Replies: retain native constraints and scroll range.
                            // A full-window LocalView is NOT the list viewport.
                            val extent = (44f * density).toInt() + io.github.offlineglass.hook.glassHostBottomMarginPx(view)
                            param.args[0] = BilibiliCommentGlass.replyViewport(view, modifier, extent, wrapHeight, requiredHeight, top, listKey)
                        }
                        // Preserve every native padding edge; reserve a complete input
                        // row, the shared bottom gap and 8dp breathing room at list end.
                        val nativePadding = param.args[2]
                        val bottom = 44f + io.github.offlineglass.hook.glassHostBottomMarginPx(view) / density + 8f
                        param.args[2] = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(paddingType)) { proxy, method, args ->
                            when {
                                method.name.startsWith("calculateBottomPadding") -> maxOf(bottom,
                                    (nativePadding?.let { method.invoke(it, *(args ?: emptyArray())) } as? Number)?.toFloat() ?: 0f)
                                method.name == "hashCode" -> System.identityHashCode(proxy)
                                method.name == "equals" -> proxy === args?.firstOrNull()
                                method.name == "toString" -> "BilibiliSafeCommentPadding"
                                nativePadding != null -> method.invoke(nativePadding, *(args ?: emptyArray()))
                                else -> 0f
                            }
                        }
                        param.args[12] = (param.args[12] as Int) and 4.inv()
                    }.onFailure { XposedBridge.log("LiquidTab detail comment viewport: $it") }
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab detail LazyColumn ABI: $it") }
    }

}
