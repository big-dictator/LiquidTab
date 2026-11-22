package io.github.offlineglass.hook.adapters.quora

import android.content.Context
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Enables Quora's own device-theme/cookie/UPDATE_THEME path; no theme enum mapping. */
internal object QuoraSystemTheme {
    private var installed=false
    fun install(context: Context,loader: ClassLoader) {
        if(installed)return
        val app=context.applicationContext ?: context
        runCatching {
            // Stable, unobfuscated business API verified in the current APK.
            // Require its complete theme contract before changing a getter.
            val type=loader.loadClass("com.quora.android.util.QThemeUtil")
            val getter=type.getDeclaredMethod("getUseDeviceTheme")
            require(getter.returnType==java.lang.Boolean.TYPE)
            require(type.getDeclaredMethod("setUseDeviceTheme",java.lang.Boolean.TYPE).returnType==java.lang.Void.TYPE)
            require(type.getDeclaredMethod("setCookies").returnType==java.lang.Void.TYPE)
            require(type.getDeclaredMethod("broadcastThemeUpdate").returnType==java.lang.Void.TYPE)
            val theme=type.getDeclaredMethod("getTheme").returnType
            require(theme.isEnum && theme.enumConstants.map { (it as Enum<*>).name }.containsAll(listOf("LIGHT","DARK")))
            XposedBridge.hookMethod(getter,object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if(HookConfigReader.read(app,"com.quora.android").enabled)param.result=true
                }
            })
            installed=true
            XposedBridge.log("[QuoraTheme] native device-theme contract enabled; preferences preserved")
        }.onFailure { XposedBridge.log("[QuoraTheme] device-theme contract unavailable: $it") }
    }
}
