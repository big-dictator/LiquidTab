package io.github.offlineglass.hook.adapters.taobao

import android.app.Application
import android.content.Context
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.glassHostBottomMarginPx
import java.lang.ref.WeakReference

/** Edits only the verified initial Flash resource delivered by Taobao's original downloader. */
internal object TaobaoFlashResource {
    @Volatile private var application = WeakReference<Application>(null)
    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.taobao.android.weex.WeexInstanceImpl", false, loader)
            val context = type.getMethod("getContext")
            XposedBridge.hookAllMethods(type, "initWithURL", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val address = param.args.firstOrNull() as? String ?: return
                    if (address.substringBefore('?') != "https://tb.ele.me/app/TBTakeout/et-home/home") return
                    runCatching {
                        val app = (context.invoke(param.thisObject) as? Context)?.applicationContext as? Application ?: return
                        application = WeakReference(app)
                    }
                }
            })
            val download = Class.forName("com.taobao.android.weex.bridge.WeexPlatformDownLoadBridge", false, loader)
            XposedBridge.hookAllMethods(download, "DownloadSuccess", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bytes = param.args.getOrNull(1) as? ByteArray ?: return
                    // Prefetch and download callbacks carry different metadata.
                    // Gate by exact program identity, never that optional string.
                    if (bytes.size != 3424752) return
                    val app = application.get() ?: return
                    runCatching {
                        val metrics = app.resources.displayMetrics
                        val config = HookConfigReader.read(app, app.packageName)
                        if (!config.enabled || config.classicNavigation || config.hiddenMask != 0 ||
                            metrics.widthPixels >= metrics.heightPixels || metrics.widthPixels / metrics.density >= 600f) return
                        val reference = View(app).apply { layout(0, 0, metrics.widthPixels, metrics.heightPixels) }
                        val reserve = glassHostHeightPx(metrics.density, config, app.packageName, 5) +
                            glassHostBottomMarginPx(reference, config) + 10f * metrics.density
                        val result = TaobaoFlashStyles.decorate(bytes, reserve / metrics.widthPixels * 100f)
                        if (result == null) {
                            app.getExternalFilesDir(null)?.let { folder ->
                                java.io.File(folder, "liquidtab-flash-resource.json").writeText(
                                    org.json.JSONObject().put("state", "resource-unmatched")
                                        .put("inputHash", java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                                            .joinToString("") { "%02x".format(it) })
                                        .put("bytes", bytes.size).toString())
                            }
                            return
                        }
                        // Keep callback identity, cache flags, native pointers and initialization unchanged.
                        param.args[1] = result
                        app.getExternalFilesDir(null)?.let { folder ->
                            java.io.File(folder, "liquidtab-flash-resource.json").writeText(
                                org.json.JSONObject().put("state", "styles-prepared")
                                    .put("version", "12.6.20").put("inputHash", TaobaoFlashStyles.HASH)
                                    .put("bytes", result.size).put("pid", android.os.Process.myPid()).toString())
                        }
                    }
                }
            })
        }
    }
}
