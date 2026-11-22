package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Each native page owns its status material; window policy is shared by those owners. */
internal object WeChatStatusGradientSurface {
    private class Surface(window: Window) {
        val window = WeakReference(window)
        val decor = WeakReference(window.decorView)
        var originalColor = window.statusBarColor
        var originalContrast = window.isStatusBarContrastEnforced
        val originalFlags = window.decorView.systemUiVisibility
        val drawings = WeakHashMap<View, Drawable>()
        val nativePadding = WeakHashMap<View, Int>()
        val nativeClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    }
    private val windows = WeakHashMap<Window, Surface>()
    private val roots = WeakHashMap<View, Surface>()
    private val nativeStatusOwners = WeakHashMap<View, Surface>()
    private var writing = false
    private val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    fun statusHeight(root: View): Int = root.rootWindowInsets
        ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top
        ?: root.resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }
            ?.let(root.resources::getDimensionPixelSize) ?: 0

    fun install(loader: ClassLoader) {
        val type = XposedHelpers.findClass("com.android.internal.policy.PhoneWindow", loader)
        XposedBridge.hookAllMethods(type, "setStatusBarColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val surface = windows[param.thisObject] ?: return
                surface.originalColor = param.args.firstOrNull() as? Int ?: return
                param.args[0] = Color.TRANSPARENT
            }
        })
        XposedBridge.hookAllMethods(type, "setStatusBarContrastEnforced", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val surface = windows[param.thisObject] ?: return
                surface.originalContrast = param.args.firstOrNull() as? Boolean ?: return
                param.args[0] = false
            }
        })
        val nativeStatus = XposedHelpers.findClass("com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout", loader)
        // Its dispatchDraw checks this public method around its two status paint
        // passes, while always calling super.dispatchDraw for content. The native
        // setWillNotDraw override is a no-op, so setting the flag cannot do this.
        XposedBridge.hookAllMethods(nativeStatus, "willNotDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (nativeStatusOwners.containsKey(param.thisObject)) param.result = true
            }
        })
        // The tiny final getter can be inlined into this verified paint caller.
        XposedBridge.framework().deoptimize(nativeStatus.getDeclaredMethod("dispatchDraw", android.graphics.Canvas::class.java))
        XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val view = param.thisObject as? View ?: return
                val surface = nativeStatusOwners[view] ?: return
                surface.nativePadding[view] = param.args[1] as Int
                param.args[1] = 0
            }
        })
    }

    fun show(container: View, gradient: Drawable, host: View) {
        var context = container.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return
        val decor = window.decorView
        val height = statusHeight(decor)
        if (height <= 0) return
        val surface = windows.getOrPut(window) { Surface(window) }
        roots[host] = surface
        writing = true
        try {
            var ancestor: View? = container.parent as? View
            var depth = 0
            while (ancestor != null && ancestor !== decor && depth++ < 24) {
                var type: Class<*>? = ancestor.javaClass
                var drawsStatus = false
                while (type != null) {
                    if (type.name == "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout") { drawsStatus = true; break }
                    type = type.superclass
                }
                if (drawsStatus) {
                    if (!surface.nativePadding.containsKey(ancestor)) surface.nativePadding[ancestor] = ancestor.paddingTop
                    nativeStatusOwners[ancestor] = surface
                    if (ancestor.paddingTop != 0) ancestor.setPadding(ancestor.paddingLeft, 0, ancestor.paddingRight, ancestor.paddingBottom)
                    if (ancestor is ViewGroup) {
                        surface.nativeClips.putIfAbsent(ancestor, ancestor.clipChildren to ancestor.clipToPadding)
                        if (ancestor.clipChildren) ancestor.clipChildren = false
                        if (ancestor.clipToPadding) ancestor.clipToPadding = false
                    }
                }
                ancestor = ancestor.parent as? View
            }
            if (window.statusBarColor != Color.TRANSPARENT) window.statusBarColor = Color.TRANSPARENT
            if (window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
            val flags = decor.systemUiVisibility or layoutFlags
            if (decor.systemUiVisibility != flags) decor.systemUiVisibility = flags
        } finally { writing = false }
        if (surface.drawings[host] !== gradient) {
            surface.drawings[host]?.let(host.overlay::remove)
            surface.drawings[host] = gradient
            // Native TestTimeForChatting/SwipeBackLayout can use either legacy
            // animation matrices or offsets. A page-local overlay inherits both
            // automatically, without guessing/sampling animation progress.
            host.overlay.add(gradient)
        }
        val bounds = gradient.bounds
        if (bounds.left != 0 || bounds.top != 0 || bounds.right != host.width || bounds.bottom != height)
            gradient.setBounds(0, 0, host.width, height)
    }

    fun hide(root: View) {
        val surface = roots.remove(root) ?: return
        val window = surface.window.get()
        val decor = surface.decor.get()
        surface.drawings.remove(root)?.let(root.overlay::remove)
        // Home and chat may be simultaneously visible during entry/back swipe.
        // Releasing one page must not remove the other's native status policy.
        if (surface.drawings.isNotEmpty()) return
        if (window != null) {
            windows.remove(window)
            writing = true
            try {
                surface.nativePadding.forEach { (view, top) ->
                    nativeStatusOwners.remove(view)
                    view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
                }
                surface.nativeClips.forEach { (view, flags) ->
                    view.clipChildren = flags.first; view.clipToPadding = flags.second
                }
                window.statusBarColor = surface.originalColor
                window.isStatusBarContrastEnforced = surface.originalContrast
                if (decor != null) decor.systemUiVisibility = (decor.systemUiVisibility and layoutFlags.inv()) or
                    (surface.originalFlags and layoutFlags)
            } finally { writing = false }
        }
    }
}
