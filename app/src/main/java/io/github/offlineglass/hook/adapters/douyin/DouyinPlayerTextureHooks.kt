package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import java.io.File
import java.util.WeakHashMap

/** Native feed output selection, verified against 40.6.0 and 40.7.0 APKs.
 * The player creates its own texture wrapper and retains its native lifecycle.
 */
internal object DouyinPlayerTextureHooks {
    /** Registered renderer identity also excludes letterboxed live media from
     * structural control lifting, regardless of its current visible size. */
    fun isPlayerTexture(view: TextureView): Boolean = synchronized(textures) { textures.containsKey(view) }

    private var livePhotoOwners: Set<String> = emptySet()
    private val feedScope = ThreadLocal.withInitial { 0 }
    private val textures = WeakHashMap<TextureView, Boolean>()
    private var lastActiveTexture = java.lang.ref.WeakReference<TextureView>(null)
    private class GeometryScratch {
        val hostLocation = IntArray(2)
        val visible = Rect()
    }
    private val geometryScratch = ThreadLocal.withInitial { GeometryScratch() }
    private var installed = false
    private var nativeStatusWritten = false
    private var installedApk: String? = null
    private var installStatus: File? = null
    fun prepare(context: Context) {
        installedApk = context.applicationInfo.sourceDir
        installStatus = context.getExternalFilesDir(null)?.let { File(it, "dy_player_binding_install.txt") }
    }
    private val diagnostics = ArrayDeque<String>()
    private val scopedCalls = java.util.Collections.synchronizedMap(WeakHashMap<XC_MethodHook.MethodHookParam, Boolean>())
    private val selectedCalls = java.util.Collections.synchronizedMap(WeakHashMap<XC_MethodHook.MethodHookParam, Boolean>())

    // The compile-only Xposed stubs expose a subset of the runtime API.
    private fun hookExact(method: java.lang.reflect.Method, hook: XC_MethodHook) {
        XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java).invoke(null, method, hook)
    }

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        if (installed || param.processName != param.packageName) return
        resolveLivePhotoOwners(param.classLoader)
        runCatching {
            val feed = XposedHelpers.findClass("com.ss.android.ugc.aweme.feed.ui.FeedVideoPlayerView", param.classLoader)
            val binding = DouyinPlayerBinding.resolve(requireNotNull(installedApk) { "Target application metadata unavailable" }, feed, param.classLoader)
            val factoryMethod = binding.factory
            val parentField = feed.declaredFields.single { it.type == ViewGroup::class.java }.apply { isAccessible = true }
            Log.i("OfflineGlassDouyin", "structural native binding: ${factoryMethod.declaringClass.name}.${factoryMethod.name} via ${binding.entry.name}")
            hookExact(binding.entry, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val parent = runCatching { parentField.get(p.thisObject) as? ViewGroup }.getOrNull() ?: return
                    if (!eligible(parent.context)) return
                    scopedCalls[p] = true
                    feedScope.set(feedScope.get() + 1)
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (scopedCalls.remove(p) == true) feedScope.set((feedScope.get() - 1).coerceAtLeast(0))
                }
            })
            hookExact(factoryMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (feedScope.get() == 0) {
                        val parent = p.args.firstOrNull() as? ViewGroup ?: return
                        // Homepage factories include photo-video prerender,
                        // which does not run through FeedVideoPlayerView.
                        val livePhoto = hasLivePhotoVideoHost(parent) ||
                            Thread.currentThread().stackTrace.any { it.className in livePhotoOwners }
                        if (!eligible(parent.context) &&
                            !(livePhoto && eligible(parent.context, requireHomepage = false))) return
                    }
                    selectedCalls[p] = true
                    p.args[1] = false // Native texture branch in both verified player factories.
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val selected = selectedCalls.remove(p) == true
                    if (p.result == null) return
                    val output = runCatching { XposedHelpers.callMethod(p.result, "getView") as? View }.getOrNull() ?: return
                    val texture = output as? TextureView ?: return
                    synchronized(textures) { textures[texture] = true }
                    Log.i("OfflineGlassDouyin", "native feed texture selected: ${texture.javaClass.name}")
                    if (!nativeStatusWritten) {
                        nativeStatusWritten = true
                        val context = (p.args.firstOrNull() as? ViewGroup)?.context
                        if (context != null) runCatching {
                            File(context.getExternalFilesDir(null), "dy_native_binding_status.txt").writeText(
                                "time=${System.currentTimeMillis()} factory=${factoryMethod.declaringClass.name}.${factoryMethod.name}\n" +
                                "texture=${texture.javaClass.name} forced=$selected livePhotoOwners=$livePhotoOwners\n")
                        }
                    }
                }
            })
            installed = true
            runCatching { installStatus?.writeText("time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} installed=true factory=${factoryMethod.declaringClass.name}.${factoryMethod.name} entry=${binding.entry.name}\n") }

            Log.i("OfflineGlassDouyin", "native feed texture hooks installed")
        }.onFailure {
            runCatching { installStatus?.writeText("time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} installed=false\n${it.stackTraceToString()}") }
            Log.w("OfflineGlassDouyin", "native texture unavailable; retain capture fallback", it)
        }
        // Feed obfuscation must not disable the independent live-player path.
        installLivePreview(param)
        installed = true
    }

    /** 40.6.0 LivePlayerView.LJII uses config.LJII to create its native renderer.
     * Keep the player's own renderer construction and surface lifecycle.
     * Scope to homepage contexts, leaving full live rooms and other apps alone.
     */
    private fun installLivePreview(param: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val player = XposedHelpers.findClass("com.bytedance.android.livesdkapi.roomplayer.LivePlayerView", param.classLoader)
            val renderType = XposedHelpers.findClass("com.bytedance.android.livesdkapi.view.TextureRenderView", param.classLoader)
            val createRenderer = player.declaredMethods.single {
                it.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                    it.returnType.isInterface && it.returnType.isAssignableFrom(renderType)
            }
            val configField = player.getDeclaredField("config").apply { isAccessible = true }
            val configClass = configField.type
            val modeField = configClass.declaredFields.single { field ->
                field.type.isEnum && field.type.enumConstants.map { (it as Enum<*>).name }.let {
                    "TEXTURE_VIEW" in it && "SURFACE_VIEW" in it
                }
            }.apply { isAccessible = true }
            val liveTexture = modeField.type.enumConstants.first { (it as Enum<*>).name == "TEXTURE_VIEW" }
            hookExact(createRenderer, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val context = p.args.firstOrNull() as? Context ?: return
                    if (!eligible(context)) return
                    val config = configField.get(p.thisObject) ?: return
                    val mode = modeField.get(config) as? Enum<*> ?: return
                    // Live streams use the ordinary native TextureRenderView,
                    // not the retention wrapper intended to reuse live surfaces.
                    if (mode.name == "SURFACE_VIEW" || mode.name == "KEEP_TEXTURE_RENDER_VIEW") {
                        modeField.set(config, liveTexture)
                    }
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
        if (File(context.getExternalFilesDir(null), "dy_capture_fallback").exists()) return false
        if (!requireHomepage) return true
        var current = context
        repeat(12) {
            if (current.javaClass.name == "com.ss.android.ugc.aweme.main.MainActivity" ||
                current.javaClass.name == "com.ss.android.ugc.aweme.splash.SplashActivity" ||
                DouyinDetailCommentGlass.isDetailContext(current)) return true
            val base = (current as? ContextWrapper)?.baseContext ?: return false
            if (base === current) return false
            current = base
        }
        return false
    }

    private fun resolveLivePhotoOwners(loader: ClassLoader) {
        // These descriptive observer classes retain references to their actual
        // owners, so owner obfuscation never needs a hardcoded X.* mapping.
        livePhotoOwners = listOf(
            "com.bytedance.android.horizon.livephoto.LivePhotoPlayer\$onCompleteObserver\$1",
            "com.ss.android.ugc.aweme.horizon.livephoto.MultiLivePhotoPlayer\$onCompleteObserver\$1",
        ).mapNotNull { name ->
            runCatching {
                val observer = Class.forName(name, false, loader)
                observer.declaredConstructors.single { it.parameterCount == 1 }.parameterTypes[0]
                    .takeIf { type -> type.declaredFields.any { ViewGroup::class.java.isAssignableFrom(it.type) } }?.name
            }.getOrNull()
        }.toSet()
    }

    private fun hasLivePhotoVideoHost(parent: View): Boolean {
        // Exact video FrameLayout inflated by X.0wei (Horizon LivePhotoPlayer).
        // Also covers native OPlayer prerender, whose call arrives before play().
        var view: View? = parent
        var horizon = false
        var photos = false
        repeat(16) {
            horizon = horizon || view?.javaClass?.simpleName == "HorizonViewPager"
            photos = photos || view?.javaClass?.simpleName == "FeedPhotosGestureDispatchLayout"
            // Actual moving-image page uses Horizon's ordinary video holder,
            // not the separate qeu LivePhoto frame (confirmed by factory log).
            if (horizon && photos) return true
            view = view?.parent as? View ?: return false
        }
        return false
    }

    fun isNativeTexture(view: View): Boolean = view is TextureView &&
        synchronized(textures) { textures.containsKey(view) }

    /** A native texture remains in the GPU scene while crossing the bar edge.
     * Do not fall back to readback just because this frame has no bar overlap.
     * Require a substantial visible player area, excluding cached offscreen pages.
     */
    fun nativeSceneVisible(host: View): Boolean {
        val root = host.rootView
        val rect = geometryScratch.get().visible
        val minimumArea = root.width.toLong() * root.height / 4
        return synchronized(textures) {
            textures.keys.any { texture ->
                texture.isAttachedToWindow && texture.isShown && texture.isAvailable &&
                    texture.rootView === root && texture.getGlobalVisibleRect(rect) &&
                    rect.width().toLong() * rect.height() >= minimumArea
            }
        }
    }

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

    /** Detail accessories follow the visible pager output, not a retained last
     * texture. Enumerates registered renderers only; never scans the view tree.
     */
    fun detailTexture(host: View): TextureView? {
        val decor = host.rootView
        val scratch = geometryScratch.get()
        val bounds = scratch.visible
        decor.getLocationOnScreen(scratch.hostLocation)
        val centerX = scratch.hostLocation[0] + decor.width / 2
        var best: TextureView? = null
        var bestScore = Long.MIN_VALUE
        synchronized(textures) {
            for (candidate in textures.keys) {
                if (candidate.rootView !== decor || !candidate.isAttachedToWindow || !candidate.isAvailable ||
                    !candidate.isShown || candidate.width < decor.width * .8f || candidate.height < decor.height * .25f) continue
                var ancestor: View? = candidate
                var alpha = 1f
                repeat(32) {
                    val node = ancestor ?: return@repeat
                    alpha *= node.alpha
                    ancestor = node.parent as? View
                }
                if (alpha <= .01f || !candidate.getGlobalVisibleRect(bounds) || bounds.width() < decor.width / 3) continue
                val score = bounds.width().toLong() * 100_000L -
                    kotlin.math.abs(bounds.centerX() - centerX) * 100L + bounds.height()
                if (score > bestScore) { bestScore = score; best = candidate }
            }
        }
        return best
    }

    private fun usable(texture: TextureView, host: View, h: IntArray, visible: Rect): Boolean =
        texture.isAttachedToWindow && texture.isShown && texture.isAvailable &&
            texture.width >= host.width / 2 && texture.height >= host.height &&
            // Check actual clipping every frame; do not cache geometry across swipes.
            texture.getGlobalVisibleRect(visible) &&
            visible.left < h[0] + host.width && visible.right > h[0] &&
            visible.top < h[1] + host.height && visible.bottom > h[1]
}







