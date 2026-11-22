package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/** BaseAddressUIFragment owns an independent native-tab padding, separate from frost. */
internal object WeChatContactsLayoutRepair {
    private val pages = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(loader: ClassLoader) {
        val type = runCatching {
            XposedHelpers.findClass("com.tencent.mm.ui.contact.address.BaseAddressUIFragment", loader)
        }.getOrNull() ?: return
        // Register at the stable Fragment root accessor as well as the native
        // writer. 8.0.79 renamed L0 to M0; registration must survive obfuscation.
        val getViewOwner = type.getMethod("getView").declaringClass
        XposedBridge.hookAllMethods(getViewOwner, "getView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!type.isInstance(param.thisObject)) return
                val page = param.result as? View ?: return
                observe(page)
                repair(page)
            }
        })
        // The root accessor and reattach-aware pre-draw guard cover delayed and
        // configuration writers without depending on their obfuscated names.
    }

    private fun observe(page: View) {
        if (!pages.add(page)) return
        val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null

            fun bind() {
                val next = page.viewTreeObserver
                if (observer === next) return
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = next
                next.addOnPreDrawListener(this)
            }

            override fun onPreDraw(): Boolean = !repair(page)
            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = null
            }
        }
        page.addOnAttachStateChangeListener(listener)
        listener.bind()
    }

    fun repair(page: View): Boolean {
        if (!pages.contains(page) || !WeChatLauncherPages.owns(page) || page.paddingBottom == 0) return false
        val bottom = page.paddingBottom
        page.setPadding(page.paddingLeft, WeChatMainPageTopContent.nativeTopPadding(page), page.paddingRight, 0)
        page.invalidate()
        XposedBridge.log("[OfflineGlass][WeChatContactsLayout] root bottom padding $bottom -> 0; top=${page.paddingTop}")
        return true
    }
}
