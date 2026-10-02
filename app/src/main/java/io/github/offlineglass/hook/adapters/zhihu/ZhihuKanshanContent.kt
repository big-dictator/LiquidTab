package io.github.offlineglass.hook.adapters.zhihu

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import java.util.WeakHashMap

/** Reserve space in the native RN container so Yoga reflows its bottom controls. */
internal object ZhihuKanshanContent {
    private const val MAIN = "com.zhihu.android.app.feed.kanshanassistant.fragment.MainActivityOneReactKanShanAssistantFragment"
    private val roots = WeakHashMap<View, Int>()
    fun install(loader: ClassLoader) {
        val type = runCatching { XposedHelpers.findClass(
            "com.zhihu.android.app.feed.kanshanassistant.fragment.OneReactKanShanAssistantFragment", loader)
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(type, "onViewCreated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != MAIN) return
                val view = param.args.firstOrNull() as? View ?: return
                roots[view] = view.paddingBottom
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        roots[v]?.let { v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, it) }
                    }
                })
            }
        })
    }
    fun update(host: GlassHostLayout) {
        val hostLocation = IntArray(2)
        val pageLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        for ((page, original) in roots) {
            if (!page.isAttachedToWindow || !page.isShown || page.rootView !== host.rootView) continue
            page.getLocationInWindow(pageLocation)
            val padding = if (host.config.enabled && host.barVisibilityTarget && !host.outerRect.isEmpty) {
                val gap = 8f * page.resources.displayMetrics.density
                (pageLocation[1] + page.height - hostLocation[1] - host.outerRect.top + gap)
                    .toInt().coerceAtLeast(original)
            } else original
            if (page.paddingBottom != padding) page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, padding)
        }
    }
}
