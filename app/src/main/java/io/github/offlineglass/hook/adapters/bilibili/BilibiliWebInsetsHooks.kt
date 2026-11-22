package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.Window
import android.webkit.WebView
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Bottom insets for the verified friends and author-space WebView containers. */
internal object BilibiliWebInsetsHooks {
    private val owners = WeakHashMap<Activity, Owner>()
    private val tree = object : BilibiliWebStructure.Tree<View> {
        override fun parent(node: View) = node.parent as? View
        override fun children(node: View) = (node as? ViewGroup)?.childCount ?: 0
        override fun child(node: View, index: Int): View = (node as ViewGroup).getChildAt(index)
        override fun web(node: View) = node is WebView
    }
    fun install(authorSpace: Boolean = false, packageName: String = "tv.danmaku.bili") {
        val adaptNativeInsets = packageName == "com.bilibili.app.in"
        for (event in listOf("onCreate", "onPostResume", "onWindowFocusChanged", "onStop", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    when (event) {
                        "onStop", "onDestroy" -> owners.remove(activity)?.stop()
                        else -> {
                            if (param.args.firstOrNull() == false) return
                            if (activity.packageName != packageName) return
                            if (authorSpace) {
                                if (activity.javaClass.name != "com.bilibili.app.authorspace.local.LocalAuthorSpaceActivity") return
                            } else {
                                if (activity.javaClass.name != "tv.danmaku.bili.ui.webview.MWebActivity") return
                                val uri = activity.intent?.data ?: return
                                if (uri.scheme != "https" || uri.host != "space.bilibili.com" || uri.path != "/h5/follow") return
                            }
                            if (HookConfigReader.startupSnapshot(activity.packageName)?.enabled != true) return
                            owners.getOrPut(activity) { Owner(activity, authorSpace, adaptNativeInsets) }.start()
                        }
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(View::class.java, "setSystemUiVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = owners.values.firstOrNull { it.root === param.thisObject && it.applied && !it.writing } ?: return
                owner.nativeFlags = param.args[0] as Int
                param.args[0] = owner.nativeFlags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        })
        if (adaptNativeInsets && authorSpace) {
            XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owners.values.firstOrNull {
                        it.applied && !it.writing && it.paddingTarget === param.thisObject
                    } ?: return
                    param.args[3] = owner.nativePadding(param.args[3] as Int)
                }
            })
        }
        // MWebActivity reapplies its native theme on resume/skin changes. Preserve
        // that desired style for release, but do not let it cover this owned page.
        val phoneWindow = Class.forName("com.android.internal.policy.PhoneWindow")
        for (name in listOf("setNavigationBarColor", "setNavigationBarDividerColor", "setNavigationBarContrastEnforced")) {
            val argument = if (name == "setNavigationBarContrastEnforced") Boolean::class.javaPrimitiveType!! else Int::class.javaPrimitiveType!!
            val method = phoneWindow.methods.singleOrNull { it.name == name && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(argument)) } ?: continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val window = param.thisObject as? Window ?: return
                    val owner = owners.values.firstOrNull { it.activity.window === window && it.applied && !it.writing } ?: return
                    owner.nativeStyle(name, param.args[0])
                    param.args[0] = if (name == "setNavigationBarContrastEnforced") false else Color.TRANSPARENT
                }
            })
        }
    }

    private class Owner(
        val activity: Activity,
        private val authorSpace: Boolean,
        private val adaptNativeInsets: Boolean,
    ) : ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        val root = activity.window.decorView
        var applied = false
        var writing = false
        var nativeFlags = root.systemUiVisibility
        private var color = activity.window.navigationBarColor
        private var divider = activity.window.navigationBarDividerColor
        private var contrast = activity.window.isNavigationBarContrastEnforced
        private var listening = false
        private var web: View? = null
        private var checkedRoot: View? = null
        private var webWrapper: View? = null
        private var navBackground: View? = null
        private var navBackgroundAlpha = 1f
        private var nativeBackground: View? = null
        private var nativeBackgroundAlpha = 1f
        var paddingTarget: View? = null
            private set
        private var nativeBottomPadding = 0
        private var navigationBottom = 0

        fun nativePadding(bottom: Int): Int {
            nativeBottomPadding = bottom
            return (bottom - navigationBottom).coerceAtLeast(0)
        }

        private fun releasePadding() {
            val target = paddingTarget ?: return
            paddingTarget = null
            if (target.paddingBottom == (nativeBottomPadding - navigationBottom).coerceAtLeast(0)) {
                target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, nativeBottomPadding)
            }
        }
        fun nativeStyle(name: String, value: Any?) {
            when (name) {
                "setNavigationBarColor" -> color = value as Int
                "setNavigationBarDividerColor" -> divider = value as Int
                "setNavigationBarContrastEnforced" -> contrast = value as Boolean
            }
        }
        // Only the platform color-view is hidden. SystemUI owns the gesture handle.
        override fun onPreDraw(): Boolean {
            if (applied) {
                navBackground?.let { if (it.alpha != 0f) it.alpha = 0f }
                nativeBackground?.let { if (it.alpha != 0f) it.alpha = 0f }
            }
            return true
        }
        fun start() {
            if (!listening) { root.viewTreeObserver.addOnGlobalLayoutListener(this); root.viewTreeObserver.addOnPreDrawListener(this); listening = true }
            onGlobalLayout()
        }

        private fun bound(): Boolean {
            if (web?.isAttachedToWindow == true && web?.isShown == true && checkedRoot?.isAttachedToWindow == true) return true
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
            fun find(name: String): View? = content.findViewById(content.resources.getIdentifier(name, "id", activity.packageName))
            val wrapper = find(if (authorSpace) "web_view" else "webview") as? ViewGroup ?: return false
            val frame = (if (authorSpace) wrapper.parent else find("content_frame")) as? ViewGroup ?: return false
            val companionView = find(if (authorSpace) "space_loading" else "nav_top_bar") ?: return false
            val candidate = if (authorSpace) BilibiliWebStructure.bindAuthor(content, frame, wrapper, companionView, tree)
                else BilibiliWebStructure.bind(content, frame, wrapper, companionView, tree)
            if (candidate == null) return false
            web = candidate; checkedRoot = frame; webWrapper = wrapper
            return true
        }

        override fun onGlobalLayout() {
            val insets = root.rootWindowInsets ?: return
            val nav = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            // Preserve native IME, side navigation and OEM freeform caption geometry.
            if (!bound() || BilibiliDetailWindowMode.windowed(activity) || nav.bottom <= 0 ||
                nav.left != 0 || nav.right != 0 || insets.isVisible(WindowInsets.Type.ime()) ||
                (!adaptNativeInsets && authorSpace && root.findViewWithTag<View>("bilibili_navigation_bar_background") != null) ||
                HookConfigReader.startupSnapshot(activity.packageName)?.enabled != true) {
                restore(); return
            }
            if (!applied) {
                nativeFlags = root.systemUiVisibility
                color = activity.window.navigationBarColor
                divider = activity.window.navigationBarDividerColor
                contrast = activity.window.isNavigationBarContrastEnforced
                applied = true
            }
            writing = true
            try {
                if (adaptNativeInsets) {
                    val background = root.findViewWithTag<View>("bilibili_navigation_bar_background")
                        ?.takeIf { it.parent === root && !it.isClickable && !it.isFocusable }
                    if (background !== nativeBackground) {
                        nativeBackground?.let { if (it.alpha == 0f) it.alpha = nativeBackgroundAlpha }
                        nativeBackground = background
                        nativeBackgroundAlpha = background?.alpha ?: 1f
                    }
                    val target = webWrapper?.takeIf { authorSpace && background != null }
                    if (target !== paddingTarget) {
                        releasePadding()
                        paddingTarget = target
                        nativeBottomPadding = target?.paddingBottom ?: 0
                    }
                    navigationBottom = nav.bottom
                    target?.let {
                        val bottom = (nativeBottomPadding - navigationBottom).coerceAtLeast(0)
                        if (it.paddingBottom != bottom) it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, bottom)
                    }
                }
                val flags = nativeFlags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                if (root.systemUiVisibility != flags) root.systemUiVisibility = flags
                if (activity.window.navigationBarColor != Color.TRANSPARENT) activity.window.navigationBarColor = Color.TRANSPARENT
                if (activity.window.navigationBarDividerColor != Color.TRANSPARENT) activity.window.navigationBarDividerColor = Color.TRANSPARENT
                if (activity.window.isNavigationBarContrastEnforced) activity.window.isNavigationBarContrastEnforced = false
                val id = root.resources.getIdentifier("navigationBarBackground", "id", "android")
                val background = if (id != 0) root.findViewById<View>(id) else null
                if (background !== navBackground) {
                    navBackground?.let { if (it.alpha == 0f) it.alpha = navBackgroundAlpha }
                    navBackground = background
                    navBackgroundAlpha = background?.alpha ?: 1f
                }
                onPreDraw()
            } finally { writing = false }
        }

        private fun restore() {
            if (!applied) return
            applied = false
            writing = true
            try {
                root.systemUiVisibility = nativeFlags
                if (activity.window.navigationBarColor == Color.TRANSPARENT) activity.window.navigationBarColor = color
                if (activity.window.navigationBarDividerColor == Color.TRANSPARENT) activity.window.navigationBarDividerColor = divider
                if (!activity.window.isNavigationBarContrastEnforced) activity.window.isNavigationBarContrastEnforced = contrast
                navBackground?.let { if (it.alpha == 0f) it.alpha = navBackgroundAlpha }
                navBackground = null
                nativeBackground?.let { if (it.alpha == 0f) it.alpha = nativeBackgroundAlpha }
                nativeBackground = null
                releasePadding()
            } finally { writing = false }
        }
        fun stop() {
            if (listening && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this); if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            listening = false; restore(); web = null; checkedRoot = null; webWrapper = null
        }
    }
}

