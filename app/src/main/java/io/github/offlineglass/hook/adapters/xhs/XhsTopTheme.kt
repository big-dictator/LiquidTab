package io.github.offlineglass.hook.adapters.xhs

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Native XYSkinManager theme, independent of system uiMode and top chrome layout. */
internal object XhsTopTheme {
    private var contextSkin: java.lang.reflect.Method? = null
    private val roots = WeakHashMap<View, Boolean>()
    fun install(loader: ClassLoader) {
        runCatching {
            // BaseActivity keeps this public contract across obfuscation. Derive
            // the manager and enum types instead of pinning eud/l1b package names.
            val base = loader.loadClass("com.xingin.xhstheme.arch.BaseActivity")
            val manager = base.getMethod("getSkinManager").returnType
            val skin = base.getMethod("getForceSkinTheme").returnType
            contextSkin = manager.declaredMethods.single { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) && method.returnType == skin &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }.apply { isAccessible = true }
            for (method in manager.declaredMethods.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(skin))
            }) XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    roots.keys.toList().forEach { root ->
                        root.post { if (root.isAttachedToWindow) root.postInvalidateOnAnimation() }
                    }
                }
            })
        }.onFailure { android.util.Log.w("LiquidTabXhsTop", "native skin binding unavailable", it) }
    }
    fun watch(root: View) { roots[root] = true }
    fun unwatch(root: View) { roots.remove(root) }
    fun statusGradient(dark: Boolean): GradientDrawable {
        val rgb = if (dark) 0x0018181B else 0x00FFFFFF
        val colors = IntArray(17) { index ->
            val t = index / 16f
            val alpha = (160f * (1f - t*t*(3f - 2f*t))).toInt().coerceIn(0, 160)
            (alpha shl 24) or rgb
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    fun dark(context: Context, themeMode: Int = GlassConfig.THEME_SYSTEM): Boolean = when (themeMode) {
        GlassConfig.THEME_LIGHT -> false
        GlassConfig.THEME_DARK -> true
        else -> runCatching { (contextSkin?.invoke(null, context) as? Enum<*>)?.name?.let { it != "SKIN_THEME_LIGHT" } }.getOrNull()
            ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
}
