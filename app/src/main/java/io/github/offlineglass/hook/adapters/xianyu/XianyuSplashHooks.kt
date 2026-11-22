package io.github.offlineglass.hook.adapters.xianyu

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** No implementation class/field names: the native protocol registry supplies the owner. */
internal object XianyuSplashHooks {
    private val attempted = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    fun install(loader: ClassLoader) {
        runCatching {
            val protocol = Class.forName("com.taobao.idlefish.protocol.splashad.PSplashAdController", false, loader)
            check(protocol.isInterface)
            val registry = Class.forName("com.taobao.idlefish.xmc.XModuleCenter", false, loader)
            val getter = registry.getDeclaredMethod("moduleForProtocol", Class::class.java)
            check(Modifier.isStatic(getter.modifiers) && getter.returnType.isInterface &&
                getter.returnType.isAssignableFrom(protocol))
            val timeline = Class.forName("com.taobao.idlefish.launcher.startup.performance.FishTimeline", false, loader)
            val finish = timeline.getDeclaredMethod("signalAdvEnd")
            check(Modifier.isStatic(finish.modifiers) && finish.returnType == Void.TYPE)
            val registryHandle = arrayOfNulls<XC_MethodHook.Unhook>(1)
            registryHandle[0] = XposedBridge.hookMethod(getter, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() !== protocol) return
                    val controller = param.result?.takeIf(protocol::isInstance) ?: return
                    val owner = controller.javaClass
                    if (!attempted.add(owner)) return
                    runCatching {
                        val binding = XianyuSplashContract.resolve(owner, protocol, Activity::class.java)
                        listOf(binding.cold(), binding.hot(), binding.share(), binding.prefetch(),
                            binding.setShowing(), binding.releaseActions()).forEach { it.isAccessible = true }
                        val handles = mutableListOf<XC_MethodHook.Unhook>()
                        val finishing = ThreadLocal<Boolean>()
                        fun complete(instance: Any) {
                            val release = Runnable {
                                if (finishing.get() == true) return@Runnable
                                finishing.set(true)
                                try {
                                    binding.setShowing().invoke(instance, false)
                                    finish.invoke(null)
                                    binding.releaseActions().invoke(instance)
                                } finally { finishing.remove() }
                            }
                            if (Looper.myLooper() == Looper.getMainLooper()) release.run()
                            else Handler(Looper.getMainLooper()).post(release)
                        }
                        try {
                            for (entry in listOf(binding.cold(), binding.hot(), binding.share())) {
                                handles += XposedBridge.hookMethod(entry, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        complete(param.thisObject)
                                        param.result = null
                                        android.util.Log.i("LiquidTab-Xianyu", "Blocked native splash: ${entry.name}")
                                    }
                                })
                            }
                            handles += XposedBridge.hookMethod(binding.prefetch(), object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                            })
                        } catch (error: Throwable) {
                            handles.forEach { it.unhook() }
                            throw error
                        }
                        // Class hooks apply to every rebuilt instance; the APK
                        // registry need not stay hooked on its hot module path.
                        registryHandle[0]?.unhook()
                        android.util.Log.i("LiquidTab-Xianyu", "Native splash contract bound; completion preserved")
                    }.onFailure { XposedBridge.log("LiquidTab Xianyu: splash contract unavailable: $it") }
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab Xianyu: splash registry unavailable: $it") }
    }
}
