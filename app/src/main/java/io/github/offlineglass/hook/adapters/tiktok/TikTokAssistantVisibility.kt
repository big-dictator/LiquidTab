package io.github.offlineglass.hook.adapters.tiktok

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Tracks the native assistant shell in the home window, including its loading screen. */
internal object TikTokAssistantVisibility {
    private val shells = WeakHashMap<View, Unit>()

    fun install(loader: ClassLoader) {
        val fragment = Class.forName(
            "com.ss.android.ugc.aweme.tako.ui.TakoChatRootFragment", false, loader,
        )
        val onViewCreated = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        XposedBridge.hookMethod(onViewCreated, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val shell = param.args.firstOrNull() as? FrameLayout ?: return
                shells[shell] = Unit
            }
        })
    }

    fun visible(root: View?): Boolean = root != null && shells.keys.any { shell ->
        // The shell precedes the conversation. An entering shell can start
        // transparent, and an inactive cached shell is hidden or detached.
        shell.isAttachedToWindow && shell.rootView === root && shell.isShown
    }
}
