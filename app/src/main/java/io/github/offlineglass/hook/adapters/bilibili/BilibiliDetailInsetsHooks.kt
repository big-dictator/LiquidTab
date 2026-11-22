package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.graphics.Insets
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import android.view.Window
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** The intro and comment tabs share this detail window; leave home navigation unchanged. */
internal object BilibiliDetailInsetsHooks {
    private val detailWindows = WeakHashMap<View, Boolean>()
    private val ownedStatusPadding = WeakHashMap<View, Int>()
    private val focusedInsets = WeakHashMap<View, WindowInsets>()
    private val owners = WeakHashMap<View, WeakReference<Activity>>()
    private val insetGeometry = WeakHashMap<View, String>()
    private val ownedLayoutFlag = WeakHashMap<View, Boolean>()
    private val ownedImmersion = WeakHashMap<View, Boolean>()
    private val windowModes = WeakHashMap<View, Boolean>()
    private fun windowed(root: View): Boolean = owners[root]?.get()?.let(BilibiliDetailWindowMode::windowed) == true
    private fun validateInsets(root: View): Boolean {
        val config = root.resources.configuration
        val key = "${root.width}/${root.height}/${config.screenWidthDp}/${config.screenHeightDp}/${config.orientation}"
        if (insetGeometry.put(root, key) != key) focusedInsets.remove(root)
        if (!windowed(root)) return true
        focusedInsets.remove(root)
        return false
    }
    fun install(loader: ClassLoader, targetPackage: String = "tv.danmaku.bili") {
        XposedBridge.hookAllMethods(View::class.java, "setSystemUiVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (detailWindows[param.thisObject] != true || param.args.size != 1) return
                if (windowed(param.thisObject as View)) return
                param.args[0] = (param.args[0] as Int) or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        })
        runCatching {
            val phoneWindow = XposedHelpers.findClass("com.android.internal.policy.PhoneWindow", loader)
            for (method in listOf("setNavigationBarColor", "setNavigationBarDividerColor", "setNavigationBarContrastEnforced")) {
                XposedBridge.hookAllMethods(phoneWindow, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val window = param.thisObject as? Window ?: return
                        if (detailWindows[window.decorView] != true || param.args.size != 1) return
                        if (windowed(window.decorView)) return
                        param.args[0] = if (method == "setNavigationBarContrastEnforced") false else Color.TRANSPARENT
                    }
                })
            }
        }.onFailure { XposedBridge.log("[LiquidTab/BilibiliDetailInsets] navigation color guard: $it") }
        // Keep a covered video page at its last focused geometry. Overlay IME
        // and system-bar animations belong to the assistant, not this player.
        for (method in listOf("dispatchApplyWindowInsets", "dispatchWindowInsetsAnimationProgress")) {
            XposedBridge.hookAllMethods(View::class.java, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val root = view.rootView
                    if (view !== root || detailWindows[root] != true) return
                    if (!validateInsets(root)) return
                    val incoming = param.args.firstOrNull() as? WindowInsets ?: return
                    if (root.hasWindowFocus()) focusedInsets[root] = incoming
                    else param.args[0] = focusedInsets[root] ?: withoutOverlayIme(incoming)
                }
            })
        }
        // TheseusCommentViewport reads ROOT insets directly, bypassing inset dispatch listeners.
        // Only the comment viewport opts out; the player's sizing repository must see native insets.
        XposedBridge.hookAllMethods(View::class.java, "getRootWindowInsets", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (detailWindows[view.rootView] != true) return
                val original = param.result as? WindowInsets ?: return
                val root = view.rootView
                if (!validateInsets(root)) return
                val stable = if (root.hasWindowFocus()) {
                    focusedInsets[root] = original
                    original
                } else focusedInsets[root] ?: withoutOverlayIme(original)
                param.result = if (isCommentViewport(view)) withoutNavigationBottom(stable) else stable
            }
        })
        // Let the native edge-to-edge service calculate status/cutout and player geometry first.
        // It owns content padding and the bottom navigation scrim; remove only their bottom part.
        runCatching {
            val edge = XposedHelpers.findClass("com.bilibili.ship.theseus.united.page.activitywindow.ActivityWindowEdgeToEdgePropertyService", loader)
            val apply = edge.declaredMethods.single {
                it.returnType == Void.TYPE && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == "androidx.core.view.WindowInsetsCompat" &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            val activityField = edge.declaredFields.single { Activity::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
            val navDrawable = loader.loadClass("com.bilibili.lib.ui.windowinsets.NavigationBarInsetsBackgroundDrawable")
            val holderField = edge.declaredFields.single { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type.declaredFields.any { it.type == navDrawable } &&
                    field.type.declaredFields.any { Window::class.java.isAssignableFrom(it.type) }
            }.apply { isAccessible = true }
            val drawableField = holderField.type.declaredFields.single { it.type == navDrawable }.apply { isAccessible = true }
            XposedBridge.hookMethod(apply, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = activityField.get(param.thisObject) as? Activity ?: return
                        if (activity.packageName != targetPackage || activity.javaClass.name != "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity") return
                        owners[activity.window.decorView] = WeakReference(activity)
                        extendBottom(activity)
                        if (BilibiliDetailWindowMode.windowed(activity)) return
                        val background = holderField.get(param.thisObject) ?: return
                        (drawableField.get(background) as? Drawable)?.alpha = 0
                    }.onFailure { XposedBridge.log("[LiquidTab/BilibiliDetailInsets] native edge: $it") }
                }
            })
        }.onFailure { XposedBridge.log("[LiquidTab/BilibiliDetailInsets] edge service unavailable: $it") }
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != targetPackage || activity.javaClass.name !=
                    "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity") return
                if (param.args.firstOrNull() == false) return
                val decor = activity.window.decorView
                owners[decor] = WeakReference(activity)
                BilibiliDetailWindowMode.invalidate(activity)
                val first = detailWindows.put(decor, true) != true
                // Preserve the native LAYOUT_STABLE/fullscreen policy. The bottom-only platform
                // opt-out is paired with explicit status-fit preservation in extendBottom().
                if (!BilibiliDetailWindowMode.windowed(activity)) {
                    if (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION == 0) ownedLayoutFlag[decor] = true
                    decor.systemUiVisibility = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    activity.window.navigationBarColor = Color.TRANSPARENT
                    activity.window.navigationBarDividerColor = Color.TRANSPARENT
                    activity.window.isNavigationBarContrastEnforced = false
                }
                extendBottom(activity)
                if (first) {
                    val reference = WeakReference(activity)
                    decor.viewTreeObserver.addOnGlobalLayoutListener {
                        reference.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let(::extendBottom)
                    }
                    decor.requestApplyInsets()
                }
            }
        }
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", callback)
        XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", callback)
        XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", callback)
        XposedBridge.hookAllMethods(Activity::class.java, "onMultiWindowModeChanged", callback)
    }

    private fun extendBottom(activity: Activity) {
        val decor = activity.window.decorView
        if (BilibiliDetailWindowMode.windowed(activity)) {
            focusedInsets.remove(decor)
            val changed = windowModes.put(decor, true) != true
            // Native freeform/caption insets own this window. Full-screen edge
            // overrides and cached status insets must never cross this boundary.
            if (ownedLayoutFlag.remove(decor) == true)
                decor.systemUiVisibility = decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.inv()
            if (ownedImmersion.remove(decor) == true) runCatching {
                val stub = XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub")
                XposedHelpers.callMethod(stub, "setForceImmersiveNavBar", false)
                XposedHelpers.callMethod(decor, "updateColorViews", null, false)
            }
            val content = activity.findViewById<View>(android.R.id.content)
            val previous = content?.let { ownedStatusPadding.remove(it) }
            if (content != null && previous != null && content.paddingTop == previous)
                content.setPadding(content.paddingLeft, 0, content.paddingRight, content.paddingBottom)
            if (changed) decor.post { if (decor.isAttachedToWindow) decor.requestApplyInsets() }
            return
        }
        if (windowModes.put(decor, false) == true) focusedInsets.remove(decor)
        // An assistant/IME overlay can dispatch layout/inset changes to the
        // unfocused video activity. Its SurfaceView is composed separately;
        // changing content padding here moves the app buffer underneath the
        // retained video/transition surfaces. Keep our existing layout until
        // focus returns (the focus callback above re-applies the correction).
        if (!decor.hasWindowFocus()) return
        // HyperOS can keep fitting the gesture edge even when the app restores its navigation
        // layout flag. Opt out at that edge; retain/correct the status fit independently below.
        runCatching {
            val stub = XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub") ?: return@runCatching
            if (XposedHelpers.callMethod(stub, "isForceImmersive") != true) {
                ownedImmersion[decor] = true
                XposedHelpers.callMethod(stub, "setForceImmersiveNavBar", true)
                XposedHelpers.callMethod(decor, "updateColorViews", null, false)
            }
        }
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val insets = content.rootWindowInsets
        val status = insets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        val location = IntArray(2)
        content.getLocationInWindow(location)
        val fullscreen = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0 ||
            insets?.isVisible(WindowInsets.Type.statusBars()) == false ||
            activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        var top = content.paddingTop
        val previous = ownedStatusPadding[content]
        if (fullscreen) {
            if (previous != null && top == previous) top = 0
            ownedStatusPadding.remove(content)
        } else if (status > 0 && location[1] < status) {
            // Some native routes fit at the framework, others use content padding. Preserve either
            // arrangement; a video surface at y=0 must not hide its first 150 px behind the status bar.
            val needed = status - location[1]
            if (top < needed) { top = needed; ownedStatusPadding[content] = needed }
        } else if (previous != null && top == previous) {
            top = 0
            ownedStatusPadding.remove(content)
        }
        if (content.paddingBottom != 0 || content.paddingTop != top)
            content.setPadding(content.paddingLeft, top, content.paddingRight, 0)
    }

    private fun isCommentViewport(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (BilibiliCommentGlass.isRegisteredView(current)) return true
            if (runCatching { current!!.resources.getResourceEntryName(current!!.id) }.getOrNull() == "comment_container") return true
            current = current.parent as? View
        }
        return false
    }

    private fun withoutNavigationBottom(original: WindowInsets): WindowInsets {
        val type = WindowInsets.Type.navigationBars()
        val visible = original.getInsets(type)
        val stable = original.getInsetsIgnoringVisibility(type)
        if (visible.bottom == 0 && stable.bottom == 0) return original
        return WindowInsets.Builder(original)
            .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
            .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
            .build()
    }

    private fun withoutOverlayIme(original: WindowInsets): WindowInsets =
        WindowInsets.Builder(original).setInsets(WindowInsets.Type.ime(), Insets.NONE)
            .setVisible(WindowInsets.Type.ime(), false).build()
}
