package io.github.offlineglass.hook.adapters.zhihu

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.targets.TargetSpec
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Zhihu owns navigation and optical policy; common rendering remains generic. */
internal object ZhihuAdapter : TargetAdapter {
    override val key = "zhihu"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val nativeSelectionReliable = true
    override val skipNavigationSnapshot = true
    override val preserveNativeSourceMetrics = true
    override val managesNativeTabVisibility = true
    override val usesContentDarkMode = true
    override val usesOpticalSurfacePipeline = true
    override val usesDirectOpticalBackdropMode = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val deferOpticalRenderDuringBarAnimation = true
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val allowsSystemBackgroundBlur = false
    override fun isOpticalSurfaceTab(selectedIndex: Int) = true
    override fun createNavigationState(context: android.content.Context,
        host: io.github.offlineglass.hook.GlassHostLayout) = ZhihuNavigationState(host)
    override fun installHooks(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: TargetSpec) {
        ZhihuMessageContent.install(lpparam.classLoader)
        ZhihuSettingsVisibility.install(lpparam.classLoader)
        ZhihuKanshanContent.install(lpparam.classLoader)
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val shell = AdapterNavigationSearch.findFirst(root) {
            it.javaClass.name == "com.zhihu.android.base.widget.ZHMainTabContainer" && it.isShown
        } ?: return null
        val menu = AdapterNavigationSearch.findFirst(shell) {
            it.javaClass.name == MENU_CLASS && it.isShown && it.width > 0 && it.height > 0
        } ?: return null
        val count = tabCount(menu)
        return if (count in spec.preferredSlots) NavigationCandidate(menu, count, 160) else null
    }

    override fun acceptsNavigationSource(navigation: ViewGroup) = navigation.javaClass.name == MENU_CLASS
    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        if (acceptsNavigationSource(group)) tabCount(group) else null
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        var parent: View? = navigation
        repeat(6) {
            val view = parent ?: return
            if (view is ViewGroup && view.javaClass.name == "com.zhihu.android.base.widget.ZHMainTabContainer") {
                bind(view)
                return
            }
            parent = view.parent as? View
        }
    }

    override fun opticalCaptureGeometry(rootWidth: Int, rootHeight: Int, left: Int, top: Int,
        width: Int, height: Int, padding: Int): io.github.offlineglass.hook.adapters.OpticalCaptureGeometry? {
        val rect = android.graphics.Rect((left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0), (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight))
        if (rect.isEmpty) return null
        return io.github.offlineglass.hook.adapters.OpticalCaptureGeometry(rect, rect.width(), rect.height(),
            android.graphics.Rect(left - rect.left, top - rect.top,
                left - rect.left + width, top - rect.top + height))
    }

    const val MENU_CLASS = "com.zhihu.android.bottomnav.core.BottomNavMenuView"
    fun tabCount(menu: ViewGroup): Int = runCatching {
        (XposedHelpers.callMethod(menu, "getTabCount") as Number).toInt()
    }.getOrDefault(0)
    private val bound = WeakHashMap<View, Boolean>()

    override fun beforeNavigationScan(activity: android.app.Activity) {
        val id = activity.resources.getIdentifier("main_tab_container", "id", activity.packageName)
        val shell = activity.findViewById<View>(id) as? ViewGroup ?: return
        if (shell.javaClass.name != "com.zhihu.android.base.widget.ZHMainTabContainer") return
        bind(shell)
    }

    private fun bind(shell: ViewGroup) {
        if (bound.put(shell, true) != null) return
        val state = ZhihuChromeState(shell)
        shell.addOnAttachStateChangeListener(state)
        if (shell.isAttachedToWindow) state.onViewAttachedToWindow(shell)
    }
}

private class ZhihuChromeState(private val shell: ViewGroup) : View.OnAttachStateChangeListener {
    private var observer: ViewTreeObserver? = null
    private var content: View? = null
    private var originalBottomPadding = 0
    private var originalAlpha = 1f
    private var originalElevation = 0f
    private var originalTranslationZ = 0f
    private var originalBackground: android.graphics.drawable.Drawable? = null
    private var originalForeground: android.graphics.drawable.Drawable? = null
    private var applied = false
    private var enabled = true
    private var nextConfigRead = 0L
    private var configReadPending = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        update()
        true
    }

    override fun onViewAttachedToWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = shell.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        update()
    }

    private fun update() {
        val now = android.os.SystemClock.uptimeMillis()
        if (!configReadPending && now >= nextConfigRead) {
            configReadPending = true
            nextConfigRead = now + 2_000L
            HookConfigReader.readFreshAsync(shell.context, "com.zhihu.android") { config ->
                configReadPending = false
                if (config != null) enabled = config.enabled
                if (!enabled && shell.isAttachedToWindow) restore()
            }
        }
        if (!enabled) {
            restore()
            return
        }
        if (!applied) {
            val id = shell.resources.getIdentifier("real_content_container", "id", "com.zhihu.android")
            content = if (id != 0) shell.rootView.findViewById(id) else null
            // Wait until the confirmed main layout is completely inflated.
            if (content == null) return
            originalBottomPadding = content!!.paddingBottom
            originalAlpha = shell.alpha
            originalElevation = shell.elevation
            originalTranslationZ = shell.translationZ
            originalBackground = shell.background
            originalForeground = shell.foreground
            applied = true
        }
        // Preserve visibility, tab states, children and listeners. Alpha hides
        // the divider and seasonal background image without erasing artwork.
        if (shell.alpha != 0f) shell.alpha = 0f
        if (shell.elevation != 0f) shell.elevation = 0f
        if (shell.translationZ != 0f) shell.translationZ = 0f
        if (shell.background != null) shell.background = null
        if (shell.foreground != null) shell.foreground = null
        content?.let {
            if (it.paddingBottom != 0) it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, 0)
        }
    }

    private fun restore() {
        if (!applied) return
        shell.alpha = originalAlpha
        shell.elevation = originalElevation
        shell.translationZ = originalTranslationZ
        shell.background = originalBackground
        shell.foreground = originalForeground
        content?.let {
            it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, originalBottomPadding)
        }
        content = null
        originalBackground = null
        originalForeground = null
        applied = false
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        restore()
    }
}
