package io.github.offlineglass.hook.adapters.tieba

import android.os.SystemClock
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

internal object TiebaNativeTabAnimation {
    private const val TAB_WIDGET = "com.baidu.tbadk.core.tabHost.FragmentTabWidget"
    private const val INDICATOR = "com.baidu.tbadk.mainTab.TbFragmentTabIndicator"
    private val animationUntil = WeakHashMap<ViewGroup, Long>()
    internal data class IconResources(var idle: Int = 0, var selected: Int = 0, var skin: Int = 0)
    private val icons = WeakHashMap<View, IconResources>()
    private var skinDrawable: java.lang.reflect.Method? = null
    private var skinColor: java.lang.reflect.Method? = null

    fun install(loader: ClassLoader) {
        runCatching {
            val widget = Class.forName(TAB_WIDGET, false, loader)
            val base = Class.forName(INDICATOR, false, loader)
            val observedMethods = mutableSetOf<java.lang.reflect.Method>()
            check(ViewGroup::class.java.isAssignableFrom(widget))
            for (typeName in listOf("MainTabBottomOptIndicator", "MaintabBottomIndicator")) {
                val type = Class.forName("com.baidu.tbadk.mainTab." + typeName, false, loader)
                check(base.isAssignableFrom(type) && View::class.java.isAssignableFrom(type))
                val callback = type.getDeclaredMethod("onTabSelected", Boolean::class.javaPrimitiveType!!)
                check(callback.returnType == Void.TYPE)
                XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] != true || param.hasThrowable()) return
                        var view = param.thisObject as? View ?: return
                        repeat(5) {
                            val parent = view.parent as? ViewGroup ?: return
                            if (widget.isInstance(parent)) {
                                animationUntil[parent] = SystemClock.uptimeMillis() + 650L
                                return
                            }
                            view = parent
                        }
                    }
                })
                bindIconResources(type, observedMethods)
            }
        }.onFailure { XposedBridge.log("[LiquidTab/Tieba] Native tab animation contract unavailable: $it") }
        runCatching {
            val skin = Class.forName("com.baidu.tbadk.core.util.SkinManager", false, loader)
            val integer = Int::class.javaPrimitiveType!!
            skinDrawable = skin.getMethod("getDrawable", integer).also {
                check(java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    Drawable::class.java.isAssignableFrom(it.returnType))
            }
            skinColor = skin.getMethod("getColor", integer).also {
                check(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == integer)
            }
        }.onFailure { XposedBridge.log("[LiquidTab/Tieba] Tab skin resource contract unavailable: $it") }
    }

    private fun bindIconResources(type: Class<*>, observedMethods: MutableSet<java.lang.reflect.Method>) {
        runCatching {
            val integer = Int::class.javaPrimitiveType!!
            fun observe(name: String, update: (IconResources, Int) -> Unit) {
                val method = type.getMethod(name, integer)
                check(method.returnType == Void.TYPE)
                if (!observedMethods.add(method)) return
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val view = param.thisObject as? View ?: return
                        update(icons.getOrPut(view) { IconResources() }, param.args[0] as Int)
                    }
                })
            }
            observe("setNormalResId") { assets, id -> assets.idle = id }
            observe("setSelectedResId") { assets, id -> assets.selected = id }
            observe("onChangeSkin") { assets, skin -> assets.skin = skin }
        }.onFailure { XposedBridge.log("[LiquidTab/Tieba] Tab state resource binding unavailable: $it") }
    }

    fun resourcesFor(view: View): IconResources? = icons[view]

    fun loadDrawable(context: Context, id: Int): Drawable? = runCatching {
        ((skinDrawable?.invoke(null, id) as? Drawable) ?: context.getDrawable(id))?.let { drawable ->
            (drawable.constantState?.newDrawable(context.resources, context.theme) ?: drawable).mutate()
        }
    }.getOrNull()

    fun nativeColor(context: Context, entry: String): Int? {
        val id = context.resources.getIdentifier(entry, "color", context.packageName)
        if (id == 0) return null
        return runCatching { (skinColor?.invoke(null, id) as? Int) ?: context.getColor(id) }.getOrNull()
    }

    fun isFollowing(source: ViewGroup?, now: Long): Boolean =
        source != null && (animationUntil[source] ?: 0L) > now
}
