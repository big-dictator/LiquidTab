package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Native list mask setter and its page-level companion share one paint owner. */
internal object WeChatStatusMaskBinding {
    private class Mask(val original: Float, var nativeAlpha: Float = original, var suppressed: Boolean = false)
    private val masks = WeakHashMap<View, Mask>()
    private var writing = false
    fun install(loader: ClassLoader) {
        val listType = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", loader)
        val setter = listType.getDeclaredMethod("setStatusBarMaskView", View::class.java)
        check(setter.returnType == Void.TYPE && android.widget.ListView::class.java.isAssignableFrom(listType))
        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.thisObject as? View ?: return
                val mask = param.args[0] as? View ?: return
                masks.putIfAbsent(mask, Mask(mask.alpha))
                list.viewTreeObserver.addOnPreDrawListener {
                    var page = list.parent as? View
                    var depth = 0
                    while (page != null && !WeChatLauncherPages.owns(page) && depth++ < 16) page = page.parent as? View
                    val owner = page as? ViewGroup
                    if (owner != null) {
                        // Current page XML: content bounce + optional controls +
                        // one plain leaf View for the second status mask. Native
                        // initActionBarView updates it together with the setter mask.
                        val companion = (0 until owner.childCount).map(owner::getChildAt)
                            .filter { it.javaClass == View::class.java && !it.isClickable && !it.isFocusable }
                            .singleOrNull()
                        if (companion != null) masks.putIfAbsent(companion, Mask(companion.alpha))
                    }
                    val suppress = WeChatChatToolbarGradient.nativeMainHeaderVisible(list.rootView)
                    writing = true
                    try {
                        masks.entries.toList().filter { it.key.rootView === list.rootView }.forEach { (view, state) ->
                            state.suppressed = suppress
                            val alpha = if (suppress) 0f else state.nativeAlpha
                            if (view.alpha != alpha) view.alpha = alpha
                        }
                    } finally { writing = false }
                    true
                }
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val state = masks[param.thisObject] ?: return
                state.nativeAlpha = param.args[0] as Float
                if (state.suppressed) param.args[0] = 0f
            }
        })
    }
}
