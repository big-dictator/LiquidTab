package io.github.offlineglass.hook.adapters.douyin

import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** The native long-press panel has its own window, separate from the feed. */
internal object DouyinLongPressInsets {
    private val contentRoots = java.util.WeakHashMap<View, Boolean>()
    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = Class.forName("androidx.fragment.app.DialogFragment", false, loader)
            val type = Class.forName("com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl", false, loader)
            require(fragment.isAssignableFrom(type))
            val dialog = fragment.getMethod("getDialog")
            require(Dialog::class.java.isAssignableFrom(dialog.returnType))
            val view = type.getMethod("getView")
            require(View::class.java.isAssignableFrom(view.returnType))
            val start = type.getDeclaredMethod("onStart")
            require(start.returnType == Void.TYPE)
            // The Dux panel's inner Coordinator also consumes the navigation inset.
            // Filter only this verified dialog content root, preserving status/IME.
            if (Build.VERSION.SDK_INT >= 30) {
                val dispatch = ViewGroup::class.java.getDeclaredMethod("dispatchApplyWindowInsets", WindowInsets::class.java)
                XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!contentRoots.containsKey(p.thisObject as? View)) return
                        val original = p.args[0] as? WindowInsets ?: return
                        p.args[0] = WindowInsets.Builder(original)
                            .setInsets(WindowInsets.Type.navigationBars(), android.graphics.Insets.NONE)
                            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), android.graphics.Insets.NONE)
                            .build()
                    }
                })
            }
            XposedBridge.hookMethod(start, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val root = view.invoke(p.thisObject) as? View ?: return
                    if (root.javaClass.name != "com.ss.android.ugc.aweme.feed.long_press_panel.ui.MaxHeightRelativeLayout") return
                    val owner = dialog.invoke(p.thisObject) as? Dialog ?: return
                    if (!HookConfigReader.read(owner.context, "com.ss.android.ugc.aweme").enabled) return
                    val window = owner.window ?: return
                    val decor = window.decorView
                    decor.findViewById<View>(android.R.id.content)?.let { contentRoots[it] = true }
                    // Layout behind navigation only; preserve status/IME behavior.
                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    if (Build.VERSION.SDK_INT >= 30) {
                        val attrs = window.attributes
                        attrs.setFitInsetsTypes(attrs.fitInsetsTypes and WindowInsets.Type.navigationBars().inv())
                        window.attributes = attrs
                    }
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                    decor.requestApplyInsets()
                    // One report on opening, no frame pump, tree scan or persistent listener.
                    decor.post {
                        val content = decor.findViewById<View>(android.R.id.content)
                        val rect = android.graphics.Rect()
                        content?.getGlobalVisibleRect(rect)
                        val chain = StringBuilder()
                        var node: View? = root
                        repeat(16) {
                            val current = node ?: return@repeat
                            val lp = current.layoutParams
                            chain.append(current.javaClass.name).append(" h=").append(current.height)
                                .append(" lp=").append(lp?.height).append(" pb=").append(current.paddingBottom)
                                .append(" mb=").append((lp as? ViewGroup.MarginLayoutParams)?.bottomMargin).append('\n')
                            node = current.parent as? View
                        }
                        runCatching { java.io.File(owner.context.getExternalFilesDir(null), "dy_longpress_insets_status.txt")
                            .writeText("time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} content=$rect decor=${decor.width}x${decor.height}\n$chain") }
                    }
                }
            })
        }.onFailure { XposedBridge.log("[LiquidTab] Douyin long-press window contract unavailable: $it") }
    }
}
