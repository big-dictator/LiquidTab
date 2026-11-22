package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Keep navigation spacing local to the active conversation layout. */
internal object WeChatChatInsets {
    private val immersiveRoots = WeakHashMap<View, Boolean>()
    private val nativeNavigationColors = WeakHashMap<View, Int>()
    private val nativeInsets = WeakHashMap<View, NativeInset>()
    private data class NativeInset(val width: Int, val height: Int, val bottom: Int)
    private val observed = WeakHashMap<View, Unit>()

    fun configure(decor: View, enabled: Boolean) {
        activityOf(decor)?.window?.navigationBarColor?.let { color ->
            if (Color.alpha(color) >= 224) nativeNavigationColors[decor] = color
        }
        if (enabled && immersiveRoots[decor] != true) {
            val insets = decor.rootWindowInsets
            val bottom = navigationBottom(decor, insets)
            if (bottom > 0) nativeInsets[decor] = NativeInset(decor.width, decor.height, bottom)
        } else if (!enabled) {
            nativeInsets.remove(decor)
        }
        immersiveRoots[decor] = enabled
    }

    fun install(loader: ClassLoader) {
        val pageType = Class.forName("com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout", false, loader)
            .asSubclass(ViewGroup::class.java)
        val embeddedType = Class.forName("com.tencent.mm.ui.tools.TestTimeForChatting", false, loader)
            .asSubclass(ViewGroup::class.java)

        XposedBridge.hookAllConstructors(pageType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val page = param.thisObject as? ViewGroup ?: return
                if (observed.put(page, Unit) != null) return
                val binding = ChatBinding(page, embeddedType)
                page.addOnAttachStateChangeListener(binding)
                if (page.isAttachedToWindow) binding.onViewAttachedToWindow(page)
            }
        })

        val windowType = Class.forName("com.android.internal.policy.PhoneWindow", false, loader)
        XposedBridge.hookAllMethods(windowType, "setNavigationBarColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val color = param.args.firstOrNull() as? Int ?: return
                if (Color.alpha(color) < 224) return
                val decor = (param.thisObject as? Window)?.peekDecorView() ?: return
                nativeNavigationColors[decor] = color
            }
        })
    }

    private fun activityOf(view: View): Activity? {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) {
            val base = context.baseContext
            if (base === context) return null
            context = base
        }
        return context as? Activity
    }

    @Suppress("DEPRECATION")
    private fun navigationBottom(view: View, insets: WindowInsets?): Int {
        insets ?: return 0
        if (Build.VERSION.SDK_INT >= 30) {
            return maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom,
            )
        }
        val current = insets.systemWindowInsetBottom
        val stable = insets.stableInsetBottom
        // On legacy Android, systemWindowInsetBottom can include the IME.
        if (current > stable && current - stable > 100 * view.resources.displayMetrics.density) return stable
        return maxOf(current, stable, if (Build.VERSION.SDK_INT >= 29) insets.mandatorySystemGestureInsets.bottom else 0)
    }

    private class ChatBinding(
        private val page: ViewGroup,
        private val embeddedType: Class<out ViewGroup>,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private var observer: ViewTreeObserver? = null
        private var activity: Activity? = null
        private var owner: View? = null
        private var nativeBottom = 0
        private var appliedBottom: Int? = null
        private val rootPosition = IntArray(2)
        private val ownerPosition = IntArray(2)
        private val pagePosition = IntArray(2)
        private val visibleFrame = Rect()
        private var metrics: android.view.WindowMetrics? = null
        private var metricsInsets: WindowInsets? = null
        private var metricsWidth = 0
        private var metricsHeight = 0
        private var metricsConfiguration = 0
        private var retryMetricsAt = 0L
        private val fill = object : ColorDrawable(Color.TRANSPARENT) {
            override fun draw(canvas: android.graphics.Canvas) {
                val container = owner ?: return
                if (!page.isShown || !page.isAttachedToWindow) return
                page.getLocationInWindow(pagePosition)
                container.getLocationInWindow(ownerPosition)
                val left = pagePosition[0] - ownerPosition[0]
                val saved = canvas.save()
                canvas.clipRect(left, bounds.top, left + page.width, bounds.bottom)
                super.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }
        private var fillAttached = false
        private var lastFillLeft = Int.MIN_VALUE

        override fun onViewAttachedToWindow(v: View) {
            val container = findOwner() ?: return
            activity = activityOf(page) ?: return
            changeOwner(container)
            observer = page.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            changeOwner(null)
            activity = null
            metrics = null
            metricsInsets = null
            retryMetricsAt = 0L
        }

        private fun changeOwner(next: View?) {
            if (owner === next) return
            owner?.let { old ->
                if (fillAttached) old.overlay.remove(fill)
                if (appliedBottom == old.paddingBottom) {
                    old.setPadding(old.paddingLeft, old.paddingTop, old.paddingRight, nativeBottom)
                }
            }
            owner = next
            nativeBottom = next?.paddingBottom ?: 0
            appliedBottom = null
            fillAttached = false
            lastFillLeft = Int.MIN_VALUE
        }

        private fun findOwner(): View? {
            var ancestor = page.parent as? View
            while (ancestor != null) {
                if (embeddedType.isInstance(ancestor)) return ancestor
                ancestor = ancestor.parent as? View
            }
            return null
        }

        override fun onPreDraw(): Boolean {
            val host = activity ?: return true
            val root = host.window.peekDecorView() ?: return true
            val currentOwner = owner ?: return true
            if (page.rootView !== root || currentOwner.rootView !== root) return true
            val enabled = HookConfigReader.startupSnapshot("com.tencent.mm")?.enabled != false &&
                immersiveRoots[root] != false
            if (!page.isShown || page.height <= 0 || root.height <= 0) {
                updateFill(currentOwner, root, 0)
                // Keep the outgoing conversation's measured geometry.
                if (enabled) return true
            }

            val retainedPadding = appliedBottom?.takeIf { currentOwner.paddingBottom == it }
            if (retainedPadding == null) nativeBottom = currentOwner.paddingBottom
            val ownedPadding = (retainedPadding ?: nativeBottom) - nativeBottom
            val extra = if (enabled) requiredPadding(host, root, currentOwner, ownedPadding) else 0
            val target = nativeBottom + extra
            updateFill(currentOwner, root, extra)
            if (currentOwner.paddingBottom == target) return true

            // Insets can be reapplied by native layout during a traversal.
            // Defer only a new geometry, never block drawing on repeated writes.
            val changedTarget = appliedBottom != target
            appliedBottom = target
            if (io.github.offlineglass.BuildConfig.DEBUG && changedTarget) {
                android.util.Log.d("LiquidTabWeChatInsets", "embedded chat: native=$nativeBottom added=$extra")
            }
            currentOwner.setPadding(currentOwner.paddingLeft, currentOwner.paddingTop, currentOwner.paddingRight, target)
            return !changedTarget
        }

        private fun requiredPadding(host: Activity, root: View, container: View, ownedPadding: Int): Int {
            val insets = root.rootWindowInsets
            if (Build.VERSION.SDK_INT >= 30 && insets?.isVisible(WindowInsets.Type.ime()) == true) return 0
            root.getLocationOnScreen(rootPosition)
            container.getLocationOnScreen(ownerPosition)
            val rootBottom = rootPosition[1] + root.height
            val window = if (Build.VERSION.SDK_INT >= 30) windowMetrics(host, root, insets) else null
            val reportedNav = maxOf(navigationBottom(root, insets), navigationBottom(root, window?.windowInsets))
            val nav = if (reportedNav > 0) reportedNav else nativeInsets[root]?.takeIf {
                it.width == root.width && it.height == root.height
            }?.bottom ?: 0
            if (nav <= 0) return 0

            // Legacy adjustResize and floating-window implementations may
            // report IME visibility only through the visible display frame.
            root.getWindowVisibleDisplayFrame(visibleFrame)
            val keyboardThreshold = maxOf(nav * 2, (80f * root.resources.displayMetrics.density).toInt())
            if (!visibleFrame.isEmpty && rootBottom - visibleFrame.bottom > keyboardThreshold) return 0
            val safeBottom = (window?.bounds?.bottom ?: rootBottom) - nav
            val ownerContentBottom = ownerPosition[1] + container.height - nativeBottom
            page.getLocationOnScreen(pagePosition)
            val nativeContentBottom = minOf(
                ownerContentBottom,
                pagePosition[1] + page.height + ownedPadding - page.paddingBottom,
            )
            return (nativeContentBottom - safeBottom).coerceIn(0, nav)
        }

        @android.annotation.TargetApi(30)
        private fun windowMetrics(host: Activity, root: View, insets: WindowInsets?): android.view.WindowMetrics? {
            val configuration = root.resources.configuration.hashCode()
            val now = SystemClock.uptimeMillis()
            if (metricsInsets != insets || metricsWidth != root.width || metricsHeight != root.height ||
                metricsConfiguration != configuration || (retryMetricsAt != 0L && now >= retryMetricsAt)
            ) {
                metricsInsets = insets
                metricsWidth = root.width
                metricsHeight = root.height
                metricsConfiguration = configuration
                metrics = runCatching { host.windowManager.currentWindowMetrics }.getOrNull()
                retryMetricsAt = if (navigationBottom(root, metrics?.windowInsets) == 0 && navigationBottom(root, insets) == 0) {
                    now + 500L
                } else 0L
            }
            return metrics
        }

        private fun updateFill(container: View, root: View, extra: Int) {
            if (extra <= 0) {
                if (fillAttached) container.overlay.remove(fill)
                fillAttached = false
                return
            }
            val color = nativeNavigationColors[root] ?: run {
                val value = TypedValue()
                if (page.context.theme.resolveAttribute(android.R.attr.navigationBarColor, value, true)) {
                    value.data
                } else Color.TRANSPARENT
            }
            fill.color = color
            val bottom = container.height - nativeBottom
            fill.setBounds(0, bottom - extra, container.width, bottom)
            page.getLocationInWindow(pagePosition)
            container.getLocationInWindow(ownerPosition)
            val left = pagePosition[0] - ownerPosition[0]
            if (left != lastFillLeft) {
                lastFillLeft = left
                fill.invalidateSelf()
            }
            if (!fillAttached || fill.callback == null) {
                container.overlay.add(fill)
                fillAttached = true
            }
        }
    }
}
