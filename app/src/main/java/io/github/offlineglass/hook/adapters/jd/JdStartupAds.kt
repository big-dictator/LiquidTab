package io.github.offlineglass.hook.adapters.jd

import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

/** Ad-only factory: native callers already own the null/no-ad cleanup path. */
internal object JdStartupAds {
    private var installed = false
    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val type = Class.forName("com.jingdong.app.mall.ad.view.BaseSplashView", false, loader)
            val activity = Class.forName("com.jingdong.common.BaseActivity", false, loader)
            val fragment = Class.forName("com.jingdong.app.mall.SplashFragment", false, loader)
            check(android.widget.RelativeLayout::class.java.isAssignableFrom(type)) {
                "splash view no longer extends RelativeLayout"
            }
            check(fragment.declaredFields.count { it.type == type && !Modifier.isStatic(it.modifiers) } == 1) {
                "splash fragment must own exactly one splash view"
            }
            // Resolve by model ownership and controller construction, never by
            // factory/model/controller symbol names. A signature alone is not
            // enough: both the view and fragment must own this model, and the
            // view's controller must accept the same activity/mode/model tuple.
            val factories = type.declaredMethods.filter { method ->
                val p = method.parameterTypes
                if (!Modifier.isStatic(method.modifiers) || method.returnType != type ||
                    p.size != 3 || p[0] != activity || p[2] != Int::class.javaPrimitiveType) {
                    false
                } else {
                    val model = p[1]
                    !model.isPrimitive && model != String::class.java &&
                        type.declaredFields.count { it.type == model && !Modifier.isStatic(it.modifiers) } == 1 &&
                        fragment.declaredFields.count { it.type == model && !Modifier.isStatic(it.modifiers) } == 1 &&
                        type.declaredConstructors.count { constructor ->
                            val c = constructor.parameterTypes
                            c.size == 2 && c[0] == activity &&
                                type.declaredFields.any { it.type == c[1] && !Modifier.isStatic(it.modifiers) } &&
                                c[1].declaredConstructors.any {
                                    it.parameterTypes.contentEquals(arrayOf(activity, Int::class.javaPrimitiveType, model))
                                }
                        } == 1
                }
            }
            check(factories.size == 1) { "splash factory contract candidates=${factories.size}; refusing ambiguous/missing binding" }
            XposedBridge.hookMethod(factories.single(), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    android.util.Log.i("JdStartupAds", "suppressed splash ad factory")
                }
            })
            installed = true
            if (installed) android.util.Log.i("JdStartupAds", "installed splash ad suppression")
        }.onFailure {
            android.util.Log.e("JdStartupAds", "splash suppression contract failed", it)
        }
    }
}
