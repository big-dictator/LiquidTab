package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.rendering.FirstVisitPresentation
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.Executors
import io.github.offlineglass.hook.adapters.wechat.WeChatPreviewDiagnostics.Stage

/** WeChat-only visual pilot. All pictures originate at runtime, inside WeChat's private cache. */
internal class WeChatFirstVisitPreview(value: GlassHostLayout) : FirstVisitPresentation {
    companion object {
        private val recyclerTypes = object : ClassValue<Boolean>() {
            override fun computeValue(type: Class<*>): Boolean {
                var candidate: Class<*>? = type
                while(candidate!=null) {
                    if(candidate.name=="androidx.recyclerview.widget.RecyclerView")return true
                    candidate=candidate.superclass
                }
                return false
            }
        }
    }
    private data class Binding(val source: ViewGroup, val parent: ViewGroup, val pager: ViewGroup, val current: Method)
    private val host = WeakReference(value)
    private val context = value.context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "LiquidTab-WeChatPreview").apply { isDaemon = true } }
    private val diagnostics=WeChatPreviewDiagnostics(context) { task -> worker.execute(task) }
    private val store = WeChatPageSnapshotStore(File(context.cacheDir, "liquidtab-wechat-first-visit-v1"),diagnostics::mark)
    private val frames = java.util.LinkedHashMap<Int, Bitmap>(4, .75f, true)
    private val loading = HashSet<Int>()
    private var binding: Binding? = null
    private var preferences: SharedPreferences? = null
    private var nativeAccount: SharedPreferences? = null
    private var nextAccountCheck = 0L
    private var accountResolveQueued = false
    private var accountResolveAttempts = 1
    private val nativeAccountOpener by lazy {
        runCatching {
            val type=Class.forName("com.tencent.mmkv.MMKV",false,context.classLoader)
            val open=type.getMethod("mmkvWithID",String::class.java,Int::class.javaPrimitiveType)
            check(java.lang.reflect.Modifier.isStatic(open.modifiers) && SharedPreferences::class.java.isAssignableFrom(open.returnType))
            open to (type.getField("MULTI_PROCESS_MODE").getInt(null) or type.getField("READ_ONLY_MODE").getInt(null))
        }.getOrNull()
    }
    private fun openNativeAccount(): SharedPreferences? = runCatching {
        nativeAccountOpener?.let { (open,mode) -> open.invoke(null,"MMKV_Name_LastLoginInfo",mode) as? SharedPreferences }
    }.getOrNull()
    private var accountHash = ""
    @Volatile private var key = ""
    private var layoutIdentity = ""
    @Volatile private var epoch = 0
    @Volatile private var disposed = false
    @Volatile private var busy = false
    private var allowed = false
    private var actual = -1
    private var lastCapture = 0L
    private var scrollUntil = 0L
    private var pagerX = Int.MIN_VALUE
    private var pagerStillSince = 0L
    private var nextSafetyCheck = 0L
    private var pageBaseColor = android.graphics.Color.WHITE
    private var captureQueued = false
    private var captureInFlight = false
    private var target = -1
    private var waitingForNative = false
    private var visualGeneration = 0
    private var overlay: Picture? = null
    private var sceneSources: List<View>? = null
    private var accessibilityOwner: ViewGroup? = null
    private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var observer: ViewTreeObserver? = null
    private var draw: ViewTreeObserver.OnPreDrawListener? = null
    private var commit: Runnable? = null
    private val position = IntArray(2)
    private val parentPosition = IntArray(2)
    private val capture = Runnable { captureQueued = false; captureNow(false) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
        if (name == "login_weixin_username") changeAccount()
    }
    private val renderNode = runCatching { View::class.java.getDeclaredMethod("updateDisplayListIfDirty").apply { isAccessible = true } }.getOrNull()
    private val snapshot = runCatching { HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
        RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true } }.getOrNull()

    init {
        diagnostics.state(Stage.STARTING)
        // Verified in the APK: native LastLoginInfo mirrors this identifier here.
        // Read no auth tokens, chat databases, pictures or external storage.
        worker.execute {
            val prefs = runCatching { context.getSharedPreferences("notify_key_pref_no_account", Context.MODE_PRIVATE) }.getOrNull()
            runCatching { prefs?.getString("login_weixin_username", "") } // load native preferences off the UI thread
            // LastLoginInfo reads this native MMKV first; the XML above is only
            // a write-time mirror and can be absent for an existing login.
            val native=openNativeAccount()
            host.get()?.post {
                if (!disposed) {
                    preferences = prefs
                    nativeAccount = native
                    prefs?.registerOnSharedPreferenceChangeListener(listener)
                    changeAccount()
                }
            }
        }
    }

    private fun digest(text: String): String {
        val bytes=MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val hex="0123456789abcdef"
        return String(CharArray(bytes.size*2) { i ->
            val value=bytes[i/2].toInt() and 255
            hex[if(i%2==0)value ushr 4 else value and 15]
        })
    }
    private fun currentAccount(): String = runCatching {
        val primary=nativeAccount
        val value=if(primary?.contains("login_weixin_username")==true)
            primary.getString("login_weixin_username","") else preferences?.getString("login_weixin_username", "")
        value?.takeIf { it.isNotBlank() }?.let(::digest).orEmpty()
    }.getOrDefault("")
    private fun changeAccount() {
        if (disposed) return
        val next = currentAccount()
        if (next == accountHash) return
        val previous = accountHash
        cancel(); epoch++; key = ""; layoutIdentity=""; frames.clear(); loading.clear(); binding = null
        accountHash = next
        diagnostics.state(if(next.isEmpty())Stage.ACCOUNT_MISSING else Stage.READY)
        if (previous.isNotEmpty()) worker.execute { runCatching { store.clear() } }
        host.get()?.postInvalidateOnAnimation()
    }

    private fun bind(source: ViewGroup?): Binding? {
        binding?.takeIf { it.source === source && it.parent.isAttachedToWindow && it.pager.parent === it.parent }?.let { return it }
        if (source?.javaClass?.name != "com.tencent.mm.ui.LauncherUIBottomTabView") {
            diagnostics.state(Stage.SOURCE_MISSING);return null
        }
        val parent = source.parent as? ViewGroup ?: return null
        val pager = (0 until parent.childCount).map { parent.getChildAt(it) }
            .filter { WeChatLauncherPages.isMainPager(it) }.singleOrNull() as? ViewGroup
            ?: run { diagnostics.state(Stage.PAGER_MISSING);return null }
        val method = runCatching { pager.javaClass.getMethod("getCurrentItem") }.getOrNull()
            ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
            ?: run { diagnostics.state(Stage.PAGER_API_MISSING);return null }
        diagnostics.state(Stage.READY)
        return Binding(source, parent, pager, method).also { binding = it }
    }
    private fun nativeIndex(b: Binding): Int? = runCatching { b.current.invoke(b.pager) as? Int }.getOrNull()?.takeIf { it in 0..3 }
    private fun activity(view: View): Activity? {
        var owner: Context = view.context
        repeat(12) {
            if (owner is Activity) return owner as Activity
            owner = (owner as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
    private fun safe(h: GlassHostLayout, b: Binding, requireFocus: Boolean = true): Boolean {
        val owner = activity(h) ?: return false
        if (disposed || !allowed || owner.javaClass.name != "com.tencent.mm.ui.LauncherUI" ||
            owner.isFinishing || owner.isDestroyed || (requireFocus && !h.hasWindowFocus()) ||
            owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
            h.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true ||
            !b.source.isAttachedToWindow || b.parent.alpha < .99f || !h.isShown || h.alpha < .99f || h.barVisibilityAnimating) return false
        // Never record a branch containing this module's own glass/overlay.
        var ancestor: View? = h
        repeat(24) { if (ancestor === b.parent) return false; ancestor = ancestor?.parent as? View }
        val queue = ArrayDeque<View>(); queue += owner.window.decorView
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 2048) {
            val v = queue.removeFirst()
            if (!v.isShown || v.alpha < .01f || v is AdapterOwnedOverlay || v === h) continue
            val name = v.javaClass.name
            if ((name.startsWith("com.tencent.mm.ui.chatting.") || name == "com.tencent.mm.ui.tools.TestTimeForChatting") &&
                v.getGlobalVisibleRect(android.graphics.Rect())) return false
            if (v is android.view.SurfaceView || v is android.view.TextureView || v is android.webkit.WebView) {
                // A module-owned compositor elsewhere in the window is not
                // part of the native branch being captured.
                if (within(v,b.parent) && v.getGlobalVisibleRect(android.graphics.Rect())) return false
                continue
            }
            // Containers above a list carry the chat/page identity; rows need no scan.
            if (v is AbsListView || recyclerTypes.get(v.javaClass)) continue
            if (v is ViewGroup) for (i in 0 until v.childCount) queue += v.getChildAt(i)
        }
        return queue.isEmpty() // fail closed if the bounded semantic scan was incomplete
    }
    private fun within(view: View, parent: ViewGroup): Boolean {
        var node: View?=view
        repeat(32) { if(node===parent)return true;node=node?.parent as? View }
        return false
    }

    fun update(h: GlassHostLayout, source: ViewGroup?, canCache: Boolean, themeColor: Int) {
        allowed = canCache
        pageBaseColor=themeColor or 0xff000000.toInt()
        busy = target >= 0 || h.adapterNavigationAnimationRunning
        val now=SystemClock.uptimeMillis()
        if(now>=nextAccountCheck) {
            nextAccountCheck=now+1500L
            if(currentAccount()!=accountHash)changeAccount()
            // Native MMKV may finish initialization after the first pre-draw.
            // Retry the already-verified handle, bounded; never scan APK/classes per frame.
            if(accountHash.isEmpty() && nativeAccount==null && !accountResolveQueued && accountResolveAttempts<8) {
                accountResolveQueued=true;accountResolveAttempts++
                worker.execute {
                    val native=openNativeAccount()
                    h.post {
                        accountResolveQueued=false
                        if(!disposed && native!=null) {nativeAccount=native;changeAccount()}
                    }
                }
            }
        }
        if (!canCache || accountHash.isEmpty()) {
            if(accountHash.isEmpty())diagnostics.state(Stage.ACCOUNT_MISSING)
            if (target >= 0) cancel(); return
        }
        val b = bind(source) ?: return
        val index = nativeIndex(b) ?: return
        if (b.pager.scrollX != pagerX) { pagerX=b.pager.scrollX;pagerStillSince=SystemClock.uptimeMillis() }
        val parent = h.parent as? FrameLayout ?: return
        b.parent.getLocationInWindow(position); parent.getLocationInWindow(parentPosition)
        val cfg = h.resources.configuration
        val identity = WeChatPreviewIdentity.of(accountHash,context.applicationInfo.sourceDir,b.parent.width,b.parent.height,
            b.parent.paddingLeft,b.parent.paddingTop,b.parent.paddingRight,b.parent.paddingBottom,
            cfg.uiMode,cfg.densityDpi,cfg.fontScale.toRawBits(),cfg.locales.toLanguageTags(),h.config.managerEnglishNavigationLabels)
        // Hash configuration changes, not every optical frame. Page-specific
        // background shades must not invalidate the other three page slots.
        val newKey = if(identity==layoutIdentity) key else digest(identity)
        layoutIdentity=identity
        if (newKey != key) {
            cancel(); epoch++; frames.clear(); loading.clear(); key = newKey
            val token = epoch; val w = b.parent.width; val height = b.parent.height
            worker.execute {
                runCatching { store.newest() }.getOrDefault(emptyList()).filter { it != index }.take(2).forEach { slot ->
                    val image = store.load(slot, newKey, w, height) ?: return@forEach
                    h.post { if (!disposed && epoch == token && key == newKey) put(slot, image) else image.recycle() }
                }
            }
        }
        val changed = actual != index
        actual = index
        if (target >= 0) {
            val owner=activity(h)
            if(owner==null || owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0 ||
                h.barVisibilityAnimating || !h.hasWindowFocus()) { cancel();return }
            val now=SystemClock.uptimeMillis()
            if(now>=nextSafetyCheck) {
                nextSafetyCheck=now+150L
                if(!safe(h,b)) { cancel();return }
            }
        }
        if (changed || (b.parent.isDirty && SystemClock.uptimeMillis() - lastCapture > 10_000L)) scheduleCapture(450L)
    }
    fun onScroll() { scrollUntil = SystemClock.uptimeMillis() + 300L; scheduleCapture(350L) }
    private fun scheduleCapture(delay: Long) {
        val h=host.get() ?: return
        if (disposed || !allowed || !h.hasWindowFocus() || captureQueued || accountHash.isEmpty()) return
        captureQueued = true; h.postDelayed(capture, delay)
    }
    private fun put(index: Int, image: Bitmap) {
        frames[index] = image
        while (frames.values.sumOf { it.width.toLong()*it.height*4 } > 24L*1024*1024) {
            val victim = frames.keys.firstOrNull { it != target } ?: break
            // A previous RenderNode can still reference it; let native/GC retire it safely.
            frames.remove(victim)
        }
    }
    private fun captureNow(exit: Boolean) {
        val h = host.get() ?: return
        val b = binding ?: return
        if (disposed || !allowed || (!exit && !h.hasWindowFocus()) || captureInFlight || accountHash.isEmpty() || key.isEmpty()) return
        busy=target>=0 || h.adapterNavigationAnimationRunning
        if (WeChatPreviewIdentity.captureBusy(target>=0,h.adapterNavigationAnimationRunning,
                SystemClock.uptimeMillis(),scrollUntil,pagerStillSince)) {
            diagnostics.mark(Stage.CAPTURE_BUSY)
            if (!exit) scheduleCapture(350L)
            return
        }
        if (!safe(h, b, requireFocus = !exit) || currentAccount() != accountHash) {
            diagnostics.mark(Stage.SAFETY_REJECTED);return
        }
        val index = nativeIndex(b) ?: return
        val width = b.parent.width; val height = b.parent.height
        if (width <= 0 || height <= 0 || width.toLong()*height*4 > WeChatPageSnapshotStore.MAX_FRAME_BYTES) return
        val picture = runCatching {
            val native = renderNode?.invoke(b.parent) as? RenderNode ?: return@runCatching null
            if (!native.hasDisplayList()) return@runCatching null
            val recording = RenderNode("LiquidTab.WeChatPageSnapshot").apply { setPosition(0,0,width,height) }
            try {
                val canvas = recording.beginRecording(width,height)
                canvas.drawColor(pageBaseColor)
                canvas.translate(-b.parent.left.toFloat(),-b.parent.top.toFloat())
                canvas.drawRenderNode(native); recording.endRecording()
                snapshot?.invoke(null, recording, width, height) as? Bitmap
            } finally { recording.discardDisplayList() }
        }.getOrNull() ?: run { diagnostics.mark(Stage.SNAPSHOT_UNAVAILABLE);return }
        diagnostics.mark(Stage.SNAPSHOT_READY)
        lastCapture = SystemClock.uptimeMillis(); captureInFlight = true
        val token = epoch; val cacheKey = key
        worker.execute {
            if (!disposed && !busy && epoch == token && key == cacheKey) runCatching { store.save(index,cacheKey,picture) }
            h.post {
                captureInFlight = false
                if (!disposed && epoch == token && key == cacheKey) put(index,picture) else picture.recycle()
            }
        }
    }

    override fun begin(index: Int) {
        cancel()
        val h = host.get() ?: return
        val b = binding ?: return
        if (index !in 0..3 || key.isEmpty() || currentAccount() != accountHash || !safe(h,b)) {
            diagnostics.mark(Stage.SHOW_REJECTED);return
        }
        busy = true; target = index; waitingForNative = true
        val cached = frames[index]
        if (cached != null) { show(cached); return }
        if (!loading.add(index)) return
        val token = epoch; val request = visualGeneration; val cacheKey = key
        val w = b.parent.width; val height = b.parent.height
        worker.execute {
            val image = store.load(index,cacheKey,w,height)
            h.post {
                if (epoch == token) loading.remove(index)
                if (image == null) return@post
                if (disposed || token != epoch || cacheKey != key) { image.recycle(); return@post }
                put(index,image)
                // A late decode must never replace a real page already being shown.
                if (request == visualGeneration && target == index && waitingForNative) show(image)
            }
        }
    }
    private fun show(image: Bitmap) {
        val h = host.get() ?: return
        val b = binding ?: return
        if (!safe(h,b) || currentAccount() != accountHash) { diagnostics.mark(Stage.SHOW_REJECTED);cancel(); return }
        val parent = h.parent as? FrameLayout ?: return
        if (overlay != null) return
        b.parent.getLocationInWindow(position); parent.getLocationInWindow(parentPosition)
        val view = Picture(h.context,image)
        overlay = view
        if(b.parent.importantForAccessibility!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            accessibilityOwner=b.parent;originalAccessibility=b.parent.importantForAccessibility
            b.parent.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        parent.addView(view,parent.indexOfChild(h),FrameLayout.LayoutParams(image.width,image.height).apply {
            leftMargin=position[0]-parentPosition[0];topMargin=position[1]-parentPosition[1]
        })
        sceneSources = listOf(b.parent,view)
        diagnostics.mark(Stage.SHOW_OK)
        h.invalidateOpticalSceneForAdapter(); h.postInvalidateOnAnimation()
    }
    override fun nativeDispatched(index: Int) {
        if (target != index) return
        waitingForNative = false
        val h = host.get() ?: return
        if (overlay == null) { cancel(); return }
        val b = binding ?: run { cancel(); return }
        val token = visualGeneration
        var lastX = Int.MIN_VALUE; var stable = 0
        val tree = h.rootView.viewTreeObserver; observer = tree
        draw = ViewTreeObserver.OnPreDrawListener {
            if (nativeIndex(b) == index && b.pager.scrollX == lastX) stable++ else stable=0
            lastX=b.pager.scrollX
            if (stable >= 2) {
                draw?.let { if(tree.isAlive)tree.removeOnPreDrawListener(it) };draw=null
                commit=Runnable { h.post { if(token==visualGeneration) fade() } }
                if(tree.isAlive)tree.registerFrameCommitCallback(commit!!)
            } else h.postInvalidateOnAnimation()
            true
        }
        if(tree.isAlive)tree.addOnPreDrawListener(draw)
        h.postInvalidateOnAnimation()
        h.postDelayed({if(token==visualGeneration)fade()},800L)
    }
    private fun fade() {
        val view=overlay ?: return
        if(view.alpha<1f)return
        val token=visualGeneration
        view.animate().alpha(0f).setDuration(100L).withEndAction { if(token==visualGeneration)cancel() }.start()
        host.get()?.postInvalidateOnAnimation()
    }
    val scene: View? get() = overlay
    fun sceneViews(): List<View>? = sceneSources
    override fun cancel() {
        visualGeneration++;target=-1;waitingForNative=false
        busy=host.get()?.adapterNavigationAnimationRunning==true
        draw?.let { if(observer?.isAlive==true)observer?.removeOnPreDrawListener(it) }
        commit?.let { if(observer?.isAlive==true)observer?.unregisterFrameCommitCallback(it) }
        draw=null;commit=null;observer=null
        val view=overlay;overlay=null;sceneSources=null
        accessibilityOwner?.let {
            if(it.importantForAccessibility==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                it.importantForAccessibility=originalAccessibility
        }
        accessibilityOwner=null
        view?.animate()?.cancel()
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if(view!=null)host.get()?.let { it.invalidateOpticalSceneForAdapter();it.postInvalidateOnAnimation() }
    }
    fun focusLost() {
        host.get()?.removeCallbacks(capture);captureQueued=false
        captureNow(true);cancel()
    }
    fun dispose() {
        if(disposed)return
        cancel();disposed=true;epoch++
        host.get()?.removeCallbacks(capture)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        frames.clear();binding=null;nativeAccount=null;diagnostics.close();worker.shutdown()
    }
    private class Picture(context: Context, private val bitmap: Bitmap) : View(context), AdapterOwnedOverlay {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init { isClickable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap,0f,0f,paint) }
    }
}
