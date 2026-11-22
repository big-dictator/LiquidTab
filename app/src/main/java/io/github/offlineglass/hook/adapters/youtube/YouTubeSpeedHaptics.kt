package io.github.offlineglass.hook.adapters.youtube

import android.content.Context
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Replace only the verified native long-hold callback's pulse. Native delay,
 * movement cancellation, release, speed and system feedback policy still own it. */
internal object YouTubeSpeedHaptics {
    private const val PACKAGE = "com.google.android.youtube"
    private var installed = false
    private val original = VibrationEffect.createOneShot(25L, 255)
    // Request the device/OEM click waveform, rather than approximating a
    // click with a sustained motor pulse. No Bilibili adapter dependency.
    private const val PRESET = VibrationEffect.EFFECT_HEAVY_CLICK
    private val replacement = VibrationEffect.createPredefined(PRESET)
    private val owners = WeakHashMap<Runnable, WeakReference<Context>>()
    private val active = ThreadLocal<Context?>()
    private val scopes = ThreadLocal<IdentityHashMap<XC_MethodHook.MethodHookParam, Context?>>()
    private var reported = false

    @Synchronized fun install(context: Context, loader: ClassLoader) {
        if (installed || context.packageName != PACKAGE) return
        runCatching {
            val binding = YouTubeHapticsBinding.resolve(context.applicationInfo.sourceDir).singleOrNull()
                ?: error("Native long-hold haptics role missing or ambiguous")
            val controller = Class.forName(binding.controller(), false, loader)
            val callback = Class.forName(binding.callback(), false, loader)
            check(Runnable::class.java.isAssignableFrom(callback))
            val constructor = controller.getDeclaredConstructor(Context::class.java, Handler::class.java)
            val run = callback.getDeclaredMethod("run")
            check(run.returnType == Void.TYPE && !Modifier.isStatic(run.modifiers))
            val fields = controller.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                .onEach { it.isAccessible = true }
            val handles = ArrayList<XC_MethodHook.Unhook>()
            try {
                handles += XposedBridge.hookMethod(Vibrator::class.java.getDeclaredMethod("vibrate", VibrationEffect::class.java),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (active.get() == null || param.args.firstOrNull() != original ||
                                HookConfigReader.startupSnapshot(PACKAGE)?.enabled != true) return
                            param.args[0] = replacement
                            if (!reported) {
                                reported = true
                                XposedBridge.log("[YouTubeSpeedHaptics] native hold pulse -> OEM HEAVY_CLICK")
                            }
                        }
                    })
                handles += XposedBridge.hookMethod(run, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val runnable = param.thisObject as? Runnable ?: return
                        val owner = synchronized(owners) { owners[runnable]?.get() } ?: return
                        val stack = scopes.get() ?: IdentityHashMap<MethodHookParam, Context?>().also(scopes::set)
                        stack[param] = active.get()
                        active.set(owner)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stack = scopes.get() ?: return
                        if (!stack.containsKey(param)) return
                        val previous = stack.remove(param)
                        if (previous == null) active.remove() else active.set(previous)
                        if (stack.isEmpty()) scopes.remove()
                    }
                })
                handles += XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val ownerContext = param.args.firstOrNull() as? Context ?: return
                        if (ownerContext.packageName != PACKAGE) return
                        val values = fields.map { it.get(param.thisObject) }
                        val vibrator = values.filterIsInstance<Vibrator>().singleOrNull() ?: return
                        if (!vibrator.hasVibrator()) return
                        // Do not silently substitute another sustained pulse
                        // through the platform's unsupported-effect fallback.
                        if (vibrator.areEffectsSupported(PRESET).firstOrNull() != Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                            XposedBridge.log("[YouTubeSpeedHaptics] OEM click unavailable; native feedback retained")
                            return
                        }
                        if (values.filterIsInstance<Handler>().singleOrNull() !== param.args[1]) return
                        val runnable = values.filterIsInstance<Runnable>().singleOrNull() ?: return
                        if (!callback.isInstance(runnable)) return
                        val effects = values.mapNotNull { value ->
                            if (value == null) null else if (value is VibrationEffect) value else runCatching {
                                value.javaClass.getMethod("get").invoke(value) as? VibrationEffect
                            }.getOrNull()
                        }
                        if (effects.singleOrNull() != original) return
                        synchronized(owners) { owners[runnable] = WeakReference(ownerContext) }
                        XposedBridge.log("[YouTubeSpeedHaptics] bound native long-hold controller")
                    }
                })
                installed = true
                XposedBridge.log("[YouTubeSpeedHaptics] capability binding ${binding.controller()} / ${binding.callback()}")
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() } }
                throw error
            }
        }.onFailure { XposedBridge.log("[YouTubeSpeedHaptics] original feedback retained: $it") }
    }
}
