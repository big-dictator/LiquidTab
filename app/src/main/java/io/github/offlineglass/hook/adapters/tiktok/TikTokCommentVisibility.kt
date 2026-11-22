package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.Rect
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Stable comment fragment lifecycle and visible panel bounds. */
internal object TikTokCommentVisibility {
    private class Page(val view: WeakReference<View>, var active: Boolean = false,
        var protocolVisible: Boolean? = null)
    private val pages = WeakHashMap<Any, Page>()
    private val rect = Rect()
    private val viewport = Rect()
    fun install(loader: ClassLoader) {
        val type = XposedHelpers.findClass(
            "com.ss.android.ugc.aweme.commentv2.commentpage.commonvideo.BaseCommentPageContainerFragment", loader)
        // Native BaseCommentPageContainerFragment's visibility protocol calls
        // BtmSDK.onPageShow(this, Boolean, params) / onPageHide(this, Boolean).
        // Closing a cached panel does not have to pause/destroy its Fragment.
        // Bind those stable semantic calls, never the protocol's renamed methods.
        val sdk = XposedHelpers.findClass("com.bytedance.android.btm.api.BtmSDK", loader)
        for ((name, shown) in listOf("onPageShow" to true, "onPageHide" to false)) {
            val method = sdk.declaredMethods.single { it.name == name &&
                it.parameterTypes.size == (if (shown) 3 else 2) &&
                it.parameterTypes[0].isAssignableFrom(type) &&
                it.parameterTypes[1] in arrayOf(Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType) }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.args.firstOrNull()?.takeIf(type::isInstance) ?: return
                    val page = pages[owner] ?: return
                    page.protocolVisible = shown
                    page.view.get()?.rootView?.postInvalidateOnAnimation()
                }
            })
        }
        XposedBridge.hookAllMethods(type, "onViewCreated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.args.firstOrNull() as? View ?: return
                pages[param.thisObject] = Page(WeakReference(view))
            }
        })
        XposedBridge.hookAllMethods(type, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { pages[param.thisObject]?.active = true }
        })
        XposedBridge.hookAllMethods(type, "onPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { pages[param.thisObject]?.active = false }
        })
        XposedBridge.hookAllMethods(type, "onDestroyView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { pages.remove(param.thisObject) }
        })
    }
    fun visible(root: View): Boolean = pages.values.any { page ->
        val view = page.view.get()
        page.active && page.protocolVisible != false && view != null && view.rootView === root && view.isAttachedToWindow && view.isShown && effectivelyVisible(view) &&
            view.getGlobalVisibleRect(rect) && root.getGlobalVisibleRect(viewport) && rect.intersect(viewport) &&
            rect.width() >= viewport.width() * .5f && rect.height() >= viewport.height() * .25f
    }
    private fun effectivelyVisible(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node.alpha <= .01f) return false
            node = node.parent as? View
        }
        return true
    }
}
