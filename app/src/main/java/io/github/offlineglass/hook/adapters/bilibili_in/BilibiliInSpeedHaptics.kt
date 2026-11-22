package io.github.offlineglass.hook.adapters.bilibili_in

import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

/** International player only: preserve native View haptics; replace sustained motor pulses. */
internal object BilibiliInSpeedHaptics {
    private var installed = false
    private var lastPulse = Long.MIN_VALUE
    private val inFlight = ThreadLocal.withInitial { false }
    private val durationGetter by lazy {
        runCatching { VibrationEffect::class.java.getDeclaredMethod("getDuration").apply { isAccessible = true } }.getOrNull()
    }
    private val click by lazy { VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) }

    private fun speedPress(): Boolean = Throwable().stackTrace.any {
        val player = it.className.startsWith("com.bilibili.ship.theseus.united.player.") ||
            it.className.startsWith("com.bilibili.ship.theseus2.united.player.") ||
            it.className.startsWith("tv.danmaku.biliplayerv2.") ||
            it.className.startsWith("com.bilibili.playerbizcommonv2.") ||
            it.className.startsWith("com.bilibili.playerbizcommon3.")
        player && (it.methodName == "onLongPress" || it.className.contains("TripleSpeedService"))
    }

    fun install() {
        if (installed) return
        installed = true
        // OEMs can override the platform entry point. Hook actual effect-bearing overloads
        // as well as the public facade; keep attributes/reason/UID untouched.
        val types = linkedSetOf<Class<*>>(Vibrator::class.java)
        runCatching { Class.forName("android.os.SystemVibrator") }.getOrNull()?.let { types += it }
        for (type in types) for (method in type.declaredMethods) {
            if (method.name != "vibrate" || Modifier.isAbstract(method.modifiers)) continue
            val effectIndex = method.parameterTypes.indexOfFirst { VibrationEffect::class.java.isAssignableFrom(it) }
            val legacy = method.parameterCount == 1 && method.parameterTypes[0] == Long::class.javaPrimitiveType
            if (effectIndex < 0 && !legacy) continue
            val entered = ThreadLocal.withInitial { ArrayList<Boolean>() }
            runCatching { XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ownsCall = inFlight.get() != true && speedPress()
                    entered.get()!!.add(ownsCall)
                    if (!ownsCall) return
                    inFlight.set(true)
                    val now = SystemClock.uptimeMillis()
                    // Delegation through several overloads is ONE pulse, not a duplicate.
                    if (lastPulse != Long.MIN_VALUE && now-lastPulse < 80L) { param.result = null; return }
                    if (effectIndex >= 0) {
                        val original = param.args[effectIndex] as? VibrationEffect ?: return
                        // Both installed apps' legacy service emits this 30ms sustained pulse.
                        // Native predefined/primitive effects and View feedback remain app-owned.
                        if ((durationGetter?.invoke(original) as? Long) != 30L) return
                        param.args[effectIndex] = click
                    } else {
                        val vibrator = param.thisObject as? Vibrator ?: return
                        if (param.args[0] != 30L) return
                        vibrator.vibrate(click)
                        param.result = null
                    }
                    lastPulse = now
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = entered.get()!!
                    if (stack.isNotEmpty() && stack.removeAt(stack.lastIndex)) inFlight.set(false)
                }
            }) }.onFailure { XposedBridge.log("LiquidTab international speed vibration overload: $it") }
        }
    }
}
