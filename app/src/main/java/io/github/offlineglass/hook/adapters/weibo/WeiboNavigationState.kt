package io.github.offlineglass.hook.adapters.weibo

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.floor
import kotlin.math.roundToInt

/** Stable Weibo assets and notification badges, independent from its recycled native row. */
internal class WeiboNavigationState(private val context: Context) : AppNavigationState {
    internal val rewardBaseTranslations = java.util.WeakHashMap<View, Float>()
    internal var rewardTarget: View? = null
    internal var lastRewardProbe = 0L
    internal var videoHideTime = 0L
    internal var messageHideTime = 0L
    internal var messagePeekUntil = 0L
    private var activeHost: GlassHostLayout? = null
    private var receiverRegistered = false
    private val tabReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == TAB_CHANGED_ACTION) activeHost?.postInvalidateOnAnimation()
        }
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val badgeTexts = arrayOfNulls<String>(5)
    private val normalLight by lazy(LazyThreadSafetyMode.NONE) { NORMAL_LIGHT.map(::loadBitmap) }
    private val selectedLight by lazy(LazyThreadSafetyMode.NONE) { SELECTED_LIGHT.map(::loadBitmap) }
    private val normalDark by lazy(LazyThreadSafetyMode.NONE) { NORMAL_DARK.map(::loadBitmap) }
    private val selectedDark by lazy(LazyThreadSafetyMode.NONE) { SELECTED_DARK.map(::loadBitmap) }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
         density: Float, barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        activeHost = host as? GlassHostLayout
        if (!receiverRegistered) runCatching {
            context.registerReceiver(tabReceiver, IntentFilter(TAB_CHANGED_ACTION))
            receiverRegistered = true
        }
    }

    override fun dispose() {
        if (receiverRegistered) runCatching { context.unregisterReceiver(tabReceiver) }
        rewardBaseTranslations.forEach { (view, base) -> if (view.isAttachedToWindow) view.translationY = base }
        rewardBaseTranslations.clear(); rewardTarget = null
        receiverRegistered = false; activeHost = null
    }

    override fun captureNavigation(source: ViewGroup, target: Bitmap): Boolean {
        if (source.width <= 0 || source.height <= 0) return false
        badgeTexts.indices.forEach { badgeTexts[it] = null }
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val stack = ArrayDeque<View>(); stack += source
        val density = context.resources.displayMetrics.density
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is ViewGroup) for (index in 0 until view.childCount) stack += view.getChildAt(index)
            if (view !== source && view is TextView && view.visibility == View.VISIBLE &&
                view.width in 1..(54f * density).roundToInt() && view.height in 1..(42f * density).roundToInt()) {
                val badge = view.text?.toString()?.trim().orEmpty()
                if (badge.matches(BADGE_PATTERN)) {
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val x = location[0] - sourceLocation[0] + view.width / 2f
                    val y = location[1] - sourceLocation[1] + view.height / 2f
                    if (x in 0f..source.width.toFloat() && y in 0f..source.height.toFloat()) {
                        val index = floor(x / source.width * LABELS.size).toInt().coerceIn(0, LABELS.lastIndex)
                        badgeTexts[index] = badge
                    }
                }
            }
        }
        Canvas(target).drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        return true
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        activeHost?.suppressNativeWeiboBottomBar(source)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        (host as? GlassHostLayout)?.adjustWeiboFloatingReward()
    }

    override fun refreshNavigationSource(host: View) =
        (host as? GlassHostLayout)?.refreshWeiboNavigationSource() == true

    override fun resolveAutoDarkMode(host: View): Boolean? =
        (host as? GlassHostLayout)?.adapterContentOrSystemDark()

    override fun pageAllowsNavigation(root: View?): Boolean? =
        activeHost?.weiboPageAllowsNavigation()

    override fun handleSystemBack(host: View): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        return when (WeiboAdapter.currentTab) {
            1 -> {
                val previous = WeiboAdapter.lastNonVideoTab
                runCatching {
                    glassHost.findWeiboTabHost()?.javaClass
                        ?.getMethod("setCurrentTab", Int::class.javaPrimitiveType)
                        ?.invoke(glassHost.findWeiboTabHost(), previous)
                }
                videoHideTime = 0L
                glassHost.snapToNavigationIndex(previous)
                WeiboAdapter.currentTab = previous
                glassHost.post { refreshNavigationSource(glassHost) }
                true
            }
            3 -> {
                messageHideTime = 0L
                messagePeekUntil = android.os.SystemClock.uptimeMillis() + 1_500L
                glassHost.resetMessageScrollStopState()
                glassHost.postInvalidateOnAnimation()
                true
            }
            else -> false
        }
    }

    override fun onHostTouchDown(host: View, selectedIndex: Int) {
        if (selectedIndex == 3) {
            messagePeekUntil = 0L
            messageHideTime = 0L
        }
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        refreshNavigationSource(glassHost)
        val tabHost = glassHost.findWeiboTabHost() ?: return false
        return runCatching {
            tabHost.javaClass.getMethod("setCurrentTab", Int::class.javaPrimitiveType)
                .invoke(tabHost, index)
            WeiboAdapter.currentTab = index
            glassHost.postInvalidateOnAnimation()
            true
        }.getOrDefault(false)
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.slotCount != LABELS.size || frame.width <= 0 || frame.height <= 0) return
        val horizontalPadding = 4f * frame.density
        val iconSize = ICON_SIZE_DP * frame.density
        val icons = if (frame.darkGlass) normalDark else normalLight
        val selectedIcons = if (frame.darkGlass) selectedDark else selectedLight
        val normalColor = if (frame.darkGlass) Color.WHITE else DARK_LABEL_COLOR
        val itemScale = frame.iconScale * frame.extraScale
        labelPaint.textSize = 11f * frame.scaledDensity
        badgeTextPaint.textSize = 9f * frame.scaledDensity
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val selected = index == frame.selectedIndex
            val bitmap = (if (selected) selectedIcons else icons).getOrNull(index) ?: return@forEachIndexed
            val centerX = horizontalPadding + (visualIndex + .5f) * frame.slotWidth
            canvas.save(); canvas.scale(itemScale, itemScale, centerX, frame.height / 2f)
            iconPaint.alpha = if (selected) 255 else 238
            iconPaint.colorFilter = if (frame.darkGlass && !selected)
                BlendModeColorFilter(Color.WHITE, BlendMode.SRC_IN) else null
            val centerY = ICON_CENTER_Y_DP * frame.density
            canvas.drawBitmap(bitmap, null, RectF(centerX - iconSize / 2f, centerY - iconSize / 2f,
                centerX + iconSize / 2f, centerY + iconSize / 2f), iconPaint)
            labelPaint.color = if (selected) ACCENT_COLOR else normalColor
            labelPaint.alpha = if (selected) 255 else 238
            canvas.drawText(LABELS[index], centerX, LABEL_BASELINE_DP * frame.density, labelPaint)
            badgeTexts.getOrNull(index)?.takeUnless(String::isNullOrBlank)?.let { badge ->
                val bx = centerX + 13f * frame.density; val by = 13f * frame.density
                canvas.drawCircle(bx, by, (if (badge.length <= 1) 8f else 10f) * frame.density, badgePaint)
                val fm = badgeTextPaint.fontMetrics
                canvas.drawText(badge, bx, by - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
            }
            canvas.restore()
        }
        iconPaint.alpha = 255; iconPaint.colorFilter = null; labelPaint.alpha = 255
    }

    private fun loadBitmap(fileName: String): Bitmap? {
        val path = "weibo/$fileName"
        runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY).assets
                .open(path).use(BitmapFactory::decodeStream)
        }.getOrNull()?.let { return it }
        return runCatching { javaClass.classLoader?.getResourceAsStream(path)?.use(BitmapFactory::decodeStream) }
            .onFailure { XposedBridge.log("[LiquidTab][WeiboIcons] $fileName: $it") }.getOrNull()
    }

    private companion object {
        const val MODULE_PACKAGE = "io.github.offlineglass"
        const val TAB_CHANGED_ACTION = "com.sina.weibo.action.MAIN_TAB_CHANGED"
        const val ACCENT_COLOR = 0xFFFF8200.toInt(); const val DARK_LABEL_COLOR = 0xFF282828.toInt()
        const val BADGE_COLOR = 0xFFF04438.toInt(); const val ICON_SIZE_DP = 32.4f
        const val ICON_CENTER_Y_DP = 21.5f; const val LABEL_BASELINE_DP = 45.5f
        val LABELS = arrayOf("首页", "视频", "发现", "消息", "我")
        val BADGE_PATTERN = Regex("^(?:\\d{1,3}\\+?|爆|99\\+)$")
        // Preserve the APK asset names and tab order from the pre-migration baseline.
        val NORMAL_LIGHT = arrayOf("tabbar_home_v2.png", "tabbar_video_v2.webp", "tabbar_discover_v2.png", "tabbar_message_center_v2.png", "tabbar_profile_v2.png")
        val SELECTED_LIGHT = arrayOf("tabbar_home_highlighted_v2.png", "tabbar_video_highlighted_v2.webp", "tabbar_discover_highlighted_v2.png", "tabbar_message_center_highlighted_v2.png", "tabbar_profile_highlighted_v2.png")
        val NORMAL_DARK = arrayOf("tabbar_icon_home_normal_dark_v2.png", "tabbar_icon_video_normal_dark_v2.webp", "tabbar_icon_discover_normal_dark_v2.png", "tabbar_icon_message_normal_dark_v2.png", "tabbar_icon_profile_normal_dark_v2.webp")
        val SELECTED_DARK = arrayOf("tabbar_icon_home_highlighted_dark_v2.png", "tabbar_icon_video_highlighted_dark_v2.webp", "tabbar_icon_discover_highlighted_dark_v2.png", "tabbar_icon_message_highlighted_dark_v2.png", "tabbar_icon_profile_highlighted_dark_v2.webp")
    }
}
