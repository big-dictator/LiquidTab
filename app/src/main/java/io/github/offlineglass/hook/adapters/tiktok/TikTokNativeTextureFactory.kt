package io.github.offlineglass.hook.adapters.tiktok

import android.app.Application
import android.content.Context
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier
import java.util.Properties
import java.util.WeakHashMap

/** Select the existing native texture branch, without replacing player objects. */
internal object TikTokNativeTextureFactory {
    private var bootstrapped = false
    private var installed = false
    fun bootstrap(loader: ClassLoader) {
        if (bootstrapped) return
        bootstrapped = true
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.args.firstOrNull() as? Context ?: return
                if (context.packageName != "com.zhiliaoapp.musically") return
                runCatching { install(context, loader) }.onFailure {
                    android.util.Log.e("TikTokGlassDiag", "native texture contract unresolved", it)
                }
            }
        })
    }
    private fun binary(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    private fun install(context: Context, loader: ClassLoader) {
        if (installed) return
        val info = context.applicationInfo
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val identity = "native-texture-contract-1|" + paths.joinToString("|") { path -> java.io.File(path).let { "$path:${it.length()}:${it.lastModified()}" } }
        val cache = java.io.File(context.codeCacheDir, "liquidtab-tiktok-texture.properties")
        val values = Properties()
        if (cache.isFile) runCatching { cache.inputStream().use(values::load) }
        val binding = if (values.getProperty("identity") == identity && values.getProperty("surfaceField") != null)
            TikTokTextureContract.Binding(values.getProperty("factory"), values.getProperty("method"),
                values.getProperty("config"), values.getProperty("surfaceField"))
        else TikTokTextureContract.inspect(paths.toTypedArray())
        val holder = Class.forName(binary(binding.factory()), false, loader)
        val config = Class.forName(binary(binding.config()), false, loader)
        val player = Class.forName("com.ss.android.ugc.aweme.feed.adapter.playerview.BaseFeedPlayerView", false, loader)
        check(player.getMethod("getSurfaceHolder").returnType.isAssignableFrom(holder))
        check(View::class.java.isAssignableFrom(holder.getMethod("getView").returnType))
        check(holder.getMethod("getSurface").returnType == Surface::class.java)
        val factory = holder.getDeclaredMethod(binding.configMethod(), config)
        check(Modifier.isStatic(factory.modifiers) && factory.returnType == holder)
        val parent = config.declaredFields.single { it.type == ViewGroup::class.java }.apply { isAccessible = true }
        val surface = config.getDeclaredField(binding.surfaceField()).apply { isAccessible = true }
        check(surface.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(surface.modifiers))
        val bool = Boolean::class.javaPrimitiveType!!
        val alternate = holder.declaredMethods.single { Modifier.isStatic(it.modifiers) && it.returnType == holder &&
            it.parameterTypes.contentEquals(arrayOf(ViewGroup::class.java, bool, bool)) }
        val initialize = holder.declaredMethods.single { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(bool, holder, ViewGroup::class.java)) }
        fun eligible(view: Any?): Boolean {
            val group = view as? ViewGroup ?: return false
            return AdapterNavigationSearch.resourceEntryName(group) == "player_view" &&
                group.context.packageName == "com.zhiliaoapp.musically" &&
                group.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                HookConfigReader.read(group.context, group.context.packageName).enabled
        }
        fun observe(value: Any?) {
            val texture = value?.let { holder.getMethod("getView").invoke(it) } as? TextureView ?: return
            TikTokTextureHooks.observe(texture)
            TikTokVideoCenter.prepare(texture)
        }
        val originals = java.util.Collections.synchronizedMap(WeakHashMap<XC_MethodHook.MethodHookParam, Boolean>())
        XposedBridge.hookMethod(factory, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val value = param.args.firstOrNull() ?: return
                if (!eligible(parent.get(value))) return
                originals[param] = surface.getBoolean(value)
                surface.setBoolean(value, false)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = originals.remove(param) ?: return
                surface.setBoolean(param.args[0], original)
                if (!param.hasThrowable()) observe(param.result)
            }
        })
        XposedBridge.hookMethod(alternate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (eligible(param.args.firstOrNull())) { param.args[1] = false; param.args[2] = false }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (eligible(param.args.firstOrNull()) && !param.hasThrowable()) observe(param.result)
            }
        })
        XposedBridge.hookMethod(initialize, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (eligible(param.args.getOrNull(2))) param.args[0] = false
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (eligible(param.args.getOrNull(2)) && !param.hasThrowable()) observe(param.args[1])
            }
        })
        installed = true
        values.clear()
        values.setProperty("identity", identity)
        values.setProperty("factory", binding.factory())
        values.setProperty("method", binding.configMethod())
        values.setProperty("config", binding.config())
        values.setProperty("surfaceField", binding.surfaceField())
        runCatching { cache.outputStream().use { values.store(it, "Discovered native texture contract; APK-identity scoped") } }
        android.util.Log.i("TikTokGlassDiag", "native texture factory contract installed")
    }
}
