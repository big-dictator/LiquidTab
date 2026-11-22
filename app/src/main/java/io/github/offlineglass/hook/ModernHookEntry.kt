package io.github.offlineglass.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage

/** The only framework entry; all adapter routing remains in HookEntry. */
class ModernHookEntry : XposedModule() {
    private var processName = ""
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        XposedBridge.initialize(this)
        android.util.Log.i("LiquidTabModern", "api=$apiVersion framework=$frameworkName process=$processName")
    }
    override fun onPackageReady(param: PackageReadyParam) {
        val input = XC_LoadPackage.LoadPackageParam().apply {
            packageName = param.packageName
            processName = this@ModernHookEntry.processName
            classLoader = param.classLoader
        }
        HookEntry().handleLoadPackage(input)
    }
}
