package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.WeakHashMap

/** Native 40.6.0 feed output selection, verified against the installed APK.
 * The player creates its own texture wrapper and retains its native lifecycle.
 */
internal object DouyinPlayerTextureHooks {
    private val feedScope = ThreadLocal.withInitial { 0 }
    private val textures = WeakHashMap<TextureView, Boolean>()
    private var lastActiveTexture = java.lang.ref.WeakReference<TextureView>(null)
    private class GeometryScratch {
        val hostLocation = IntArray(2)
        val visible = Rect()
    }
    private val geometryScratch = ThreadLocal.withInitial { GeometryScratch() }
    private var installed = false
    private val diagnostics = ArrayDeque<String>()
    private val scopedCalls = java.util.Collections.synchronizedMap(WeakHashMap<XC_MethodHook.MethodHookParam, Boolean>())
    private val selectedCalls = java.util.Collections.synchronizedMap(WeakHashMap<XC_MethodHook.MethodHookParam, Boolean>())

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        if (installed || param.processName != param.packageName) return
        runCatching {
            val feed = XposedHelpers.findClass("com.ss.android.ugc.aweme.feed.ui.FeedVideoPlayerView", param.classLoader)
            val factory = XposedHelpers.findClass("X.0wMt", param.classLoader)
            factory.getDeclaredMethod("LJIILIIL", ViewGroup::class.java, Boolean::class.javaPrimitiveType)
            feed.getDeclaredMethod("LJJIJIIJIL")
            XposedBridge.hookAllMethods(feed, "LJJIJIIJIL", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val parent = runCatching { feed.getDeclaredField("a").apply { isAccessible = true }.get(p.thisObject) as? ViewGroup }.getOrNull() ?: return
                    if (!eligible(parent.context)) return
                    scopedCalls[p] = true
                    feedScope.set(feedScope.get() + 1)
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (scopedCalls.remove(p) == true) feedScope.set((feedScope.get() - 1).coerceAtLeast(0))
                }
            })
            XposedBridge.hookAllMethods(factory, "LJIILIIL", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (feedScope.get() == 0) {
                        val parent = p.args.firstOrNull() as? ViewGroup ?: return
                        // X.0wei is the installed APK's Horizon LivePhotoPlayer,
                        // verified through LivePhotoPlayer$onCompleteObserver$1.
                        val livePhoto = hasLivePhotoVideoHost(parent) ||
                            Thread.currentThread().stackTrace.any { it.className == "X.0wei" || it.className == "X.0weh" }
                        // Horizon prerender may use an application Context;
                        // its exact LivePhoto host, rather than Context type,
                        // identifies this path.
                        if (!livePhoto || !eligible(parent.context, requireHomepage = false)) return
                    }
                    selectedCalls[p] = true
                    p.args[1] = false // Verified native else branch: X.0DmT(parent).
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val selected = selectedCalls.remove(p) == true
                    if (p.result == null) return
                    val output = runCatching { XposedHelpers.callMethod(p.result, "getView") as? View }.getOrNull() ?: return
                    val parent = p.args.firstOrNull() as? ViewGroup
                    val chain = ArrayList<String>()
                    var node: View? = parent
                    repeat(8) { node?.let { v ->
                        chain += "${v.javaClass.simpleName}/${runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("-")}"
                        node = v.parent as? View
                    } }
                    synchronized(diagnostics) {
                        if (diagnostics.size >= 12) diagnostics.removeFirst()
                        diagnostics.addLast("factory selected=$selected output=${output.javaClass.simpleName} context=${parent?.context?.javaClass?.name} chain=$chain")
                    }
                    if (!selected) return
                    val texture = output as? TextureView ?: return
                    synchronized(textures) { textures[texture] = true }
                    Log.i("OfflineGlassDouyin", "native feed texture selected: ${texture.javaClass.name}")
                }
            })
            installed = true
            installLivePreview(param)
            Log.i("OfflineGlassDouyin", "native feed texture hooks installed")
        }.onFailure { Log.w("OfflineGlassDouyin", "native texture unavailable; retain capture fallback", it) }
    }

    /** 40.6.0 LivePlayerView.LJII uses config.LJII to create its native renderer.
     * Keep the player's own renderer construction and surface lifecycle.
     * Scope to homepage contexts, leaving full live rooms and other apps alone.
     */
    private fun installLivePreview(param: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val player = XposedHelpers.findClass("com.bytedance.android.livesdkapi.roomplayer.LivePlayerView", param.classLoader)
            player.getDeclaredMethod("LJII", Context::class.java)
            val configField = player.getDeclaredField("config").apply { isAccessible = true }
            val configClass = configField.type
            val modeField = configClass.getDeclaredField("LJII").apply { isAccessible = true }
            val keepTexture = modeField.type.enumConstants.first { (it as Enum<*>).name == "KEEP_TEXTURE_RENDER_VIEW" }
            XposedBridge.hookAllMethods(player, "LJII", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val context = p.args.firstOrNull() as? Context ?: return
                    if (!eligible(context)) return
                    val config = configField.get(p.thisObject) ?: return
                    val mode = modeField.get(config) as? Enum<*> ?: return
                    if (mode.name == "SURFACE_VIEW") modeField.set(config, keepTexture)
                    selectedCalls[p] = true
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (selectedCalls.remove(p) != true) return
                    val texture = p.result as? TextureView ?: return
                    synchronized(textures) { textures[texture] = true }
                    synchronized(diagnostics) {
                        if (diagnostics.size >= 12) diagnostics.removeFirst()
                        diagnostics.addLast("live-preview native texture=${texture.javaClass.name}")
                    }
                }
            })
            Log.i("OfflineGlassDouyin", "native live preview texture hook installed")
        }.onFailure { Log.w("OfflineGlassDouyin", "live preview retains capture fallback", it) }
    }

    fun drainDiagnostics(log: (String) -> Unit) {
        val pending = synchronized(diagnostics) {
            if (diagnostics.isEmpty()) return
            diagnostics.toList().also { diagnostics.clear() }
        }
        pending.forEach(log)
    }

    private fun eligible(context: Context, requireHomepage: Boolean = true): Boolean {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        if (version != "40.6.0" || File(context.getExternalFilesDir(null), "dy_capture_fallback").exists()) return false
        if (!requireHomepage) return true
        var current = context
        repeat(12) {
            if (current.javaClass.name == "com.ss.android.ugc.aweme.main.MainActivity" ||
                current.javaClass.name == "com.ss.android.ugc.aweme.splash.SplashActivity") return true
            val base = (current as? ContextWrapper)?.baseContext ?: return false
            if (base === current) return false
            current = base
        }
        return false
    }

    private fun hasLivePhotoVideoHost(parent: View): Boolean {
        // Exact video FrameLayout inflated by X.0wei (Horizon LivePhotoPlayer).
        // Also covers native OPlayer prerender, whose call arrives before play().
        var view: View? = parent
        var horizon = false
        var photos = false
        val pinchVideo = parent.javaClass.simpleName == "VideoPinchViewContainer"
        repeat(16) {
            if (view?.id == 2131389877) return true
            horizon = horizon || view?.javaClass?.simpleName == "HorizonViewPager"
            photos = photos || view?.javaClass?.simpleName == "FeedPhotosGestureDispatchLayout"
            // Actual moving-image page uses Horizon's ordinary video holder,
            // not the separate qeu LivePhoto frame (confirmed by factory log).
            if (pinchVideo && horizon && photos) return true
            view = view?.parent as? View ?: return false
        }
        return false
    }

    fun isNativeTexture(view: View): Boolean = view is TextureView &&
        synchronized(textures) { textures.containsKey(view) }

    /** Small registered-player set, avoiding per-frame traversal of the app tree. */
    fun activeTexture(host: View): TextureView? {
        val scratch = geometryScratch.get()
        val h = scratch.hostLocation.also(host::getLocationOnScreen)
        val visible = scratch.visible
        return synchronized(textures) {
            val retained = lastActiveTexture.get()
            if (retained != null && textures.containsKey(retained) &&
                usable(retained, host, h, visible)) return@synchronized retained
            val next = textures.keys.firstOrNull { usable(it, host, h, visible) }
            if (next !== retained) lastActiveTexture = java.lang.ref.WeakReference(next)
            next
        }
    }

    private fun usable(texture: TextureView, host: View, h: IntArray, visible: Rect): Boolean =
        texture.isAttachedToWindow && texture.isShown && texture.isAvailable &&
            texture.width >= host.width / 2 && texture.height >= host.height &&
            // Check actual clipping every frame; do not cache geometry across swipes.
            texture.getGlobalVisibleRect(visible) &&
            visible.left < h[0] + host.width && visible.right > h[0] &&
            visible.top < h[1] + host.height && visible.bottom > h[1]
}
