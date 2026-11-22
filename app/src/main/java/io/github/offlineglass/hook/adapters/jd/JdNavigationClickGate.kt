package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.content.Context
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** The proxy already coalesces taps before the owned native dispatch.
 * Consume JD's additional 800ms UI debounce only for our one owned click. */
internal object JdNavigationClickGate {
    private val owned = ThreadLocal<Boolean>()
    @Volatile var installed = false
        private set
    private val preparing = AtomicBoolean(false)

    fun prepare(context: Context, loader: ClassLoader) {
        if (!preparing.compareAndSet(false, true)) return
        val apk = context.applicationInfo.sourceDir
        val cache = java.io.File(context.cacheDir, "liquidtab-jd-click-contract-v1.properties").path
        // One APK-identity-cached discovery, never a UI-frame/reflection scan.
        Thread({
            runCatching {
                val binding = JdClickContractIndex.resolveCached(apk, cache)
                val type = Class.forName(binding.owner(), false, loader)
                val method = type.getDeclaredMethod(binding.method())
                val timestamp = type.getDeclaredField(binding.timestamp()).apply { isAccessible = true }
                check(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType &&
                    Modifier.isStatic(timestamp.modifiers) && timestamp.type == Long::class.javaPrimitiveType)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (owned.get() != true) return
                        owned.remove()
                        if (runCatching { timestamp.setLong(null, SystemClock.elapsedRealtime()) }.isSuccess)
                            param.result = false
                    }
                })
                installed = true
            }.onFailure { XposedBridge.log("LiquidTab JD navigation cooldown capability unavailable: $it") }
        }, "LiquidTab-JdNavContract").apply { isDaemon = true; start() }
    }

    fun click(action: () -> Unit) {
        owned.set(true)
        try { action() } finally { owned.remove() }
    }
}
