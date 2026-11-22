package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Tholins' ART trampolines conflict with the injected runtime on this device.
 * NativeBridge.init already reports failure and its Java callers support that
 * fallback. Do not patch ART, native code, or unrelated initialization. */
internal object DouyinStartupCompatibility {
    private var installed = false
    fun install(param: XC_LoadPackage.LoadPackageParam) {
        if (installed || param.processName != param.packageName) return
        runCatching {
            val bridge = Class.forName("com.bytedance.tholins.NativeBridge", false, param.classLoader)
            val method = bridge.getDeclaredMethod("init", Context::class.java)
            require(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setResult(false)
                }
            }
            XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                .invoke(null, method, hook)
            installed = true
            XposedBridge.log("[LiquidTab] Douyin Tholins initialization uses Java failure fallback")
        }.onFailure {
            XposedBridge.log("[LiquidTab] Douyin startup compatibility unavailable: $it")
        }
    }
}
