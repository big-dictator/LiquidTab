package io.github.offlineglass.hook.adapters.xjtu

import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** DCloud reserves native-tab space in both its container and each tab page's FrameOptions. */
internal object XjtuContent {
    private val owners = WeakHashMap<ViewGroup, WeakReference<Any>>()
    private val active = WeakHashMap<ViewGroup, Boolean>()
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        val clazz = XposedHelpers.findClass("io.dcloud.common.core.ui.TabBarWebview", loader)
        val constructorHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject
                val bar = owner.javaClass.getField("mTabBar").get(owner) as? ViewGroup ?: return
                owners[bar] = WeakReference(owner)
            }
        }
        // The project's compile-only Xposed stub omits constructors; the runtime bridge provides it.
        XposedBridge::class.java.getMethod("hookAllConstructors", Class::class.java, XC_MethodHook::class.java)
            .invoke(null, clazz, constructorHook)

        // This adapter runs only in XJTU's app/UI processes. Keep DCloud from reinstating
        // its native-tab reservation when a page is added, shown, or restyled.
        XposedBridge.hookAllMethods(clazz, "setTabItemsBottomMargin", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.isNotEmpty()) param.args[0] = 0
            }
        })
        XposedBridge.hookAllMethods(clazz, "append", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // append() writes FrameOptions.bottom directly; reset after it adds the page.
                XposedHelpers.callMethod(param.thisObject, "setTabItemsBottomMargin", 0)
            }
        })
        installed = true
    }

    fun acquire(bar: ViewGroup) {
        if (active[bar] == true) return
        val owner = owners[bar]?.get() ?: return
        active[bar] = true
        XposedHelpers.callMethod(owner, "setTabItemsBottomMargin", 0)
    }

    /** Recover if DCloud or a later page transition restores a native bottom reservation. */
    fun ensureNoBottomReservation(bar: ViewGroup?) {
        val native = bar?.let(XjtuAdapter::nativeBar) ?: return
        val owner = owners[native]?.get() ?: return
        val scene = runCatching { owner.javaClass.getField("mTabLayout").get(owner) as? ViewGroup }
            .getOrNull() ?: return
        val margin = (scene.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        if (active[native] != true || margin != 0) {
            active[native] = true
            XposedHelpers.callMethod(owner, "setTabItemsBottomMargin", 0)
        }
    }

    /** Reapply zero to the native container and every existing tab FrameOptions. */
    fun refreshAllTabViewports(bar: ViewGroup?) {
        val native = bar?.let(XjtuAdapter::nativeBar) ?: return
        val owner = owners[native]?.get() ?: return
        active[native] = true
        XposedHelpers.callMethod(owner, "setTabItemsBottomMargin", 0)
    }

    fun scene(bar: ViewGroup): ViewGroup? = owners[bar]?.get()?.let {
        it.javaClass.getField("mTabLayout").get(it) as? ViewGroup
    }

    fun release(bar: ViewGroup) {
        if (active.remove(bar) != true) return
        val owner = owners[bar]?.get() ?: return
        // XJTU content must remain edge-to-edge even while the glass bar is detached.
        XposedHelpers.callMethod(owner, "setTabItemsBottomMargin", 0)
    }
}
