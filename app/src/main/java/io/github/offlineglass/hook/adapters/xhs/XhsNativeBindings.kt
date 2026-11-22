package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** APK-scoped semantic bindings shared by layout, native actions and artwork. */
internal object XhsNativeBindings {
    @Volatile private var fields: Map<String, Field> = emptyMap()
    @Volatile private var methods: Map<String, Method> = emptyMap()
    @Volatile var ready = false
        private set
    private val started = AtomicBoolean()
    fun field(role: String) = fields[role]
    fun method(role: String) = methods[role]
    fun install(loader: ClassLoader) {
        fun prepare(context: Context) {
            if (!started.compareAndSet(false, true)) return
            val app = context.applicationContext ?: context
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val began = android.os.SystemClock.uptimeMillis()
                runCatching {
                    val paths = arrayOf(app.applicationInfo.sourceDir, *app.applicationInfo.splitSourceDirs.orEmpty())
                    val roles = XhsNativeContract.resolve(paths, app.codeCacheDir.resolve("liquidtab-xhs-contract.properties").path)
                    val fs = mutableMapOf<String, Field>(); val ms = mutableMapOf<String, Method>()
                    for (role in listOf("maxHeight", "lottieFilter", "channelTitle")) runCatching {
                        val parts = roles.getProperty(role)?.split('|') ?: return@runCatching
                        fs[role] = type(parts[0], loader).getDeclaredField(parts[1]).apply {
                            check(type == type(parts[2], loader)); isAccessible = true
                            check(java.lang.reflect.Modifier.isStatic(modifiers) == (role == "lottieFilter"))
                        }
                    }.onFailure { android.util.Log.w("LiquidTabXhsBinding", "$role interface rejected", it) }
                    for (role in listOf("sidebar", "coldError", "coldGetter", "coldCompletion", "coldHandoff", "coldLinker", "hotFailure", "hotLoad", "hotShow")) runCatching {
                        val parts = roles.getProperty(role)?.split('|') ?: return@runCatching
                        ms[role] = type(parts[0], loader).getDeclaredMethod(parts[1],
                            *parts[3].split(',').filter(String::isNotEmpty).map { type(it, loader) }.toTypedArray()).apply {
                            check(returnType == type(parts[2], loader)); isAccessible = true
                        }
                    }.onFailure { android.util.Log.w("LiquidTabXhsBinding", "$role interface rejected", it) }
                    fields = fs; methods = ms; ready = true
                    XhsOriginalImageExport.install(roles, loader, app)
                    XhsSplashAdsHooks.installResolved(roles, loader, ms)
                    android.util.Log.i("LiquidTabXhsBinding", "native contracts ${android.os.SystemClock.uptimeMillis() - began}ms: fields=${fs.keys}, methods=${ms.keys}; " +
                        roles.stringPropertyNames().filter { it.endsWith(".count") }.sorted().joinToString { "$it=${roles[it]}" })
                }.onFailure { android.util.Log.w("LiquidTabXhsBinding", "APK contract discovery unavailable", it) }
            }, "LiquidTab-XhsNativeBinding").apply { isDaemon = true }.start()
        }
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { (param.args.firstOrNull() as? Context)?.let(::prepare) }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { (param.thisObject as? Context)?.let(::prepare) }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { (param.thisObject as? Context)?.let(::prepare) }
        })
        // Hook installation may occur after attach/onCreate; bootstrap from the
        // platform application accessor rather than waiting for a new Activity.
        runCatching {
            (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context)?.let(::prepare)
        }
    }
    fun type(descriptor: String, loader: ClassLoader): Class<*> = when (descriptor) {
        "V" -> Void.TYPE; "I" -> Int::class.javaPrimitiveType!!; "Z" -> Boolean::class.javaPrimitiveType!!
        "J" -> Long::class.javaPrimitiveType!!; "F" -> Float::class.javaPrimitiveType!!; "D" -> Double::class.javaPrimitiveType!!
        "B" -> Byte::class.javaPrimitiveType!!; "S" -> Short::class.javaPrimitiveType!!; "C" -> Char::class.javaPrimitiveType!!
        else -> loader.loadClass(if (descriptor.startsWith("[")) descriptor.replace('/', '.') else descriptor.removePrefix("L").removeSuffix(";").replace('/', '.'))
    }
}
