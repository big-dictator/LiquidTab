package io.github.offlineglass.hook


import android.animation.Animator

import android.animation.AnimatorListenerAdapter

import android.animation.TimeInterpolator

import android.animation.ValueAnimator

import android.app.Activity

import android.content.Context

import android.content.ContextWrapper

import android.content.res.Configuration

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.BlendMode

import android.graphics.BlendModeColorFilter

import android.graphics.Canvas

import android.graphics.Color

import android.graphics.ColorMatrix

import android.graphics.ColorMatrixColorFilter

import android.graphics.LinearGradient

import android.graphics.Paint

import android.graphics.Path

import android.graphics.PixelFormat

import android.graphics.PorterDuff

import android.graphics.PorterDuffXfermode

import android.graphics.RadialGradient

import android.graphics.Rect

import android.graphics.RectF

import android.graphics.RenderEffect

import android.graphics.RenderNode

import android.graphics.RuntimeShader

import android.graphics.Shader

import android.graphics.Typeface

import android.graphics.drawable.ColorDrawable

import android.graphics.drawable.GradientDrawable

import android.graphics.drawable.Drawable

import android.hardware.Sensor

import android.hardware.SensorEvent

import android.hardware.SensorEventListener

import android.hardware.SensorManager

import java.lang.ref.WeakReference

import android.os.Handler

import android.os.HandlerThread

import android.os.Build

import android.os.Looper

import android.os.SystemClock

import android.util.Log

import android.util.TypedValue

import android.view.Choreographer

import android.view.MotionEvent

import android.view.PixelCopy

import android.view.Surface
import android.view.SurfaceControl

import android.view.SurfaceHolder

import android.view.SurfaceView

import android.view.TextureView

import android.view.View

import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

import android.view.ViewConfiguration

import android.view.ViewOutlineProvider

import android.view.ViewTreeObserver

import android.view.WindowInsets

import android.view.animation.PathInterpolator

import android.widget.FrameLayout

import android.widget.ImageView

import android.widget.RelativeLayout

import android.widget.SeekBar

import android.widget.TextView


import androidx.graphics.shapes.CornerRounding

import androidx.graphics.shapes.RoundedPolygon

import androidx.graphics.shapes.toPath

import de.robv.android.xposed.XposedBridge

import de.robv.android.xposed.XposedHelpers

import de.robv.android.xposed.XC_MethodHook

import io.github.offlineglass.config.GlassConfig

import kotlin.math.abs

import kotlin.math.ceil

import kotlin.math.cos

import kotlin.math.floor

import kotlin.math.max

import kotlin.math.min

import kotlin.math.roundToInt

import kotlin.math.sin

import kotlin.math.sqrt



/**

 * Rebuilds an Android canvas path with MIUIX's continuous squircle curve.

 *

 * The injected renderer is View/Canvas based, while miuix-squircle exposes a

 * Compose Path extension. This is the allocation-free Android Path equivalent

 * of miuix-squircle 0.9.3's addSquircleRect (extension 1.1, control 0.357), so

 * the moving indicator does not allocate a Compose wrapper on every frame.

 */

internal fun Path.setBottomBarSquircle(rect: RectF, radius: Float, smoothing: Float = 0.5f) {

    reset()

    if (rect.isEmpty) return

    val strength = smoothing.coerceIn(-1f, 1f)

    val cornerRadius = max(0f, radius).coerceAtMost(min(rect.width(), rect.height()) * 0.5f)

    if (cornerRadius <= 0f) {

        addRect(rect, Path.Direction.CW)

        return

    }

    if (strength < 0f) {

        // AndroidX graphics-shapes keeps the rectangle bounds fixed and builds

        // the smoothing transition inside them.  This is the canonical inward

        // continuous-corner construction; do not emulate it by mirroring a

        // single cubic control point (that creates pinched, non-G2 corners).

        RoundedPolygon(

            vertices = floatArrayOf(

                rect.left, rect.top,

                rect.right, rect.top,

                rect.right, rect.bottom,

                rect.left, rect.bottom,

            ),

            rounding = CornerRounding(cornerRadius, -strength),

            centerX = rect.centerX(),

            centerY = rect.centerY(),

        ).toPath(this)

        return

    }

    val extent = max(0f, radius * (1f + 0.2f * strength))

        .coerceAtMost(min(rect.width(), rect.height()) * 0.5f)

    if (extent <= 0f) {

        addRect(rect, Path.Direction.CW)

        return

    }

    // 0 = conventional circular corner. Positive values retain the approved

    // MIUIX-style outward/full curve.

    val controlRatio = 0.448f - 0.182f * strength

    val control = extent * controlRatio

    moveTo(rect.left + extent, rect.top)

    lineTo(rect.right - extent, rect.top)

    cubicTo(rect.right - control, rect.top, rect.right, rect.top + control, rect.right, rect.top + extent)

    lineTo(rect.right, rect.bottom - extent)

    cubicTo(rect.right, rect.bottom - control, rect.right - control, rect.bottom, rect.right - extent, rect.bottom)

    lineTo(rect.left + extent, rect.bottom)

    cubicTo(rect.left + control, rect.bottom, rect.left, rect.bottom - control, rect.left, rect.bottom - extent)

    lineTo(rect.left, rect.top + extent)

    cubicTo(rect.left, rect.top + control, rect.left + control, rect.top, rect.left + extent, rect.top)

    close()

}



internal fun android.graphics.drawable.Drawable.toBitmapSafely(w: Int, h: Int): Bitmap? {

    if (w <= 0 || h <= 0) return null

    return runCatching {

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bmp)

        val oldBounds = bounds

        setBounds(0, 0, w, h)

        try {

            draw(canvas)

        } finally {

            bounds = oldBounds

        }

        bmp

    }.getOrNull()

}



/**

 * Shared device-tilt tracker driving the bloom-stroke primary light, ported

 * from miuix-blur's rememberDeviceTilt (game rotation vector at GAME rate,

 * 0.15 low-pass) plus KernelSU's gravity-rotated highlight fallback: a

 * near-flat screen keeps the upright (0, -1) reference direction. One listener

 * serves every host in this process; hosts register only while attached,

 * window-visible, and configured to draw the bloom stroke, so backgrounded

 * apps stop the sensor entirely and rest state triggers zero repaints.

 */

internal object BloomTiltTracker {

    /** Screen-plane gravity projection in device space (+X right, +Y top); (0, 0) before the first sample. */

    @Volatile

    var gravityX = 0f

        private set



    @Volatile

    var gravityY = 0f

        private set



    const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, �?° tilt

    private const val SMOOTHING = 0.15f

    private const val INVALIDATE_DELTA_SQ = 0.0007f // �?.5° direction change before a repaint



    private val clients = java.util.concurrent.CopyOnWriteArrayList<WeakReference<View>>()

    private val rotationMatrix = FloatArray(9)

    private var sensorManager: SensorManager? = null

    private var listener: SensorEventListener? = null

    private var smoothX = 0f

    private var smoothY = 0f

    private var initialized = false

    private var lastDirX = 0f

    private var lastDirY = -1f



    fun register(view: View) {

        clients.removeAll { it.get() === view || it.get() == null }

        clients.add(WeakReference(view))

        ensureListening(view)

    }



    fun unregister(view: View) {

        clients.removeAll { it.get() === view || it.get() == null }

        if (clients.isEmpty()) stopListening()

    }



    private fun ensureListening(view: View) {

        if (listener != null) return

        val manager = runCatching {

            view.context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        }.getOrNull() ?: return

        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            ?: return

        val sensorListener = object : SensorEventListener {

            override fun onSensorChanged(event: SensorEvent) {

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Gravity in device space = R^T * (0, 0, -1)_world = -(R third row).

                val gx = -rotationMatrix[6]

                val gy = -rotationMatrix[7]

                if (!initialized) {

                    smoothX = gx

                    smoothY = gy

                    initialized = true

                } else {

                    smoothX += (gx - smoothX) * SMOOTHING

                    smoothY += (gy - smoothY) * SMOOTHING

                }

                gravityX = smoothX

                gravityY = smoothY

                invalidateTiltedClients()

            }



            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        }

        val registered = runCatching {

            manager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)

        }.getOrDefault(false)

        if (!registered) return

        sensorManager = manager

        listener = sensorListener

    }



    private fun stopListening() {

        listener?.let { sensorManager?.unregisterListener(it) }

        sensorManager = null

        listener = null

        initialized = false

    }



    private fun invalidateTiltedClients() {

        val magSq = smoothX * smoothX + smoothY * smoothY

        val dirX: Float

        val dirY: Float

        if (magSq > GRAVITY_DIR_THRESHOLD_SQ) {

            val invMag = 1f / sqrt(magSq)

            dirX = smoothX * invMag

            dirY = smoothY * invMag

        } else {

            dirX = 0f

            dirY = -1f

        }

        val deltaSq = (dirX - lastDirX) * (dirX - lastDirX) + (dirY - lastDirY) * (dirY - lastDirY)

        if (deltaSq < INVALIDATE_DELTA_SQ) return

        lastDirX = dirX

        lastDirY = dirY

        for (client in clients) {

            val view = client.get() ?: continue

            if (view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE) {

                view.postInvalidateOnAnimation()

            }

        }

    }

}

/**

 * Native custom renderer for the floating glass navigation bar.

 *

 * Rendering order mirrors the reference: vibrancy -> 4 dp blur -> outer 24/24

 * lens -> translucent surface, then a 56 dp selected pill whose pressed lens is

 * 10/14 with depth and 0.5 chromatic dispersion.

 */

class GlassHostLayout(

    context: Context,

    rawConfig: GlassConfig,

    private var slotCount: Int,

    nonSelectableIndices: Set<Int> = emptySet(),

    private val nativeSelectionReliable: Boolean = true,

    navigationSource: ViewGroup? = null,

    suppressedOriginalChrome: List<View> = emptyList(),


    sliderEnabled: Boolean = true,

) : FrameLayout(context) {
    private val targetAdapter by lazy(LazyThreadSafetyMode.NONE) {
        TargetAdapterRegistry.forPackage(context.packageName)
    }
    internal val appNavigationState: AppNavigationState? by lazy(LazyThreadSafetyMode.NONE) {
        targetAdapter?.createNavigationState(context, this)
    }
    private val appNativeChromeController by lazy(LazyThreadSafetyMode.NONE) {
        targetAdapter?.createNativeChromeController()
    }

    // The file manager owns two very different bars (main tab row vs

    // multi-select action bar); the host is re-parameterized when it

    // switches between them, so these two stay mutable.

    private var nonSelectableIndices: Set<Int> = nonSelectableIndices + (targetAdapter?.actionOnlyIndices ?: emptySet())

    private var sliderEnabled: Boolean = sliderEnabled

    /** Keep Weibo on the live window backdrop instead of a retained row bitmap. */

    private fun packageConfig(raw: GlassConfig): GlassConfig {

        // Always use the cross-device Android backdrop pipeline. Do not rewrite
        // persisted legacy flags: a rollback must restore the old preference.
        val normalized = raw.normalized().copy(
            backdropCapture = if (targetAdapter?.honorsBackdropCaptureSetting == true) raw.backdropCapture else true,
            nativeBlur = false,
        )

        val adapted = targetAdapter?.normalizeConfig(normalized) ?: normalized
        // Rendering switches are enforced after app-specific normalization so
        // no adapter can accidentally re-enable a retired or disabled path.
        val solid = adapted.solidBarEnabled
        return adapted.copy(
            liquidGlassEnabled = adapted.liquidGlassEnabled && !solid,
            backdropCapture = adapted.backdropCapture && !solid &&
                (adapted.liquidGlassEnabled || adapted.blurRadius > 0f),
            nativeBlur = false,
            bottomGradientBlurEnabled = false,
        )

    }



internal var config = packageConfig(rawConfig)

    /**
     * Douyin: the glass host is attached to its own translucent panel window
     * carrying blurBehindRadius, so SurfaceFlinger blurs every layer below it
     * (the feed video SurfaceView included) in real time, like the system
     * control center. While active, the in-tree RenderNode/PixelCopy backdrop
     * pipelines are disabled: they cannot capture the video surface anyway,
     * and their stale frames would cover the compositor blur.
     */
    internal var compositorBlurBehind: Boolean = false






    init {

        if (targetAdapter?.disablePlatformForceDark == true && android.os.Build.VERSION.SDK_INT >= 29) {

            // HyperOS force-dark inverts the module's own drawing: a dark glass

            // fill would come back light and white icons dark. The module

            // renders both palettes itself, so opt the whole glass subtree out.

            runCatching { setForceDarkAllowed(false) }

        }

    }

    internal var navigationSource: ViewGroup? = navigationSource

    internal fun invalidateNavigationSnapshot() {
        navigationSnapshotSignature = Long.MIN_VALUE
        pendingNavigationSignature = Long.MIN_VALUE
        pendingNavigationStableFrames = 0
        postInvalidateOnAnimation()
    }

    private var suppressedOriginalChrome: List<View> = suppressedOriginalChrome

    private val qqRecreatedBottomChrome = ArrayList<View>(4)

    private var lastQqBottomChromeProbe = 0L


private var barVisibilityInitialized = false

internal var barVisibilityTarget = true

    private var barVisibilityGeneration = 0

internal var barVisibilityAnimating = false

    private var barVisibilityAnimator: ValueAnimator? = null

    private val meituanTakeoutScrollBasePaddings = java.util.WeakHashMap<View, IntArray>()

    private var lastMeituanTakeoutScrollProbe = 0L

    internal var adapterMsgHideTime = 0L

    internal var adapterMsgPeekUntil = 0L



    fun snapToNavigationIndex(index: Int) {

        selectedIndex = index

        appNavigationState?.onNavigationTabSwitched(index, slotCount, SystemClock.uptimeMillis())

        dragTarget = visualPositionForNavigationIndex(index)

        positionSpring.snapTo(dragTarget)

        postInvalidateOnAnimation()

    }



    /**

     * Whether this host currently sits on XHS's message tab. Back interception

     * (finish/moveTaskToBack/predictive-back) asks this before consuming the

     * event so sub-pages and other tabs keep the default back behaviour.

     */

    internal fun isAdapterBackPeekTab(): Boolean =

        targetAdapter?.isBackPeekTab(selectedIndex) == true





    /**

     * JD rebuilds its NavigationGroup on page switches. Until the host

     * rebinds, the old source is detached and the freshly created bar draws

     * unsuppressed for several frames �?a native-bar flicker at the bottom.

     * Detect the stale source in the same preDraw pass and re-bind by class

     * name, then immediately suppress the new bar and let the content

     * adjuster re-run on the next frame (throttle reset), so the wrong

     * artwork is never drawn.

     */

    /**

     * The file manager owns two bars that replace each other across UI modes:

     * the multi-select action bar (split_action_bar, created/destroyed with

     * each selection session) and the main tab row (bottom_navigation_container,

     * 最近/浏览 plus the standalone circular search key). ScannerSession locks

     * `installed` after the first install and never scans again, so every

     * subsequent switch must re-bind here: prefer the live action bar, fall

     * back to the main tab row, and re-parameterize the host (slider, slot

     * count, non-selectable search key, geometry) for the bound bar.

     */

    fun refreshFileManagerNavigationSource(): Boolean {

        if (context.packageName != FILE_MANAGER_PACKAGE) return false

        val current = navigationSource

        val currentId = current?.takeIf {

            it.isAttachedToWindow && it.width > 0 && it.height > 0

        }?.let { viewResourceEntryName(it) }

        val currentIsAuthoritative = currentId == FILE_MANAGER_ACTION_BAR_ID ||

            currentId == FILE_MANAGER_BOTTOM_NAV_ID

        if (currentIsAuthoritative) {

            val content = parent as? ViewGroup ?: return false

            val rect = if (currentId == FILE_MANAGER_BOTTOM_NAV_ID) {

                GlassInstaller.fileManagerMainPillRect(current as ViewGroup, content)

            } else {

                GlassInstaller.floatingPillRect(current as ViewGroup, content)

            } ?: return false

            val params = layoutParams as? FrameLayout.LayoutParams ?: return false

            val targetRight = (content.width - rect.right).coerceAtLeast(0)

            val targetBottom = (content.height - rect.bottom).coerceAtLeast(0)

            if (params.width == rect.width() &&

                params.height == rect.height() &&

                params.leftMargin == rect.left &&

                params.rightMargin == targetRight &&

                params.bottomMargin == targetBottom

            ) return false

            params.width = rect.width()

            params.height = rect.height()

            params.leftMargin = rect.left

            params.rightMargin = targetRight

            params.bottomMargin = targetBottom

            layoutParams = params

            requestLayout()

            navigationSnapshotSignature = Long.MIN_VALUE

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            postInvalidateOnAnimation()

            return true

        }

        val root = rootView as? ViewGroup ?: return false

        var fresh: ViewGroup? = null

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < 800) {

            val (view, depth) = stack.removeLast()

            if (view is ViewGroup && view !== current && view.isAttachedToWindow &&

                view.width > 0 && view.height > 0 && view.visibility == View.VISIBLE &&

                viewResourceEntryName(view) == FILE_MANAGER_ACTION_BAR_ID

            ) {

                fresh = view

                break

            }

            if (view is ViewGroup && depth < 12) {

                for (index in 0 until view.childCount) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        if (fresh == null) {

            val fallback = ArrayDeque<Pair<View, Int>>()

            fallback += root to 0

            visited = 0

            while (fallback.isNotEmpty() && visited++ < 800) {

                val (view, depth) = fallback.removeLast()

                if (view is ViewGroup && view !== current && view.isAttachedToWindow &&

                    view.width > 0 && view.height > 0 && view.visibility == View.VISIBLE &&

                    viewResourceEntryName(view) == FILE_MANAGER_BOTTOM_NAV_ID

                ) {

                    fresh = view

                    break

                }

                if (view is ViewGroup && depth < 12) {

                    for (index in 0 until view.childCount) {

                        fallback += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

        }

        val navigation = fresh ?: return false

        val content = parent as? ViewGroup

        val isActionBar = viewResourceEntryName(navigation) == FILE_MANAGER_ACTION_BAR_ID

        navigationSource = navigation

        if (navigation.alpha != 0f) navigation.alpha = 0f

        if (navigation.background != null) navigation.background = null

        sliderEnabled = !isActionBar

        nonSelectableIndices = if (isActionBar) emptySet() else setOf(2)

        if (isActionBar) {

            // Multi-select bar: bind the pill itself (not the full-width

            // container) as the projection source and derive the slot count

            // from its action buttons.

            var pill: ViewGroup? = null

            if (content != null) {

                val rect = GlassInstaller.floatingPillRect(navigation, content)

                if (rect != null) {

                    pill = GlassInstaller.floatingPillSource(navigation, content, rect)

                    val params = layoutParams as? FrameLayout.LayoutParams

                    if (params != null) {

                        params.width = rect.width()

                        params.height = rect.height()

                        params.leftMargin = rect.left

                        params.rightMargin = (content.width - rect.right).coerceAtLeast(0)

                        params.bottomMargin = (content.height - rect.bottom).coerceAtLeast(0)

                        layoutParams = params

                        requestLayout()

                    }

                }

            }

            if (pill == null) {

                pill = (0 until navigation.childCount)

                    .mapNotNull { navigation.getChildAt(it) as? ViewGroup }

                    .firstOrNull { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

            }

            if (pill != null) {

                navigationSource = pill

                slotCount = pill.childCount.coerceIn(2, 7)

            }

        } else {

            // Main tab row: artwork is drawn from the extracted static cache,

            // so the container itself stays the extraction source. Re-pin the

            // host over the whole native pill (tabs + search circle).

            slotCount = 3

            if (content != null) {

                val rect = GlassInstaller.fileManagerMainPillRect(navigation, content)

                if (rect != null) {

                    val params = layoutParams as? FrameLayout.LayoutParams

                    if (params != null) {

                        params.width = rect.width()

                        params.height = rect.height()

                        params.leftMargin = rect.left

                        params.rightMargin = (content.width - rect.right).coerceAtLeast(0)

                        params.bottomMargin = (content.height - rect.bottom).coerceAtLeast(0)

                        layoutParams = params

                        requestLayout()

                    }

                }

            }

        }

        // Re-extract main-bar artwork after any re-bind; the multi-select bar

        // stays on the generic snapshot path.

        fmStaticIconsPopulated = false

        fmLastCaptureSignature = Long.MIN_VALUE

        fmStableFrames = 0

        navigationSnapshotSignature = Long.MIN_VALUE

        pendingNavigationSignature = Long.MIN_VALUE

        pendingNavigationStableFrames = 0

        postInvalidateOnAnimation()

        XposedBridge.log(

            "[OfflineGlass][FileManager] re-bound " +

                (if (isActionBar) "action-bar" else "main-tab-row") +

                " slots=$slotCount slider=$sliderEnabled",

        )

        return true

    }



    private var configSyncBroadcastRegistered = false

    // Shared scroll-stop-hide timer; adapter policy supplies the delay.

    private val msgScrollHideRunnable: Runnable = Runnable {

        if (msgUserTouching) {

            postDelayed(msgScrollHideRunnable, 200L)

            return@Runnable

        }

        msgScrolledToStop = true

        postInvalidateOnAnimation()

    }

    internal var msgScrolledToStop = false

    private var msgScrollListenerAttached = false

    private var msgUserTouching = false

    internal val navigationIndex: Int get() = selectedIndex


internal val density = resources.displayMetrics.density

internal val clipPath = Path()

    private val outlinePath = Path()

internal val outerRect = RectF()

    private val expandedBackdropRect = RectF()

    private val selectionRect = RectF()

    private val selectionPath = Path()

    private val lightingPath = Path()

    private val lightingRect = RectF()

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val navigationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val snapshotCopyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val outerRenderNode = RenderNode("OfflineGlassOuter")

    private val tabsBackdropNode = RenderNode("OfflineGlassTabsBackdrop")

    private val indicatorCombinedNode = RenderNode("OfflineGlassIndicatorCombined")

    private val indicatorRenderNode = RenderNode("OfflineGlassIndicator")

    private val indicatorShadowNode = RenderNode("OfflineGlassIndicatorShadow")

    private val indicatorCausticsNode = RenderNode("OfflineGlassIndicatorCaustics")

    private val liveBackdropNode = RenderNode("OfflineGlassLiveBackdrop")
    internal val liveBackdropNodeForAdapter: RenderNode get() = liveBackdropNode
    internal val liveBackdropActiveForAdapter: Boolean get() = liveBackdropActive

    // UI-thread scratch only. Clear references after each sweep so replaced pages

    // are not retained; keep capacity instead of allocating containers each frame.

    private val sceneNodesScratch = ArrayList<RenderNode>()

    private val sceneViewsScratch = ArrayList<View>()

    private val sceneContentLocation = IntArray(2)

    private val sceneHostLocation = IntArray(2)

    private var sceneSweepInProgress = false

    private var innerShadowBlurRadius = Float.NaN

    private var innerShadowBlurEffect: RenderEffect? = null

    private val navigationContentNode = RenderNode("OfflineGlassNavigationContent")

internal val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val plainBlurOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.STROKE

    }

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }

    private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    internal val panelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val hiddenTabsTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var drawingHiddenNavigationForOptics = false
    private var lastAppVideoBackdropEpoch = -1

    private val qqStaticIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val qqStaticLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        textAlign = Paint.Align.CENTER

        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)

    }

    private val qqNormalIconBitmaps by lazy(LazyThreadSafetyMode.NONE) {

        QQ_NORMAL_ICON_FILES.map(::loadQqTabBitmap)

    }

    private val qqSelectedIconBitmaps by lazy(LazyThreadSafetyMode.NONE) {

        QQ_SELECTED_ICON_FILES.map(::loadQqTabBitmap)

    }




    private var outerLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "outer")

    private var combinedOuterLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "tabs")

    private var opticalSurfaceOuterLensShader =

        createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "optical-surface-outer")

    private var indicatorLensShader = createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "indicator")

    private val outerBloomShader = runCatching { RuntimeShader(BLOOM_STROKE_DUAL_SHADER) }.getOrNull()

    private val indicatorBloomShader = runCatching { RuntimeShader(BLOOM_STROKE_DUAL_SHADER) }.getOrNull()

    private var bloomTiltRegistered = false



    /** Keep the shared tilt sensor alive only while this host actually draws bloom strokes. */

    private fun updateBloomTiltRegistration() {

        val want = isAttachedToWindow &&

            windowVisibility == View.VISIBLE &&

            config.liquidGlassEnabled &&

            config.outlineEnabled

        if (want == bloomTiltRegistered) return

        bloomTiltRegistered = want

        if (want) BloomTiltTracker.register(this) else BloomTiltTracker.unregister(this)

    }

    private var outerGlassEffect: RenderEffect? = null

    private var opticalSurfaceOuterGlassEffect: RenderEffect? = null

    private var tabsBackdropEffect: RenderEffect? = null



internal var selectedIndex = 0
    internal val adapterSlotCount: Int get() = slotCount
    internal val adapterSelectedIndex: Int get() = selectedIndex
    internal fun adapterShowDuringScroll() {
        removeCallbacks(msgScrollHideRunnable)
        if (msgScrolledToStop) {
            msgScrolledToStop = false
            postInvalidateOnAnimation()
        }
    }
    internal fun adapterHideAfter(delayMs: Long) {
        removeCallbacks(msgScrollHideRunnable)
        postDelayed(msgScrollHideRunnable, delayMs)
    }
    internal fun updateAdapterSlotCount(count: Int, selected: Int) {
        if (count !in 1..7 || count == slotCount) return
        slotCount = count
        selectedIndex = selected.coerceIn(0, count - 1)
        dragTarget = selectedIndex.toFloat()
        indicatorPosition = dragTarget
        positionSpring.snapTo(dragTarget)
        updateHostLayoutForConfig()
        invalidate()
    }
    internal fun adapterResolvedSelection(): Int? = resolveSelectedFromViewState()

    private var selectionInitialized = false

    private var dragTarget = 0f

    private var indicatorPosition = 0f

    private var indicatorVelocity = 0f

    private var pressProgress = 0f

    private var outerPanelScale = 1f

    private var indicatorScaleX = 1f

    private var indicatorScaleY = 1f

    private var interactiveHighlightProgress = 0f

    private var touchX = 0f

    private var touchY = 0f

    private var lastTouchX = 0f

    private var gestureStartX = 0f

    private var gestureStartY = 0f

    private var touching = false

    private var indicatorGestureActive = false

    private var pendingTapIndex = -1

    private var pendingTapMoved = false

internal val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var panelDragOffset = 0f

    private var releaseGeneration = 0

    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)



    private var backdrop: Bitmap? = null

    private var liveBackdropActive = false

    private var liveBackdropSignature = Long.MIN_VALUE

    private var pendingBackdropSignature = Long.MIN_VALUE

    private var pendingBackdropStableFrames = 0

    private var liveBackdropStableOffsetX = Int.MIN_VALUE

    private var liveBackdropStableOffsetY = Int.MIN_VALUE

    private var opticalRefreshGeneration = 0

    private var liveBackdropLogged = false

    private var captureListener: ViewTreeObserver.OnPreDrawListener? = null

    private var sourceVisibilityListener: ViewTreeObserver.OnPreDrawListener? = null

    private var lastCapture = 0L

    private var capturing = false

    private var qqStaticBackdropRefreshPending = context.packageName == QQ_PACKAGE

    private var qqStaticBackdropRefreshGeneration = 0

    private var detectedContentDark: Boolean? = null

    private var lastThemeProbe = 0L

    private var lastConfigPoll = 0L

    private var configPollPending = false

    private var configPollGeneration = 0L

    private var lastConfigDiagLog = 0L

    private var nativeBlurActive = false

    private var lastPageVisibilityCheck = 0L

    private var pageAllowsNavigation = true

    private var adapterLastReadySourceAt = 0L

    private val originalChromeVisibility = LinkedHashMap<View, Int>().apply {

        suppressedOriginalChrome.forEach { put(it, it.visibility) }

    }

    private var lastQqLayerSignature = ""

    private var opticalSurfaceSnapshotReady = false

    private var opticalSurfaceTextureView: TextureView? = null

    private var opticalSurfaceLastTextureProbe = 0L

    private var opticalSurfacePixelCopyInFlight = false

    private var opticalSurfacePixelCopyScratch: Bitmap? = null

    private var opticalSurfaceBackdropPadding = 0f

    private var opticalSurfaceNativeBlurView: View? = null

    private var opticalSurfaceNativeBlurActive = false

    private var opticalSurfaceNativeIndicatorView: View? = null

    private var opticalSurfaceNativeIndicatorActive = false

    private var opticalSurfaceNativeIndicatorShader =

        createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "optical-surface-native-indicator")

    private var opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

    private var opticalSurfaceSurfaceView: SurfaceView? = null
    internal val opticalSurfaceViewForAdapter: SurfaceView? get() = opticalSurfaceSurfaceView

    private var opticalSurfaceSurfaceReady = false

    private var opticalSurfaceSurfacePipelineActive = false

    private var lastAppVideoDiagLog = 0L

    // Written from the PixelCopy callback thread, read on the UI thread to

    // gate the copy cadence; must be visible across threads promptly.

    @Volatile private var opticalSurfaceRootCopyInFlight = false

    private var opticalSurfaceRootCopyScratch: Bitmap? = null


    private val opticalSurfacePixelCopyHandler = Handler(Looper.getMainLooper())

    private var compositorPixelCopyThread: HandlerThread? = null

    private var compositorPixelCopyHandler: Handler? = null

    /** Serializes surface renders between the UI thread and the copy callback thread. */

    private val surfaceRenderLock = Any()

    private val fallbackOpticalSurfaceRenderQueue by lazy(LazyThreadSafetyMode.NONE) {
        io.github.offlineglass.hook.adapters.OpticalSurfaceRenderQueue()
    }

    private val sharedOpticalBlurNode = RenderNode("LiquidTab-SharedOpticalBlur").apply {

        setClipToBounds(false)

    }

    private var sharedOpticalBlurEffect: RenderEffect? = null

    private var sharedOpticalBlurPrepared = false

    private val reuseOpticalBlur = targetAdapter?.reuseOpticalBlur == true

    private val opticalLightingBlurRadii = floatArrayOf(Float.NaN, Float.NaN)

    private val opticalLightingBlurEffects = arrayOfNulls<RenderEffect>(2)


    // JD's feed owns a large dynamic hierarchy.  Its cleanup pass must never

    // run as a timer from pre-draw: clearing backgrounds while cards are being

    // rebound makes only the information flow flash.  A single cleanup after a

    // newly bound native bar is enough to remove the old bar artwork.

    // A JD NavigationGroup stays attached while the user changes home channels.

    // The structural correction must run once per actual bar instance, not as a

    // periodic probe, otherwise ordinary channels continuously re-layout.

    // JD page switches re-apply the bar stack's LayoutParams (or re-parent the

    // navigation row into a fresh uncollapsed container). The once-per-source

    // guard then kept a restored native bar on screen, covering the feed.

    // Track the collapsed bar and the extended spines so every preDraw can

    // cheaply verify the structural correction is still in effect.

    // JD's React Native renderer lays out the flash-page controls repeatedly.

    // Moving them with offsetTopAndBottom is undone by each layout pass, which

    // makes the controls visibly oscillate between the native and safe slots.

    // Keep the original property value for detach and anchor with translation

    // instead: translations survive ordinary RN layout passes and move hit tests

    // together with the rendered control.

    private var themeTransitionUntil = 0L

    private var sourceSizingGeneration = 0

    private var navigationRefreshGeneration = 0

    private var navigationSnapshot: Bitmap? = null

    private var navigationCaptureScratch: Bitmap? = null

    private var navigationSnapshotSignature = Long.MIN_VALUE

    private var pendingNavigationSignature = Long.MIN_VALUE

    private var pendingNavigationStableFrames = 0

    private var navigationSnapshotFrozenUntil = 0L

    // Keep completed bar inputs during native page construction so the
    // indicator does not share its frame budget with backdrop/icon discovery.
    private var barAnimationPriorityUntil = 0L
    private var lastPriorityNavigationProbeAt = 0L

    // File manager static icon cache — the main tab row (最近/浏览/搜索) is

    // extracted once from the settled native bar and re-drawn independently,

    // mirroring the JD static-icon path; the search key is the standalone

    // circular slot. The multi-select action bar never uses this cache.

    private val fmStaticSlots = arrayOfNulls<Bitmap>(3)

    private var fmStaticIconsPopulated = false

    private var fmLastCaptureSignature = Long.MIN_VALUE

    private var fmStableFrames = 0

    private val fmIconPaint = Paint(Paint.ANTI_ALIAS_FLAG)


    // The navigation snapshot bitmap is static between tab switches, yet

    // drawNavigationContent used to re-record navigationContentNode on every

    // glass render. Keyed by (bitmap, size); the recording paints with the

    // reset navigationPaint (colorFilter=null, alpha=255), so the cached

    // display list is bit-identical to a fresh recording.

    private var navigationContentRecordedBitmap: Bitmap? = null

    private var navigationContentRecordedWidth = 0

    private var navigationContentRecordedHeight = 0

    private var lastNavigationNormalization = "none"

    private var lastGeometryLogSignature = ""



    private var perfFrames = 0

    private var perfPrepareNs = 0L

    private var perfPrepareMaxNs = 0L

    private var perfPanelNs = 0L

    private var perfPanelMaxNs = 0L

    private var perfDispatchNs = 0L

    private var perfDispatchMaxNs = 0L

    private var perfSceneSweeps = 0

    private var perfSceneSiblingVisits = 0

    private var perfSceneReRecords = 0

    private var perfEffectRebuilds = 0

    private var perfNodeRecords = 0

    private var perfSurfaceRenders = 0

    private var perfLastLogUptime = 0L



    private val positionSpring = SpringFloat(0f, 1_000f, 1f, 0.001f) { value, velocity ->

        indicatorPosition = value

        if (touching) {

            velocitySpring.animateTo(velocity / (visualSlotCount() - 1).coerceAtLeast(1))

        }

        invalidate()

    }

    private val velocitySpring = SpringFloat(0f, 300f, 0.5f, 0.01f) { value, _ ->

        indicatorVelocity = value

        invalidate()

    }

    private val pressSpring = SpringFloat(0f, 1_000f, 1f, 0.001f) { value, _ ->

        pressProgress = value.coerceIn(0f, 1f)

        updateOuterScale()

        invalidate()

    }

    private val scaleXSpring = SpringFloat(1f, 250f, 0.6f, 0.001f) { value, _ ->

        indicatorScaleX = value

        invalidate()

    }

    private val scaleYSpring = SpringFloat(1f, 250f, 0.7f, 0.001f) { value, _ ->

        indicatorScaleY = value

        invalidate()

    }

    private val interactiveSpring = SpringFloat(0f, 300f, 0.5f, 0.001f) { value, _ ->

        interactiveHighlightProgress = value.coerceIn(0f, 1f)

        invalidate()

    }

    private val panelOffsetSpring = SpringFloat(0f, 300f, 0.5f, 0.01f) { value, _ ->

        panelDragOffset = value

        updatePanelTranslation()

        invalidate()

    }



    init {

        setWillNotDraw(false)

        // Mi Health inserts/re-attaches its main fragment after returning from

        // CommonBaseActivity. Its transparent native TabLayout then stops

        // being a reliable physical hit target. Make the retained glass host

        // itself the explicit target; dispatchTouchEvent still routes the

        // committed tab through TabLayout.Tab.select().

        isClickable = targetAdapter?.ownsNavigationTap == true

        isFocusable = false

        clipChildren = false

        clipToPadding = false

        outlineProvider = BottomBarSquircleOutlineProvider(

            radius = { outerCornerRadiusPx() },

            smoothing = { config.cornerSmoothing },

        )

        clipToOutline = false

    }



    /** Carries selection across QQ's in-place navigation rebuild on theme changes. */

    fun adoptRuntimeStateFrom(previous: GlassHostLayout) {

        selectedIndex = previous.selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        selectionInitialized = previous.selectionInitialized

        dragTarget = if (targetAdapter?.snapIndicatorToSelectionOnRebind == true) {

            visualPositionForNavigationIndex(selectedIndex)

        } else {

            previous.indicatorPosition.coerceIn(0f, (slotCount - 1).coerceAtLeast(0).toFloat())

        }

        indicatorPosition = dragTarget

        positionSpring.snapTo(dragTarget)

        velocitySpring.snapTo(0f)

        pressSpring.snapTo(0f)

        scaleXSpring.snapTo(1f)

        scaleYSpring.snapTo(1f)

        interactiveSpring.snapTo(0f)

        panelOffsetSpring.snapTo(0f)

    }



    internal fun onAdapterBlockingOverlayVisibilityChanged(visible: Boolean) {

        post {

            if (!isAttachedToWindow) return@post

            updateAnimatedVisibility(!visible)

        }

    }



    /**

     * QQ recreates its tab row in-place while switching colour modes. Keep this

     * renderer (and therefore the Backdrop, springs and touch state) alive and

     * atomically point it at the completed replacement row instead of replacing

     * the whole glass View for one configuration frame.

     */

    fun rebindNavigationSource(

        source: ViewGroup,

        chrome: List<View>,

        rawConfig: GlassConfig,

    ) {

        if (targetAdapter?.usesOpticalSurfacePipeline == true && navigationSource !== source) {

            opticalSurfaceTextureView = null

            opticalSurfaceLastTextureProbe = 0L

            opticalSurfaceSnapshotReady = false

        }

        navigationSource = source

        suppressNativeQqBottomChrome(source, forceProbe = true)

        if (targetAdapter?.ownsHostNativeChrome == true) {
            appNavigationState?.suppressHostNativeChrome(source, rootView, resolveSelectedFromViewState() ?: -1)
        }

        if (source != null && targetAdapter?.suppressSourceOnHostRebind == true) {
            targetAdapter?.suppressNativeBottomChromeOnRebind(source)
        }

        appNavigationState?.suppressNativeChromeEarly(source)

        suppressNativeMeituanTakeoutBottomBar(source)

        source?.let { targetAdapter?.suppressNativeBottomChrome(this, it) }

        appNavigationState?.suppressNativeChrome(source)

        source?.let { targetAdapter?.suppressNativeBottomChrome(this, it) }


        suppressNativeRedrawnBottomBar(source)

        chrome.forEach { view ->

            originalChromeVisibility.putIfAbsent(view, view.visibility)

            view.visibility = View.INVISIBLE

        }

        suppressedOriginalChrome = chrome

        val updated = packageConfig(rawConfig)

        if (updated != config) applyLiveConfig(updated)

        // A Bilibili page replacement can rebind this host while its temporary

        // content root still carries the previous page's inset geometry. Refresh

        // the screen-anchored layout even when configuration itself is unchanged.

        if (targetAdapter?.refreshHostGeometryOnRebind == true) updateHostLayoutForConfig()

        beginThemeTransition(resizeSourceAfterSettle = false)

        scheduleNavigationSnapshotRefresh(longArrayOf(240L, 720L, 1_500L))

        requestQqStaticBackdropRefresh(160L)

        visibility = View.VISIBLE

        bringToFront()

        post { logGeometryIfChanged("rebind") }

        invalidate()

    }



    /**

     * Release clipping only inside the application's content hierarchy.

     *

     * The liquid slider and the show/hide overshoot need room outside the host

     * bounds, but the freeform-window corner mask belongs to the DecorView (and

     * other window-level parents above android.R.id.content). Clearing their

     * outline provider makes the whole small window rectangular. Keep every

     * ancestor outline intact and stop before the application content root;

     * GlassInstaller already releases clipping on that content root itself.

     */

    private fun releaseAncestorClipping() {

        if (targetAdapter?.releaseOutlineClipping == true) {

            clipToOutline = false

            outlineProvider = null

        }

        val contentRoot = rootView.findViewById<View>(android.R.id.content)

        val maxDepth = targetAdapter?.contentAncestorMaxDepth ?: 12

        var ancestor = parent as? ViewGroup ?: return

        var guard = 0

        while (contentRoot != null && guard < maxDepth && ancestor !== contentRoot) {

            ancestor.clipChildren = false

            ancestor.clipToPadding = false

            val next = ancestor.parent as? ViewGroup ?: break

            if (next === ancestor) break

            ancestor = next

            guard++

        }

        appNavigationState?.enforceContentClipping()

    }



    override fun onAttachedToWindow() {

        if (context.packageName == MI_THEME_PACKAGE ||

            context.packageName == MI_THEME_PROXY_PACKAGE

        ) {

            android.util.Log.i("ThemeGlassDiag", "onAttached parent=${parent?.javaClass?.simpleName}")

            post {

                navigationSource?.let { dumpNavigationTree(it) }

            }

        }

        super.onAttachedToWindow()

        targetAdapter?.requestedFrameRateMax?.takeIf { Build.VERSION.SDK_INT >= 35 }?.let { maximum ->
            val requestedRate = display?.supportedModes
                ?.maxOfOrNull { it.refreshRate }
                ?.coerceAtMost(maximum)
                ?: maximum
            setRequestedFrameRate(requestedRate)
            appNavigationState?.logDiagnostic("requested frame rate=$requestedRate")
        }

        context.findActivity()?.let { activity ->

            if (targetAdapter?.navigationImmersionOverride != null) {

                GlassInstaller.updateAdapterNavigationBarForPage(activity)

            } else {

                GlassInstaller.ensureNavigationBarImmersion(activity)

            }

        }

        releaseAncestorClipping()

        navigationSource?.let {

            val root = rootView

            sourceVisibilityListener = ViewTreeObserver.OnPreDrawListener {

                maybeReloadConfig()

                if (context.packageName == MI_THEME_PACKAGE ||

                    context.packageName == MI_THEME_PROXY_PACKAGE

                ) {

                    suppressThemePillNativeIndicator()

                }

                // The host can remain attached while an app temporarily hides

                // its liquid bar and replaces page content. Keep the Activity's

                // navigation-edge layout state independent from bar visibility,

                // and repair only if the app changed the window flags or colour.

                val forceNavigationBarImmersed = appNavigationState?.forceNavigationBarImmersed(this)

                context.findActivity()?.let { activity ->

                    if (forceNavigationBarImmersed == false) {

                        GlassInstaller.setNavigationBarImmersion(activity, false)

                    } else if (targetAdapter?.navigationImmersionOverride != null) {

                        GlassInstaller.updateAdapterNavigationBarForPage(activity)

                    } else {

                        GlassInstaller.ensureNavigationBarImmersion(activity)

                    }

                }

                for (view in suppressedOriginalChrome) {

                    if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE

                }

                // Mi Health rebuilds its TabLayout when returning from device

                // sub-pages; re-bind a detached source BEFORE this frame's

                // suppression, snapshot and readiness checks consume it.

                if (targetAdapter?.ownsNavigationTap == true) {

                    appNavigationState?.refreshNavigationSource(this)

                }

                // The file manager's multi-select action bar is destroyed when

                // selection mode ends and re-inflated on the next session;

                // re-bind the fresh split_action_bar before suppression and

                // snapshot checks consume the stale source. Its main tab row

                // artwork is extracted here once the native bar has settled.

                if (context.packageName == FILE_MANAGER_PACKAGE) {

                    refreshFileManagerNavigationSource()

                    populateFileManagerStaticIcons()

                }

                val source = navigationSource

                suppressNativeQqBottomChrome(source)

                if (targetAdapter?.ownsHostNativeChrome == true) {
                    appNavigationState?.suppressHostNativeChrome(source, rootView, resolveSelectedFromViewState() ?: -1)
                }

                if (source != null && targetAdapter?.suppressSourceOnHostRebind == true) {
                    targetAdapter?.suppressNativeBottomChromeOnRebind(source)
                }

                val prioritizeTapTransition = SystemClock.uptimeMillis() < barAnimationPriorityUntil

                if (!prioritizeTapTransition && targetAdapter?.hasPerFrameScene == true) {
                    stabilizeLiftedHostPosition()
                    appNavigationState?.onHostPreDraw(
                        this, navigationSource,
                        { (resolveSelectedFromViewState() ?: selectedIndex).coerceIn(0, (slotCount - 1).coerceAtLeast(0)) },
                        config.enabled, density,
                        glassHostHeightPx(density, config, context.packageName),
                        { adapterNavigationSurfaceColor() },
                    )
                }

                appNavigationState?.suppressNativeChromeEarly(source)

                appNavigationState?.refreshNavigationSource(this)
                appNavigationState?.suppressNativeChrome(navigationSource ?: source)

                appNavigationState?.adjustFloatingActions(this, navigationSource)

                // Resolve the selected Surface-backed homepage channel before

                // positioning its independent bottom overlays.

                if (!prioritizeTapTransition) appNavigationState?.onHostFrame(this)

                suppressNativeMeituanTakeoutBottomBar(source)

                adjustMeituanTakeoutScrollSafety()

                appNavigationState?.refreshNavigationSource(this)
                source?.let { targetAdapter?.suppressNativeBottomChrome(this, it) }

                appNavigationState?.suppressNativeChrome(source)

                appNavigationState?.adjustFloatingActions(this, navigationSource)

                source?.let { targetAdapter?.suppressNativeBottomChrome(this, it) }



                appNativeChromeController?.update(this, source)

                // JD rebuilds its NavigationGroup when switching pages; until

                // the host rebinds, the stale source left the freshly created

                // bar unsuppressed for visible frames (native-bar flicker).

                // Re-bind by class name before suppressing, like Weibo does.

                suppressNativeRedrawnBottomBar(source)

                val now = SystemClock.uptimeMillis()

                releaseAncestorClipping()

                if (context.packageName != QQ_PACKAGE) {

                    maybeRefreshNavigationSnapshot()

                }

                val sourceReady = source != null && source.isAttachedToWindow &&

                    source.width > 0 && source.height > 0 &&

                    if (targetAdapter?.sourceParentVisibilityOnly == true) {

                        (source.parent as? View)?.ancestorsAreVisible() != false

                    } else {

                        source.ancestorsAreVisible()

                    }

                if (targetAdapter?.sourceSwapGraceMs != 0L && sourceReady) {

                    adapterLastReadySourceAt = now

                }

                // WeChat swaps LauncherUIBottomTabView after returning from a

                // conversation or another app. Preserve the already-rendered

                // host during that short detach instead of playing a false exit

                // animation. The exact replacement is dispatched by the native

                // tab's onLayout hook and rebinds within this bounded window.

                val adapterSourceSwapGrace = (targetAdapter?.sourceSwapGraceMs ?: 0L) > 0L &&

                    adapterLastReadySourceAt > 0L &&

                    now - adapterLastReadySourceAt <= (targetAdapter?.sourceSwapGraceMs ?: 0L)

                val sourceBeingRebuilt = source != null && now < themeTransitionUntil

                if (targetAdapter?.ownsNavigationTap == true) {

                    // A returned settings page can append/re-attach content

                    // after this host. Reassert the already-visible glass as

                    // the physical touch target without changing its geometry.

                    if (!isClickable) isClickable = true

                    if (!isEnabled) isEnabled = true

                    // bringToFront() unconditionally requests a layout; calling

                    // it every preDraw frame kept the UI thread in a permanent

                    // relayout loop (starved page switches and view dumps).

                    val hostParent = parent as? ViewGroup

                    if (hostParent != null &&

                        hostParent.getChildAt(hostParent.childCount - 1) !== this

                    ) bringToFront()

                }

                // While Shorts' back-peek window is open the bar must reappear

                // even though the Shorts pager may have detached the native row

                // underneath it; the retained navigation snapshot still supplies

                // the projected content.

                val retainsHiddenNavigation = appNavigationState?.retainNavigationWhenNativeRowHidden(
                    selectedIndex,
                ) == true

                val shouldShow = config.enabled &&

                    (sourceReady || sourceBeingRebuilt || adapterSourceSwapGrace ||
                        appNavigationState?.retainNavigationWithoutNativeSource(selectedIndex) == true ||
                        retainsHiddenNavigation) &&

                    currentPageAllowsNavigation()

                if (usesSceneVisibilityAnimation()) {

                    updateAnimatedVisibility(shouldShow)

                    syncOpticalVisibilityCompanions()

                } else {

                    val wanted = if (shouldShow) View.VISIBLE else View.GONE

                    if (visibility != wanted) visibility = wanted

                }


                true

            }.also { root.viewTreeObserver.addOnPreDrawListener(it) }

        }

        // JD uses a separate compositor-backed optical sheet below this host.

        // Applying native blur to the host itself would cover that sheet and

        // reduce the result to a flat blur without edge/pill refraction.

        nativeBlurActive = targetAdapter?.allowsSystemBackgroundBlur != false && shouldUseNativeBackgroundBlur() &&

            enableMiuiBackgroundBlur()

        if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

            post {

                if (!isAttachedToWindow) return@post

                syncBackdropRendererMode()

            }

        }

        if (config.backdropCapture) installCaptureLoop()

        if (context.packageName == QQ_PACKAGE) {

            scheduleNavigationSnapshotRefresh(longArrayOf(120L, 360L, 900L, 1_500L))

            requestQqStaticBackdropRefresh(96L)

        }

        registerConfigSyncBroadcast()

        // A theme switch gives the replacement Activity its measured size

        // before the new hardware render tree is fully attached. Rebind both

        // the retained source scene and its effects after the first traversals.

        scheduleOpticalPipelineRefresh(longArrayOf(0L, 32L, 96L), "attached")

        updateBloomTiltRegistration()

    }



    override fun onWindowVisibilityChanged(visibility: Int) {

        super.onWindowVisibilityChanged(visibility)

        updateBloomTiltRegistration()

    }



    override fun onDetachedFromWindow() {

        if (targetAdapter?.requestedFrameRateMax != null && Build.VERSION.SDK_INT >= 35) {
            setRequestedFrameRate(0f)
        }

        if (context.packageName == MI_THEME_PACKAGE ||

            context.packageName == MI_THEME_PROXY_PACKAGE

        ) {

            android.util.Log.i("ThemeGlassDiag", "onDetached")

        }

        configPollGeneration++

        configPollPending = false

        bloomTiltRegistered = false

        BloomTiltTracker.unregister(this)

        opticalRefreshGeneration++

        navigationRefreshGeneration++

        qqStaticBackdropRefreshGeneration++

        qqStaticBackdropRefreshPending = false

        val root = rootView

        captureListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        captureListener = null

        sourceVisibilityListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        sourceVisibilityListener = null

        if (configSyncBroadcastRegistered) unregisterConfigSyncBroadcast()

        appNavigationState?.onHostDetached()



        if (usesSceneVisibilityAnimation()) {

            barVisibilityGeneration++

            barVisibilityAnimating = false

            barVisibilityAnimator?.cancel()

            barVisibilityAnimator = null

            animate().cancel()

            alpha = 1f

            scaleX = 1f

            scaleY = 1f

            translationY = 0f

        }

        originalChromeVisibility.forEach { (view, visibility) -> view.visibility = visibility }

        listOf(

            positionSpring,

            velocitySpring,

            pressSpring,

            scaleXSpring,

            scaleYSpring,

            interactiveSpring,

            panelOffsetSpring,

        )

            .forEach(SpringFloat::cancel)

        translationX = 0f

        scaleX = 1f

        scaleY = 1f

        backdrop?.recycle()

        backdrop = null

        opticalSurfacePixelCopyInFlight = false

        opticalSurfaceTextureView = null

        opticalSurfaceLastTextureProbe = 0L

        opticalSurfacePixelCopyScratch?.recycle()

        opticalSurfacePixelCopyScratch = null

        opticalSurfaceBackdropPadding = 0f

        opticalSurfaceNativeBlurView?.let { blur ->

            disableOpticalSurfaceNativeBlur(blur)

            (blur.parent as? ViewGroup)?.removeView(blur)

        }

        opticalSurfaceNativeBlurView = null

        opticalSurfaceNativeBlurActive = false

        opticalSurfaceNativeIndicatorView?.let { indicator ->

            disableOpticalSurfaceNativeBlur(indicator)

            indicator.setRenderEffect(null)

            (indicator.parent as? ViewGroup)?.removeView(indicator)

        }

        opticalSurfaceNativeIndicatorView = null

        opticalSurfaceNativeIndicatorActive = false

        opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

        opticalSurfaceSurfaceView?.let { surface ->

            surface.visibility = View.GONE

            (surface.parent as? ViewGroup)?.removeView(surface)

        }

        opticalSurfaceSurfaceView = null

        opticalSurfaceSurfaceReady = false

        opticalSurfaceSurfacePipelineActive = false

        opticalSurfaceRootCopyInFlight = false

        opticalSurfaceRootCopyScratch?.recycle()

        opticalSurfaceRootCopyScratch = null


        compositorPixelCopyThread?.quitSafely()

        compositorPixelCopyThread = null

        compositorPixelCopyHandler = null

        compositorSurfaceProbeScheduled = false

        compositorSurfaceProbeRunning = false

        compositorSurfaceVsyncTick = 0

        compositorSurfaceStaticStreak = 0

        lastSurfaceRenderDark = null

        navigationSnapshot?.recycle()

        navigationSnapshot = null

        navigationContentRecordedBitmap = null

        navigationCaptureScratch?.recycle()

        navigationCaptureScratch = null

        appNavigationState?.dispose()

        fmStaticSlots.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }

        fmStaticSlots.fill(null)

        fmStaticIconsPopulated = false

        fmLastCaptureSignature = Long.MIN_VALUE


        outerRenderNode.setRenderEffect(null)

        tabsBackdropNode.setRenderEffect(null)

        indicatorCombinedNode.setRenderEffect(null)

        indicatorRenderNode.setRenderEffect(null)

        indicatorShadowNode.setRenderEffect(null)

        indicatorCausticsNode.setRenderEffect(null)

        liveBackdropNode.setRenderEffect(null)

        navigationContentNode.setRenderEffect(null)

        outerRenderNode.discardDisplayList()

        tabsBackdropNode.discardDisplayList()

        indicatorCombinedNode.discardDisplayList()

        indicatorRenderNode.discardDisplayList()

        indicatorShadowNode.discardDisplayList()

        indicatorCausticsNode.discardDisplayList()

        liveBackdropNode.discardDisplayList()

        navigationContentNode.discardDisplayList()

        liveBackdropActive = false

        liveBackdropSignature = Long.MIN_VALUE

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        liveBackdropStableOffsetX = Int.MIN_VALUE

        liveBackdropStableOffsetY = Int.MIN_VALUE

        outerGlassEffect = null

        opticalSurfaceOuterGlassEffect = null

        tabsBackdropEffect = null

        disableMiuiBackgroundBlur()

        super.onDetachedFromWindow()

    }



    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {

        super.onWindowFocusChanged(hasWindowFocus)
        appNavigationState?.onHostWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {

            context.findActivity()?.let { activity ->

                if (appNavigationState?.forceNavigationBarImmersed(this) == false) {

                    GlassInstaller.setNavigationBarImmersion(activity, false)

                } else if (targetAdapter?.navigationImmersionOverride != null) {

                    GlassInstaller.updateAdapterNavigationBarForPage(activity)

                } else {

                    GlassInstaller.ensureNavigationBarImmersion(activity)

                }

            }

        }

        if (!hasWindowFocus && targetAdapter?.hasPerFrameScene == true) {

            // Window lost focus (e.g. user swiped up to home). The Activity

            // is only stopped, not destroyed, so onDetachedFromWindow won't

            // fire. Reset the fold bar translation now to prevent the View

            // from being recycled with a stale translationY when the user

            // returns �?that residue is what creates the empty chat block.

            appNavigationState?.onHostFocusLost()

        }

        if (targetAdapter?.liveBackdropFramePump != true) return

        if (hasWindowFocus) {

            postInvalidateOnAnimation()

        }

    }



    /**

     * Whitelist only adapters that have a real main/secondary-page visibility

     * transition. Even for these packages the animation is state-driven: a

     * page whose navigation bar stays visible never enters this path.

     */

    private fun usesSceneVisibilityAnimation(): Boolean =

        targetAdapter?.sceneVisibilityAnimation == true ||
            context.packageName in SCENE_VISIBILITY_ANIMATION_PACKAGES



    /**

     * Scene-driven bar transition shared by the verified adapters. This is

     * called only after the existing visibility decision changes; it never

     * invents a hide state for pages whose bar is meant to remain visible.

     */

internal fun updateAnimatedVisibility(shouldShow: Boolean) {

        if (!barVisibilityInitialized) {

            barVisibilityInitialized = true

            barVisibilityTarget = shouldShow

            barVisibilityAnimating = false

            barVisibilityAnimator?.cancel()

            barVisibilityAnimator = null

            animate().cancel()

            alpha = if (shouldShow) 1f else 0f

            scaleX = if (shouldShow) 1f else OPTICAL_BAR_HIDDEN_SCALE_X

            scaleY = if (shouldShow) 1f else OPTICAL_BAR_HIDDEN_SCALE_Y

            translationY = if (shouldShow) 0f else visibilityHiddenTranslationY()

            visibility = if (shouldShow) View.VISIBLE else View.GONE

            return

        }

        if (barVisibilityTarget == shouldShow) return



        barVisibilityTarget = shouldShow

        barVisibilityAnimating = true

        val generation = ++barVisibilityGeneration

        releaseAncestorClipping()

        barVisibilityAnimator?.cancel()

        barVisibilityAnimator = null

        animate().cancel()

        if (context.packageName == QQ_PACKAGE) {

            // QQ's drawer/page layer may be added after the host. Keep the

            // transition itself above every sibling without changing the

            // steady-state bar geometry or its visibility decision.

            bringToFront()

            // Match WeChat's proven composition path: sibling draw order is

            // enough here. A separate high translationZ creates a second

            // RenderNode shadow which trails the host transform by a frame.

            translationZ = 0f

        }

        // SurfaceView buffers do not participate reliably in View-property

        // transforms.  While the bar is entering/leaving, render the same live

        // host glass used by the ordinary tabs and keep the native blur sibling

        // underneath it.  The promotion Surface pipeline resumes only after

        // the transform has completely settled.

        if (isOpticalSurfaceSelected()) {

            updateOpticalSurfaceSurfaceRenderer(false)

            updateOpticalSurfaceNativeBlur(true)

            invalidate()

        }

        pivotX = width * 0.5f

        pivotY = height.toFloat()

        if (shouldShow) {

            if (visibility != View.VISIBLE) visibility = View.VISIBLE

            // Restore touch on the native bar so tab buttons work again

            setNativeBarTouchable(navigationSource, true)

            // When appearing from a completed GONE state, these are the exact

            // terminal values of the hide animation, making this its reverse.

            if (alpha <= 0.001f) {

                scaleX = OPTICAL_BAR_HIDDEN_SCALE_X

                scaleY = OPTICAL_BAR_HIDDEN_SCALE_Y

                translationY = visibilityHiddenTranslationY()

            }

            if (targetAdapter?.refreshOpticsOnBarShow == true) {

                scheduleOpticalPipelineRefresh(longArrayOf(0L, 16L, 48L, 96L), "weibo-show")

                scheduleNavigationSnapshotRefresh(longArrayOf(0L, 48L, 120L))

            }

            animateVisibilityFrameDriven(

                targetAlpha = 1f,

                targetScaleX = OPTICAL_BAR_SHOW_OVERSHOOT_X,

                targetScaleY = OPTICAL_BAR_SHOW_OVERSHOOT_Y,

                targetTranslationY = -OPTICAL_BAR_SHOW_LIFT_DP * density,

                duration = OPTICAL_BAR_SHOW_EXPAND_MS,

                interpolator = OPTICAL_BAR_SHOW_INTERPOLATOR,

                generation = generation,

            ) {

                if (generation == barVisibilityGeneration && barVisibilityTarget) {

                    animateVisibilityFrameDriven(

                        targetAlpha = 1f,

                        targetScaleX = 1f,

                        targetScaleY = 1f,

                        targetTranslationY = 0f,

                        duration = OPTICAL_BAR_SHOW_SETTLE_MS,

                        interpolator = OPTICAL_BAR_SETTLE_INTERPOLATOR,

                        generation = generation,

                    ) {

                        if (generation == barVisibilityGeneration && barVisibilityTarget) {

                            alpha = 1f

                            scaleX = 1f

                            scaleY = 1f

                            translationY = 0f

                            barVisibilityAnimating = false

                            restoreQqSteadyStateZ()

                            syncOpticalVisibilityCompanions()

                            postInvalidateOnAnimation()

                        }

                    }

                }

            }

        } else {

            if (visibility != View.VISIBLE) visibility = View.VISIBLE

            // Disable touch on the native bar immediately so the glass

            // bar's touch proxy stops intercepting content underneath

            setNativeBarTouchable(navigationSource, false)

            animateVisibilityFrameDriven(

                targetAlpha = 0f,

                targetScaleX = OPTICAL_BAR_HIDDEN_SCALE_X,

                targetScaleY = OPTICAL_BAR_HIDDEN_SCALE_Y,

                targetTranslationY = visibilityHiddenTranslationY(),

                duration = OPTICAL_BAR_VISIBILITY_DURATION_MS,

                interpolator = OPTICAL_BAR_HIDE_INTERPOLATOR,

                generation = generation,

            ) {

                if (generation == barVisibilityGeneration && !barVisibilityTarget) {

                    barVisibilityAnimating = false

                    visibility = View.INVISIBLE

                    // Invalidate the backdrop so the next show animates

                    // from a fresh live scene instead of the frozen frame

                    // captured at the end of the hide animation.

                    liveBackdropSignature = Long.MIN_VALUE

                    pendingBackdropSignature = Long.MIN_VALUE

                    pendingBackdropStableFrames = 0

                    restoreQqSteadyStateZ()

                    syncOpticalVisibilityCompanions()

                }

            }

        }

    }



    /**

     * Drive transforms from the UI choreographer instead of a render-thread

     * ViewPropertyAnimator. Every visual frame also advances the live backdrop

     * capture, so the glass cannot lag behind the expanding panel.

     */

    private fun animateVisibilityFrameDriven(

        targetAlpha: Float,

        targetScaleX: Float,

        targetScaleY: Float,

        targetTranslationY: Float,

        duration: Long,

        interpolator: TimeInterpolator,

        generation: Int,

        onEnd: () -> Unit,

    ) {

        val startAlpha = alpha

        val startScaleX = scaleX

        val startScaleY = scaleY

        val startTranslationY = translationY

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {

            this.duration = duration

            this.interpolator = interpolator

            addUpdateListener { valueAnimator ->

                if (generation != barVisibilityGeneration) return@addUpdateListener

                val progress = valueAnimator.animatedValue as Float

                alpha = lerp(startAlpha, targetAlpha, progress)

                scaleX = lerp(startScaleX, targetScaleX, progress)

                scaleY = lerp(startScaleY, targetScaleY, progress)

                translationY = lerp(startTranslationY, targetTranslationY, progress)

                if (targetAdapter?.releaseOutlineClipping == true) releaseAncestorClipping()

                syncOpticalVisibilityCompanions()

                postInvalidateOnAnimation()


                if (targetAdapter?.usesNativeOpticalBlur == true) {

                    opticalSurfaceNativeBlurView?.postInvalidateOnAnimation()

                    rootView.postInvalidateOnAnimation()

                }

            }

            addListener(object : AnimatorListenerAdapter() {

                private var cancelled = false



                override fun onAnimationCancel(animation: Animator) {

                    cancelled = true

                }



                override fun onAnimationEnd(animation: Animator) {

                    if (barVisibilityAnimator === this@apply) {

                        barVisibilityAnimator = null

                    }

                    if (!cancelled && generation == barVisibilityGeneration) onEnd()

                }

            })

        }

        barVisibilityAnimator = animator

        animator.start()

    }



    private fun opticalHiddenTranslationY(): Float =

        max(height * OPTICAL_BAR_HIDDEN_TRANSLATION_FRACTION, 48f * density)



    /** Weibo's native host is a short bottom container; keep its exit inside

     * that container while the scale/alpha animation supplies the visual

     * collapse. This prevents the panel and its shadow from being sliced. */

    private fun visibilityHiddenTranslationY(): Float =

        targetAdapter?.hiddenBarTranslationPx(height, density) ?: opticalHiddenTranslationY()



    private fun restoreQqSteadyStateZ() {

        if (context.packageName != QQ_PACKAGE) return

        translationZ = 0f

    }



    /** Keep compositor-backed optical siblings on the exact host transform. */

internal fun syncOpticalVisibilityCompanions() {
        if (appNavigationState?.syncVisibilityCompanion(this, selectedIndex) == true) return

        if (targetAdapter?.usesOpticalSurfacePipeline != true &&
            targetAdapter?.retainsOpticalSurfaceWhenBarHidden != true &&

            context.packageName != MEITUAN_TAKEOUT_PACKAGE

        ) return

        val visibleDuringTransition = appNavigationState?.opticalSurfaceVisibleDuringTransition(
            visibility, alpha,
        ) ?: (barVisibilityTarget || barVisibilityAnimating)

        opticalSurfaceSurfaceView?.let { surface ->

            val overflow = opticalSurfaceSurfaceOverflowPx().toFloat()

            surface.pivotX = surface.width * 0.5f

            surface.pivotY = (surface.height - overflow).coerceAtLeast(0f)

            // JD's compositor Surface spans from above the floating bar to

            // the physical screen bottom so it can also carry the gradient

            // veil. Scaling that whole Surface during bar exit pulls its

            // bottom edge away from the window and exposes a white block for

            // one composition frame. Keep the Surface geometry fixed; the

            // bar content itself receives the same transform when rendered.

            if (targetAdapter?.opticalSurfaceFollowsBarTransform == false) {

                surface.scaleX = 1f

                surface.scaleY = 1f

                surface.translationY = 0f

            } else {

                surface.scaleX = scaleX

                surface.scaleY = scaleY

                surface.translationY = translationY

            }

            surface.alpha = alpha

            if (appNavigationState?.directOpticalBackdropActive() == true) {

                // Keep JD's transparent Surface attached while normal tabs use

                // the direct RenderNode scene. GONE destroys its BLAST queue;

                // recreating that queue on every tab switch races JD's own

                // video/RN surfaces and previously caused native crashes.

                if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE

                return@let

            }

            // JD video auto-hide must not destroy/recreate the compositor

            // Surface. Keep it attached with alpha=0 while the bar is hidden;

            // recreating it against the active player caused a flash followed

            // by a native process crash.

            val wantsOpticalSurface = wantsRootSurfaceOpticalPipeline() &&

                (visibleDuringTransition || targetAdapter?.retainsOpticalSurfaceWhenBarHidden == true)

            if (wantsOpticalSurface && !opticalSurfaceSurfacePipelineActive &&

                !opticalSurfaceSurfaceReady && !barVisibilityAnimating

            ) {

                // The compositor surface must stay VISIBLE across a full

                // layout pass to be created at all. Flipping it back to GONE

                // within the same preDraw in which the renderer requested

                // VISIBLE starves the pipeline in a deadlock: no

                // surfaceCreated -> pipeline never activates -> GONE again.

                // The unrendered TRANSLUCENT surface is fully transparent, so

                // this pending window costs nothing visually; during bar

                // enter/exit animations the classic GONE + host-rendered

                // glass path is still taken.

                if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE

            } else {

                surface.visibility = if (

                    wantsOpticalSurface && opticalSurfaceSurfacePipelineActive

                ) View.VISIBLE else View.GONE

            }

        }

        opticalSurfaceNativeBlurView?.let { blur ->

            blur.pivotX = blur.width * 0.5f

            blur.pivotY = blur.height.toFloat()

            blur.scaleX = scaleX

            blur.scaleY = scaleY

            blur.translationY = translationY

            blur.alpha = alpha

            if (!visibleDuringTransition) blur.visibility = View.INVISIBLE

        }

    }



    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {

        if (context.packageName == MI_THEME_PACKAGE ||

            context.packageName == MI_THEME_PROXY_PACKAGE

        ) {

            android.util.Log.i("ThemeGlassDiag", "onSizeChanged ${w}x${h} old=${oldw}x${oldh}")

        }

        super.onSizeChanged(w, h, oldw, oldh)

        liveBackdropActive = false

        liveBackdropSignature = Long.MIN_VALUE

        outerRect.set(0f, 0f, w.toFloat(), h.toFloat())

        clipPath.setBottomBarSquircle(outerRect, outerCornerRadiusPx(), config.cornerSmoothing)

        invalidateOutline()

        configureGlassEffects()

        if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

            post {

                if (!isAttachedToWindow) return@post

                syncBackdropRendererMode()

            }

        }

        requestQqStaticBackdropRefresh(64L)

        logGeometryIfChanged("size")

    }



    override fun onConfigurationChanged(newConfig: Configuration) {

        super.onConfigurationChanged(newConfig)

        context.findActivity()?.let(GlassInstaller::ensureNavigationBarImmersion)

        // Keep geometry, gesture state and springs alive while the theme-owned

        // content hierarchy is being replaced, then reconnect the optical

        // source after the new hierarchy has settled.

        detectedContentDark = null

        lastThemeProbe = 0L

        beginThemeTransition(resizeSourceAfterSettle = false)

        scheduleOpticalPipelineRefresh(longArrayOf(0L, 48L, 160L, 360L), "config")

        scheduleNavigationSnapshotRefresh(longArrayOf(240L, 720L, 1_500L))

        requestQqStaticBackdropRefresh(220L)

        logGeometryIfChanged("config-now", force = true)

        listOf(16L, 80L, 180L, 360L, 720L).forEach { delay ->

            postDelayed({ logGeometryIfChanged("config+$delay", force = true) }, delay)

        }

        invalidate()

    }



    private fun scheduleOpticalPipelineRefresh(delays: LongArray, reason: String) {

        val generation = ++opticalRefreshGeneration

        for (delay in delays) {

            postDelayed({

                if (!isAttachedToWindow || generation != opticalRefreshGeneration) return@postDelayed



                // Do not clear liveBackdropActive or discard its display list.

                // Marking only the signature stale lets prepareLiveBackdropScene

                // swap in the new QQ scene atomically after BACKDROP_STABLE_FRAMES.

                liveBackdropSignature = Long.MIN_VALUE

                pendingBackdropSignature = Long.MIN_VALUE

                pendingBackdropStableFrames = 0

                configureGlassEffects()

                invalidate()

                logGeometryIfChanged("$reason+$delay", force = true)

            }, delay)

        }

    }



    internal fun scheduleNavigationSnapshotRefresh(delays: LongArray) {

        // JD rebinds and config/theme transitions in place without tearing

        // the row down, and its icon/label colours can settle later than the

        // first stable layout frame. The staged signature resets force

        // re-captures so the freshly themed native pixels reach the caches.

        if (context.packageName != QQ_PACKAGE && targetAdapter?.supportsScheduledNavigationSnapshotRefresh != true) return

        val generation = ++navigationRefreshGeneration

        for (delay in delays) {

            postDelayed({

                if (!isAttachedToWindow || generation != navigationRefreshGeneration) return@postDelayed

                navigationSnapshotSignature = Long.MIN_VALUE

                pendingNavigationSignature = Long.MIN_VALUE

                pendingNavigationStableFrames = 0

                invalidate()

            }, delay)

        }

    }



    private fun requestQqStaticBackdropRefresh(delayMs: Long = 0L) {

        if (context.packageName != QQ_PACKAGE) return

        val generation = ++qqStaticBackdropRefreshGeneration

        liveBackdropSignature = Long.MIN_VALUE

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        if (delayMs <= 0L) {

            qqStaticBackdropRefreshPending = false

            postInvalidateOnAnimation()

            return

        }

        postDelayed({

            if (!isAttachedToWindow || generation != qqStaticBackdropRefreshGeneration) return@postDelayed

            qqStaticBackdropRefreshPending = false

            liveBackdropSignature = Long.MIN_VALUE

            pendingBackdropSignature = Long.MIN_VALUE

            pendingBackdropStableFrames = 0

            postInvalidateOnAnimation()

        }, delayMs)

    }



    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {

        super.onLayout(changed, left, top, right, bottom)

        if (changed) logGeometryIfChanged("layout")

    }



    override fun onDraw(canvas: Canvas) {

        // The backdrop capture uses a software bitmap canvas. Never recursively

        // draw RuntimeShader/RenderEffect content into that capture.

        if (capturing) return

        super.onDraw(canvas)

        if (opticalSurfaceSurfacePipelineActive) return

        sharedOpticalBlurPrepared = false

        val appWantsHostPanel = appNavigationState?.beforeHostDraw(
            this, navigationSource as? ViewGroup,
        ) ?: true

        val perfT0 = System.nanoTime()

        if (canvas.isHardwareAccelerated && !config.solidBarEnabled &&
            SystemClock.uptimeMillis() >= barAnimationPriorityUntil &&
            !compositorBlurBehind && appNavigationState?.maintainsCompositorSamplingWithoutSurface() != true &&

            // Pre-draw already prepared the live scene for this traversal. Its
            // child RenderNodes stay live as Android draws the content below us.
            // A second sweep advances stability counters twice per screen frame.
            !(captureListener != null && liveBackdropActive)

        ) {

            prepareLiveBackdropScene()

        }

        val perfT1 = System.nanoTime()

        if (context.packageName != QQ_PACKAGE) {

            updateDetectedContentTheme()

        }

        // MyRecentPlayActivity has no navigation row. Its tiny host exists

        // only to keep probing/skinning the native mini player; painting the

        // normal bottom panel here would either cover the player or leave an

        // empty liquid capsule. The player itself is drawn by its own glass

        // drawable in the adapter-owned mini-player maintenance path.

        if (!appWantsHostPanel) {

            glassPerfRecord(perfT1 - perfT0, 0L)

            return

        }

        drawGlassPanel(canvas, isDarkGlass())

        val perfT2 = System.nanoTime()

        glassPerfRecord(perfT1 - perfT0, perfT2 - perfT1)

        // Weibo renders feed video through a compositor-backed texture. Those

        // texture frames do not necessarily invalidate the surrounding Android

        // View tree, so the retained RenderNode glass otherwise redraws only on

        // unrelated layout/touch events and appears delayed or low-FPS. Keep a

        // display-rate frame pump only while Weibo's glass is actually visible;

        // the retained GPU scene then samples the current video texture without

        // falling back to expensive whole-page software captures.

        if (targetAdapter?.liveBackdropFramePump == true &&

            liveBackdropActive && isShown && alpha > 0.001f

        ) {

            postInvalidateOnAnimation()

        }

        // Adapter-owned live scenes may require a continuous frame pump.

        if (appNavigationState?.needsContinuousHomeFrames(selectedIndex) == true &&

            liveBackdropActive && isShown && alpha > 0.001f

        ) {

            postInvalidateOnAnimation()

        }

        // Douyin feed video frames are compositor-backed and do not invalidate

        // this overlay, so keep a display-rate pump while the live scene is up

        // while the live scene is up.

        if (targetAdapter?.continuousFramePump == true &&

            liveBackdropActive && isShown && alpha > 0.001f

        ) {

            postInvalidateOnAnimation()

        }

    }



    /** One geometry rule for every app: the bar radius follows its real height. */

    private fun outerCornerRadiusPx(): Float =

        min(width.toFloat(), height.toFloat()) * (config.cornerRadiusPercent / 100f)



    /**

     * 1.0 = chrome fully visible, 0.0 = fully hidden on adapter-managed pages;

     * returns 1.0 everywhere else. After the adapter-owned idle period without

     * a touch, the bar chrome (glass surface, slider, ambient shadow, bloom

     * stroke) fades out; the text layer

     * drawn in dispatchDraw is untouched. First draw initializes the timestamp

     * so the chrome starts visible.

     */




    private fun drawGlassPanel(canvas: Canvas, dark: Boolean) {

        val appVideoBackdrop = appNavigationState?.videoBackdrop()
        if (appVideoBackdrop != null) {

            val now = SystemClock.uptimeMillis()

            if (now - lastAppVideoDiagLog >= 1000L) {

                lastAppVideoDiagLog = now

                appNavigationState?.logDiagnostic(

                    "glass dark=$dark live=$liveBackdropActive " +

                        "bmp=${!appVideoBackdrop.bitmap.isRecycled} hw=${canvas.isHardwareAccelerated} " +

                        "eff=${outerGlassEffect != null} shared=${sharedOpticalBlurEffect != null} " +

                        "reuse=$reuseOpticalBlur blurBehind=$compositorBlurBehind",

                )

            }

        }

        val appChromeFade = appNavigationState?.hostChromeAlpha(this, selectedIndex) ?: 1f

        // NetEase home: the whole bar chrome (backdrop refraction, fill,

        // interactive highlight, bloom stroke, ambient shadow) fades together

        // with the idle timer. The text layer in dispatchDraw stays untouched.

        val appChromeLayer = if (appChromeFade < 1f) {

            canvas.saveLayerAlpha(

                0f,

                0f,

                width.toFloat(),

                height.toFloat(),

                (appChromeFade * 255).roundToInt().coerceIn(0, 255),

            )

        } else {

            -1

        }

        canvas.save()

        canvas.scale(outerPanelScale, outerPanelScale, width / 2f, height / 2f)

        drawPanelAmbientShadow(canvas, dark, appChromeFade)
        appNavigationState?.drawAdditionalAmbientShadow(canvas, dark, appChromeFade)

        canvas.save()

        canvas.clipPath(clipPath)

        // Extra clipRect ensures the backdrop RenderNode (which extends

        // beyond the view bounds for blur sampling) is hard-clipped on

        // hardware-accelerated canvases where clipPath alone is unreliable

        // for RenderNodes carrying RenderEffects.

        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // Always render the glass effect when a backdrop is available.

        // drawOuterGlass already handles hybrid mode internally with

        // opticalSurfaceOuterGlassEffect and reduced alpha.

        if (hasUsableBackdrop() && !config.solidBarEnabled) drawOuterGlass(canvas)



        // During the exit animation, skip the surface fill and bloom stroke

        // entirely so only the backdrop refraction fades out. The fill and

        // bloom draw full-rect shapes whose shader/fill can produce visible

        // edge artifacts that clipPath doesn't fully suppress on hardware

        // canvases �?they show up as a gray patch during the scale-down.

        val isExiting = barVisibilityAnimating && !barVisibilityTarget

        if (!isExiting || alpha > 0.7f) {

            val configuredFillColor = surfaceContainerColor(dark)

            val fillColor = if (config.solidBarEnabled) {

                Color.argb(

                    255,

                    Color.red(configuredFillColor),

                    Color.green(configuredFillColor),

                    Color.blue(configuredFillColor),

                )

            } else {

                configuredFillColor

            }

            val effectiveFillAlpha = if (isExiting) {

                val fadeProgress = ((0.7f - alpha) / 0.7f).coerceIn(0f, 1f)

                (Color.alpha(fillColor) * (1f - fadeProgress)).toInt().coerceIn(0, 255)

            } else {

                Color.alpha(fillColor)

            }

            fillPaint.color = Color.argb(

                effectiveFillAlpha,

                Color.red(fillColor),

                Color.green(fillColor),

                Color.blue(fillColor),

            )

            canvas.drawPath(clipPath, fillPaint)

            if (!config.liquidGlassEnabled && config.outlineEnabled) {

                val strokeWidth = 1f * density

                val inset = strokeWidth * 0.5f

                plainBlurOutlinePaint.strokeWidth = strokeWidth

                plainBlurOutlinePaint.color = if (dark) {

                    Color.argb((0.32f * 255f).toInt(), 255, 255, 255)

                } else {

                    Color.argb((0.24f * 255f).toInt(), 0, 0, 0)

                }

                lightingRect.set(outerRect)

                lightingRect.inset(inset, inset)

                outlinePath.setBottomBarSquircle(

                    lightingRect,

                    outerCornerRadiusPx() - inset,

                    config.cornerSmoothing,

                )

                canvas.drawPath(outlinePath, plainBlurOutlinePaint)

            }

            if (config.liquidGlassEnabled) {

                drawInteractiveHighlight(canvas)

                if (config.outlineEnabled) {

                    drawBloomStroke(

                        canvas,

                        outerRect,

                        outerCornerRadiusPx(),

                        ((if (usesOpticalSurfaceHybridBackdrop()) {

                            0.10f

                        } else if (dark) {

                            0.58f

                        } else {

                            0.75f

                        }) * if (dark) config.darkBarHighlightStrength else 1f)

                            .coerceAtMost(1f),

                        -45f,

                        outerBloomShader,

                    )

                }

            }

        }

        canvas.restore()

        canvas.restore()

        if (appChromeLayer >= 0) canvas.restoreToCount(appChromeLayer)

    }



    /**

     * Offset-free ambient shadow ringing the panel on all sides. Drawn before

     * the panel fill with the panel path clipped out, so only the outer half

     * of the blur shows and the top edge stays separable from content without

     * dimming the glass fill. Matches the manager UI bar's dropShadow params.

     */

    private fun drawPanelAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float = 1f) {

        if (outerRect.isEmpty || !canvas.isHardwareAccelerated) return

        panelShadowPaint.color = Color.TRANSPARENT

        // WeChat renders a much tighter ambient ring; every other app keeps the

        // standard radius.

        val blurDp = targetAdapter?.panelShadowBlurDp ?: PANEL_SHADOW_BLUR_DP

        panelShadowPaint.setShadowLayer(

            blurDp * density,

            0f,

            0f,

            Color.argb(

                ((if (dark) PANEL_SHADOW_ALPHA else PANEL_LIGHT_SHADOW_ALPHA) *

                    255f * alphaScale.coerceIn(0f, 1f)).toInt(),

                0,

                0,

                0,

            ),

        )

        canvas.save()

        canvas.clipOutPath(clipPath)

        canvas.drawPath(clipPath, panelShadowPaint)

        canvas.restore()

    }



    /**

     * Ambient shadow ringing the mini-player capsule, drawn on the host surface

     * (whose bounds are larger than the capsule) so the outer halo isn't clipped

     * away the way it is inside the player's own bounded canvas. Mirrors

     * drawPanelAmbientShadow so the play bar reads clearly on light feeds.

     */




    private fun glassPerfRecord(prepareNs: Long, panelNs: Long) {

        perfFrames++

        perfPrepareNs += prepareNs

        if (prepareNs > perfPrepareMaxNs) perfPrepareMaxNs = prepareNs

        perfPanelNs += panelNs

        if (panelNs > perfPanelMaxNs) perfPanelMaxNs = panelNs

        glassPerfLogTick()

    }



    private fun glassPerfLogTick() {

        val now = SystemClock.uptimeMillis()

        if (now - perfLastLogUptime < 1000L) return

        perfLastLogUptime = now

        val frames = perfFrames

        if (frames > 0) {

            android.util.Log.i(

                "GlassPerf",

                "pkg=${context.packageName} fps=$frames " +

                    "prep=${perfPrepareNs / frames / 1000}us max=${perfPrepareMaxNs / 1000}us " +

                    "panel=${perfPanelNs / frames / 1000}us max=${perfPanelMaxNs / 1000}us " +

                    "disp=${perfDispatchNs / frames / 1000}us max=${perfDispatchMaxNs / 1000}us " +

                    "sweeps=$perfSceneSweeps sibs=$perfSceneSiblingVisits " +

                    "sceneRec=$perfSceneReRecords eff=$perfEffectRebuilds nodeRec=$perfNodeRecords " +

                    "surfR=$perfSurfaceRenders live=$liveBackdropActive",

            )

        }

        perfFrames = 0

        perfPrepareNs = 0L

        perfPrepareMaxNs = 0L

        perfPanelNs = 0L

        perfPanelMaxNs = 0L

        perfDispatchNs = 0L

        perfDispatchMaxNs = 0L

        perfSceneSweeps = 0

        perfSceneSiblingVisits = 0

        perfSceneReRecords = 0

        perfEffectRebuilds = 0

        perfNodeRecords = 0

        perfSurfaceRenders = 0

    }



    private fun glassDispatchPerfRecord(dispatchNs: Long) {

        perfDispatchNs += dispatchNs

        if (dispatchNs > perfDispatchMaxNs) perfDispatchMaxNs = dispatchNs

    }



    override fun draw(canvas: Canvas) {
        // Exclude the glass from software background capture without mutating
        // alpha: property changes invalidate the retained screen display list.
        if (capturing) return
        super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {

        if (capturing) return

        (if (nativeSelectionReliable) resolveSelectedFromViewState() else null)?.let { resolved ->

            if (appNavigationState?.acceptNativeNavigationSelection(
                    resolved, SystemClock.uptimeMillis(),
                ) == false
            ) return@let

            val effectiveResolved = if (isNavigationEntryDisabled(resolved)) {

                nearestSelectableIndex(resolved)

            } else {

                resolved

            }

            if (!selectionInitialized) {

                selectionInitialized = true

                selectedIndex = effectiveResolved

                dragTarget = visualPositionForNavigationIndex(effectiveResolved)

                positionSpring.snapTo(dragTarget)

            } else if (effectiveResolved != selectedIndex) {

                selectedIndex = effectiveResolved

                animateToIndex(
                    effectiveResolved,
                    includePress = !touching,
                    prioritizePageTransition = false,
                )

                requestQqStaticBackdropRefresh(120L)

            }

        }

        if (opticalSurfaceSurfacePipelineActive) {

            val holdForOpticalFrame = appNavigationState?.holdDirectBackdropUntilOpticalFrame(
                liveBackdropActive, liveBackdropNode.hasDisplayList(),
            ) == true

            if (holdForOpticalFrame) {

                // Keep the previous direct GPU scene visible until the first

                // compositor-backed frame has actually been posted.

            } else {

            // JD queues animation state to the same worker as PixelCopy.

            // Surface buffer waits must not block the application's UI thread.

            val animating = isBarVisualStateAnimating()
            when (appNavigationState?.shouldQueueOpticalRenderFromUi(animating)) {
                true -> queueOpticalSurfaceRender()
                false -> Unit
                null -> renderOpticalSurfaceSurface()
            }

            if (targetAdapter?.needsContinuousOpticalSurfaceFrames(selectedIndex) == true) {
                postInvalidateOnAnimation()
            }

            return

            }

        }

        val dispatchPerfT0 = System.nanoTime()

        super.dispatchDraw(canvas)

        // The active optical surface uses a compositor-blurred base plus an

        // edge-only snapshot lens. Paint the lens first and the extracted

        // navigation artwork once on top; otherwise the moving translucent

        // pill washes/repeats the icon that was already painted underneath.

        // NetEase sub-pages without a native tab bar draw no navigation row;

        // the host there only drives the mini-player glass probe.

        if (appNavigationState?.drawsNavigationContent() != false) {

            if (usesOpticalSurfaceHybridBackdrop() || !config.liquidGlassEnabled ||
                targetAdapter?.foregroundNavigationOverSelection == true) {

                // Douyin's selected icon must remain above the refracting lens.
                // Swiping the feed can otherwise cover it with the slider layer.
                drawSelectionWithFade(canvas, isDarkGlass())

                val customForeground = appNavigationState?.drawForeground(
                    canvas,
                    adapterNavigationFrame(1f),
                    foregroundNavigationClipPath(),
                ) { drawNavigationSource(canvas) } == true
                if (!customForeground) {
                    drawNavigationSource(canvas)
                }

            } else {

                drawNavigationSource(canvas)

                drawSelectionWithFade(canvas, isDarkGlass())

            }

        }

        if (context.packageName == QQ_PACKAGE) drawQqBadgeOverlay(canvas)

        glassDispatchPerfRecord(System.nanoTime() - dispatchPerfT0)

    }



    fun invalidateProjectionCache() {

        navigationSnapshotSignature = Long.MIN_VALUE

        liveBackdropSignature = Long.MIN_VALUE

        pendingNavigationSignature = Long.MIN_VALUE

        postInvalidateOnAnimation()

    }



    private fun suppressThemePillNativeIndicator() {

        val pill = navigationSource as? ViewGroup ?: return

        if (pill.width <= 0) return

        var suppressed = false

        for (i in 0 until pill.childCount) {

            val child = pill.getChildAt(i)

            if (child.javaClass.simpleName == "OverlayView" &&

                child.width >= pill.width / 5f &&

                child.visibility != View.INVISIBLE

            ) {

                child.visibility = View.INVISIBLE

                suppressed = true

            }

        }

        if (suppressed) {

            // The stale projection snapshot was captured while the native

            // indicator overlay was still visible; force a re-capture so the

            // old slider never bleeds through the liquid glass again.

            invalidateProjectionCache()

        }

    }



    private fun dumpNavigationTree(root: ViewGroup) {

        val sb = StringBuilder("NAVTREE ${root.javaClass.simpleName}\n")

        fun walk(v: View, depth: Int) {

            repeat(depth) { sb.append("  ") }

            sb.append(v.javaClass.simpleName)

                .append(" id=").append(viewResourceEntryName(v).orEmpty())

                .append(" ").append(v.width).append("x").append(v.height)

                .append(" click=").append(v.isClickable)

                .append(" sel=").append(v.isSelected)

                .append(" act=").append(v.isActivated)

                .append(" vis=").append(v.visibility)

                .append(" alpha=").append(v.alpha)

                .append('\n')

            if (v is ViewGroup) {

                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)

            }

        }

        walk(root, 0)

        android.util.Log.i("ThemeGlassDiag", sb.toString())

    }



    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        if (context.packageName == MI_THEME_PACKAGE ||

            context.packageName == MI_THEME_PROXY_PACKAGE

        ) {

            if (event.actionMasked == MotionEvent.ACTION_DOWN) {

                android.util.Log.i(

                    "ThemeGlassDiag",

                    "dispatch DOWN x=${event.x} y=${event.y} host=${width}x${height} " +

                        "slots=$slotCount vis=$visibility alpha=$alpha " +

                        "attached=$isAttachedToWindow parent=${parent?.javaClass?.simpleName} " +

                        "index=${parent?.let { (it as? ViewGroup)?.indexOfChild(this) }} " +

                        "src=${navigationSource?.javaClass?.simpleName} " +

                        "src=${navigationSource?.width}x${navigationSource?.height} " +

                        "sel=$selectionRect gesture=$indicatorGestureActive " +

                        "slider=$sliderEnabled",

                )

            }

        }

        if (slotCount <= 0 || width <= 0) return super.dispatchTouchEvent(event)

        if (appNavigationState?.allowsHostTouch() == false) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            appNavigationState?.onHostTouchDown(this, selectedIndex)
        }

        val slotWidth = tabWidthPx()

        val visualSlots = visualSlotCount()

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                if (appNavigationState?.beginOverlayTouch(this, event) == true) {

                    return true

                }

                appNavigationState?.onBarGesture(0, this)

                // User interacted with the bar on Weibo message tab: cancel

                // auto-hide so the bar stays visible while they use it

                appNavigationState?.onHostTouchDown(this, selectedIndex)

                // Same contract for XHS's message tab: touching the bar during

                // its back-peek window keeps it on screen.

                if (targetAdapter?.resetScrollStopHideOnOtherTabs() == true) {

                    adapterMsgPeekUntil = 0L

                    adapterMsgHideTime = 0L

                }

                indicatorGestureActive = sliderEnabled && selectionRect.contains(event.x, event.y)
                touching = indicatorGestureActive

                lastTouchX = event.x

                gestureStartX = event.x

                gestureStartY = event.y

                touchX = event.x

                touchY = event.y

                dragTarget = indicatorPosition

                pendingTapMoved = false

                pendingTapIndex = if (indicatorGestureActive) {

                    -1

                } else {

                    navigationIndexForVisualIndex(

                        ((event.x - 4f * density) / slotWidth).toInt()

                            .coerceIn(0, visualSlots - 1),

                    ).takeUnless(::isNavigationEntryDisabled) ?: -1

                }

                if (indicatorGestureActive) {

                    releaseGeneration++

                    press()

                }

            }



            MotionEvent.ACTION_MOVE -> {

                if (appNavigationState?.hasActiveOverlayTouch() == true) {
                    appNavigationState?.forwardOverlayTouch(this, event)

                    return true

                }

                appNavigationState?.onBarGesture(1, this)

                if (!indicatorGestureActive) {

                    if (abs(event.x - gestureStartX) > touchSlop ||

                        abs(event.y - gestureStartY) > touchSlop

                    ) {

                        pendingTapMoved = true

                    }

                } else {

                    touchX = event.x

                    touchY = event.y

                    val delta = event.x - lastTouchX

                    if (event.x in 0f..width.toFloat() && lastTouchX in 0f..width.toFloat()) {

                        dragTarget = (dragTarget + delta / slotWidth)

                            .coerceIn(0f, (visualSlots - 1).toFloat())

                        positionSpring.animateTo(dragTarget)

                        panelOffsetSpring.snapTo(panelDragOffset + delta)

                    }

                    lastTouchX = event.x

                }

            }



            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                if (appNavigationState?.hasActiveOverlayTouch() == true) {
                    appNavigationState?.forwardOverlayTouch(this, event)
                    appNavigationState?.clearOverlayTouch()

                    return true

                }

                appNavigationState?.onBarGesture(2, this)

                if (indicatorGestureActive) {

                    touching = false

                    val generation = ++releaseGeneration

                    val rawTarget = navigationIndexForVisualIndex(

                        dragTarget.roundToInt().coerceIn(0, visualSlots - 1),

                    )

                    val actionTarget = event.actionMasked == MotionEvent.ACTION_UP &&

                        rawTarget in nonSelectableIndices && !isNavigationEntryDisabled(rawTarget)

                    val target = if (actionTarget) {

                        selectedIndex

                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {

                        nearestSelectableIndex(rawTarget)

                    } else {

                        selectedIndex

                    }

                    selectedIndex = target

                    dragTarget = visualPositionForNavigationIndex(target)

                    positionSpring.animateTo(dragTarget)

                    velocitySpring.animateTo(0f)

                    panelOffsetSpring.animateTo(0f)

                    if (event.actionMasked == MotionEvent.ACTION_UP) {

                        performNavigationSelection(if (actionTarget) rawTarget else target)

                    }

                    releaseWhenSettled(generation)

                } else if (event.actionMasked == MotionEvent.ACTION_UP && !pendingTapMoved) {

                    val upIndex = navigationIndexForVisualIndex(

                        ((event.x - 4f * density) / slotWidth).toInt()

                            .coerceIn(0, visualSlots - 1),

                    )

                    val tapped = pendingTapIndex.takeIf { it == upIndex } ?: -1

                    if (tapped in nonSelectableIndices && !isNavigationEntryDisabled(tapped)) {

                        performNavigationSelection(tapped)

                    } else if (tapped >= 0) {

                        // The source QQTabLayout is transparent and scaled into this

                        // host. Re-dispatching raw MotionEvents to that detached hit

                        // geometry occasionally loses a quick tap. Invoke the exact

                        // original TabView click instead so QQ's own controller still

                        // performs the page change, while this host owns the gesture.

                        selectedIndex = tapped

                        if (sliderEnabled) animateToIndex(tapped, includePress = true)

                        performNavigationSelection(tapped)

                    }

                }

                indicatorGestureActive = false

                pendingTapIndex = -1

                pendingTapMoved = false

            }

        }

        // This overlay is the sole physical hit target. Navigation is routed via

        // the original tab views above, never by synthesising a second gesture.

        return true

    }



    /**

     * Starts forwarding a mini player touch. The player capsule sits above the

     * navigation capsule inside this extended host; touches there must reach

     * the native (hidden) player controls instead of becoming nav taps. The

     * event is re-dispatched to the original child view so its own click

     * listeners (play/pause, playlist, open player page) still run.

     */




    private fun press() {

        if (!config.liquidGlassEnabled) {

            pressSpring.snapTo(0f)

            scaleXSpring.snapTo(1f)

            scaleYSpring.snapTo(1f)

            interactiveSpring.snapTo(0f)

            return

        }

        pressSpring.animateTo(1f)

        scaleXSpring.animateTo(PRESSED_SCALE)

        scaleYSpring.animateTo(PRESSED_SCALE)

        interactiveSpring.animateTo(1f)

    }



    private fun release() {

        pressSpring.animateTo(0f)

        scaleXSpring.animateTo(1f)

        scaleYSpring.animateTo(1f)

        interactiveSpring.animateTo(0f)

        velocitySpring.animateTo(0f)

    }



    private fun releaseWhenSettled(generation: Int) {

        if (generation != releaseGeneration || touching) return

        if (abs(indicatorPosition - dragTarget) <= visualSlotCount().coerceAtLeast(1) * 0.025f) {

            release()

        } else {

            postOnAnimationDelayed({ releaseWhenSettled(generation) }, 16L)

        }

    }



    private fun animateToIndex(
        index: Int,
        includePress: Boolean,
        prioritizePageTransition: Boolean = true,
    ) {

        if (prioritizePageTransition) {
            barAnimationPriorityUntil = SystemClock.uptimeMillis() + 240L
        }

        val target = visualPositionForNavigationIndex(index)

        dragTarget = target

        if (includePress) press()

        positionSpring.animateTo(target)

        velocitySpring.animateTo(0f)

        if (includePress) releaseWhenSettled(++releaseGeneration)

    }



    private fun updateOuterScale() {

        if (!config.liquidGlassEnabled) {

            outerPanelScale = 1f

            return

        }

        val grow = if (width > 0) 16f * density / width else 0f

        outerPanelScale = 1f + grow * pressProgress

        invalidate()

    }



    private fun updatePanelTranslation() {

        if (width <= 0) {

            translationX = 0f

            return

        }

        val fraction = (panelDragOffset / width).coerceIn(-1f, 1f)

        val eased = easeOut.getInterpolation(abs(fraction))

        translationX = 4f * density * if (fraction < 0f) -eased else eased

    }



    private data class SourceTransform(val scale: Float, val dx: Float, val dy: Float)



    private fun sourceTransform(source: View): SourceTransform {

        return sourceTransform(source.width, source.height)

    }



    private fun sourceTransform(sourceWidthPx: Int, sourceHeightPx: Int): SourceTransform {

        val sourceWidth = sourceWidthPx.coerceAtLeast(1).toFloat()

        val sourceHeight = sourceHeightPx.coerceAtLeast(1).toFloat()

        val horizontalPadding = 4f * density

        val contentWidth = (width - horizontalPadding * 2f).coerceAtLeast(1f)

        val scale = min(contentWidth / sourceWidth, height / sourceHeight).coerceAtLeast(0.0001f)

        return SourceTransform(

            scale = scale,

            dx = horizontalPadding + (contentWidth - sourceWidth * scale) / 2f,

            dy = (height - sourceHeight * scale) / 2f,

        )

    }



    /** Maps one native slot into one compact visual slot without squeezing its siblings. */

    private fun sourceTransformForSlot(

        sourceWidthPx: Int,

        sourceHeightPx: Int,

        nativeIndex: Int,

        visualIndex: Int,

    ): SourceTransform {

        val sourceWidth = sourceWidthPx.coerceAtLeast(1).toFloat()

        val sourceHeight = sourceHeightPx.coerceAtLeast(1).toFloat()

        val nativeSlotWidth = sourceWidth / slotCount.coerceAtLeast(1)

        val visualSlotWidth = tabWidthPx()

        val scale = min(visualSlotWidth / nativeSlotWidth, height / sourceHeight)

            .coerceAtLeast(0.0001f)

        val sourceCenterX = (nativeIndex + 0.5f) * nativeSlotWidth

        val targetCenterX = 4f * density + (visualIndex + 0.5f) * visualSlotWidth

        return SourceTransform(

            scale = scale,

            dx = targetCenterX - sourceCenterX * scale,

            dy = (height - sourceHeight * scale) / 2f,

        )

    }

    /** A small generic coordinate bridge for adapter-owned tab adornments. */
    internal fun adapterTabTransform(index: Int): FloatArray? {
        val source = navigationSource ?: return null
        if (isNavigationEntryDisabled(index)) return null
        val visualIndex = enabledNavigationIndices().indexOf(index)
        if (visualIndex < 0) return null
        val transform = sourceTransformForSlot(source.width, source.height, index, visualIndex)
        return floatArrayOf(transform.scale, transform.dx, transform.dy, sourceTransform(source).scale, density)
    }

    internal val adapterNavigationSource: ViewGroup? get() = navigationSource

    internal fun adapterScheduleBackdropRefresh(delayMs: Long) {
        postDelayed({
            if (isAttachedToWindow && appNavigationState?.deferThemeDetection(SystemClock.uptimeMillis()) != true) {
                liveBackdropSignature = Long.MIN_VALUE
                navigationSnapshotSignature = Long.MIN_VALUE
                pendingNavigationSignature = Long.MIN_VALUE
                pendingNavigationStableFrames = 0
                postInvalidateOnAnimation()
            }
        }, delayMs)
    }

    internal fun adapterOnScrollStopHidePageEntered(delayMs: Long) {
        msgScrolledToStop = false
        removeCallbacks(msgScrollHideRunnable)
        ensureMsgScrollStopHideListener()
        postDelayed(msgScrollHideRunnable, delayMs)
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        alpha = 1f
    }

    internal fun adapterOnScrollStopHidePageExited() {
        postInvalidateOnAnimation()
    }

    internal fun adapterCancelScrollStopHide() {
        removeCallbacks(msgScrollHideRunnable)
        msgScrolledToStop = false
    }



    private fun tabWidthPx(): Float =

        ((width - 8f * density).coerceAtLeast(1f) / visualSlotCount().coerceAtLeast(1))



    private fun logGeometryIfChanged(reason: String, force: Boolean = false) {

        if (context.packageName != QQ_PACKAGE) return

        val hostLocation = IntArray(2).also { runCatching { getLocationInWindow(it) } }

        val source = navigationSource

        val sourceLocation = IntArray(2).also { location ->

            source?.let { runCatching { it.getLocationInWindow(location) } }

        }

        val params = layoutParams as? FrameLayout.LayoutParams

        val transform = source?.takeIf { it.width > 0 && it.height > 0 }?.let(::sourceTransform)

        val snapshot = navigationSnapshot?.takeUnless(Bitmap::isRecycled)

        val uiNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val signature = buildString {

            append("reason=").append(reason)

            append(" ui=").append(uiNight)

            append(" host=").append(hostLocation[0]).append(',').append(hostLocation[1])

                .append(' ').append(width).append('x').append(height)

            append(" frame=").append(left).append(',').append(top).append(',').append(right).append(',').append(bottom)

            append(" lp=").append(params?.width).append('x').append(params?.height)

                .append(" bm=").append(params?.bottomMargin)

            append(" source=").append(source?.javaClass?.name)

                .append('@').append(sourceLocation[0]).append(',').append(sourceLocation[1])

                .append(' ').append(source?.width).append('x').append(source?.height)

                .append(" attached=").append(source?.isAttachedToWindow)

            append(" snapshot=").append(snapshot?.width).append('x').append(snapshot?.height)

            append(" navNorm=").append(lastNavigationNormalization)

            append(" transform=").append(transform?.scale).append(',').append(transform?.dx).append(',').append(transform?.dy)

            append(" indicator=").append(indicatorPosition).append('/').append(selectedIndex)

            append(" live=").append(liveBackdropActive).append('/').append(liveBackdropNode.hasDisplayList())

            append(" effects=").append(outerGlassEffect != null).append('/').append(tabsBackdropEffect != null)

            append(" shaders=").append(outerLensShader != null).append('/')

                .append(combinedOuterLensShader != null).append('/').append(indicatorLensShader != null)

        }

        val stableSignature = signature.substringAfter(' ')

        if (!force && stableSignature == lastGeometryLogSignature) return

        lastGeometryLogSignature = stableSignature

        Log.i(DIAGNOSTIC_TAG, signature)

        XposedBridge.log("[$DIAGNOSTIC_TAG] $signature")

    }



    private fun maybeReloadConfig() {

        val now = SystemClock.uptimeMillis()

        if (configPollPending || now - lastConfigPoll < CONFIG_POLL_INTERVAL_MS) return

        lastConfigPoll = now

        // WeChat is being edited independently; preserve its existing refresh path.

        if (targetAdapter?.synchronousConfigPolling == true) {

            val updated = packageConfig(HookConfigReader.readFresh(context, context.packageName))

            if (now - lastConfigDiagLog >= 3_000L) {

                lastConfigDiagLog = now

                Log.i(

                    DIAGNOSTIC_TAG,

                    "cfgPoll pkg=${context.packageName} read.blur=${updated.blurRadius} " +

                        "live.blur=${config.blurRadius} eq=${updated == config}",

                )

            }

            if (updated != config) applyLiveConfig(updated)

            return

        }

        configPollPending = true

        val generation = configPollGeneration

        val startingConfig = config

        val hostRef = WeakReference(this)

        HookConfigReader.readFreshAsync(context, context.packageName) { result ->

            val host = hostRef.get()

            if (host != null && host.configPollGeneration == generation) {

                host.configPollPending = false

                // Do not overwrite a newer pushed configuration or a replacement host.

                if (result != null && host.isAttachedToWindow && host.config === startingConfig) {

                    val updated = host.packageConfig(result)

                    if (updated != host.config) host.applyLiveConfig(updated)

                }

            }

        }

    }



    private fun beginThemeTransition(resizeSourceAfterSettle: Boolean) {

        val transitionStart = SystemClock.uptimeMillis()

        themeTransitionUntil = transitionStart + THEME_TRANSITION_GRACE_MS

        navigationSnapshotFrozenUntil = transitionStart + SOURCE_RESIZE_DELAY_MS

        pendingNavigationSignature = Long.MIN_VALUE

        pendingNavigationStableFrames = 0

        val generation = ++sourceSizingGeneration

        if (!resizeSourceAfterSettle || context.packageName != QQ_PACKAGE) return



        // QQ applies night resources, rebuilds custom icon Views, and lays the

        // row out in separate passes. Reapplying our user scale in the middle of

        // those passes is what produced the permanently compressed/overlapping

        // row. Wait for three identical layout frames and then size it once.

        var previousSignature = Long.MIN_VALUE

        var stableFrames = 0

        fun awaitStableSource() {

            if (generation != sourceSizingGeneration || !isAttachedToWindow) return

            val signature = navigationLayoutSignature(navigationSource)

            if (signature != Long.MIN_VALUE && signature == previousSignature) {

                stableFrames++

            } else {

                previousSignature = signature

                stableFrames = if (signature == Long.MIN_VALUE) 0 else 1

            }

            if (stableFrames >= SOURCE_STABLE_FRAMES &&

                SystemClock.uptimeMillis() - transitionStart >= SOURCE_RESIZE_DELAY_MS

            ) {

                applyLiveNavigationSizing()

                // setIconSize/textSize may legitimately request one final QQ

                // layout. The retained host/backdrop stays visible throughout.

                postOnAnimation {

                    pendingBackdropSignature = Long.MIN_VALUE

                    pendingBackdropStableFrames = 0

                    navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + SOURCE_POST_RESIZE_SETTLE_MS

                    pendingNavigationSignature = Long.MIN_VALUE

                    pendingNavigationStableFrames = 0

                    invalidate()

                }

            } else {

                postOnAnimation { awaitStableSource() }

            }

        }

        postOnAnimation { awaitStableSource() }

    }



    private fun navigationLayoutSignature(source: ViewGroup?): Long {

        source ?: return Long.MIN_VALUE

        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return Long.MIN_VALUE

        var signature = 17L

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += source to 0

        var laidOutViews = 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            signature = signature * 31L + System.identityHashCode(view)

            signature = signature * 31L + view.left

            signature = signature * 31L + view.top

            signature = signature * 31L + view.width

            signature = signature * 31L + view.height

            signature = signature * 31L + view.visibility

            // Fade-in animations (JD page switches) wash out or blank the

            // captured row when alpha is ignored: the mid-fade capture stays

            // frozen because nothing else changes once the fade completes.

            signature = signature * 31L + view.alpha.toRawBits()

            if (view.width > 0 && view.height > 0) laidOutViews++

            if (view is TextView) {

                signature = signature * 31L + view.currentTextColor

                signature = signature * 31L + view.textSize.toRawBits()

                // JD binds its tab labels asynchronously AFTER the row's

                // geometry has settled. Without the text in the signature the

                // blank capture stayed frozen and the glass bar lost its

                // labels until the next geometry change.

                signature = signature * 31L + (view.text?.hashCode() ?: 0)

            }

            // Mi Health re-binds its tab icons (ImageView drawables) after the

            // row geometry settles on return from device sub-pages. Without

            // the drawable identity in the signature a capture taken mid-rebind

            // (icons still empty) stayed frozen and the icons disappeared.

            if (view is ImageView && targetAdapter?.trackSourceDrawableIdentity == true) {

                val drawable = view.drawable

                signature = signature * 31L + (drawable?.let { System.identityHashCode(it) } ?: 0)

            }

            signature = signature * 31L + view.drawableState.contentHashCode()

            signature = signature * 31L + if (view.isSelected) 1 else 0

            signature = signature * 31L + if (view.isActivated) 1 else 0

            // JD's tab labels sit deeper inside its NavigationGroup; the deeper

            // walk makes their async text rebinds change the signature so a

            // blank capture is never left frozen on screen.

            if (view is ViewGroup && depth < (targetAdapter?.navigationSignatureMaxDepth ?: 5)) {

                signature = signature * 31L + view.childCount

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return signature.takeIf { laidOutViews >= slotCount * 2 } ?: Long.MIN_VALUE

    }



    /**

     * The Tencent adapter owns an independent navigation View

     * and only uses QQ's row as an adapter. Keep the same separation here by

     * flattening the completed QQ icon/text row into a tiny transparent bitmap.

     * QQ may freely tear down or half-layout its native row during a theme

     * switch without those intermediate frames entering our glass renderer.

     */

    private fun maybeRefreshNavigationSnapshot() {

        val probeTime = SystemClock.uptimeMillis()
        if (navigationSnapshot?.isRecycled == false &&
            probeTime < barAnimationPriorityUntil
        ) return
        val limitProbeCadence = config.solidBarEnabled || probeTime < barAnimationPriorityUntil
        if (limitProbeCadence && lastPriorityNavigationProbeAt != 0L &&
            probeTime - lastPriorityNavigationProbeAt < 48L
        ) return
        if (limitProbeCadence) lastPriorityNavigationProbeAt = probeTime

        if (targetAdapter?.skipNavigationSnapshot == true) return

        if (context.packageName == FILE_MANAGER_PACKAGE && fmStaticIconsPopulated) {

            // The main tab row draws from the extracted static cache; the

            // full-width container snapshot is never displayed, so skip the

            // capture walk entirely once the artwork is populated.

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            return

        }

        if (appNavigationState?.retainBackdropDuringTransition(SystemClock.uptimeMillis()) == true &&

            navigationSnapshot?.let { !it.isRecycled } == true

        ) return

        val now = SystemClock.uptimeMillis()

        if (now < navigationSnapshotFrozenUntil) return

        val source = navigationSource ?: return

        if (targetAdapter?.ownsNavigationSnapshotCapturePolicy == true &&
            appNavigationState?.skipNavigationSnapshotCapture(
                source, navigationSnapshot, touching, isBarVisualStateAnimating(), slotCount, now,
            ) == true
        ) return

        val signature = navigationLayoutSignature(source)

        val followingNativeAnimation = appNavigationState?.followingNativeNavigationAnimation(now) == true

        val pendingNativeTarget = appNavigationState?.pendingNativeNavigationTarget(now)
        if (pendingNativeTarget != null) {

            // Never publish a frame belonging to an earlier rapid-switch

            // request. Continue probing, but keep the last complete snapshot

            // until JD's native controller reports the latest committed target.

            val nativeSelection = if (nativeSelectionReliable) {

                resolveSelectedFromViewState()

            } else {

                pendingNativeTarget

            }

            if (nativeSelection != pendingNativeTarget) {

                pendingNavigationSignature = Long.MIN_VALUE

                pendingNavigationStableFrames = 0

                return

            }

        }

        if (signature == Long.MIN_VALUE ||

            (signature == navigationSnapshotSignature && !followingNativeAnimation)

        ) {

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            return

        }

        if (signature == pendingNavigationSignature) {

            pendingNavigationStableFrames++

        } else {

            pendingNavigationSignature = signature

            pendingNavigationStableFrames = 1

        }

        val requiredFrames = targetAdapter?.navigationSnapshotStableFrames
            ?: NAVIGATION_SNAPSHOT_STABLE_FRAMES

        if (pendingNavigationStableFrames < requiredFrames) return



        val captureLabels = appNavigationState?.inspectNavigationCaptureLabels(
            source, navigationSnapshot != null,
        )
        if ((captureLabels?.retryDelayMs ?: 0L) > 0L) {
            navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + captureLabels!!.retryDelayMs
            return
        }



        runCatching {

            val snapshotHeight = if (context.packageName == QQ_PACKAGE) {

                min(source.height, (QQ_SOURCE_ROW_HEIGHT_DP * density).roundToInt().coerceAtLeast(1))

            } else {

                source.height

            }

            val stagedCapture = targetAdapter?.stagedNavigationSnapshot == true

            var target = if (stagedCapture) {

                // Never draw JD's asynchronous icon directly into the bitmap

                // currently displayed by the Surface. Capture into a staging

                // bitmap and swap references only after visual validation.

                navigationCaptureScratch

                    ?.takeIf { !it.isRecycled && it.width == source.width && it.height == snapshotHeight }

                    ?: Bitmap.createBitmap(source.width, snapshotHeight, Bitmap.Config.ARGB_8888).also {

                        navigationCaptureScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                        navigationCaptureScratch = it

                    }

            } else {

                navigationSnapshot

                    ?.takeIf { !it.isRecycled && it.width == source.width && it.height == snapshotHeight }

                    ?: Bitmap.createBitmap(source.width, snapshotHeight, Bitmap.Config.ARGB_8888).also {

                        navigationSnapshot?.recycle()

                        navigationSnapshot = it

                    }

            }

            val captureAccepted = if (context.packageName == QQ_PACKAGE) {

                captureNormalizedQqNavigation(source, target)

                true

            } else if (appNavigationState?.captureNavigation(source, target) == true) {

                true

            } else if (targetAdapter?.nativeNavigationDrawsInSourceSpace == true) {

                // WeChat: source.draw() produces empty bitmaps. We draw native

                // tabs directly via drawWeChatNativeTabs, so skip snapshot capture.

                true

            } else {

                val recording = Canvas(target)

                recording.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

                val savedAlpha = source.alpha

                if (savedAlpha < 0.01f) source.alpha = 1f

                val hiddenLabels = appNavigationState?.temporarilyHideNavigationLabels(
                    captureLabels?.labels.orEmpty(), selectedIndex,
                ).orEmpty()

                try {

                    source.draw(recording)

                } finally {

                    if (savedAlpha < 0.01f) source.alpha = savedAlpha

                    hiddenLabels.forEach { (view, visibility) -> view.visibility = visibility }

                }

                true

            }

            if (!captureAccepted) return@runCatching

            val transformedSnapshot = appNavigationState?.transformNavigationSnapshot(source, target, slotCount)
            if (transformedSnapshot != null && transformedSnapshot !== target) {
                if (target === navigationSnapshot) navigationSnapshot = transformedSnapshot
                if (target === navigationCaptureScratch) navigationCaptureScratch = transformedSnapshot
                target.recycle()
                target = transformedSnapshot
            }

            appNavigationState?.replaceCapturedVideoSlot(target)

            val appAccepted = appNavigationState?.acceptAndCommitNavigationSnapshot(
                navigationSource, target, selectedIndex, slotCount, ::commitNavigationSnapshot,
            )
            if (appAccepted == false) return@runCatching
            if (appAccepted == null &&
                appNavigationState?.acceptCapturedNavigation(navigationSource, target, selectedIndex) == false
            ) return@runCatching

            if (targetAdapter?.stagedNavigationSnapshot == true) {

                // Publish a complete staged row; never draw into the bitmap on screen.

                val previous = navigationSnapshot

                navigationSnapshot = target

                navigationCaptureScratch = previous

            }

            navigationSnapshotSignature = signature

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            // The capture may have redrawn content in place into the reused

            // bitmap object; the recorded display list must be rebuilt.

            navigationContentRecordedBitmap = null

            navigationSnapshot?.takeUnless(Bitmap::isRecycled)?.let {
                appNavigationState?.onNavigationSnapshotReady(it, slotCount)
            }

            logGeometryIfChanged("snapshot", force = true)

            if (targetAdapter?.redrawSurfaceAfterNavigationCapture == true &&
                opticalSurfaceSurfacePipelineActive
            ) {

                // A snapshot change is independent from the sampled page strip;

                // submit it once even when the feed is already stationary.

                queueOpticalSurfaceRender()

            }

            invalidate()

        }.onFailure { error ->

            XposedBridge.log("[OfflineGlass][QQNavSnapshot] capture failed: $error")

        }

    }



    /**

     * JD's bottom bar labels are TextViews in the lower half of the native

     * NavigationGroup row. The deep scan is affordable because it only runs

     * when a snapshot capture is actually attempted and the row subtree is

     * small; a width cap keeps full-width text strips out of the result.
     */

    /** Keep JD's unread badges out of the icon/label colour-filter layer. */




    /**

     * Extract the file manager's main tab row artwork (最近/浏览 tabs plus the

     * standalone circular search key) into the static icon cache. Unlike the

     * generic snapshot path, each element is drawn in isolation, so the pill

     * geometry never distorts the artwork. The multi-select action bar does

     * not use this cache (its source id is split_action_bar).

     */

    private fun populateFileManagerStaticIcons() {

        if (fmStaticIconsPopulated || context.packageName != FILE_MANAGER_PACKAGE) return

        val source = navigationSource as? ViewGroup ?: return

        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return

        if (viewResourceEntryName(source) != FILE_MANAGER_BOTTOM_NAV_ID) {

            fmStableFrames = 0

            return

        }

        val tabs = (0 until source.childCount).mapNotNull { source.getChildAt(it) as? ViewGroup }

            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

        val search = (0 until source.childCount).mapNotNull { source.getChildAt(it) as? ImageView }

            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

            .maxByOrNull { it.width * it.height }

        if (tabs.size < 2 || search == null) {

            fmStableFrames = 0

            return

        }

        var signature = search.width * 31L + search.height

        for (tab in tabs) signature = signature * 31 + tab.width * 31L + tab.height

        if (signature != fmLastCaptureSignature) {

            fmLastCaptureSignature = signature

            fmStableFrames = 0

            return

        }

        if (fmStableFrames < 2) {

            fmStableFrames++

            return

        }

        runCatching {

            val captured = ArrayList<Bitmap>(3)

            for (view in tabs + search) {

                val savedAlpha = view.alpha

                if (savedAlpha < 0.01f) view.alpha = 1f

                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

                view.draw(Canvas(bitmap))

                if (savedAlpha < 0.01f) view.alpha = savedAlpha

                captured += bitmap

            }

            for (index in 0 until minOf(captured.size, fmStaticSlots.size)) {

                fmStaticSlots[index]?.takeUnless(Bitmap::isRecycled)?.recycle()

                fmStaticSlots[index] = captured[index]

            }

            fmStaticIconsPopulated = true

            postInvalidateOnAnimation()

            XposedBridge.log(

                "[OfflineGlass][FileManager] populated main-bar icons " +

                    "tabs=${tabs.size} search=${search.width}x${search.height}",

            )

        }

    }



    /** Draw the file manager's extracted main-bar artwork into the glass host.

     *  The rightmost search key stays a standalone circle. */

    private fun drawFileManagerNavigation(canvas: Canvas, extraScale: Float) {

        if (!fmStaticIconsPopulated || slotCount <= 0 || width <= 0 || height <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val contentCenterY = height / 2f

        for (visualIndex in 0 until minOf(slotCount, fmStaticSlots.size)) {

            val bitmap = fmStaticSlots[visualIndex]?.takeUnless(Bitmap::isRecycled) ?: continue

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            val bmpWidth = bitmap.width.toFloat().coerceAtLeast(1f)

            val bmpHeight = bitmap.height.toFloat().coerceAtLeast(1f)

            val drawScale = min(slotWidth / bmpWidth, height / bmpHeight) * extraScale

            val drawWidth = bmpWidth * drawScale

            val drawHeight = bmpHeight * drawScale

            val iconRect = RectF(

                centerX - drawWidth / 2f,

                contentCenterY - drawHeight / 2f,

                centerX + drawWidth / 2f,

                contentCenterY + drawHeight / 2f,

            )

            canvas.save()

            if (visualIndex == slotCount - 1) {

                val radius = min(drawWidth, drawHeight) / 2f

                val circle = Path().apply {

                    addCircle(centerX, contentCenterY, radius, Path.Direction.CW)

                }

                canvas.clipPath(circle)

            }

            canvas.drawBitmap(bitmap, null, iconRect, fmIconPaint)

            canvas.restore()

        }

    }






    /** Commit the candidate bitmap as the navigation snapshot. */

    private fun commitNavigationSnapshot(candidate: Bitmap) {

        val previous = navigationSnapshot

        navigationSnapshot = candidate

        navigationCaptureScratch = previous?.takeIf {

            !it.isRecycled && it.width == candidate.width && it.height == candidate.height

        }

        if (previous !== navigationCaptureScratch) {

            previous?.takeUnless(Bitmap::isRecycled)?.recycle()

        }

    }



    private fun sampledBitmapSignature(bitmap: Bitmap): Long {

        var signature = 17L

        val step = 4

        var y = 0

        while (y < bitmap.height) {

            var x = 0

            while (x < bitmap.width) {

                val color = bitmap.getPixel(x, y)

                // Quantisation ignores tiny antialiasing noise while retaining

                // real icon-frame changes.

                val quantized = (Color.alpha(color) shr 5 shl 15) or

                    (Color.red(color) shr 5 shl 10) or

                    (Color.green(color) shr 5 shl 5) or

                    (Color.blue(color) shr 5)

                signature = signature * 31L + quantized

                x += step

            }

            y += step

        }

        return signature

    }






    /** Preserve a notification badge above the neutral tint applied to unselected items. */
    /** Exclude the badge from the item that receives the unselected colour tint. */
    /**

     * QQ uses both 165 px and 217 px tab shells. More importantly, its first

     * launch may initially center the icon row in the full 217 px shell and

     * later move it into the upper 165 px content band without changing the

     * shell geometry. Locate the actual transparent icon/text pixels and map

     * their vertical centre into our canonical row instead of cropping at y=0.

     */

    private fun captureNormalizedQqNavigation(source: ViewGroup, target: Bitmap) {

        val scratch = navigationCaptureScratch

            ?.takeIf { !it.isRecycled && it.width == source.width && it.height == source.height }

            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {

                navigationCaptureScratch?.recycle()

                navigationCaptureScratch = it

            }

        val rawCanvas = Canvas(scratch)

        rawCanvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

        source.draw(rawCanvas)



        val recording = Canvas(target)

        recording.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

        val splitY = (QQ_ICON_LABEL_SPLIT_DP * density).roundToInt()

            .coerceIn(1, scratch.height)

        val iconBand = findNavigationContentBand(scratch, 0, splitY)

        val labelBand = findNavigationContentBand(scratch, splitY, scratch.height)

        if (iconBand != null && labelBand != null) {

            val iconShift = QQ_CANONICAL_ICON_CENTER_DP * density -

                (iconBand.first + iconBand.second + 1) / 2f

            val labelShift = QQ_CANONICAL_LABEL_CENTER_DP * density -

                (labelBand.first + labelBand.second + 1) / 2f

            recording.drawBitmap(

                scratch,

                Rect(0, 0, scratch.width, splitY),

                RectF(0f, iconShift, scratch.width.toFloat(), splitY + iconShift),

                snapshotCopyPaint,

            )

            recording.drawBitmap(

                scratch,

                Rect(0, splitY, scratch.width, scratch.height),

                RectF(

                    0f,

                    splitY + labelShift,

                    scratch.width.toFloat(),

                    scratch.height + labelShift,

                ),

                snapshotCopyPaint,

            )

            lastNavigationNormalization =

                "i${iconBand.first}-${iconBand.second}:$iconShift," +

                    "l${labelBand.first}-${labelBand.second}:$labelShift/${source.height}"

        } else {

            val contentBand = findNavigationContentBand(scratch, 0, scratch.height, 0.8f)

            val shiftY = contentBand?.let { (top, bottom) ->

                target.height / 2f - (top + bottom + 1) / 2f

            }?.coerceIn(-source.height / 2f, source.height / 2f) ?: 0f

            recording.save()

            recording.translate(0f, shiftY)

            recording.drawBitmap(scratch, 0f, 0f, snapshotCopyPaint)

            recording.restore()

            lastNavigationNormalization = contentBand?.let { (top, bottom) ->

                "$top-$bottom/${source.height}:$shiftY"

            } ?: "raw/${source.height}:0"

        }

    }



    private fun findNavigationContentBand(

        bitmap: Bitmap,

        startY: Int,

        endY: Int,

        maximumRowFraction: Float = 0.22f,

    ): Pair<Int, Int>? {

        val width = bitmap.width

        val height = bitmap.height

        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maximumContentPixelsPerRow = (width * maximumRowFraction).toInt().coerceAtLeast(3)

        var top = height

        var bottom = -1

        for (y in startY.coerceAtLeast(0) until endY.coerceAtMost(height)) {

            var visiblePixels = 0

            val row = y * width

            for (x in 0 until width) {

                if ((pixels[row + x] ushr 24) >= 12) visiblePixels++

            }

            // Ignore empty rows and full-width shell/divider surfaces. The

            // remaining alpha belongs to the three icon/label slots.

            if (visiblePixels in 2 until maximumContentPixelsPerRow) {

                if (top == height) top = y

                bottom = y

            }

        }

        return if (bottom >= top) top to bottom else null

    }



    private fun applyLiveConfig(updated: GlassConfig) {

        val old = config

        config = packageConfig(updated)

        Log.i(

            DIAGNOSTIC_TAG,

            "cfgApply pkg=${context.packageName} blur ${old.blurRadius} -> ${config.blurRadius}",

        )

        detectedContentDark = null

        lastThemeProbe = 0L

        // Config changes keep the last valid GPU scene until the replacement

        // signature is stable. This prevents the panel flashing into its plain

        // translucent fallback while a source View is remeasured.

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0



        if (old.classicNavigation != config.classicNavigation ||
            old.tabWidth != config.tabWidth || old.bottomPadding != config.bottomPadding ||

            old.hiddenMask != config.hiddenMask || old.blurRadius != config.blurRadius ||

            old.cornerRadiusPercent != config.cornerRadiusPercent ||

            old.cornerSmoothing != config.cornerSmoothing || old.barHeight != config.barHeight

        ) {

            updateHostLayoutForConfig()

        }

        if (old.cornerRadiusPercent != config.cornerRadiusPercent ||

            old.cornerSmoothing != config.cornerSmoothing || old.barHeight != config.barHeight

        ) {

            clipPath.setBottomBarSquircle(outerRect, outerCornerRadiusPx(), config.cornerSmoothing)

            invalidateOutline()

            opticalSurfaceNativeBlurView?.invalidateOutline()

            opticalSurfaceNativeIndicatorView?.invalidateOutline()

        }

        if (old.hiddenMask != config.hiddenMask) {

            // Entry switches affect four independent layers: the retained

            // native controller row, our artwork, the pill's legal drag stops,

            // and direct tap routing.  Keep the native child indices stable,

            // but hide their pixels and move an invalid current selection to

            // the nearest remaining entry immediately.

            findSlotContainer()?.let { container ->

                for (index in 0 until min(container.childCount, slotCount)) {

                    container.getChildAt(index).visibility =

                        if (isNavigationEntryDisabled(index)) View.INVISIBLE else View.VISIBLE

                }

            }

            val target = selectedIndex.takeUnless(::isNavigationEntryDisabled)

                ?: enabledNavigationIndices().firstOrNull()

            if (target != null) {

                val selectionChanged = target != selectedIndex

                selectedIndex = target

                dragTarget = visualPositionForNavigationIndex(target)

                indicatorPosition = dragTarget

                positionSpring.snapTo(dragTarget)

                if (selectionChanged) performNavigationSelection(target)

            }

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

        }

        applyLiveNavigationSizing()

        if (!config.liquidGlassEnabled) {

            pressSpring.snapTo(0f)

            scaleXSpring.snapTo(1f)

            scaleYSpring.snapTo(1f)

            interactiveSpring.snapTo(0f)

            outerPanelScale = 1f

        }

        configureGlassEffects()



        if (old.backdropCapture != config.backdropCapture) {

            if (config.backdropCapture) installCaptureLoop() else removeCaptureLoop()

        }

        if (old.nativeBlur != config.nativeBlur || old.solidBarEnabled != config.solidBarEnabled ||
            old.themeMode != config.themeMode ||

            old.blurRadius != config.blurRadius

        ) {

            if (nativeBlurActive) disableMiuiBackgroundBlur()

            nativeBlurActive = targetAdapter?.allowsSystemBackgroundBlur != false && shouldUseNativeBackgroundBlur() &&

                enableMiuiBackgroundBlur()

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

                opticalSurfaceNativeBlurView?.let(::disableOpticalSurfaceNativeBlur)

                opticalSurfaceNativeIndicatorView?.let(::disableOpticalSurfaceNativeBlur)

                opticalSurfaceNativeBlurActive = false

                opticalSurfaceNativeIndicatorActive = false

                appNavigationState?.onOpticalRendererConfigurationChanged()

                opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

                syncBackdropRendererMode()

            }

        }

        invalidate()

        updateBloomTiltRegistration()

    }



    private fun updateHostLayoutForConfig() {

        val content = parent as? ViewGroup ?: return

        val params = layoutParams as? FrameLayout.LayoutParams ?: return

        val desiredHeight = glassHostHeightPx(density, config, context.packageName)

        // Four-or-fewer bars share the five-tab height and bottom gap. Four

        // tabs also share its side gap; fewer tabs retain the resulting slot

        // width. WeChat remains on its existing app-specific +10dp geometry.

        val symmetricFloatingLayout = visualSlotCount() <= 4

        val desiredBottom = if (symmetricFloatingLayout) {

            glassHostBottomMarginPx(content, config) + if (targetAdapter?.hostLiftDp != null) {

                // WeChat lift, shared with the initial layout and side insets.

                ((targetAdapter?.hostLiftDp ?: 0f) * density).toInt()

            } else {

                0

            }

        } else {

            glassHostBottomMarginPx(content, config)

        }

        val desiredWidth = if (symmetricFloatingLayout) {

            symmetricGlassHostWidthPx(content, density, config, visualSlotCount())

        } else {

            glassHostWidthPx(content, visualSlotCount(), config, density)

        }

        if (params.width != desiredWidth || params.height != desiredHeight ||

            params.bottomMargin != desiredBottom

        ) {

            params.width = desiredWidth

            params.height = desiredHeight

            params.bottomMargin = desiredBottom

            layoutParams = params

            requestLayout()

        }

    }



    private fun applyLiveNavigationSizing() {

        // QQ's row is an adapter only. Icon/text size is applied in our own

        // fixed slot coordinate space when drawing, never back into QQ Views.

        if (context.packageName == QQ_PACKAGE) invalidate()

    }



    private fun drawNavigationSource(canvas: Canvas) {

        val heightScale = (height / (64f * density)).coerceIn(0.875f, 1f)

        appNavigationState?.prepare(navigationSource) { delay, action -> postDelayed(action, delay) }

        if (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) {

            drawQqStaticNavigation(canvas, extraScale = heightScale)

        } else if (targetAdapter?.ownsNavigationDrawing == true && appNavigationState?.readyForDrawing != false) {

            drawAdapterNavigation(canvas, extraScale = heightScale)

        } else if (context.packageName == FILE_MANAGER_PACKAGE && fmStaticIconsPopulated) {

            drawFileManagerNavigation(canvas, extraScale = heightScale)

        } else {

            drawNavigationContent(

                canvas,

                extraScale = heightScale,

                // Preserve native icon coloring (white outline icons

                // that turn blue when selected). The liquid pill indicator

                // alone marks the active tab; no additional icon tint is

                // applied on top.

                tintBySelection = context.packageName == QQ_PACKAGE,

            )

        }

        appNavigationState?.drawAppBadgeOverlay(canvas, this, heightScale)

    }



    /** Scale every item around its own centre in the hidden source row. */

    private fun drawScaledNavigationSource(canvas: Canvas, contentScale: Float) {

        val scaledContent = contentScale * (height / (64f * density)).coerceIn(0.875f, 1f)

        if (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) {

            drawQqStaticNavigation(canvas, extraScale = scaledContent)

        } else if (targetAdapter?.ownsNavigationDrawing == true && appNavigationState?.readyForDrawing != false) {

            drawAdapterNavigation(
                canvas,
                extraScale = scaledContent,
                selectedSlot = navigationIndexForVisualIndex(
                    indicatorPosition.roundToInt().coerceIn(0, visualSlotCount() - 1),
                ),
            )

        } else if (context.packageName == FILE_MANAGER_PACKAGE && fmStaticIconsPopulated) {

            drawFileManagerNavigation(canvas, extraScale = scaledContent)

        } else {

            drawNavigationContent(

                canvas,

                extraScale = scaledContent,

                // No icon tint for any package on this path: Mi Health and the

                // generic adapters keep their native selected/unselected

                // icon states verbatim, the pill alone marks the active tab.

                tintBySelection = false,

            )

        }

        appNavigationState?.drawAppBadgeOverlay(canvas, this, scaledContent)

    }



    /** Draws the three default QQ tab icons without QQTabDragAnimationView. */

    private fun drawQqStaticNavigation(canvas: Canvas, extraScale: Float) {

        if (slotCount != 3 || width <= 0 || height <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val iconSize = 28f * density

        val iconCenterY = 20f * density

        val labelBaseline = 53f * density

        val unselectedColor = if (isDarkGlass()) Color.WHITE else QQ_DEFAULT_DARK_COLOR

        val itemScale = config.iconScale * extraScale

        qqStaticLabelPaint.textSize = 12f * resources.displayMetrics.scaledDensity



        for ((visualIndex, index) in enabledNavigationIndices().withIndex()) {

            val selected = index == selectedIndex

            val bitmap = (if (selected) qqSelectedIconBitmaps else qqNormalIconBitmaps)[index] ?: continue

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            val color = if (selected) QQ_DEFAULT_ACCENT_COLOR else unselectedColor

            canvas.save()

            canvas.scale(itemScale, itemScale, centerX, height / 2f)



            qqStaticIconPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)

            qqStaticIconPaint.alpha = if (selected) 255 else 235

            val iconRect = RectF(

                centerX - iconSize / 2f,

                iconCenterY - iconSize / 2f,

                centerX + iconSize / 2f,

                iconCenterY + iconSize / 2f,

            )

            canvas.drawBitmap(bitmap, null, iconRect, qqStaticIconPaint)



            qqStaticLabelPaint.color = color

            qqStaticLabelPaint.alpha = if (selected) 255 else 235

            canvas.drawText(QQ_TAB_LABELS[index], centerX, labelBaseline, qqStaticLabelPaint)

            canvas.restore()

        }

        qqStaticIconPaint.colorFilter = null

        qqStaticIconPaint.alpha = 255

        qqStaticLabelPaint.alpha = 255

    }



    private fun hasQqStaticIcons(): Boolean =

        qqNormalIconBitmaps.size == 3 && qqSelectedIconBitmaps.size == 3 &&

            qqNormalIconBitmaps.all { it != null } && qqSelectedIconBitmaps.all { it != null }



    private fun loadQqTabBitmap(fileName: String): Bitmap? = runCatching {

        javaClass.classLoader

            ?.getResourceAsStream("qq_icons/$fileName")

            ?.use(BitmapFactory::decodeStream)

    }.onFailure { error ->

        XposedBridge.log("[OfflineGlass][QQStaticIcons] $fileName: $error")

    }.getOrNull()



    /** Draw JD's cached icons with theme-based color tinting, scaled by the

     *  slot transform factor to match the visual slot size. The entire cached

     *  slot (icon + label) is drawn with correct aspect ratio; no separate

     *  label text is drawn to avoid double-text. */


    /**

     * Draw Weibo's own light/dark, selected/unselected assets in LiquidTab's

     * Draw Weibo's own light/dark, selected/unselected assets in LiquidTab's

     * stable coordinate space. The native row stays attached only as the page

     * controller and badge source, so feed cards and transient tab animations

     * can never be captured into the liquid bar.

     */







    private fun drawAdapterNavigation(canvas: Canvas, extraScale: Float, selectedSlot: Int = selectedIndex) {
        appNavigationState?.prepare(navigationSource) { delay, action -> postDelayed(action, delay) }
        val frame = adapterNavigationFrame(extraScale, selectedSlot)
        appNavigationState?.draw(canvas, frame) ?: targetAdapter?.drawNavigation(canvas, frame)
    }

    private fun adapterNavigationFrame(extraScale: Float, selectedSlot: Int = selectedIndex) = AdapterNavigationFrame(
            context = context,
            extraScale = extraScale,
            slotCount = slotCount,
            width = width,
            height = height,
            slotWidth = tabWidthPx(),
            density = density,
            iconScale = config.iconScale,
            textSize = config.textSize,
            scaledDensity = resources.displayMetrics.scaledDensity,
            selectedIndex = selectedSlot,
            enabledIndices = enabledNavigationIndices(),
            iconOnly = config.iconOnly,
            darkGlass = isDarkGlass(),
            accentColor = selectedNavigationAccent(),
            navigationOpticalMix = navigationOpticalMix(),
            hideSelectedForOptics = drawingHiddenNavigationForOptics,
        )

    private fun foregroundNavigationClipPath(): Path {
        val cutout = RectF(selectionRect).apply { inset(-density, -density) }
        return Path().apply {
            setBottomBarSquircle(
                cutout,
                cutout.height() * (config.cornerRadiusPercent / 100f),
                config.cornerSmoothing,
            )
        }
    }

    private fun navigationOpticalMix(): Float =
        if (targetAdapter?.usesNavigationOpticalMix != true || !config.liquidGlassEnabled || !sliderEnabled) 0f
        else if (touching) 1f
        else pressProgress.coerceIn(0f, 1f)



    /**

     * WeChat's native tab icons cannot be captured via View.draw() (produces

     * empty bitmap). Instead, walk the source's view tree and directly draw

     * each tab's icon Drawable, text label, and notification badge.

     *

     * Source structure: LauncherUIBottomTabView �?LinearLayout �?N × RelativeLayout

     *   Each RelativeLayout: LinearLayout(label area) + RelativeLayout(icon area)

     *     Icon area: TabIconView(icon 85×85) + ImageView(badge dot) + TextView(badge count)

     *

     * Text disappearing fix: draw text directly via canvas.drawText() instead of

     * labelView.draw(), avoiding parent alpha=0 interference.

     *

     * Badge fix: detect badge views (ImageView dot + TextView count) and draw

     * them when they are VISIBLE (WeChat sets them VISIBLE on unread messages).

     */




    private fun drawNavigationContent(canvas: Canvas, extraScale: Float, tintBySelection: Boolean) {

        val source = navigationSource?.takeIf { it.width > 0 && it.height > 0 }

        // For WeChat: source.draw() cannot capture native tab icons via snapshot

        // (produces empty bitmap). Always draw directly from source with alpha=1.

        val snapshot = when {

            targetAdapter?.nativeNavigationDrawsInSourceSpace == true -> null

            else -> navigationSnapshot?.takeUnless(Bitmap::isRecycled)

        }

        if (snapshot == null && source == null) return

        val sourceWidth = snapshot?.width ?: source!!.width

        val sourceHeight = snapshot?.height ?: source!!.height

        val firstEnabled = enabledNavigationIndices().firstOrNull() ?: return

        val transform = sourceTransformForSlot(sourceWidth, sourceHeight, firstEnabled, 0)

        val projectionCompensation = when (context.packageName) {

            QQ_PACKAGE -> (QQ_TARGET_ICON_DP / QQ_NATIVE_ICON_DP) * config.iconScale /

                transform.scale.coerceAtLeast(0.0001f)

            context.packageName.takeIf { targetAdapter?.nativeNavigationDrawsInSourceSpace == true } ->
                config.iconScale / transform.scale.coerceAtLeast(0.0001f)

            MEITUAN_TAKEOUT_PACKAGE -> config.iconScale / transform.scale.coerceAtLeast(0.0001f)

            context.packageName.takeIf { targetAdapter?.projectedNavigationScale != 1f } ->
                (config.iconScale * (targetAdapter?.projectedNavigationScale ?: 1f)) /
                    transform.scale.coerceAtLeast(0.0001f)

            else -> 1f

        }

        val finalScale = projectionCompensation * extraScale



        if (canvas.isHardwareAccelerated && targetAdapter?.nativeNavigationDrawsInSourceSpace != true) {

            if (snapshot != null) {

                if (snapshot !== navigationContentRecordedBitmap ||

                    sourceWidth != navigationContentRecordedWidth ||

                    sourceHeight != navigationContentRecordedHeight

                ) {

                    val navigationCanvas = navigationContentNode.beginRecording(sourceWidth, sourceHeight)

                    navigationCanvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)

                    navigationContentNode.endRecording()

                    navigationContentNode.setPosition(0, 0, sourceWidth, sourceHeight)

                    navigationContentRecordedBitmap = snapshot

                    navigationContentRecordedWidth = sourceWidth

                    navigationContentRecordedHeight = sourceHeight

                }

            } else {

                val navigationCanvas = navigationContentNode.beginRecording(sourceWidth, sourceHeight)

                val src = source!!

                val saved = src.alpha; if (saved < 0.01f) src.alpha = 1f

                src.draw(navigationCanvas)

                if (saved < 0.01f) src.alpha = saved

                navigationContentNode.endRecording()

                navigationContentNode.setPosition(0, 0, sourceWidth, sourceHeight)

                navigationContentRecordedBitmap = null

            }

        }



        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val dark = isDarkGlass()

        for ((visualIndex, index) in enabledNavigationIndices().withIndex()) {

            val slotTransform = sourceTransformForSlot(

                sourceWidth,

                sourceHeight,

                index,

                visualIndex,

            )

            val left = horizontalPadding + visualIndex * slotWidth

            val right = left + slotWidth

            val centerX = (left + right) / 2f

            canvas.save()

            canvas.clipRect(left, 0f, right, height.toFloat())

            if (appNavigationState?.drawFixedNavigationSlot(

                    canvas = canvas,

                    index = index,

                    selectedIndex = selectedIndex,

                    dark = dark,

                    centerX = centerX,

                    centerY = height / 2f +

                        (slotTransform.dy + sourceHeight * slotTransform.scale / 2f - height / 2f) *

                        finalScale,

                    boxSize = min(sourceWidth / 5f, sourceHeight.toFloat()) *

                        slotTransform.scale * finalScale,

                ) == true

            ) {

                // Stable artwork lives in glass coordinates and is never

                // sourced from asynchronously recycled native tab Views.

                // Hidden native slots remain the click/selection targets.

                canvas.restore()

                continue

            }

            val tintLayer = if (tintBySelection) {

                val tint = if (index == selectedIndex) {

                    selectedNavigationAccent()

                } else if (dark) {

                    Color.WHITE

                } else {

                    Color.BLACK

                }

                navigationPaint.colorFilter = BlendModeColorFilter(tint, BlendMode.SRC_IN)

                navigationPaint.alpha = if (index == selectedIndex) 255 else 235

                canvas.saveLayer(left, 0f, right, height.toFloat(), navigationPaint)

            } else {

                -1

            }

            canvas.scale(finalScale, finalScale, centerX, height / 2f)

            val centerAction = targetAdapter?.sourceCenterActionIndex
            if (centerAction != null && index == centerAction) {
                canvas.translate(0f, height * 0.13f + (targetAdapter?.sourceCenterTranslationDp ?: 0f) * density)
                canvas.clipRect(left, 0f, right, height * (targetAdapter?.sourceCenterClipRatio ?: 1f))
            } else {
                canvas.translate(0f, (targetAdapter?.sourceDefaultTranslationDp ?: 0f) * density)
            }

            val contentOffsetY = appNavigationState?.sourceContentOffsetY(
                index, selectedIndex, height, slotTransform.dy, slotTransform.scale
            ) ?: (targetAdapter?.sourceContentOffsetDp ?: 0f) * density

            canvas.translate(slotTransform.dx, slotTransform.dy + contentOffsetY)

            canvas.scale(slotTransform.scale, slotTransform.scale)

            if (targetAdapter?.nativeNavigationDrawsInSourceSpace == true) {

                // WeChat: draw native tabs directly (source.draw() doesn't work)

                appNavigationState?.drawNativeNavigation(canvas, source!!, density)

            } else if (canvas.isHardwareAccelerated) {

                canvas.drawRenderNode(navigationContentNode)

            } else if (snapshot != null) {

                canvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)

            } else {

                val src = source!!

                val saved = src.alpha; if (saved < 0.01f) src.alpha = 1f

                src.draw(canvas)

                if (saved < 0.01f) src.alpha = saved

            }

            // Douyin keeps its four navigation glyphs in the upper part of
            // the captured slot. Repaint only that glyph band at 1.5x around
            // the slot centre; the label band remains at its original size
            // and the slot geometry/touch positioning is unchanged.
            if (targetAdapter?.opticalIconBandScale != 1f) {
                val iconBandBottom = height * 0.48f
                canvas.save()
                canvas.clipRect(left, 0f, right, iconBandBottom)
                canvas.scale(targetAdapter?.opticalIconBandScale ?: 1f, targetAdapter?.opticalIconBandScale ?: 1f, centerX, 22f * density)
                if (canvas.isHardwareAccelerated) {
                    canvas.drawRenderNode(navigationContentNode)
                } else if (snapshot != null) {
                    canvas.drawBitmap(snapshot, 0f, 0f, navigationPaint)
                }
                canvas.restore()
            }

            if (tintLayer >= 0) canvas.restoreToCount(tintLayer)

            canvas.restore()

        }

        navigationPaint.colorFilter = null

        navigationPaint.alpha = 255

    }




    /**

     * QQ's unread badge belongs to the native tab child rather than the static

     * icon. Paint that child as the final layer so its original red artwork is

     * never included in the blue selected-icon mask.

     */

    private fun drawQqBadgeOverlay(canvas: Canvas) {

        if (slotCount != 3) return

        val source = navigationSource?.takeIf { it.width > 0 && it.height > 0 } ?: return

        val container = findSlotContainer() ?: return

        val sourceLocation = IntArray(2).also(source::getLocationInWindow)

        for (index in 0 until min(container.childCount, slotCount)) {

            if (isNavigationEntryDisabled(index)) continue

            val visualIndex = enabledNavigationIndices().indexOf(index)

            if (visualIndex < 0) continue

            val transform = sourceTransformForSlot(

                source.width,

                source.height,

                index,

                visualIndex,

            )

            val item = container.getChildAt(index)

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += item to 0

            while (stack.isNotEmpty()) {

                val (candidate, depth) = stack.removeLast()

                if (candidate.javaClass.name == QQ_BADGE_CLASS &&

                    candidate.visibility == View.VISIBLE && candidate.width > 0 && candidate.height > 0

                ) {

                    val badgeLocation = IntArray(2).also(candidate::getLocationInWindow)

                    val centerX = transform.dx +

                        (badgeLocation[0] - sourceLocation[0] + candidate.width / 2f) * transform.scale

                    val centerY = transform.dy +

                        (badgeLocation[1] - sourceLocation[1] + candidate.height / 2f) * transform.scale

                    val badgeScale = max(

                        transform.scale,

                        QQ_BADGE_MIN_HEIGHT_DP * density / candidate.height.coerceAtLeast(1),

                    )

                    val save = canvas.save()

                    canvas.translate(

                        centerX - candidate.width * badgeScale / 2f,

                        centerY - candidate.height * badgeScale / 2f,

                    )

                    canvas.scale(badgeScale, badgeScale)

                    val oldAlpha = candidate.alpha

                    candidate.alpha = 1f

                    candidate.draw(canvas)

                    candidate.alpha = oldAlpha

                    canvas.restoreToCount(save)

                    break

                }

                if (candidate is ViewGroup && depth < 6) {

                    for (childIndex in candidate.childCount - 1 downTo 0) {

                        stack += candidate.getChildAt(childIndex) to (depth + 1)

                    }

                }

            }

        }

    }



    private fun readInstanceField(instance: Any, name: String): Any? {

        var type: Class<*>? = instance.javaClass

        while (type != null) {

            val field = runCatching { type.getDeclaredField(name) }.getOrNull()

            if (field != null) {

                field.isAccessible = true

                return runCatching { field.get(instance) }.getOrNull()

            }

            type = type.superclass

        }

        return null

    }



    private fun nearestSelectableIndex(rawIndex: Int): Int {

        val clamped = rawIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        if (clamped !in nonSelectableIndices && !isNavigationEntryDisabled(clamped)) return clamped

        val direction = if (dragTarget >= selectedIndex) 1 else -1

        for (distance in 1 until slotCount) {

            val preferred = clamped + distance * direction

            if (preferred in 0 until slotCount && preferred !in nonSelectableIndices &&

                !isNavigationEntryDisabled(preferred)

            ) return preferred

            val alternate = clamped - distance * direction

            if (alternate in 0 until slotCount && alternate !in nonSelectableIndices &&

                !isNavigationEntryDisabled(alternate)

            ) return alternate

        }

        return selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

    }



    private fun isNavigationEntryDisabled(index: Int): Boolean =

        index in 0 until slotCount &&

            config.hiddenMask and (1 shl index) != 0



    private fun enabledNavigationIndices(): List<Int> =

        (0 until slotCount).filterNot(::isNavigationEntryDisabled)



    private fun visualSlotCount(): Int = enabledNavigationIndices().size.coerceAtLeast(1)



    /**

     * Enables or disables touch on the native bottom bar and all its

     * descendants. When the glass bar is hidden we must also disable

     * touch on the native bar underneath, otherwise its tab buttons

     * still intercept touches meant for the content behind it.

     */

    private fun setNativeBarTouchable(view: View?, touchable: Boolean) {

        if (view == null) return

        if (appNavigationState?.setNativeBarTouchable(view, touchable) == true) return

        view.isClickable = touchable
        view.isFocusable = touchable

        if (view is ViewGroup) {

            for (i in 0 until view.childCount) {

                setNativeBarTouchable(view.getChildAt(i), touchable)

            }

        }

    }



    private fun visualPositionForNavigationIndex(index: Int): Float {

        val enabled = enabledNavigationIndices().let {
            if (targetAdapter?.reverseVisualSlotOrder == true) it.asReversed() else it
        }

        val position = enabled.indexOf(index).takeIf { it >= 0 } ?: 0

        return position.toFloat()

    }



    private fun navigationIndexForVisualIndex(visualIndex: Int): Int {

        val enabled = enabledNavigationIndices().let {
            if (targetAdapter?.reverseVisualSlotOrder == true) it.asReversed() else it
        }

        return enabled.getOrElse(visualIndex.coerceIn(0, enabled.lastIndex.coerceAtLeast(0))) {

            selectedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

        }

    }



    private fun performNavigationSelection(index: Int) {

        if (isNavigationEntryDisabled(index)) return

        if (!indicatorGestureActive) {
            barAnimationPriorityUntil = SystemClock.uptimeMillis() + 240L
        }

        if (targetAdapter?.ownsNavigationTap == true) {
            if (appNavigationState?.performTap(this, navigationSource, index, slotCount) == true) {
                if (targetAdapter?.commitSelectionAfterTap == true) {
                    selectedIndex = index.coerceIn(0, slotCount - 1)
                    selectionInitialized = true
                }
                afterNativeNavigationClick()
                appNavigationState?.onTapSucceeded { delay ->
                    if (delay <= 0L) postInvalidateOnAnimation()
                    else postDelayed({ postInvalidateOnAnimation() }, delay)
                }
            }
            return
        }

        if (appNavigationState?.onNavigationSelectionRequested(
                index, slotCount, SystemClock.uptimeMillis(),
            ) == true
        ) {
            compositorSurfaceStaticStreak = 0
            pendingNavigationSignature = Long.MIN_VALUE
            pendingNavigationStableFrames = 0
            navigationSnapshotFrozenUntil = 0L
            postOnAnimation { requestOpticalSurfaceRootSurfaceFrame() }
        }

        // Adapters with non-View-native tabs own dispatch; the host only commits
        // the resulting selection and schedules the shared visual transition.
        if (appNavigationState?.dispatchNativeNavigationTap(this, navigationSource, index, slotCount) == true ||
            targetAdapter?.dispatchNavigationTap(navigationSource, index, slotCount) == true
        ) {
                selectedIndex = index.coerceIn(0, slotCount - 1)

                selectionInitialized = true

                postInvalidateOnAnimation()

                postDelayed({ postInvalidateOnAnimation() }, 80L)

                return

        }

        // Theme Store's floating pill is a self-drawn surface: its tab cells

        // expose no View-level children to accessibility, so the shared

        // findSlotContainer + performClick path below cannot resolve a click

        // target and the page never switches (the pill just slides). Synthesize

        // a tap at the tab centre straight onto the pill: its own hit-testing

        // (Compose pointerInput, onTouchEvent or child click) performs the

        // switch and keeps the pill's selection state in sync with the glass.

        if (context.packageName == MI_THEME_PACKAGE ||

            context.packageName == MI_THEME_PROXY_PACKAGE

        ) {

            val source = navigationSource

            if (source != null && source.isAttachedToWindow && source.width > 0 &&

                source.height > 0

            ) {

                val x = source.width * (index + 0.5f) / slotCount

                val y = source.height / 2f

                val now = SystemClock.uptimeMillis()

                listOf(

                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0),

                    MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, x, y, 0),

                ).forEach { event ->

                    runCatching { source.dispatchTouchEvent(event) }

                    event.recycle()

                }

                selectedIndex = index.coerceIn(0, slotCount - 1)

                selectionInitialized = true

                postInvalidateOnAnimation()

                postDelayed({ postInvalidateOnAnimation() }, 80L)

                return

            }

        }

        if (context.packageName == FILE_MANAGER_PACKAGE && sliderEnabled) {

            // Main tab row: 最近/浏览 are the first two children of the

            // bottom_navigation_container; the standalone search circle is its

            // largest ImageView child (the container also hosts the selection

            // indicator View, so plain child indexing would mis-map the tap).

            val fmSource = navigationSource as? ViewGroup ?: return

            val target = if (index < 2) {

                fmSource.getChildAtOrNull(index)

            } else if (index == 2) {

                (0 until fmSource.childCount).mapNotNull { fmSource.getChildAt(it) as? ImageView }

                    .firstOrNull { it.width > 0 && it.height > 0 }

            } else {

                null

            }

            if (target?.performClick() == true) {

                afterNativeNavigationClick()

                return

            }

        }

        val container = findSlotContainer() ?: return

        val item = container.getChildAtOrNull(index) ?: return

        if (item.performClick()) {

            afterNativeNavigationClick()

            return

        }

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += item to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view !== item && view.isShown && view.isClickable && view.performClick()) {

                afterNativeNavigationClick()

                return

            }

            if (view is ViewGroup && depth < 5) {

                for (childIndex in 0 until view.childCount) {

                    stack += view.getChildAt(childIndex) to (depth + 1)

                }

            }

        }

    }



    /**

     * Mi Health's bottom bar is a stock material TabLayout. Prefer the bound

     * navigationSource; when it is stale or detached mid page transition, walk

     * the window for the TabLayout class so Tab.select() always has a live

     * receiver.

     */

    private fun afterNativeNavigationClick() {

        if (context.packageName == QQ_PACKAGE) {

            requestQqStaticBackdropRefresh(120L)

            return

        }

        val settleMs = if (context.packageName == MEITUAN_TAKEOUT_PACKAGE) MEITUAN_SELECTION_SETTLE_MS
            else targetAdapter?.selectionSettleMs ?: 0L
        if (settleMs <= 0L) return

        navigationSnapshotFrozenUntil = SystemClock.uptimeMillis() + settleMs

        postDelayed({

            navigationSnapshotSignature = Long.MIN_VALUE

            pendingNavigationSignature = Long.MIN_VALUE

            pendingNavigationStableFrames = 0

            invalidate()

        }, settleMs)

        postDelayed({ invalidate() }, settleMs + 32L)

        postDelayed({ invalidate() }, settleMs + 64L)

    }



    private fun createRuntimeShader(source: String, name: String): RuntimeShader? =

        runCatching { RuntimeShader(source) }

            .onFailure { error ->

                Log.e(DIAGNOSTIC_TAG, "shader $name creation failed", error)

                XposedBridge.log("[$DIAGNOSTIC_TAG] shader $name creation failed: $error")

            }

            .getOrNull()



    private fun configureGlassEffects() {

        // A solid bar never draws the captured backdrop. Avoid constructing
        // GPU blur/shader chains during tab switches or theme updates.
        if (config.solidBarEnabled) {
            outerGlassEffect = null
            tabsBackdropEffect = null
            opticalSurfaceOuterGlassEffect = null
            sharedOpticalBlurEffect = null
            sharedOpticalBlurPrepared = false
            outerLensShader = null
            combinedOuterLensShader = null
            opticalSurfaceOuterLensShader = null
            indicatorLensShader = null
            return
        }


        // Douyin's compositor input is already sampled through a downscaled
        // hardware buffer. Applying the global blur radius unchanged makes
        // the video appear softer than other apps even after increasing the
        // capture resolution. Keep the global setting intact elsewhere, but
        // reduce only Douyin's second blur pass so captured detail survives.
        val blurRadius = config.blurRadius * density * (targetAdapter?.secondaryBlurScale ?: 1f)

        val blurred = io.github.offlineglass.rendering.NativeBackdropBlur.effect(blurRadius)

        sharedOpticalBlurPrepared = false

        sharedOpticalBlurEffect = if (reuseOpticalBlur) blurred else null

        if (!config.liquidGlassEnabled) {

            // Release the per-host refraction shaders while their feature is
            // disabled. They are recreated lazily when Liquid Glass is enabled.
            outerLensShader = null
            combinedOuterLensShader = null
            opticalSurfaceOuterLensShader = null
            indicatorLensShader = null

            val blurOnly = config.blurRadius > 0f
            outerGlassEffect = blurred.takeIf { blurOnly }

            tabsBackdropEffect = blurred.takeIf { blurOnly }

            opticalSurfaceOuterGlassEffect = blurred.takeIf { blurOnly }

            return

        }

        if (outerLensShader == null) {

            outerLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "outer-retry")

        }

        if (combinedOuterLensShader == null) {

            combinedOuterLensShader = createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "tabs-retry")

        }

        if (opticalSurfaceOuterLensShader == null) {

            opticalSurfaceOuterLensShader =

                createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "optical-surface-outer-retry")

        }

        if (indicatorLensShader == null) {

            indicatorLensShader = createRuntimeShader(ROUNDED_RECT_DISPERSION_SHADER, "indicator-retry")

        }

        val outer = outerLensShader

        val combinedOuter = combinedOuterLensShader

        val opticalSurfaceOuter = opticalSurfaceOuterLensShader

        val indicator = indicatorLensShader

        if (outer == null || combinedOuter == null || indicator == null || width <= 0 || height <= 0) return



        setOuterLensUniforms(outer)

        setTabsBackdropLensUniforms(combinedOuter)

        opticalSurfaceOuter?.let {

            setOuterLensUniforms(

                it,

                refractionHeightDp = 10f,

                refractionAmountDp = -26f,

            )

        }

        perfEffectRebuilds++



        val outerLensEffect = RenderEffect.createRuntimeShaderEffect(outer, "content")

        outerGlassEffect = if (reuseOpticalBlur) outerLensEffect else

            RenderEffect.createChainEffect(outerLensEffect, blurred)

        // The compositor supplies the live base. Blur the retained optical

        // sheet before displacement so old WebView details cannot appear as a

        // second readable frame, while the stronger edge displacement remains.

        opticalSurfaceOuterGlassEffect = opticalSurfaceOuter?.let {

            RenderEffect.createChainEffect(

                RenderEffect.createRuntimeShaderEffect(it, "content"),

                blurred,

            )

        }



        val tabLensEffect = RenderEffect.createRuntimeShaderEffect(combinedOuter, "content")

        tabsBackdropEffect = if (reuseOpticalBlur) tabLensEffect else

            RenderEffect.createChainEffect(tabLensEffect, blurred)

        // The indicator effect is intentionally created in drawSelection.

        // Android RenderEffect snapshots RuntimeShader uniforms at creation;

        // Rebuild this effect whenever animated

        // press/geometry parameters change.

    }



    private fun setOuterLensUniforms(

        shader: RuntimeShader,

        refractionHeightDp: Float = 24f,

        refractionAmountDp: Float = -24f,

    ) {

        val corner = outerCornerRadiusPx()

        val padding = backdropPaddingPx()

        shader.setFloatUniform("size", width.toFloat(), height.toFloat())

        shader.setFloatUniform("offset", -padding, -padding)

        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)

        shader.setFloatUniform("refractionHeight", refractionHeightDp * density)

        shader.setFloatUniform("refractionAmount", refractionAmountDp * density)

        shader.setFloatUniform("depthEffect", 0f)

    }



    private fun setTabsBackdropLensUniforms(shader: RuntimeShader) {

        val rowHeight = (height - 8f * density).coerceAtLeast(1f)

        val rowTop = (height - rowHeight) / 2f

        val rowLeft = 4f * density

        val rowWidth = (width - 8f * density).coerceAtLeast(1f)

        val corner = rowHeight / 2f

        val padding = backdropPaddingPx()

        // The hidden source tab row is 56 dp high and inset by 4 dp. Its

        // effect is recorded in our padded host-sized RenderNode, so the lens

        // origin must include both that scene padding and the row's own inset.

        // Using the host origin here left the shader shape 4 dp above/left of

        // the clipped row, exposing its lower curve inside the selected pill.

        shader.setFloatUniform("size", rowWidth, rowHeight)

        shader.setFloatUniform("offset", -(padding + rowLeft), -(padding + rowTop))

        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)

        shader.setFloatUniform("refractionHeight", 24f * density)

        shader.setFloatUniform("refractionAmount", -24f * density)

        shader.setFloatUniform("depthEffect", 0f)

    }



internal fun hasUsableBackdrop(): Boolean =

        appNavigationState?.requiresSampledBackdrop() != false && !compositorBlurBehind && (

            (liveBackdropActive && liveBackdropNode.hasDisplayList()) ||

                appNavigationState?.videoBackdrop()?.bitmap?.let { !it.isRecycled } == true ||

                backdrop?.let { !it.isRecycled } == true

            )



    private fun drawBackdropContent(canvas: Canvas, destination: RectF) {

        val appVideoBackdrop = appNavigationState?.videoBackdrop()
        if (appVideoBackdrop != null) {

            // Douyin: the glass blurs the real video frame sampled from the
            // feed's SurfaceView (backdrop, painted first as the background
            // layer) plus the overlay controls (live RenderNode scene on top).
            // Without the Surface sample the blur input would only contain the
            // controls, which is the "plain transparency over video" artifact.
            appVideoBackdrop.bitmap.takeUnless(Bitmap::isRecycled).takeUnless { appNavigationState?.blocksBackdropSampling == true }?.let { bmp ->
                if (appVideoBackdrop.surfaceWidth > 0 && appVideoBackdrop.surfaceHeight > 0) {
                    // Map the captured surface pixels back onto this host's
                    // window rect using the surface geometry recorded at
                    // capture time, so the glass refracts exactly the video
                    // pixels behind it even when the surface is not full-bleed.
                    val hostLoc = IntArray(2).also(::getLocationInWindow)
                    val padX = ((destination.width() - width) / 2f).roundToInt()
                    val padY = ((destination.height() - height) / 2f).roundToInt()
                    hostLoc[0] -= padX
                    hostLoc[1] -= padY
                    val hostRight = hostLoc[0] + destination.width().roundToInt()
                    val hostBottom = hostLoc[1] + destination.height().roundToInt()
                    val sLeft = appVideoBackdrop.surfaceLeft
                    val sTop = appVideoBackdrop.surfaceTop
                    val sRight = sLeft + appVideoBackdrop.surfaceWidth
                    val sBottom = sTop + appVideoBackdrop.surfaceHeight
                    val cropLeft = maxOf(hostLoc[0], sLeft)
                    val cropTop = maxOf(hostLoc[1], sTop)
                    val cropRight = minOf(hostRight, sRight)
                    val cropBottom = minOf(hostBottom, sBottom)
                    if (cropRight > cropLeft && cropBottom > cropTop) {
                        val sx = bmp.width.toFloat() / appVideoBackdrop.surfaceWidth
                        val sy = bmp.height.toFloat() / appVideoBackdrop.surfaceHeight
                        val src = Rect(
                            ((cropLeft - sLeft) * sx).roundToInt(),
                            ((cropTop - sTop) * sy).roundToInt(),
                            ((cropRight - sLeft) * sx).roundToInt(),
                            ((cropBottom - sTop) * sy).roundToInt(),
                        )
                        val dstLeft = (cropLeft - hostLoc[0]) +
                            destination.left
                        val dstTop = (cropTop - hostLoc[1]) +
                            destination.top
                        val dstRight = (cropRight - hostLoc[0]) +
                            destination.left
                        val dstBottom = (cropBottom - hostLoc[1]) +
                            destination.top
                        if (src.width() > 0 && src.height() > 0) {
                            canvas.drawBitmap(
                                bmp, src,
                                RectF(dstLeft, dstTop, dstRight, dstBottom),
                                indicatorPaint,
                            )
                        }
                    } else {
                        // No overlap: leave the ordinary View backdrop visible.
                    }
                } else {
                    canvas.drawBitmap(bmp, null, destination, indicatorPaint)
                }
            }

            if (canvas.isHardwareAccelerated && liveBackdropActive &&

                liveBackdropNode.hasDisplayList()

            ) {

                // SurfaceView records a CLEAR hole for its separate video surface.
                // Isolate that operation so it cannot erase the sampled video below.
                val overlaySave = canvas.saveLayer(destination, null)
                canvas.drawRenderNode(liveBackdropNode)
                canvas.restoreToCount(overlaySave)

            }

            return

        }

        val compositorBackdropReady = targetAdapter?.usesDirectOpticalBackdropMode == true &&

            opticalSurfaceSurfacePipelineActive && backdrop?.let { !it.isRecycled } == true

        if (!compositorBackdropReady && canvas.isHardwareAccelerated &&

            liveBackdropActive && liveBackdropNode.hasDisplayList()

        ) {

            canvas.drawRenderNode(liveBackdropNode)

            return

        }

        backdrop?.takeUnless(Bitmap::isRecycled)?.let {
            if (appNavigationState?.drawSampledBackdrop(canvas, it, destination, indicatorPaint) != true) {
                canvas.drawBitmap(it, null, destination, indicatorPaint)
            }
        }

    }



    /**

     * Records only the content directly behind this 64 dp bar into a small GPU

     * scene. The scene references QQ's existing RenderNodes, so their pixels

     * stay current without synchronously redrawing QQ into a software bitmap.

     */

    private fun prepareLiveBackdropScene(): Boolean {
        if (!config.backdropCapture || config.solidBarEnabled) {
            liveBackdropActive = false
            sharedOpticalBlurPrepared = false
            return false
        }
        if (appNavigationState?.requiresSampledBackdrop() == false) return false

        if (appNavigationState?.skipLiveBackdrop(selectedIndex) == true) {

            // Browse and full-screen video contain independently composed

            // surfaces which a parent RenderNode cannot sample reliably.

            liveBackdropActive = false

            return false

        }

        if (appNavigationState?.canReuseLiveBackdrop(selectedIndex) == true &&
            liveBackdropNode.hasDisplayList()
        ) {

            // The wrapper keeps live references to JD's existing child

            // RenderNodes. Their pixels update with the app's normal traversal;

            // rebuilding the wrapper or walking 1,000+ views every frame is

            // unnecessary and was the main-thread cost of earlier attempts.

            liveBackdropActive = true

            return true

        }

        // Adapter-owned independent Surfaces use the dedicated capture path.

        if (appNavigationState?.needsSurfaceBackdrop() == true) {

            liveBackdropActive = false

            return false

        }

        if (appNavigationState?.retainBackdropDuringTransition(SystemClock.uptimeMillis()) == true &&

            liveBackdropNode.hasDisplayList()

        ) {

            // The retained node already references the last complete page

            // scene. Do not walk siblings or request display-list updates while

            // an adapter is constructing the destination page.

            liveBackdropActive = true

            return true

        }

        // Some compositor-backed pages cannot safely use live RenderNode sampling.

        // In practice, capturing the parent MainFrameContainerView's RenderNode

        // (which includes the WebView) works fine and gives real-time glass.

        if (targetAdapter?.usesOpticalSurfacePipeline == true && !isOpticalSurfaceSelected()) {

            opticalSurfaceSnapshotReady = false

            opticalSurfaceTextureView = null

            if (opticalSurfaceBackdropPadding != 0f) {

                opticalSurfaceBackdropPadding = 0f

                configureGlassEffects()

            }

        }

        // Use the snapshot-based hybrid backdrop for the selected optical surface.

        if (isOpticalSurfaceSelected()) {

            liveBackdropActive = false

            return false

        }

        // Same class of problem as other compositor-backed pages: Takeout's promotion tabs

        // (神券/活动) are WebView-composed pages. Referencing the pager's

        // RenderNode from the glass blur chain makes the async WebView

        // texture submit twice per frame, so those pages flicker nonstop

        // while the native home tab is unaffected. Keep the live scene only

        // on the home tab; every other tab falls back to the periodic

        // software-snapshot backdrop, which never re-submits page layers.

        if (context.packageName == MEITUAN_TAKEOUT_PACKAGE) {

            if (liveBackdropActive || liveBackdropNode.hasDisplayList()) {

                android.util.Log.i(

                    "WmGlassDiag",

                    "liveBackdropOff: tab=$selectedIndex, dropping RenderNode scene",

                )

                liveBackdropActive = false

                liveBackdropNode.setRenderEffect(null)

                liveBackdropNode.discardDisplayList()

                liveBackdropSignature = Long.MIN_VALUE

                pendingBackdropSignature = Long.MIN_VALUE

                pendingBackdropStableFrames = 0

                configureGlassEffects()

                invalidate()

            }

            return false

        }

        if (!isHardwareAccelerated || width <= 0 || height <= 0) return false

        if (appNavigationState?.retainHomeBackdrop(selectedIndex) == true && liveBackdropNode.hasDisplayList()

        ) {

            // The wrapper retains references to the app's child RenderNodes; their

            // pixels keep updating with the feed. Re-walking and forcing every

            // dirty child display list on each 120 Hz frame is unnecessary and

            // was the source of homepage-only scrolling jank.

            liveBackdropActive = true

            return true

        }

        val adapterScene = appNavigationState?.resolveBackdropScene(this)
        val content = (adapterScene?.parent ?: parent) as? ViewGroup ?: return false
        val hostIndex = if (adapterScene != null) content.childCount else

            content.indexOfChild(this).takeIf { it >= 0 } ?: return false

        perfSceneSweeps++

        perfSceneSiblingVisits += hostIndex

        // A target app can re-enter drawing while updating its display list.

        // Such a nested sweep must not mutate the outer sweep's scratch storage.

        if (sceneSweepInProgress) return liveBackdropActive

        sceneSweepInProgress = true

        val nodes = sceneNodesScratch

        val views = sceneViewsScratch

        try {

        val safeSceneViews = appNavigationState?.backdropSceneViews(content, hostIndex)
        for (index in 0 until (safeSceneViews?.size ?: hostIndex)) {
            val child = safeSceneViews?.get(index) ?: content.getChildAt(index)


            if (adapterScene != null && child !== adapterScene) continue

            if (child === opticalSurfaceSurfaceView) continue

            if (child.visibility != View.VISIBLE || child.alpha <= 0.001f || child.width <= 0 || child.height <= 0) continue

            val node = viewRenderNode(child) ?: continue

            if (!node.hasDisplayList()) continue

            views += child

            nodes += node

        }

        if (nodes.isEmpty()) {

            if (safeSceneViews != null) {
                clearLiveBackdropForAdapter()
                return false
            }

            liveBackdropActive = liveBackdropNode.hasDisplayList()

            return liveBackdropActive

        }



        val padding = backdropPaddingPx().roundToInt()

        val expandedWidth = width + padding * 2

        val expandedHeight = height + padding * 2

        val contentLocation = sceneContentLocation.also(content::getLocationInWindow)

        val hostLocation = sceneHostLocation.also(::getLocationInWindow)

        val rawOffsetX = contentLocation[0] - hostLocation[0] + padding

        val rawOffsetY = contentLocation[1] - hostLocation[1] + padding

        // Window/layout rounding can alternate the relative origin by one

        // physical pixel while neither the bar nor its content actually moves.

        // Re-recording the blurred layer at both origins reads as shimmer.

        // Keep a one-pixel deadband; genuine layout changes still update at once.

        if (liveBackdropStableOffsetX == Int.MIN_VALUE ||

            abs(rawOffsetX - liveBackdropStableOffsetX) > 1

        ) liveBackdropStableOffsetX = rawOffsetX

        if (liveBackdropStableOffsetY == Int.MIN_VALUE ||

            abs(rawOffsetY - liveBackdropStableOffsetY) > 1

        ) liveBackdropStableOffsetY = rawOffsetY

        val offsetX = liveBackdropStableOffsetX

        val offsetY = liveBackdropStableOffsetY

        var signature = 17L

        val adapterSceneBase = adapterScene?.let {
            appNavigationState?.backdropSceneBaseColor(it, surfaceContainerColor(isDarkGlass()))
        } ?: Color.TRANSPARENT
        signature = signature * 31L + adapterSceneBase

        signature = signature * 31L + expandedWidth

        signature = signature * 31L + expandedHeight

        signature = signature * 31L + offsetX

        signature = signature * 31L + offsetY

        for (index in nodes.indices) {

            val view = views[index]

            signature = signature * 31L + System.identityHashCode(nodes[index])

            signature = signature * 31L + view.width

            signature = signature * 31L + view.height

            if (safeSceneViews != null) {
                val location = IntArray(2).also(view::getLocationInWindow)
                signature = signature * 31L + location[0]
                signature = signature * 31L + location[1]
            }

        }



        // During visibility animations, skip the stable-frame delay so the

        // backdrop updates every frame and the refraction stays live as

        // the panel scales/translates. Without this, the backdrop freezes

        // on the first animation frame and the exit looks like a static

        // image snapping away.

        // Douyin's animated image/feed cards can legitimately change their
        // RenderNode content every frame while keeping the same node identity.
        // Waiting for consecutive identical scene signatures therefore never
        // commits a new live backdrop for those cards (video is handled by the
        // separate Surface capture path). Keep the stability guard for every
        // other app, but let Douyin's dynamic scene submit immediately.
        val skipStableDelay = barVisibilityAnimating || targetAdapter?.skipBackdropStableDelay == true



        val hasRetainedScene = liveBackdropNode.hasDisplayList()

        if (!skipStableDelay && signature != liveBackdropSignature && hasRetainedScene) {

            if (signature == pendingBackdropSignature) {

                pendingBackdropStableFrames++

            } else {

                pendingBackdropSignature = signature

                pendingBackdropStableFrames = 1

            }

            if (pendingBackdropStableFrames < BACKDROP_STABLE_FRAMES) {

                liveBackdropActive = true

                return true

            }

        }



        // Weibo video textures can submit new compositor frames without

        // changing any View/RenderNode identity or geometry. Merely invalidating

        // this host redraws the old effected input on some HyperOS builds. Mark

        // the lightweight wrapper display list fresh every visible frame so the

        // blur/refraction chain samples the current texture; keep the expensive

        // effect objects intact while the scene signature itself is unchanged.

        val forceCompositorFrame =

            (targetAdapter?.forceCompositorFrames == true ||
                appNavigationState?.forceLiveBackdropFrame(selectedIndex) == true) &&

                isShown && alpha > 0.001f

        val sceneChanged = signature != liveBackdropSignature || !hasRetainedScene

        if (sceneChanged || forceCompositorFrame) {

            perfSceneReRecords++

            val recording = liveBackdropNode.beginRecording(expandedWidth, expandedHeight)

            if (adapterScene != null) recording.drawColor(adapterSceneBase)

            recording.clipRect(0f, 0f, expandedWidth.toFloat(), expandedHeight.toFloat())

            recording.translate(offsetX.toFloat(), offsetY.toFloat())

            for (index in nodes.indices) {
                if (safeSceneViews == null) {
                    recording.drawRenderNode(nodes[index])
                } else {
                    // Descendant RenderNodes include their own View transform,
                    // but need the intervening ancestor transforms in this scene.
                    recording.save()
                    val ancestors = ArrayList<View>()
                    var ancestor = views[index].parent as? View
                    while (ancestor != null && ancestor !== content) {
                        ancestors += ancestor
                        ancestor = ancestor.parent as? View
                    }
                    recording.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
                    for (parentView in ancestors.asReversed()) {
                        recording.translate(parentView.left.toFloat(), parentView.top.toFloat())
                        recording.concat(parentView.matrix)
                        if (parentView is ViewGroup && parentView.clipChildren) {
                            if (parentView.clipToPadding) {
                                recording.clipRect(parentView.paddingLeft, parentView.paddingTop,
                                    parentView.width - parentView.paddingRight,
                                    parentView.height - parentView.paddingBottom)
                            } else recording.clipRect(0, 0, parentView.width, parentView.height)
                        }
                        recording.translate(-parentView.scrollX.toFloat(), -parentView.scrollY.toFloat())
                    }
                    recording.drawRenderNode(nodes[index])
                    recording.restore()
                }
            }

            liveBackdropNode.endRecording()

            liveBackdropNode.setPosition(0, 0, expandedWidth, expandedHeight)

            liveBackdropSignature = signature

            appNavigationState?.onLiveBackdropRecorded(selectedIndex)

            // RenderEffect objects built before a replacement Activity joined

            // the hardware tree can remain valid Kotlin objects while drawing

            // only their transparent surface. Recreate the chain against the

            // newly committed Backdrop scene.

            // Effects depend on configuration and geometry, not on the current
            // feed's node identity. Keep the GPU chain across scene replacements;
            // configuration/layout updates already rebuild it explicitly.
            if (outerGlassEffect == null) configureGlassEffects()

        }

        pendingBackdropSignature = Long.MIN_VALUE

        pendingBackdropStableFrames = 0

        if (!liveBackdropLogged) {

            liveBackdropLogged = true

            val sourceDescription = views.joinToString("|") { view ->

                val location = IntArray(2).also(view::getLocationInWindow)

                "${view.javaClass.name}@${location[0]},${location[1]} frame=${view.left},${view.top},${view.width}x${view.height}"

            }

            XposedBridge.log(

                "[OfflineGlass][LiveBackdrop] host=${hostLocation[0]},${hostLocation[1]} ${width}x$height " +

                    "content=${contentLocation[0]},${contentLocation[1]} ${content.width}x${content.height} " +

                    "offset=$offsetX,$offsetY pad=$padding sources=$sourceDescription",

            )

        }

        liveBackdropActive = true

        // Douyin owns the video bitmap through its low-cadence PixelCopy

        // pipeline; it is composited under the live scene in drawBackdropContent,

        // so it must survive the scene commit.

        backdrop?.recycle()
        backdrop = null

        return true

        } finally {

            nodes.clear()

            views.clear()

            sceneSweepInProgress = false

        }

    }



    private val retainedRenderNodeField by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
    }

    private fun viewRenderNode(view: View): RenderNode? {

        if (targetAdapter?.allowRenderNodeRefresh != true) {

            // JD's 新品 feed is drawn by an obfuscated custom renderer

            // (currently d6.core.c40). updateDisplayListIfDirty() is not a

            // passive getter for that renderer: invoking it again from our

            // pre-draw backdrop pass makes its video-preview/card display list

            // alternate between populated and empty. The actively playing

            // video remains stable because it is composed separately.

            //

            // Reuse the RenderNode already produced by Android's normal draw

            // traversal without recording a second display list for generic apps.

            val retained = runCatching {
                retainedRenderNodeField?.get(view) as? RenderNode
            }.getOrNull()
            if (retained?.hasDisplayList() == true || targetAdapter?.retainsExistingBackdropRenderNode == true) {
                return retained
            }

        }

        return runCatching {

            XposedHelpers.callMethod(view, "updateDisplayListIfDirty") as? RenderNode

        }.getOrNull()

    }



    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0

internal fun drawOuterGlass(canvas: Canvas) {

        if (!canvas.isHardwareAccelerated || outerGlassEffect == null) {

            backdrop?.takeUnless(Bitmap::isRecycled)?.let {

                canvas.drawBitmap(it, null, outerRect, glassPaint)

            }

            return

        }

        val rendered = runCatching {

            val padding = backdropPaddingPx().roundToInt()

            val expandedWidth = width + padding * 2

            val expandedHeight = height + padding * 2

            expandedBackdropRect.set(0f, 0f, expandedWidth.toFloat(), expandedHeight.toFloat())

            outerRenderNode.setPosition(-padding, -padding, width + padding, height + padding)

            // A recorded drawRenderNode keeps a live reference to the source

            // RenderNode. Re-recording this wrapper every frame is redundant

            // and can alternate blur texture allocation/phase. Cache it for

            // both the live scene and JD's compositor-backed bitmap path.

            val reusable = liveBackdropActive ||
                appNavigationState?.reuseOuterBackdropForSurface(opticalSurfaceSurfacePipelineActive) == true
            val stateNeedsRecord = appNavigationState?.outerBackdropNeedsRecord(
                backdrop, expandedWidth, expandedHeight, outerRenderNode.hasDisplayList(),
            )
            val needsRecord = appNavigationState?.forceLiveBackdropFrame(selectedIndex) == true ||
                outerRecordedWidth != expandedWidth || outerRecordedHeight != expandedHeight ||
                (stateNeedsRecord ?: (!reusable || !outerRenderNode.hasDisplayList()))

            if (needsRecord) {

                perfNodeRecords++

                val recordingCanvas = outerRenderNode.beginRecording(expandedWidth, expandedHeight)

                drawSharedOpticalBackdrop(recordingCanvas, expandedBackdropRect)

                outerRenderNode.endRecording()

                outerRecordedWidth = expandedWidth
                outerRecordedHeight = expandedHeight

                if (reusable) appNavigationState?.onOuterBackdropRecorded(backdrop, expandedWidth, expandedHeight)

            }

            val hybridBackdrop = usesOpticalSurfaceHybridBackdrop()

            outerRenderNode.setRenderEffect(

                if (hybridBackdrop) opticalSurfaceOuterGlassEffect else outerGlassEffect,

            )

            outerRenderNode.alpha = if (hybridBackdrop) 0.34f else 1f

            canvas.drawRenderNode(outerRenderNode)

            val appVideoEpoch = appNavigationState?.videoBackdrop()?.epoch ?: -1
            if (appVideoEpoch >= 0 && appVideoEpoch != lastAppVideoBackdropEpoch) {
                lastAppVideoBackdropEpoch = appVideoEpoch
                if (appVideoEpoch % 15 == 0) {
                    appNavigationState?.logDiagnostic("optical draw epoch=$appVideoEpoch liquid=${config.liquidGlassEnabled} lens=${outerLensShader != null} effect=${outerGlassEffect != null}")
                }
            }

        }.isSuccess

        if (!rendered) {

            backdrop?.takeUnless(Bitmap::isRecycled)?.let {

                canvas.drawBitmap(it, null, outerRect, glassPaint)

            }

        }

    }



    /** Slider variant that honors an adapter-owned chrome fade as a layer alpha. */

    private fun drawSelectionWithFade(canvas: Canvas, dark: Boolean) {

        val fade = appNavigationState?.hostChromeAlpha(this, selectedIndex) ?: 1f

        if (fade >= 1f) {

            drawSelection(canvas, dark)

            return

        }

        val saved = canvas.saveLayerAlpha(

            0f,

            0f,

            width.toFloat(),

            height.toFloat(),

            (fade * 255).roundToInt().coerceIn(0, 255),

        )

        drawSelection(canvas, dark)

        if (saved >= 0) canvas.restoreToCount(saved)

    }



    private fun drawSelection(canvas: Canvas, dark: Boolean) {

        // Selection bars (file manager action mode) render the liquid glass

        // panel without any slider: every slot is a plain tap target.

        if (!sliderEnabled) return

        if (slotCount <= 0 || width <= 0) return

        val slotWidth = tabWidthPx()

        val horizontalPadding = 4f * density

        val centerX = horizontalPadding + (indicatorPosition + 0.5f) * slotWidth

        val navCenterY: Float

        val navHeight: Float

        val adapterNavigationGeometry = appNavigationState?.navigationBarGeometry()
        if (adapterNavigationGeometry != null) {
            navCenterY = adapterNavigationGeometry.centerY()
            navHeight = adapterNavigationGeometry.height()

        } else {

            navCenterY = height / 2f

            navHeight = height.toFloat()

        }

        val centerY = navCenterY

        val baseHalfWidth = slotWidth.coerceAtLeast(1f) / 2f

        val baseHalfHeight = (navHeight - 8f * density).coerceAtLeast(1f) / 2f

        val velocity = indicatorVelocity / 10f

        val scaleX = indicatorScaleX / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))

        val scaleY = indicatorScaleY * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))

        val visualLeft = centerX - baseHalfWidth * scaleX

        val visualTop = centerY - baseHalfHeight * scaleY

        val visualRight = centerX + baseHalfWidth * scaleX

        val visualBottom = centerY + baseHalfHeight * scaleY



        // The backdrop is evaluated on the fixed 56 dp indicator and

        // DampedDragAnimation scales that completed layer afterwards. Growing

        // the sampling rect itself made the 78 dp pressed indicator read past

        // the 56 dp combined tab layer, exposing its lower edge as an internal

        // crescent. Keep all optical work at the base geometry, then transform

        // the finished result as one continuous layer.

        selectionRect.set(

            centerX - baseHalfWidth,

            centerY - baseHalfHeight,

            centerX + baseHalfWidth,

            centerY + baseHalfHeight,

        )

        val radius = selectionRect.height() * (config.cornerRadiusPercent / 100f)

        syncOpticalSurfaceNativeIndicator(

            centerX,

            centerY,

            baseHalfWidth,

            baseHalfHeight,

            scaleX,

            scaleY,

            radius,

        )

        val transformedLayer = canvas.save()

        canvas.scale(scaleX, scaleY, centerX, centerY)



        val runtime = indicatorLensShader

        if (!config.solidBarEnabled && hasUsableBackdrop() && canvas.isHardwareAccelerated &&

            (!usesOpticalSurfaceHybridBackdrop() || targetAdapter?.usesDirectOpticalBackdropMode == true)

        ) {

            runCatching {

                val scenePadding = backdropPaddingPx()

                val scenePaddingInt = scenePadding.roundToInt()

                val sceneWidth = width + scenePaddingInt * 2

                val sceneHeight = height + scenePaddingInt * 2

                expandedBackdropRect.set(0f, 0f, sceneWidth.toFloat(), sceneHeight.toFloat())



                // Build the combined backdrop in one stable, panel-local

                // coordinate space first (raw screen + hidden accent tab row).

                indicatorCombinedNode.setPosition(0, 0, sceneWidth, sceneHeight)

                val reusableScene = appNavigationState?.reuseCombinedIndicatorScene(
                    opticalSurfaceSurfacePipelineActive,
                ) == true
                val stateNeedsRecord = appNavigationState?.combinedIndicatorSceneNeedsRecord(
                    backdrop, navigationSnapshot, navigationSnapshotSignature, sceneWidth, sceneHeight,
                    dark, indicatorCombinedNode.hasDisplayList(),
                )
                val combinedNeedsRecord = if (!reusableScene) true
                    else stateNeedsRecord ?: !indicatorCombinedNode.hasDisplayList()

                if (combinedNeedsRecord) {

                    perfNodeRecords++

                    val combinedCanvas = indicatorCombinedNode.beginRecording(sceneWidth, sceneHeight)

                    drawBackdropContent(combinedCanvas, expandedBackdropRect)

        // The promotion page already keeps a live, native-blurred navigation row

        // behind this host. Repainting the hidden source tabs into the selection

        // lens duplicates the static foreground icons (especially while dragging).

        val showHiddenTabs = appNavigationState?.drawHiddenTabsInIndicatorScene(
            usesOpticalSurfaceHybridBackdrop(),
        ) ?: !usesOpticalSurfaceHybridBackdrop()
        if (showHiddenTabs) {

            drawHiddenTabsBackdrop(

                combinedCanvas,

                dark,

                scenePadding,

                sceneWidth,

                sceneHeight,

                includeNavigation = appNavigationState?.includeNavigationInIndicatorScene(
                    opticalSurfaceSurfacePipelineActive,
                ) ?: !opticalSurfaceSurfacePipelineActive,

            )

        }

                    indicatorCombinedNode.endRecording()

                    if (reusableScene) {
                        appNavigationState?.onCombinedIndicatorSceneRecorded(
                            backdrop, navigationSnapshot, navigationSnapshotSignature, sceneWidth, sceneHeight, dark,
                        )
                    }

                }



                // Apply the lens to a pill-sized layer so the transformed

                // drawBackdrop modifier. Applying it to the whole bar made the

                // RuntimeShader inherit the source RenderNode's global coords,

                // so the pill sampled distant avatars as an opaque flat colour.

                val nodeLeft = floor(selectionRect.left - scenePadding).toInt()

                val nodeTop = floor(selectionRect.top - scenePadding).toInt()

                val nodeRight = ceil(selectionRect.right + scenePadding).toInt()

                val nodeBottom = ceil(selectionRect.bottom + scenePadding).toInt()

                val nodeWidth = (nodeRight - nodeLeft).coerceAtLeast(1)

                val nodeHeight = (nodeBottom - nodeTop).coerceAtLeast(1)

                val localPaddingX = selectionRect.left - nodeLeft

                val localPaddingY = selectionRect.top - nodeTop



                indicatorRenderNode.setPosition(nodeLeft, nodeTop, nodeRight, nodeBottom)

                perfNodeRecords++

                val recordingCanvas = indicatorRenderNode.beginRecording(nodeWidth, nodeHeight)

                // miuix-blur's LayerBackdrop applies the inverse of

                // drawBackdrop.layerBlock before capturing each backdrop.

                // This keeps the sampled scene registered to the screen while

                // the completed 56 dp indicator layer is scaled by the damped

                // drag animation. Without this inverse transform the captured

                // pixels scale with the pill and the refraction loses the

                // stable through-glass appearance used by the bar.

                val localCenterX = centerX - nodeLeft

                val localCenterY = centerY - nodeTop

                recordingCanvas.translate(localCenterX, localCenterY)

                recordingCanvas.scale(1f / scaleX, 1f / scaleY)

                recordingCanvas.translate(

                    -(centerX + scenePaddingInt),

                    -(centerY + scenePaddingInt),

                )

                recordingCanvas.drawRenderNode(indicatorCombinedNode)

                indicatorRenderNode.endRecording()

                val hybridBackdrop = usesOpticalSurfaceHybridBackdrop()

                // Outside the hybrid path the reference lens grows only while

                // pressed. The promotion path no longer paints a hidden tab row

                // (that row caused the duplicated icons), so retain a modest

                // lens at rest to preserve liquid refraction and dispersion.

                val lensProgress = if (!config.liquidGlassEnabled) {

                    0f

                } else if (hybridBackdrop) {

                    pressProgress.coerceAtLeast(0.72f)

                } else if (targetAdapter?.restingOpticalMix != null) {

                    // Douyin's feed is an independent compositor surface. Keep
                    // the optical displacement active at rest; tying it only
                    // to pressProgress turns the bar into plain translucency
                    // between touches even when a fresh video frame is present.
                    targetAdapter?.restingOpticalMix ?: 0f

                } else {

                    pressProgress

                }

                if (runtime != null && lensProgress > 0.001f) {

                    runtime.setFloatUniform("size", selectionRect.width(), selectionRect.height())

                    runtime.setFloatUniform("offset", -localPaddingX, -localPaddingY)

                    runtime.setFloatUniform("cornerRadii", radius, radius, radius, radius)

                    val refractionHeight = if (hybridBackdrop) {

                        lerp(5.5f, 8f, pressProgress) * density

                    } else {

                        10f * density * lensProgress

                    }

                    val refractionAmount = if (hybridBackdrop) {

                        -lerp(16f, 20f, pressProgress) * density

                    } else {

                        -14f * density * lensProgress

                    }

                    runtime.setFloatUniform("refractionHeight", refractionHeight)

                    runtime.setFloatUniform("refractionAmount", refractionAmount)

                    runtime.setFloatUniform("depthEffect", 1f)

                    runtime.setFloatUniform("chromaticAberration", 0.5f)

                    val lensEffect = RenderEffect.createRuntimeShaderEffect(runtime, "content")

                    indicatorRenderNode.setRenderEffect(

                        if (hybridBackdrop) {

                            RenderEffect.createChainEffect(

                                lensEffect,

                                io.github.offlineglass.rendering.NativeBackdropBlur.effect(
                                    config.blurRadius * density * (targetAdapter?.secondaryBlurScale ?: 1f),
                                ),

                            )

                        } else {

                            lensEffect

                        },

                    )

                } else {

                    // Keep drawing the combined backdrop at rest; only the lens is

                    // omitted when its animated dimensions reach zero.

                    indicatorRenderNode.setRenderEffect(null)

                }

                indicatorRenderNode.alpha = if (hybridBackdrop) 0.34f else 1f

                selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

                val composed = appNavigationState?.drawSelectionBackdrop(
                    canvas, selectionPath, selectedIndex,
                ) { canvas.drawRenderNode(indicatorRenderNode) } == true
                if (!composed) {
                    canvas.save()
                    canvas.clipPath(selectionPath)
                    canvas.drawRenderNode(indicatorRenderNode)
                    canvas.restore()
                }

            }

        }



        val idleAlpha = 0.10f * (1f - pressProgress)

        val pressedAlpha = 0.03f * pressProgress

        val accent = if (config.customAccentEnabled) config.customAccentColor else Color.WHITE

        fillPaint.color = when {

            config.selectedAccent -> Color.argb(

                (idleAlpha * 255f).toInt(),

                Color.red(accent),

                Color.green(accent),

                Color.blue(accent),

            )

            dark -> Color.argb((idleAlpha * 255f).toInt(), 255, 255, 255)

            else -> Color.argb((idleAlpha * 255f).toInt(), 0, 0, 0)

        }

        selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

        canvas.drawPath(selectionPath, fillPaint)

        if (pressedAlpha > 0f) {

            fillPaint.color = Color.argb((pressedAlpha * 255f).toInt(), 0, 0, 0)

            canvas.drawPath(selectionPath, fillPaint)

        }



        if (config.liquidGlassEnabled) {

            if (config.outlineEnabled) {

                // A full-strength white bloom around the enlarged pill reads as

                // an almost closed luminous ring on dark content. Keep the same

                // moving dual-light shader, but lower only the pressed indicator

                // energy in dark mode. The outer panel and light mode are intact.

                val indicatorBloomAlpha = pressProgress * if (dark) 0.48f else 1f

                drawBloomStroke(

                    canvas,

                    selectionRect,

                    radius,

                    indicatorBloomAlpha,

                    90f,

                    indicatorBloomShader,

                )

            }

            if (context.packageName == QQ_PACKAGE || targetAdapter?.indicatorInnerShadow == true) {

                drawIndicatorInnerShadow(canvas, radius)

            } else {

                drawLiquidIndicatorLighting(canvas, dark, radius)

            }

        }

        canvas.restoreToCount(transformedLayer)



        // Touch hit-testing follows the visually transformed pill, while the

        // renderer above always consumes the stable 56 dp optical layer.

        selectionRect.set(visualLeft, visualTop, visualRight, visualBottom)

    }



    private fun drawHiddenTabsBackdrop(

        combinedCanvas: Canvas,

        dark: Boolean,

        padding: Float,

        expandedWidth: Int,

        expandedHeight: Int,

        includeNavigation: Boolean = true,

    ) {

        val effect = tabsBackdropEffect ?: return

        // Keep the captured coordinate space identical to the final indicator

        // pass. The previous row-slice node shifted the effect by one padding

        // radius, which made the lens sample the light edge pixels and turn

        // into an opaque white blob while being dragged.

        tabsBackdropNode.setPosition(0, 0, expandedWidth, expandedHeight)

        perfNodeRecords++

        val tabsCanvas = tabsBackdropNode.beginRecording(expandedWidth, expandedHeight)

        drawSharedOpticalBackdrop(tabsCanvas, expandedBackdropRect)

        tabsBackdropNode.endRecording()

        tabsBackdropNode.setRenderEffect(effect)



        val rowHeight = (height - 8f * density).coerceAtLeast(1f)

        val rowTop = (height - rowHeight) / 2f

        val rowRadius = rowHeight / 2f

        val horizontalInset = 4f * density

        // The hidden 56 dp source row is padded by 4 dp on both sides.

        // Keeping the backdrop row at the full panel width displaced its left

        // rounded corner from the first indicator by exactly that inset, which

        // exposed a second curved boundary inside the stationary pill.

        val rowRect = RectF(

            padding + horizontalInset,

            padding + rowTop,

            padding + width - horizontalInset,

            padding + rowTop + rowHeight,

        )

        // This is an internal sampling mask, not the visible pill outline.

        // If it exactly coincides with the final indicator path, the two

        // antialiased edges multiply their fractional alpha (especially on

        // AndroidX's three-segment inward corner) and expose a translucent

        // crescent. Give the sampled row a tiny bleed; drawSelection still

        // applies the exact configured squircle as the final clip.

        val samplingBleed = 1.5f * density

        rowRect.inset(-samplingBleed, -samplingBleed)

        selectionPath.setBottomBarSquircle(

            rowRect,

            rowRect.height() * (config.cornerRadiusPercent / 100f),

            config.cornerSmoothing,

        )

        combinedCanvas.save()

        combinedCanvas.clipPath(selectionPath)

        combinedCanvas.drawRenderNode(tabsBackdropNode)



        fillPaint.color = surfaceContainerColor(dark)

        combinedCanvas.drawPath(selectionPath, fillPaint)



        // InteractiveHighlight is part of the captured hidden row as well.

        combinedCanvas.save()

        combinedCanvas.translate(padding, padding)

        drawInteractiveHighlight(combinedCanvas)



        if (includeNavigation) {

            drawingHiddenNavigationForOptics = targetAdapter?.usesNavigationOpticalMix == true

            if (targetAdapter?.drawsNavigationInOptics == true ||

                (context.packageName == QQ_PACKAGE && hasQqStaticIcons())

            ) {

                // WeChat's native TabIconView already supplies the correct selected

                // green while its separate badge Views remain red. Tinting the

                // whole row with SRC_IN also recolours those badges, so preserve

                // the native pixels as an indivisible icon/label/badge composition.

                if (targetAdapter?.usesNavigationOpticalMix == true) {
                    val opticalMix = navigationOpticalMix()
                    if (opticalMix > 0f) {
                        val layer = combinedCanvas.saveLayerAlpha(
                            0f, 0f, width.toFloat(), height.toFloat(),
                            (opticalMix * 255f).roundToInt().coerceIn(0, 255),
                        )
                        drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)
                        combinedCanvas.restoreToCount(layer)
                    }
                } else {
                    drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)
                }

            } else {

                hiddenTabsTintPaint.colorFilter = BlendModeColorFilter(

                    selectedNavigationAccent(),

                    BlendMode.SRC_IN,

                )

                val tintLayer = combinedCanvas.saveLayer(

                    0f,

                    0f,

                    width.toFloat(),

                    height.toFloat(),

                    hiddenTabsTintPaint,

                )

                drawScaledNavigationSource(combinedCanvas, 1f + 0.2f * pressProgress)

                combinedCanvas.restoreToCount(tintLayer)

                appNavigationState?.drawNavigationSnapshotOverlay(
                    combinedCanvas,
                    adapterNavigationFrame(1f + 0.2f * pressProgress),
                    navigationSnapshot,
                )

            }

            drawingHiddenNavigationForOptics = false

        }

        combinedCanvas.restore()

        combinedCanvas.restore()

    }



    private val transparentDrawable by lazy {

        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

    }






    private fun stabilizeLiftedHostPosition() {

        if (targetAdapter?.hostLiftDp == null) return

        val content = parent as? View ?: return

        val params = layoutParams as? FrameLayout.LayoutParams ?: return

        // Mirror mi_health geometry exactly. The bottom offset is kept in the

        // current parent coordinate space while the side inset always remains

        // screen-relative, including the first frame of inset convergence.

        // WeChat lift, shared with the initial layout and side insets.

        val wanted = glassHostBottomMarginPx(content, config) + ((targetAdapter?.hostLiftDp ?: 0f) * density).toInt()

        // With fewer than four tabs the bar keeps the 4-slot per-slot width

        // (slider length unchanged) and simply shortens, staying centred.

        val wantedWidth = symmetricGlassHostWidthPx(

            content,

            density,

            config,

            visualSlotCount(),

        )

        if (params.bottomMargin != wanted || params.width != wantedWidth) {

            params.bottomMargin = wanted

            params.width = wantedWidth

            layoutParams = params

            requestLayout()

        }

    }








    /**

     * JD's reselect contract (Home -> scroll to top and icon press animation)

     * is implemented in its touch pipeline, not in the plain OnClickListener.

     * Deliver a complete local gesture to the original hidden tab view.

     */

    private fun adapterNavigationSurfaceColor(): Int {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return targetAdapter?.navigationSurfaceColor(context.findActivity(), navigationSource, dark)
            ?: Color.TRANSPARENT
    }





























    private fun drawSharedOpticalBackdrop(canvas: Canvas, destination: RectF) {

        val effect = sharedOpticalBlurEffect

        if (!reuseOpticalBlur || !config.liquidGlassEnabled || effect == null || !canvas.isHardwareAccelerated) {

            drawBackdropContent(canvas, destination)

            return

        }

        val w = destination.width().roundToInt().coerceAtLeast(1)

        val h = destination.height().roundToInt().coerceAtLeast(1)

        val currentBackdrop = backdrop

        val needsRecord = appNavigationState?.opticalBlurNodeNeedsRecord(
            sharedOpticalBlurPrepared, currentBackdrop, w, h, effect,
        ) ?: !sharedOpticalBlurPrepared

        if (needsRecord) {

            val recording = sharedOpticalBlurNode.beginRecording(w, h)

            drawBackdropContent(recording, destination)

            sharedOpticalBlurNode.endRecording()

            sharedOpticalBlurNode.setPosition(0, 0, w, h)

            sharedOpticalBlurNode.setRenderEffect(effect)

            sharedOpticalBlurPrepared = true

            appNavigationState?.onOpticalBlurNodeRecorded(currentBackdrop, w, h, effect)

        }

        canvas.drawRenderNode(sharedOpticalBlurNode)

    }



    /**

     * The checkout strip used to be lifted by the 400 ms probe only, so the

     * first frames after entering the cart page drew it at JD's native anchor

     * (behind the glass) before it jumped up. To kill the jump, detection and

     * correction now run BETWEEN layout and draw: a global-layout listener on

     * the window root fires right after RN lays the strip out, finds it and

     * offsets it in the same frame �?the wrong position is never drawn. A

     * cheap per-preDraw corrector (two getLocationInWindow calls) keeps the

     * offset alive across RN re-renders that reset it.

     */

    internal fun onAdapterContentScroll() {
        appNavigationState?.onContentScroll()
    }

    internal fun resetCompositorSamplingCadence() {
        compositorSurfaceStaticStreak = 0
        compositorSurfaceVsyncTick = 0
    }



    /**

     * New Products owns a custom full-page renderer which can restore its own

     * measured height after every feed update. Re-applying our content-spine

     * layout params from every pre-draw creates a layout tug-of-war, while

     * referencing the texture-backed scene from a second RenderNode can make

     * whole tiles disappear. Detection is cached in steady state; this page

     * keeps the extended outer viewport but uses compositor-native blur.

     */








    /**

     * The flash ("秒�?) page's two floating controls, identified from a live

     * view-tree dump:

     *

     *  - Coupon banner: `HourlyGoScreenBottomBarView` [0,2586 1220x117] �?

     *    full-width strip pinned at the screen bottom (bottom edge runs 47 px

     *    offscreen), hosting the coupon copy and the 去使�?button. It sits as

     *    a direct child of HourlyGoMvpFragmentView, OUTSIDE the feed's

     *    RecyclerView. Matched by class name (JD-private, unambiguous) with a

     *    full-width bottom-strip shape fallback.

     *

     *  - Floating cart: a 130x130 SimpleDraweeView at (1064,2378) inside a

     *    130x143 ConstraintLayout �?the square right-edge FAB, half-covered

     *    by the glass. Matched as a near-square right-edge image button

     *    outside every scroll container; its direct parent container is what

     *    gets lifted (keeps the badge and touch area together).

     */

    /**

     * Weibo recreates its navigation hierarchy while a page is settling.  The

     * source row is therefore used only as a controller and badge provider;

     * copying any of its artwork allows feed cards, avatar strips and Weibo's

     * blue selected disk to leak into the retained bitmap after repeated drags.

     */
    private val configSyncReceiver = object : android.content.BroadcastReceiver() {

        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {

            if (intent?.action != io.github.offlineglass.config.ConfigContract.ACTION_CONFIG_RESPONSE) return

            val extras = intent.extras ?: return

            val packageName = extras.getString(io.github.offlineglass.config.ConfigContract.KEY_PACKAGE).orEmpty()

            if (packageName != context.packageName) return

            val pushed = GlassConfig.fromBundle(extras)

            HookConfigReader.acceptPushedConfig(packageName, pushed)

            val updated = packageConfig(pushed)

            if (updated != config) applyLiveConfig(updated)

        }

    }



    private fun registerConfigSyncBroadcast() {

        if (configSyncBroadcastRegistered) return

        runCatching {

            context.registerReceiver(

                configSyncReceiver,

                android.content.IntentFilter(

                    io.github.offlineglass.config.ConfigContract.ACTION_CONFIG_RESPONSE,

                ),

                io.github.offlineglass.config.ConfigContract.CONFIG_SYNC_PERMISSION,

                null,

                Context.RECEIVER_EXPORTED,

            )

            configSyncBroadcastRegistered = true

            context.sendBroadcast(

                android.content.Intent(

                    io.github.offlineglass.config.ConfigContract.ACTION_CONFIG_REQUEST,

                )

                    .setComponent(

                        android.content.ComponentName(

                            MODULE_PACKAGE,

                            "$MODULE_PACKAGE.config.ConfigRequestReceiver",

                        ),

                    )

                    .putExtra(

                        io.github.offlineglass.config.ConfigContract.EXTRA_REQUEST_PACKAGE,

                        context.packageName,

                    ),

            )

        }.onFailure {

            XposedBridge.log("[OfflineGlass][ConfigSync] register/request failed: $it")

        }

    }



    private fun unregisterConfigSyncBroadcast() {

        runCatching { context.unregisterReceiver(configSyncReceiver) }

        configSyncBroadcastRegistered = false

    }



    /**

     * Keep Weibo's native tab row alive as the page/selection controller, but

     * remove its artwork in one non-destructive operation.  Do not walk into

     * the row and disable click/focus state: Weibo rebuilds that state while

     * leaving/returning from Video and LiquidTab relies on it to restore the

     * floating bar immediately.

     */
    /** Keep Weibo's independent reward envelope above the floating bar. */
    private fun suppressNativeMeituanTakeoutBottomBar(source: ViewGroup?) {

        if (context.packageName != MEITUAN_TAKEOUT_PACKAGE || source == null) return

        // The app's native scroll-show can fade the TitleIndicator back in on

        // the order/profile pages; install-time alpha=0 is never re-asserted

        // elsewhere, so enforce it every pass like the other adapters.

        if (source.alpha != 0f) source.alpha = 0f

        // Hierarchy (from uiautomator dump):

        //   content

        //   ├── pager_container                       [full width, stops above the bar]

        //   ├── navigation_bar_margin_layout_container [opaque native strip]

        //   ├── indicator_bg_container                [TitleIndicator + tab_line]

        //   └── GlassHostLayout (this host)

        // 1) Make every wrapper between TitleIndicator and the activity content

        //    transparent. These strips only span the bottom band, so clearing

        //    them never touches page content.

        var node: ViewGroup? = source.parent as? ViewGroup

        var guard = 0

        while (node != null && guard++ < 6 && node.id != android.R.id.content) {

            if (node.background != null) {

                meituanTakeoutChainRestoreCount++

                node.background = null

            }

            if (node.backgroundTintList != null) node.backgroundTintList = null

            if (node.foreground != null) node.foreground = null

            // Sibling decoration layers (tab_line, home indicator paints) that

            // live inside the same strip container.

            for (index in 0 until node.childCount) {

                val layer = node.getChildAt(index)

                if (layer === source || layer === this) continue

                if (layer.background != null) layer.background = null

                if (layer.foreground != null) layer.foreground = null

            }

            node = node.parent as? ViewGroup

        }

        // 2) Extend the page pager to the window bottom. Meituan Takeout

        //    reserves the native bar's height inside pager_container, so once

        //    the native strip is transparent the reserved band exposes the

        //    window background ("white residual bar" over the feed). Restore

        //    full-bleed like the Meituan main app's id="main" treatment.

        val content = node as? ViewGroup ?: (source.rootView.findViewById<ViewGroup>(android.R.id.content))

        if (content != null) {

            for (index in 0 until content.childCount) {

                val child = content.getChildAt(index)

                if (child === this || child === source) continue

                val idName = runCatching {

                    if (child.id == View.NO_ID) null else child.resources.getResourceEntryName(child.id)

                }.getOrNull() ?: continue

                if (idName != "pager_container") continue

                val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue

                var changed = false

                if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {

                    params.height = ViewGroup.LayoutParams.MATCH_PARENT

                    changed = true

                    meituanTakeoutPagerRestoreCount++

                }

                if (params.bottomMargin != 0) {

                    params.bottomMargin = 0

                    changed = true

                }

                if (changed) {

                    child.layoutParams = params

                    child.requestLayout()

                    content.requestLayout()

                }

                break

            }

        }

        // 3) The white residual band is painted by indicator_bg_container, a

        //    sibling branch under content (it hosts the tab_line hairline; the

        //    TitleIndicator itself lives under navigation_bar_margin_layout_

        //    container). The parent-chain walk above never reaches it.

        //    On the promotion tabs (神券/活动) the app re-applies the strip's

        //    background every few frames, so per-frame clearing raced with the

        //    re-apply and produced a flickering white band. Make the container

        //    INVISIBLE instead: an invisible view never paints, no matter how

        //    often the app swaps its background. Clicks are unaffected - the

        //    glass host sits above it and forwards synthesized touches to the

        //    TitleIndicator itself.

        if (content != null) {

            for (index in 0 until content.childCount) {

                val sibling = content.getChildAt(index)

                if (sibling === this || sibling === source) continue

                if (meituanTakeoutIdName(sibling) != "indicator_bg_container") continue

                meituanTakeoutIndicatorBg = sibling

                if (sibling.visibility == View.VISIBLE) {

                    meituanTakeoutBgRestoreCount++

                    sibling.visibility = View.INVISIBLE

                }

                if (sibling is ViewGroup) {

                    for (childIndex in 0 until sibling.childCount) {

                        val inner = sibling.getChildAt(childIndex)

                        if (inner !== source && meituanTakeoutIdName(inner) == "tab_line" &&

                            inner.visibility == View.VISIBLE

                        ) {

                            meituanTakeoutTabLineRestoreCount++

                            inner.visibility = View.INVISIBLE

                        }

                    }

                }

            }

        }

        val now = SystemClock.uptimeMillis()

        if (now - meituanTakeoutSuppressDiagAt > 10_000L) {

            meituanTakeoutSuppressDiagAt = now

            logMeituanTakeoutSuppressDiag(source)

        }

    }



    private var meituanTakeoutSuppressDiagAt = 0L



    private var meituanTakeoutBgRestoreCount = 0

    private var meituanTakeoutTabLineRestoreCount = 0

    private var meituanTakeoutChainRestoreCount = 0

    private var meituanTakeoutPagerRestoreCount = 0

    private var meituanTakeoutIndicatorBg: View? = null

    private var compositorSurfaceProbeScheduled = false

    private var compositorSurfaceProbeRunning = false

    private var compositorSurfaceVsyncTick = 0

    private var compositorSurfaceStaticStreak = 0

    private var compositorSurfaceCopyCount = 0

    private var compositorSurfaceRenderCount = 0

    private var compositorSurfacePerfLogAt = 0L

    private var lastSurfaceRenderDark: Boolean? = null



    /**

     * Single vsync-aligned driver for compositor-readback backdrops (Meituan

     * Takeout's tabs and other adapter-selected pages). The former pipeline

     * requeued an unconditional

     * PixelCopy on every animation frame, which taxed the compositor and

     * dropped frames. Here the copy itself is demand-scheduled: every vsync

     * while the strip keeps changing, and one cheap probe every 8th vsync

     * once the page has been still for a dozen samples. Motion therefore

     * samples at full display rate �?the glass refraction tracks the feed's

     * own frame rate instead of stepping at half rate during fast scrolls �?

     * while still pages fall back to the deep 8-vsync probe stride. The

     * in-flight copy guard self-throttles the cadence whenever the GPU

     * readback cannot keep up, so cost never exceeds what the pipeline can

     * actually sustain. Touch input and bar motion pin the fast cadence so

     * scrolling never resumes on a stale backdrop. Bar animations never wait

     * on copies �?dispatchDraw's surface-render branch repaints at full frame

     * rate from the cached backdrop bitmap.

     */

    private fun scheduleCompositorSurfaceProbe() {

        if (compositorSurfaceProbeScheduled) return

        compositorSurfaceProbeScheduled = true

        if (!compositorSurfaceProbeRunning) {

            compositorSurfaceProbeRunning = true

            android.util.Log.i(

                "WmGlassDiag",

                "surfaceProbe: start pkg=" + context.packageName + " tab=" + selectedIndex,

            )

        }

        postOnAnimation {

            compositorSurfaceProbeScheduled = false

            if (!opticalSurfaceSurfacePipelineActive &&
                appNavigationState?.maintainsCompositorSamplingWithoutSurface() != true
            ) {

                compositorSurfaceProbeRunning = false

                // An adapter may leave the pipeline active after a tab switch.
                // Without this reset the re-entered tab's first

                // copy would wait out the stale 8-vsync backoff stride.

                compositorSurfaceStaticStreak = 0

                compositorSurfaceVsyncTick = 0

                return@postOnAnimation

            }

            requestOpticalSurfaceRootSurfaceFrame()

            val now = SystemClock.uptimeMillis()

            if (now - compositorSurfacePerfLogAt > 5_000L) {

                compositorSurfacePerfLogAt = now

                android.util.Log.i(

                    "WmGlassDiag",

                    "surfacePerf pkg=" + context.packageName +

                        " copies=" + compositorSurfaceCopyCount +

                        " renders=" + compositorSurfaceRenderCount +

                        " streak=" + compositorSurfaceStaticStreak,

                )

            }

            scheduleCompositorSurfaceProbe()

        }

    }



    private fun compositorSurfaceCopyDue(): Boolean {
        compositorSurfaceVsyncTick++
        val backoff = compositorSurfaceStaticStreak >= 12 &&
            !touching && !isBarVisualStateAnimating() &&
            SystemClock.uptimeMillis() >= (appNavigationState?.contentMotionHoldUntil() ?: 0L)
        val stride = if (backoff) {
            // Step down gradually: the first backoff tier halves the wake-up
            // interval so a scroll that starts right after the page settles is
            // detected within ~2 vsyncs instead of averaging 4-8. Long-idle
            // pages still settle to the same deep stride as before, so the
            // steady-state cost is unchanged.
            if (compositorSurfaceStaticStreak >= 36) 8 else 4
        } else 1
        return compositorSurfaceVsyncTick % stride == 0
    }


    /** Throttled dump of the native bar chain: pinpoints which layer still

     *  paints the white residual strip when background clearing is not enough. */

    private fun logMeituanTakeoutSuppressDiag(source: ViewGroup) {

        val sb = StringBuilder("suppressDiag source=").append(source.javaClass.simpleName)

        var node: ViewGroup? = source.parent as? ViewGroup

        var guard = 0

        while (node != null && guard++ < 6 && node.id != android.R.id.content) {

            sb.append(" up").append(guard)

                .append("=").append(node.javaClass.simpleName)

                .append("/id=").append(meituanTakeoutIdName(node))

                .append("/").append(node.width).append("x").append(node.height)

                .append("@y").append(node.top)

                .append("/bg=").append(node.background?.javaClass?.simpleName ?: "null")

                .append("/fg=").append(node.foreground?.javaClass?.simpleName ?: "null")

                .append("/alpha=").append(node.alpha)

            node = node.parent as? ViewGroup

        }

        val content = source.rootView.findViewById<ViewGroup>(android.R.id.content)

        if (content != null) {

            sb.append(" contentChildren=").append(content.childCount)

            for (index in 0 until content.childCount) {

                val child = content.getChildAt(index)

                sb.append(" c").append(index)

                    .append("=").append(child.javaClass.simpleName)

                    .append("/id=").append(meituanTakeoutIdName(child))

                    .append("/").append(child.width).append("x").append(child.height)

                    .append("@y").append(child.top)

                    .append("/bg=").append(child.background?.javaClass?.simpleName ?: "null")

                    .append("/vis=").append(child.visibility)

                    .append("/alpha=").append(child.alpha)

            }

        }

        sb.append(" restoreCounters bg=").append(meituanTakeoutBgRestoreCount)

            .append(" tabLine=").append(meituanTakeoutTabLineRestoreCount)

            .append(" chain=").append(meituanTakeoutChainRestoreCount)

            .append(" pager=").append(meituanTakeoutPagerRestoreCount)

            .append(" indicatorBgVis=").append(meituanTakeoutIndicatorBg?.visibility ?: -1)

        android.util.Log.i("WmGlassDiag", sb.toString())

    }



    private fun meituanTakeoutIdName(view: View): String = runCatching {

        if (view.id == View.NO_ID) "none" else view.resources.getResourceEntryName(view.id)

    }.getOrDefault("err")

    /** Adapter entry point for the shared optical surface renderer. */
    internal fun updateOpticalSurfacePipeline(enabled: Boolean) {
        val renderBackdrop = enabled && !config.solidBarEnabled && config.backdropCapture
        updateOpticalSurfaceSurfaceRenderer(renderBackdrop)
        updateOpticalSurfaceNativeBlur(renderBackdrop && !opticalSurfaceSurfacePipelineActive)
    }



    private fun adjustMeituanTakeoutScrollSafety() {

        if (context.packageName != MEITUAN_TAKEOUT_PACKAGE || width <= 0 || height <= 0) return

        val onProfile = selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX

        val now = SystemClock.uptimeMillis()

        if (onProfile && now - lastMeituanTakeoutScrollProbe < MEITUAN_TAKEOUT_SCROLL_PROBE_INTERVAL_MS) return

        lastMeituanTakeoutScrollProbe = now



        if (!onProfile) {

            for ((view, base) in meituanTakeoutScrollBasePaddings) {

                if (!view.isAttachedToWindow) continue

                if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                    view.paddingRight != base[2] || view.paddingBottom != base[3]

                ) {

                    view.setPadding(base[0], base[1], base[2], base[3])

                    view.requestLayout()

                }

            }

            return

        }



        // While the bar is scroll-hidden on this page, keep the last applied

        // padding so the layout does not jump when the bar reappears.

        val hostRect = Rect()

        if (!getGlobalVisibleRect(hostRect)) return

        val gap = globalGlassBottomGapPx(this)

        val wantedItemBottom = hostRect.top - gap



        val candidates = ArrayList<ViewGroup>()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += (rootView as? ViewGroup ?: return) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view === this || view === navigationSource) continue

            if (view is ViewGroup) {

                val className = view.javaClass.name

                val scrollLike = className.contains("ScrollView") ||

                    className.contains("RecyclerView") ||

                    className.contains("NestedScroll")

                if (scrollLike && view.visibility == View.VISIBLE) {

                    val rect = Rect()

                    if (view.getGlobalVisibleRect(rect) && rect.top < hostRect.top &&

                        rect.bottom > hostRect.top

                    ) {

                        if (candidates.none { isDescendantOf(view, it) }) {

                            candidates += view

                        }

                    }

                }

                if (depth < 28) {

                    for (index in view.childCount - 1 downTo 0) {

                        stack += view.getChildAt(index) to depth + 1

                    }

                }

            }

        }



        val active = HashSet<View>(candidates)

        for ((view, base) in meituanTakeoutScrollBasePaddings) {

            if (!view.isAttachedToWindow || view in active) continue

            if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                view.paddingRight != base[2] || view.paddingBottom != base[3]

            ) {

                view.setPadding(base[0], base[1], base[2], base[3])

                view.requestLayout()

            }

        }

        for (view in candidates) {

            val rect = Rect()

            if (!view.getGlobalVisibleRect(rect)) continue

            val base = meituanTakeoutScrollBasePaddings.getOrPut(view) {

                intArrayOf(

                    view.paddingLeft,

                    view.paddingTop,

                    view.paddingRight,

                    view.paddingBottom,

                )

            }

            val required = (rect.bottom - wantedItemBottom).coerceAtLeast(base[3])

            if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                view.paddingRight != base[2] || view.paddingBottom != required

            ) {

                view.setPadding(base[0], base[1], base[2], required)

                view.clipToPadding = false

                view.requestLayout()

            }

        }

    }



    internal fun viewResourceEntryName(view: View): String? = runCatching {

        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)

    }.getOrNull()

    internal fun findViewByClassName(root: View, className: String): View? {
        if (root.javaClass.name.endsWith(className)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewByClassName(root.getChildAt(index), className)?.let { return it }
            }
        }
        return null
    }

    internal fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }



    internal fun findScrollableList(root: ViewGroup): View? {

        // Match by class name (handles subclasses like WBRecyclerView, etc.)

        val cn = root.javaClass.name

        val sn = root.javaClass.simpleName

        if (sn == "RecyclerView" || sn == "ListView" || sn == "AbsListView" ||

            cn.contains("RecyclerView") || cn.contains("AbsListView") ||

            cn.contains("ListView") || cn.contains("ScrollableList")

        ) return root

        for (i in 0 until root.childCount) {

            val child = root.getChildAt(i) ?: continue

            if (child is ViewGroup) {

                val found = findScrollableList(child)

                if (found != null) return found

            }

        }

        return null

    }



    /**

     * Render the liquid bar in an independent Surface. PixelCopy can then read

     * the application's root surface without recursively copying the glass or

     * asking a custom page renderer to produce a second display list.

     */

    private fun updateOpticalSurfaceSurfaceRenderer(enabled: Boolean) {

        if (targetAdapter?.usesOpticalSurfacePipeline != true && targetAdapter?.usesDirectOpticalBackdropMode != true &&

            context.packageName != MEITUAN_TAKEOUT_PACKAGE

        ) return

        val compositorEnabled = enabled && !config.solidBarEnabled && config.backdropCapture &&
            (targetAdapter?.keepsOpticalSurfaceDuringBarAnimation == true || !barVisibilityAnimating)

        val content = parent as? FrameLayout ?: return

        var surface = opticalSurfaceSurfaceView

        if (!compositorEnabled && surface == null) return

        if (surface == null) {

            surface = SurfaceView(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                setZOrderOnTop(true)

                holder.setFormat(PixelFormat.TRANSLUCENT)

                visibility = View.GONE

                holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {

                        opticalSurfaceSurfaceReady = true

                        if (wantsRootSurfaceOpticalPipeline() &&
                            (targetAdapter?.keepsOpticalSurfaceDuringBarAnimation == true || !barVisibilityAnimating) &&
                            appNavigationState?.directOpticalBackdropActive() != true

                        ) {

                            appNavigationState?.onOpticalSurfaceFrameInvalidated()

                            opticalSurfaceSurfacePipelineActive = true

                            visibility = View.VISIBLE

                            updateOpticalSurfaceNativeBlur(false)

                            if (usesCompositorSurfaceProbe()) {

                                scheduleCompositorSurfaceProbe()

                            } else {

                                requestOpticalSurfaceRootSurfaceFrame()

                            }

                        }

                    }



                    override fun surfaceChanged(

                        holder: SurfaceHolder,

                        format: Int,

                        width: Int,

                        height: Int,

                    ) {

                        if (opticalSurfaceSurfacePipelineActive) {

                            requestOpticalSurfaceRootSurfaceFrame()

                        }

                    }



                    override fun surfaceDestroyed(holder: SurfaceHolder) {

                        opticalSurfaceSurfaceReady = false

                        opticalSurfaceSurfacePipelineActive = false

                        appNavigationState?.onOpticalSurfaceFrameInvalidated()

                        opticalSurfaceRootCopyInFlight = false

                    }

                })

            }

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(surface, hostIndex, copyHostLayoutParams())

            opticalSurfaceSurfaceView = surface

        }

        syncOpticalSurfaceSurfaceLayout(surface)

        if (compositorEnabled) {

            appNavigationState?.onOpticalSurfacePipelineEnabled(opticalSurfaceSurfacePipelineActive)

            surface.visibility = View.VISIBLE

            if (opticalSurfaceSurfaceReady) {

                appNavigationState?.onOpticalSurfacePipelineEnabled(opticalSurfaceSurfacePipelineActive)

                opticalSurfaceSurfacePipelineActive = true

                updateOpticalSurfaceNativeBlur(false)

                if (usesCompositorSurfaceProbe()) {

                    // The probe loop is the single copy driver; requesting

                    // directly here as well would double-count the vsync tick

                    // and break the cadence stride.

                    scheduleCompositorSurfaceProbe()

                } else {

                    requestOpticalSurfaceRootSurfaceFrame()

                }

            }

        } else {

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {
                if (appNavigationState?.enterDirectOpticalBackdrop(
                        surface, opticalSurfaceSurfacePipelineActive,
                    ) != true) return
                opticalSurfaceSurfacePipelineActive = false
                opticalSurfaceRootCopyInFlight = false
                return

            }

            if (!opticalSurfaceSurfacePipelineActive && surface.visibility == View.GONE) return

            opticalSurfaceSurfacePipelineActive = false

            opticalSurfaceRootCopyInFlight = false

            surface.visibility = View.GONE

            surface.holder.surface.takeIf(Surface::isValid)?.let {

                runCatching {

                    val canvas = surface.holder.lockCanvas()

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    surface.holder.unlockCanvasAndPost(canvas)

                }

            }

        }

    }



    private fun syncOpticalSurfaceSurfaceLayout(surface: SurfaceView) {

        val host = layoutParams as? FrameLayout.LayoutParams ?: return

        val target = surface.layoutParams as? FrameLayout.LayoutParams ?: copyHostLayoutParams()

        val overflow = opticalSurfaceSurfaceOverflowPx()

        if (targetAdapter?.ownsOpticalSurfaceLayout == true) {
            val content = parent as? FrameLayout ?: return
            val layout = targetAdapter?.opticalSurfaceLayout(
                content.width, content.height, left, top, width, height, overflow,
            ) ?: return
            if (target.width == layout.width && target.height == layout.height &&
                target.leftMargin == layout.left && target.topMargin == layout.top &&
                target.rightMargin == layout.right && target.bottomMargin == layout.bottom &&
                target.gravity == layout.gravity
            ) return
            target.width = layout.width
            target.height = layout.height
            target.gravity = layout.gravity
            target.leftMargin = layout.left
            target.topMargin = layout.top
            target.rightMargin = layout.right
            target.bottomMargin = layout.bottom
            surface.layoutParams = target
            return
        }

        val hostWidth = if (host.width > 0) host.width else width.coerceAtLeast(1)

        val hostHeight = if (host.height > 0) host.height else height.coerceAtLeast(1)

        val targetWidth = hostWidth + overflow * 2

        val targetHeight = hostHeight + overflow * 2

        val targetBottomMargin = host.bottomMargin - overflow

        if (target.width == targetWidth && target.height == targetHeight &&

            target.gravity == host.gravity && target.leftMargin == host.leftMargin &&

            target.topMargin == host.topMargin && target.rightMargin == host.rightMargin &&

            target.bottomMargin == targetBottomMargin

        ) return

        target.width = targetWidth

        target.height = targetHeight

        target.gravity = host.gravity

        target.leftMargin = host.leftMargin

        target.topMargin = host.topMargin

        target.rightMargin = host.rightMargin

        // The bar is bottom/center anchored. Expanding around its centre and

        // relaxing the bottom margin leaves equal overflow on every edge.

        target.bottomMargin = targetBottomMargin

        surface.layoutParams = target

    }



    private fun opticalSurfaceSurfaceOverflowPx(): Int =

        (32f * density).roundToInt()



    private fun requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit: Boolean = false) {
        if (config.solidBarEnabled || !config.backdropCapture) return
        if (SystemClock.uptimeMillis() < barAnimationPriorityUntil) return
        if (appNavigationState?.requiresSampledBackdrop() == false) return
        // A ready adapter-owned Surface frame already supplies the glass pixels.
        // Root PixelCopy cannot include that layer and would compete for no gain.
        if (appNavigationState?.needsSurfaceBackdrop() == true &&
            appNavigationState?.videoBackdrop()?.bitmap?.let { !it.isRecycled } == true
        ) return
        if (!opticalSurfaceSurfacePipelineActive || !opticalSurfaceSurfaceReady ||
            opticalSurfaceRootCopyInFlight || width <= 0 || height <= 0
        ) return
        if (!afterFrameCommit && usesCompositorSurfaceProbe() && !compositorSurfaceCopyDue()) return
        val root = rootView
        if (!afterFrameCommit && appNavigationState?.scheduleOpticalSurfaceCopy(root) {
                requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
            } == true) return
        val viewRoot = runCatching {
            XposedHelpers.callMethod(root, "getViewRootImpl")
        }.getOrNull() ?: return
        val sourceSurface = readPrivateField(viewRoot, "mSurface")
            as? Surface ?: return
        if (!sourceSurface.isValid) return

        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val hostLocation = IntArray(2).also(::getLocationInWindow)
        val localLeft = hostLocation[0] - rootLocation[0]
        val localTop = hostLocation[1] - rootLocation[1]
        val desiredPadding = 24f * density
        val availablePadding = minOf(
            desiredPadding,
            localLeft.toFloat(),
            localTop.toFloat(),
            (root.width - localLeft - width).toFloat(),
            (root.height - localTop - height).toFloat(),
        ).coerceAtLeast(0f)
        if (abs(availablePadding - opticalSurfaceBackdropPadding) >= 1f) {
            opticalSurfaceBackdropPadding = availablePadding
            configureGlassEffects()
        }
        val padding = availablePadding.roundToInt()
        val captureGeometry = targetAdapter?.opticalCaptureGeometry(
            root.width, root.height, localLeft, localTop, width, height, padding,
        )
        if (targetAdapter?.usesDirectOpticalBackdropMode == true && captureGeometry == null) return
        val sourceRect = captureGeometry?.sourceRect ?: Rect(
            localLeft - padding,
            localTop - padding,
            localLeft + width + padding,
            localTop + height + padding,
        )
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return
        val targetWidth = captureGeometry?.targetWidth
            ?: max(1, (sourceRect.width() / CAPTURE_DOWNSCALE).roundToInt())
        val targetHeight = captureGeometry?.targetHeight
            ?: max(1, (sourceRect.height() / CAPTURE_DOWNSCALE).roundToInt())
        val requestedBarSource = captureGeometry?.barSource
        val current = backdrop
        val reusable = opticalSurfaceRootCopyScratch?.takeIf {
            !it.isRecycled && it !== current &&
                it.width == targetWidth && it.height == targetHeight
        }
        val target = reusable ?: Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888,
        )
        if (reusable != null) opticalSurfaceRootCopyScratch = null
        opticalSurfaceRootCopyInFlight = true
        // Adapter-selected pages deliver their copy
        // callbacks on a dedicated thread: the strip compare and the full
        // glass render used to run on the UI thread every vsync, competing
        // with the app's own feed rendering and reading as insufficient bar
        // frame rate during fast scrolls.
        val copyHandler = appNavigationState?.opticalSurfaceCopyHandler() ?: if (usesCompositorSurfaceProbe()) {
            compositorPixelCopyHandler ?: HandlerThread("LiquidTab-Compositor-PixelCopy").let { thread ->
                thread.start()
                compositorPixelCopyThread = thread
                Handler(thread.looper).also { compositorPixelCopyHandler = it }
            }
        } else {
            opticalSurfacePixelCopyHandler
        }
        runCatching {
            PixelCopy.request(
                sourceSurface,
                sourceRect,
                target,
                { result ->
                    var copySlotReleased = false
                    try {
                        glassPerfLogTick()
                        if (result == PixelCopy.SUCCESS && opticalSurfaceSurfacePipelineActive) {
                            val previous = backdrop
                            appNavigationState?.onOpticalBackdropCaptured(requestedBarSource)
                            backdrop = target
                            if (previous !== target) {
                                opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                                opticalSurfaceRootCopyScratch = previous
                            }
                            val stripChanged = previous == null || previous.isRecycled ||
                                !previous.sameAs(target)
                            if (context.packageName == MEITUAN_TAKEOUT_PACKAGE ||
                                targetAdapter?.countsOpticalCompositorCopies == true ||
                                targetAdapter?.usesOpticalSurfacePipeline == true
                            ) {
                                compositorSurfaceCopyCount++
                                if (stripChanged) {
                                    compositorSurfaceStaticStreak = 0
                                } else {
                                    compositorSurfaceStaticStreak++
                                }
                            }
                            if (stripChanged) appNavigationState?.onOpticalBackdropChanged()
                            if (appNavigationState?.overlapsOpticalCopyAndRender() == true) {
                                // The published target remains read-only. The next copy
                                // writes only the retired scratch buffer; callbacks and
                                // rendering remain serialized on the adapter worker.
                                copySlotReleased = true
                                opticalSurfaceRootCopyInFlight = false
                                appNavigationState?.onOpticalSurfaceCopyCompleted(root) {
                                    requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
                                }
                            }
                            val barAnimating = isBarVisualStateAnimating()
                            if (stripChanged || barAnimating || isDarkGlass() != lastSurfaceRenderDark) {
                                if (targetAdapter?.deferOpticalRenderDuringBarAnimation == true && barAnimating) {
                                    // During a bar gesture/transition the UI frame
                                    // pump owns Surface submission. The copy thread
                                    // only publishes the newest backdrop, avoiding
                                    // duplicate lock contention without dropping an
                                    // animation or optical update.
                                    postInvalidateOnAnimation()
                                } else {
                                    compositorSurfaceRenderCount++
                                    renderOpticalSurfaceSurface()
                                }
                            }
                        } else if (target !== backdrop) {
                            opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                            opticalSurfaceRootCopyScratch = target
                        }
                    } finally {
                        // An overlapped request may already own the slot. Do not
                        // clear its guard when the preceding render completes.
                        if (!copySlotReleased) opticalSurfaceRootCopyInFlight = false
                        if (!copySlotReleased && result == PixelCopy.SUCCESS && opticalSurfaceSurfacePipelineActive) {
                            appNavigationState?.onOpticalSurfaceCopyCompleted(root) {
                                requestOpticalSurfaceRootSurfaceFrame(afterFrameCommit = true)
                            }
                        }
                    }
                },
                copyHandler,
            )
        }.onFailure {
            opticalSurfaceRootCopyInFlight = false
            if (target !== backdrop) {
                opticalSurfaceRootCopyScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                opticalSurfaceRootCopyScratch = target
            }
        }
    }



    private fun readPrivateField(instance: Any, name: String): Any? {

        var type: Class<*>? = instance.javaClass

        while (type != null) {

            val field = runCatching { type.getDeclaredField(name) }.getOrNull()

            if (field != null) {

                return runCatching {

                    field.isAccessible = true

                    field.get(instance)

                }.getOrNull()

            }

            type = type.superclass

        }

        return null

    }



    /** True while any spring/press/visibility state still drives bar visuals. */

    private fun isBarVisualStateAnimating(): Boolean =

        touching || pressProgress > 0.001f || interactiveHighlightProgress > 0.001f ||

            barVisibilityAnimating || abs(panelDragOffset) > 0.001f ||

            abs(indicatorPosition - dragTarget) > 0.001f ||

            abs(indicatorScaleX - 1f) > 0.001f || abs(indicatorScaleY - 1f) > 0.001f


    internal fun queueOpticalSurfaceRender() {
        val renderHandler = appNavigationState?.opticalSurfaceCopyHandler() ?: compositorPixelCopyHandler
        if (appNavigationState?.queueOpticalSurfaceRender(
                renderHandler,
                active = { opticalSurfaceSurfacePipelineActive },
                render = ::renderOpticalSurfaceSurface,
            ) == true
        ) return
        fallbackOpticalSurfaceRenderQueue.request(
            renderHandler, active = { opticalSurfaceSurfacePipelineActive },
            render = ::renderOpticalSurfaceSurface,
        )
    }



    private fun renderOpticalSurfaceSurface() {

        if (!opticalSurfaceSurfacePipelineActive || !hasUsableBackdrop()) return

        val surface = opticalSurfaceSurfaceView ?: return

        if (!surface.holder.surface.isValid) return

        // Renders are invoked both from dispatchDraw (UI thread) and from the

        // PixelCopy callback (compositor thread for JD, Meituan Takeout, and

        // compositor-backed pages).

        // The shared RenderNodes must never be recorded concurrently.

        synchronized(surfaceRenderLock) {

            // JD's backdrop bitmap is immutable between PixelCopy deliveries.

            // Slider springs may render several frames against that same frame;

            // retain the already blurred optical node instead of re-running the

            // identical Gaussian pass for every animation frame.

            if (targetAdapter?.reuseSurfaceBlurDuringBarAnimation != true) sharedOpticalBlurPrepared = false

            perfSurfaceRenders++

            runCatching {

            val canvas = surface.holder.lockHardwareCanvas()

            try {

                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val contentAlignedOpticalSurface = targetAdapter?.usesContentAlignedOpticalSurface == true

                val overflow = opticalSurfaceSurfaceOverflowPx().toFloat()

                val surfaceParams = surface.layoutParams as? FrameLayout.LayoutParams

                val barOffsetX = if (contentAlignedOpticalSurface) {

                    (left - (surfaceParams?.leftMargin ?: surface.left)).toFloat()

                } else {

                    overflow

                }

                val barOffsetY = if (contentAlignedOpticalSurface) {

                    (top - (surfaceParams?.topMargin ?: surface.top)).toFloat()

                } else {

                    overflow

                }

                canvas.save()

                if (contentAlignedOpticalSurface) {

                    // Preserve the established scale/slide exit animation on

                    // the liquid bar in its content-aligned Surface coordinates.

                    val pivotX = barOffsetX + width * 0.5f

                    val pivotY = barOffsetY + height

                    canvas.translate(pivotX, pivotY + translationY)

                    canvas.scale(scaleX, scaleY)

                    canvas.translate(-pivotX, -pivotY)

                }

                canvas.translate(barOffsetX, barOffsetY)

                // JD follows system night mode directly. Walking its native

                // navigation hierarchy here cannot affect the result and used

                // to add periodic work to the compositor render thread.

                updateDetectedContentTheme()

                val dark = isDarkGlass()

                lastSurfaceRenderDark = dark

                drawGlassPanel(canvas, dark)

                if (targetAdapter?.navigationBehindIndicator == true) {

                    // The indicator's combined scene includes this same native

                    // artwork, so drawing the row first lets the liquid lens

                    // replace/refract it instead of leaving a sharp overlay.

                    drawNavigationSource(canvas)

                    drawSelection(canvas, dark)

                } else {

                    drawSelection(canvas, dark)

                    val customForeground = appNavigationState?.drawForeground(
                        canvas,
                        adapterNavigationFrame(1f),
                        foregroundNavigationClipPath(),
                    ) { drawNavigationSource(canvas) } == true
                    if (!customForeground) {
                        drawNavigationSource(canvas)
                    }

                }

                canvas.restore()

            } finally {

                surface.holder.unlockCanvasAndPost(canvas)

                if (appNavigationState?.onOpticalSurfaceFrameRendered() == true) postInvalidateOnAnimation()

            }

        }.onFailure { error ->

            Log.e(DIAGNOSTIC_TAG, "Optical surface render failed", error)

        }

        }

    }








    /**

     * Meco's regular WebView has no independently copyable Surface. HyperOS'

     * compositor blur is therefore kept as a rounded, non-interactive sibling

     * behind the glass only on the promotion tab. It samples the already

     * composed page in real time and never asks Meco to paint a second frame.

     */

    private fun updateOpticalSurfaceNativeBlur(enabled: Boolean) {

        if (targetAdapter?.usesNativeOpticalBlur != true) return

        val content = parent as? FrameLayout ?: return

        var blur = opticalSurfaceNativeBlurView

        if (blur == null) {

            blur = View(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                background = GradientDrawable().apply {

                    shape = GradientDrawable.RECTANGLE

                    cornerRadius = height * (config.cornerRadiusPercent / 100f)

                    setColor(Color.TRANSPARENT)

                }

                outlineProvider = BottomBarSquircleOutlineProvider(

                    radius = { outerCornerRadiusPx() },

                    smoothing = { config.cornerSmoothing },

                )

                clipToOutline = true

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                visibility = View.INVISIBLE

            }

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(blur, hostIndex, copyHostLayoutParams())

            opticalSurfaceNativeBlurView = blur

        }

        var indicator = opticalSurfaceNativeIndicatorView

        if (indicator == null) {

            indicator = View(context).apply {

                isClickable = false

                isFocusable = false

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                background = GradientDrawable().apply {

                    shape = GradientDrawable.RECTANGLE

                    cornerRadius = height * (config.cornerRadiusPercent / 100f)

                    setColor(Color.TRANSPARENT)

                }

                outlineProvider = BottomBarSquircleOutlineProvider(

                    radius = { height * (config.cornerRadiusPercent / 100f) },

                    smoothing = { config.cornerSmoothing },

                )

                clipToOutline = true

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                visibility = View.INVISIBLE

            }

            // The live indicator belongs above the full-bar compositor blur,

            // but below this host's static icons and lighting.

            val hostIndex = content.indexOfChild(this).coerceAtLeast(0)

            content.addView(

                indicator,

                hostIndex,

                FrameLayout.LayoutParams(1, 1).apply {

                    gravity = android.view.Gravity.NO_GRAVITY

                },

            )

            opticalSurfaceNativeIndicatorView = indicator

        }

        syncOpticalSurfaceBlurLayout(blur)

        appNavigationState?.syncNativeSurfaceOuterEffect(
            blur, config.liquidGlassEnabled, width, height, config.blurRadius,
            config.cornerRadiusPercent, density,
        ) { createRuntimeShader(ROUNDED_RECT_REFRACTION_SHADER, "adapter-native-outer-retry") }

        if (enabled && !opticalSurfaceNativeBlurActive) {

            opticalSurfaceNativeBlurActive = enableOpticalSurfaceNativeBlur(blur)

            blur.visibility = if (opticalSurfaceNativeBlurActive) View.VISIBLE else View.INVISIBLE

        } else if (!enabled && opticalSurfaceNativeBlurActive) {

            disableOpticalSurfaceNativeBlur(blur)

            opticalSurfaceNativeBlurActive = false

            blur.visibility = View.INVISIBLE

        }

        if (enabled && opticalSurfaceNativeBlurActive && !opticalSurfaceNativeIndicatorActive) {

            opticalSurfaceNativeIndicatorActive = enableOpticalSurfaceNativeBlur(indicator, 0.62f)

            indicator.visibility =

                if (opticalSurfaceNativeIndicatorActive) View.VISIBLE else View.INVISIBLE

        } else if ((!enabled || !opticalSurfaceNativeBlurActive) && opticalSurfaceNativeIndicatorActive) {

            disableOpticalSurfaceNativeBlur(indicator)

            indicator.setRenderEffect(null)

            opticalSurfaceNativeIndicatorActive = false

            opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

            indicator.visibility = View.INVISIBLE

        }

    }



    /**

     * Positions a second compositor-backed blur layer under the animated pill.

     * Unlike the old WebView bitmap, this layer is sampled by SurfaceFlinger on

     * every frame, so its refraction can never reveal historical page content.

     */

    private fun syncOpticalSurfaceNativeIndicator(

        centerX: Float,

        centerY: Float,

        baseHalfWidth: Float,

        baseHalfHeight: Float,

        scaleX: Float,

        scaleY: Float,

        radius: Float,

    ) {

        val indicator = opticalSurfaceNativeIndicatorView ?: return

        if (!usesOpticalSurfaceHybridBackdrop() || !opticalSurfaceNativeIndicatorActive) {

            indicator.visibility = View.INVISIBLE

            return

        }

        val baseWidth = (baseHalfWidth * 2f).roundToInt().coerceAtLeast(1)

        val baseHeight = (baseHalfHeight * 2f).roundToInt().coerceAtLeast(1)

        val params = indicator.layoutParams as? FrameLayout.LayoutParams ?: return

        if (params.width != baseWidth || params.height != baseHeight) {

            params.width = baseWidth

            params.height = baseHeight

            indicator.layoutParams = params

        }

        indicator.pivotX = baseWidth / 2f

        indicator.pivotY = baseHeight / 2f

        val hostPivotX = width * 0.5f

        val hostPivotY = height.toFloat()

        val transformedCenterX = hostPivotX + (centerX - hostPivotX) * this.scaleX

        val transformedCenterY = hostPivotY + (centerY - hostPivotY) * this.scaleY + translationY

        indicator.x = x + transformedCenterX - baseHalfWidth

        indicator.y = y + transformedCenterY - baseHalfHeight

        indicator.scaleX = scaleX * this.scaleX

        indicator.scaleY = scaleY * this.scaleY

        indicator.alpha = alpha

        indicator.visibility = View.VISIBLE



        if (!config.liquidGlassEnabled) {

            indicator.setRenderEffect(null)

            opticalSurfaceNativeIndicatorEffectSignature = Long.MIN_VALUE

            indicator.invalidate()

            return

        }

        val shader = opticalSurfaceNativeIndicatorShader ?: return

        val effectSignature =

            (baseWidth.toLong() shl 32) xor

                (baseHeight.toLong() shl 16) xor

                (pressProgress * 100f).roundToInt().toLong() xor

                ((radius * 100f).roundToInt().toLong() shl 8)

        if (effectSignature == opticalSurfaceNativeIndicatorEffectSignature) return

        opticalSurfaceNativeIndicatorEffectSignature = effectSignature

        val lensProgress = pressProgress.coerceAtLeast(0.72f)

        shader.setFloatUniform("size", baseWidth.toFloat(), baseHeight.toFloat())

        shader.setFloatUniform("offset", 0f, 0f)

        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)

        shader.setFloatUniform(

            "refractionHeight",

            lerp(5.5f, 8f, pressProgress) * density,

        )

        shader.setFloatUniform(

            "refractionAmount",

            -lerp(14f, 19f, pressProgress) * density * lensProgress,

        )

        shader.setFloatUniform("depthEffect", 1f)

        shader.setFloatUniform("chromaticAberration", 0.42f)

        indicator.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))

        indicator.invalidate()

    }



    /**

     * JD cannot expose its custom feed RenderNode to a second scene without

     * making cards flicker. Distort the compositor-native blur sheet itself;

     * this restores the same rounded edge refraction without touching JD's

     * display list or invoking root.draw().

     */




    private fun copyHostLayoutParams(): FrameLayout.LayoutParams {

        val source = layoutParams as? FrameLayout.LayoutParams

        return FrameLayout.LayoutParams(

            source?.width ?: width.coerceAtLeast(1),

            source?.height ?: height.coerceAtLeast(1),

        ).apply {

            gravity = source?.gravity ?: android.view.Gravity.NO_GRAVITY

            leftMargin = source?.leftMargin ?: 0

            topMargin = source?.topMargin ?: 0

            rightMargin = source?.rightMargin ?: 0

            bottomMargin = source?.bottomMargin ?: 0

        }

    }



    private fun syncOpticalSurfaceBlurLayout(blur: View) {

        val host = layoutParams as? FrameLayout.LayoutParams ?: return

        val target = blur.layoutParams as? FrameLayout.LayoutParams ?: copyHostLayoutParams()

        if (target.width == host.width && target.height == host.height &&

            target.gravity == host.gravity && target.leftMargin == host.leftMargin &&

            target.topMargin == host.topMargin && target.rightMargin == host.rightMargin &&

            target.bottomMargin == host.bottomMargin

        ) return

        target.width = host.width

        target.height = host.height

        target.gravity = host.gravity

        target.leftMargin = host.leftMargin

        target.topMargin = host.topMargin

        target.rightMargin = host.rightMargin

        target.bottomMargin = host.bottomMargin

        blur.layoutParams = target

    }



    private fun enableOpticalSurfaceNativeBlur(view: View, radiusScale: Float = 1f): Boolean {

        fun call(name: String, vararg args: Any): Boolean = runCatching {

            XposedHelpers.callMethod(view, name, *args)

            true

        }.getOrDefault(false)

        val baseRadius = if (isDarkGlass()) {

            (config.blurRadius * 4f).toInt().coerceIn(1, 36)

        } else {

            (config.blurRadius * 2.75f).toInt().coerceIn(1, 24)

        }

        val radius = (baseRadius * radiusScale).roundToInt().coerceAtLeast(1)

        val mode = call("setMiBackgroundBlurMode", 1)

        val blurRadius = call("setMiBackgroundBlurRadius", radius)

        val viewMode = call("setMiViewBlurMode", 1)

        runCatching { XposedHelpers.callMethod(view, "clearMiBackgroundBlendColor") }

        view.invalidate()

        return mode && blurRadius && viewMode

    }



internal fun disableOpticalSurfaceNativeBlur(view: View) {

        runCatching { XposedHelpers.callMethod(view, "clearMiBackgroundBlendColor") }

        runCatching { XposedHelpers.callMethod(view, "setMiBackgroundBlurRadius", 0) }

        runCatching { XposedHelpers.callMethod(view, "setMiViewBlurMode", 0) }

        runCatching { XposedHelpers.callMethod(view, "setMiBackgroundBlurMode", 0) }

    }



    /**

     * QQ rebuilds QQBlurViewWrapper after a light/dark switch. The installer

     * can only hide the instance that existed at install time, so keep probing

     * for the replacement and suppress only this QQ-owned bottom chrome.

     */

    private fun suppressNativeQqBottomChrome(source: ViewGroup?, forceProbe: Boolean = false) {

        if (context.packageName != QQ_PACKAGE || source == null) return

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

        source.backgroundTintList = null



        val iterator = qqRecreatedBottomChrome.iterator()

        while (iterator.hasNext()) {

            val view = iterator.next()

            if (!view.isAttachedToWindow) {

                iterator.remove()

            } else {

                view.visibility = View.INVISIBLE

                view.alpha = 0f

                view.background = null

                view.backgroundTintList = null

            }

        }



        val now = SystemClock.uptimeMillis()

        if (!forceProbe && now - lastQqBottomChromeProbe < QQ_BOTTOM_CHROME_PROBE_INTERVAL_MS) return

        lastQqBottomChromeProbe = now

        val root = rootView

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.javaClass.name == QQ_BOTTOM_BLUR_CLASS) {

                view.visibility = View.INVISIBLE

                view.alpha = 0f

                view.background = null

                view.backgroundTintList = null

                if (view !in qqRecreatedBottomChrome) qqRecreatedBottomChrome += view

            }

            if (view is ViewGroup && depth < 14) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

    }



    /** Static QQ/Bilibili renderers never expose their native animated row. */

    private fun suppressNativeRedrawnBottomBar(source: ViewGroup?) {

        if (source == null) return

        val usesStaticRenderer =

            (context.packageName == QQ_PACKAGE && hasQqStaticIcons()) ||

                targetAdapter?.ownsNavigationDrawing == true ||

                targetAdapter?.hideRedrawnNavigationSource == true ||

                context.packageName == MEITUAN_TAKEOUT_PACKAGE ||

                targetAdapter?.usesOpticalSurfacePipeline == true ||

                targetAdapter?.ownsNavigationDrawing == true

        if (!usesStaticRenderer) return

        if (appNavigationState?.suppressRedrawnSource() == false) return

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

    }



    /**

     * NetEase leaves a translucent/gradient shell behind its hidden native

     * NavigationTabLayout. Clear only the bottom-chrome-sized branch: making

     * the whole Activity ancestor transparent would also erase page surfaces.

     */











    private fun drawIndicatorInnerShadow(canvas: Canvas, radius: Float) {

        val progress = pressProgress.coerceIn(0f, 1f)

        if (progress <= 0.001f || !canvas.isHardwareAccelerated) return

        val blurRadius = 8f * density * progress

        selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

        runCatching {

            indicatorShadowNode.setPosition(0, 0, width, height)

            val recording = indicatorShadowNode.beginRecording(width, height)

            val layer = recording.saveLayer(selectionRect, null)

            lightingPaint.blendMode = BlendMode.SRC_OVER

            lightingPaint.color = Color.argb((0.15f * 255f).toInt(), 0, 0, 0)

            recording.drawPath(selectionPath, lightingPaint)

            recording.save()

            recording.translate(0f, blurRadius)

            lightingPaint.blendMode = BlendMode.CLEAR

            recording.drawPath(selectionPath, lightingPaint)

            recording.restore()

            recording.restoreToCount(layer)

            lightingPaint.blendMode = null

            indicatorShadowNode.endRecording()

            indicatorShadowNode.setAlpha(progress)

            if (innerShadowBlurRadius != blurRadius || innerShadowBlurEffect == null) {

                innerShadowBlurEffect = RenderEffect.createBlurEffect(

                    blurRadius, blurRadius, Shader.TileMode.DECAL,

                )

                innerShadowBlurRadius = blurRadius

            }

            indicatorShadowNode.setRenderEffect(

                innerShadowBlurEffect,

            )

            canvas.save()

            canvas.clipPath(selectionPath)

            canvas.drawRenderNode(indicatorShadowNode)

            canvas.restore()

        }

    }



    /**

     * Native selected-pill lighting pass.

     *

     * The reference draws four clipped rounded rectangles: a very faint white

     * lift, a soft black inset, then blue and pink additive caustics moving in

     * opposite directions as the pill is pressed.

     */

    private fun drawLiquidIndicatorLighting(canvas: Canvas, dark: Boolean, radius: Float) {

        val progress = pressProgress.coerceIn(0f, 1f)

        selectionPath.setBottomBarSquircle(selectionRect, radius, config.cornerSmoothing)

        canvas.save()

        canvas.clipPath(selectionPath)



        lightingPaint.shader = null

        lightingPaint.blendMode = BlendMode.PLUS

        val liftAlpha = if (dark) {

            0.012f + 0.024f * progress

        } else {

            0.008f + 0.022f * progress

        }

        lightingPaint.color = Color.argb((liftAlpha * 255f).toInt(), 255, 255, 255)

        canvas.drawPath(selectionPath, lightingPaint)



        // This is a property of the glass component, not an app adapter:

        // a light bar gets a light pill and a dark bar gets a dark pill.

        val blackAlpha = if (dark) {

            0.13f + 0.14f * progress

        } else {

            0.04f + 0.08f * progress

        }

        val blackBlur = lerp(0.55f, 0.9f, progress) * density

        lightingPaint.blendMode = BlendMode.SRC_OVER

        lightingPaint.color = Color.argb((blackAlpha * 255f).toInt(), 0, 0, 0)

        if (canvas.isHardwareAccelerated) {

            indicatorShadowNode.setPosition(0, 0, width, height)

            perfNodeRecords++

            val shadowCanvas = indicatorShadowNode.beginRecording(width, height)

            shadowCanvas.drawPath(selectionPath, lightingPaint)

            indicatorShadowNode.endRecording()

            indicatorShadowNode.setRenderEffect(

                opticalLightingBlur(0, blackBlur),

            )

            canvas.drawRenderNode(indicatorShadowNode)

        } else {

            canvas.drawPath(selectionPath, lightingPaint)

        }



        val colorBlur = lerp(0.3f, 0.6f, progress) * density

        val offset = lerp(0.25f, 1.1f, progress) * density

        val negativeOffset = -lerp(0.15f, 0.55f, progress) * density

        lightingPaint.blendMode = BlendMode.PLUS

        val causticsCanvas = if (canvas.isHardwareAccelerated) {

            indicatorCausticsNode.setPosition(0, 0, width, height)

            perfNodeRecords++

            indicatorCausticsNode.beginRecording(width, height)

        } else {

            canvas

        }

        val blueAlpha = 0.035f * progress + if (dark) 0.025f else 0.018f

        lightingPaint.color = Color.argb((blueAlpha * 255f).toInt(), 0x64, 0xC8, 0xFF)

        lightingRect.set(selectionRect)

        lightingRect.offset(-offset, negativeOffset)

        lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

        causticsCanvas.drawPath(lightingPath, lightingPaint)



        val pinkAlpha = 0.03f * progress + if (dark) 0.020f else 0.016f

        lightingPaint.color = Color.argb((pinkAlpha * 255f).toInt(), 0xFF, 0xA0, 0xD8)

        lightingRect.set(selectionRect)

        lightingRect.offset(offset * 0.9f, negativeOffset * 0.5f)

        lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

        causticsCanvas.drawPath(lightingPath, lightingPaint)

        if (canvas.isHardwareAccelerated) {

            indicatorCausticsNode.endRecording()

            indicatorCausticsNode.setRenderEffect(

                opticalLightingBlur(1, colorBlur),

            )

            canvas.drawRenderNode(indicatorCausticsNode)

        }



        lightingPaint.blendMode = null

        canvas.restore()

    }



    private fun opticalLightingBlur(slot: Int, radius: Float): RenderEffect {

        if (opticalLightingBlurRadii[slot] == radius) {

            opticalLightingBlurEffects[slot]?.let { return it }

        }

        val effect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)

        opticalLightingBlurRadii[slot] = radius

        opticalLightingBlurEffects[slot] = effect

        return effect

    }



    private fun drawInteractiveHighlight(canvas: Canvas) {

        val progress = interactiveHighlightProgress

        if (progress <= 0.001f) return

        fillPaint.shader = null

        fillPaint.blendMode = BlendMode.PLUS

        fillPaint.color = Color.argb((0.06f * progress * 255f).toInt(), 255, 255, 255)

        canvas.drawRect(outerRect, fillPaint)

        fillPaint.shader = RadialGradient(

            (4f * density + (indicatorPosition + 0.5f) * tabWidthPx() + translationX)

                .coerceIn(0f, width.toFloat()),

            height / 2f,

            height * 1.2f,

            Color.argb((0.12f * progress * 255f).toInt(), 255, 255, 255),

            Color.TRANSPARENT,

            Shader.TileMode.CLAMP,

        )

        canvas.drawRect(outerRect, fillPaint)

        fillPaint.shader = null

        fillPaint.blendMode = null

    }



    private fun drawBloomStroke(

        canvas: Canvas,

        target: RectF,

        radius: Float,

        alpha: Float,

        rotation: Float,

        shader: RuntimeShader?,

    ) {

        if (alpha <= 0f || target.isEmpty || shader == null || !canvas.isHardwareAccelerated) return

        runCatching {

            val width = target.width()

            val height = target.height()

            val halfWidth = width / 2f

            val halfHeight = height / 2f

            shader.setFloatUniform("halfView", halfWidth, halfHeight)

            shader.setFloatUniform("highlightAlpha", alpha)

            shader.setColorUniform("strokeColor", Color.WHITE)

            shader.setFloatUniform("strokeAlphaMul", 0.12f)

            setBloomLight(shader, "1", rotation, primary = true)

            setBloomLight(shader, "2", rotation, primary = false)



            bloomPaint.shader = shader

            bloomPaint.style = Paint.Style.STROKE

            bloomPaint.strokeWidth = density

            lightingRect.set(0f, 0f, width, height)

            lightingPath.setBottomBarSquircle(lightingRect, radius, config.cornerSmoothing)

            canvas.save()

            canvas.translate(target.left, target.top)

            canvas.drawPath(lightingPath, bloomPaint)

            canvas.restore()

            bloomPaint.shader = null

            bloomPaint.style = Paint.Style.FILL

        }

    }



    private fun setBloomLight(shader: RuntimeShader, suffix: String, rotation: Float, primary: Boolean) {

        val dx: Float

        val dy: Float

        val dz: Float

        val intensity: Float

        if (primary) {

            // The primary light follows the screen-plane gravity direction

            // (KernelSU liquid-bar / miuix-blur rememberDeviceTilt port); a

            // near-flat device keeps the upright (0, -1) reference. Each

            // highlight then rotates only its primary light (-45 degrees for

            // the panel, +90 for the pill).

            var gx = BloomTiltTracker.gravityX

            var gy = BloomTiltTracker.gravityY

            when (displayRotationCompat()) {

                Surface.ROTATION_90 -> { val remap = gx; gx = -gy; gy = remap }

                Surface.ROTATION_180 -> { gx = -gx; gy = -gy }

                Surface.ROTATION_270 -> { val remap = gx; gx = gy; gy = -remap }

            }

            val gMagSq = gx * gx + gy * gy

            val lx0: Float

            val ly0: Float

            if (gMagSq > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {

                val invMag = 1f / sqrt(gMagSq)

                lx0 = gx * invMag

                ly0 = gy * invMag

            } else {

                lx0 = 0f

                ly0 = -1f

            }

            val radians = Math.toRadians(rotation.toDouble())

            val c = cos(radians).toFloat()

            val s = sin(radians).toFloat()

            dx = c * lx0 - s * ly0

            dy = s * lx0 + c * ly0

            dz = -0.05f

            intensity = 1f

        } else {

            dx = 0f

            dy = 0.1f

            dz = -0.5f

            intensity = 0.4f

        }

        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.000001f)

        shader.setFloatUniform("lightDir$suffix", dx / length, dy / length, dz / length)

        shader.setColorUniform("lightColor$suffix", Color.WHITE)

        shader.setFloatUniform("lightIntensity$suffix", intensity)

    }



    private fun displayRotationCompat(): Int =

        runCatching { display?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)



internal fun surfaceContainerColor(dark: Boolean): Int {

        val alpha = ((if (dark) config.darkAlpha else config.lightAlpha) * 255f)

            .toInt().coerceIn(0, 255)

        return if (dark) Color.argb(alpha, 33, 31, 38) else Color.argb(alpha, 255, 255, 255)

    }



    private fun resolveSelectedFromViewState(): Int? {

        if (slotCount <= 0) return null

        if (targetAdapter?.reverseVisualSlotOrder == true) {
            // LiteTabBar's selected View flags can lag behind the visible page
            // on cold start. Amap opens Home; subsequent tab changes originate
            // from this host and keep selectedIndex in native cell order.
            return if (selectionInitialized) selectedIndex.coerceIn(0, slotCount - 1)
                else (slotCount - 1).coerceAtLeast(0)
        }

        // XHS fragment changes and the global show/hide animation can overlap.

        // Its native selected flags are stale for several draw frames (and in

        // some builds Store remains selected while Home is already visible).

        // The glass host is the sole hit target, so its own committed index is

        // the stable source of truth.  This prevents animation invalidations

        // from changing navigation state and keeps cold start on Home.

        if (targetAdapter?.usesCommittedSelection == true) {

            return selectedIndex.coerceIn(0, slotCount - 1)

        }

        // JD's hidden row can report two selected descendants at once and its

        // asynchronous callbacks arrive out of order during rapid switching.

        // The glass host is the only physical navigation target, so the latest

        // committed host index is the deterministic source of truth.

        appNavigationState?.resolveSelectedIndex(navigationSource, slotCount)?.let { return it }

        if (context.packageName == FILE_MANAGER_PACKAGE && sliderEnabled) {

            // The main tab row exposes its selection as a plain View overlay

            // (the moving pill) at child index 2 — exactly the index the

            // nonSelectable search slot occupies, so the generic scorer would

            // skip it. Map the overlay's horizontal position to the slot.

            val fmSource = navigationSource as? ViewGroup ?: return null

            val indicator = (0 until fmSource.childCount).mapNotNull { fmSource.getChildAt(it) }

                .firstOrNull {

                    it !is ViewGroup && it !is ImageView && it.visibility == View.VISIBLE &&

                        it.width > 0 && it.height > 0

                } ?: return null

            val indicatorLocation = IntArray(2).also(indicator::getLocationInWindow)

            val hostLocation = IntArray(2).also(::getLocationInWindow)

            val center = indicatorLocation[0] - hostLocation[0] + indicator.width / 2f

            val index = ((center - 4f * density) / tabWidthPx()).toInt().coerceIn(0, slotCount - 1)

            if (index !in nonSelectableIndices) return index

        }

        findSlotContainer()?.let { container ->

            var bestIndex = -1

            var bestScore = 0

            for (index in 0 until minOf(container.childCount, slotCount)) {

                if (index in nonSelectableIndices) continue

                val score = selectionStateScore(container.getChildAt(index))

                if (score > bestScore) {

                    bestScore = score

                    bestIndex = index

                }

            }

            return bestIndex.takeIf { it >= 0 }

        }



        val candidates = ArrayList<View>()

        val stack = ArrayDeque<Pair<View, Int>>()

        if (childCount > 0) stack += getChildAt(0) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.isShown && (view.isSelected || view.isActivated)) candidates += view

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        val hostLocation = IntArray(2).also(::getLocationInWindow)

        return candidates

            .asSequence()

            .map { selected ->

                val viewLocation = IntArray(2).also(selected::getLocationInWindow)

                val center = viewLocation[0] - hostLocation[0] + selected.width / 2f

                val index = ((center - 4f * density) / tabWidthPx()).toInt().coerceIn(0, slotCount - 1)

                Triple(index, selectionStateScore(selected), selected.width * selected.height)

            }

            .filter { (index) -> index !in nonSelectableIndices }

            .maxWithOrNull(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.third })

            ?.first

    }



    private fun selectionStateScore(root: View): Int {

        var score = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.isShown) {

                if (view.isSelected) score += 100 - depth.coerceAtMost(20)

                if (view.isActivated) score += 80 - depth.coerceAtMost(20)

                if (view.isPressed) score += 20 - depth.coerceAtMost(10)

            }

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return score

    }



    internal fun navigationSlotForAdapter(index: Int): View? = findSlotContainer()?.getChildAtOrNull(index)

    private fun findSlotContainer(): ViewGroup? {

        val root = navigationSource?.takeIf { it.isAttachedToWindow }

            ?: (getChildAtOrNull(0) as? ViewGroup)

        if (root != null) {

            val found = findSlotContainerFrom(root)

            if (found != null) return found

        }

        if (appNavigationState?.refreshNavigationSource(this) == true) {
            navigationSource?.let { refreshed ->
                findSlotContainerFrom(refreshed)?.let { return it }
            }
        }

        return null

    }



    private fun findSlotContainerFrom(root: ViewGroup): ViewGroup? {

        var best: ViewGroup? = null

        var bestDepth = Int.MAX_VALUE

        val stack = ArrayDeque<Pair<ViewGroup, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (group, depth) = stack.removeLast()

            if (group.childCount in (slotCount - 1).coerceAtLeast(2)..(slotCount + 1) && depth < bestDepth) {

                val laidOut = (0 until group.childCount).count {

                    val child = group.getChildAt(it)

                    child.width > 0 && child.height > 0

                }

                if (laidOut >= 2) {

                    best = group

                    bestDepth = depth

                }

            }

            if (depth < 6) {

                for (index in 0 until group.childCount) {

                    (group.getChildAt(index) as? ViewGroup)?.let { stack += it to (depth + 1) }

                }

            }

        }

        return best

    }



    private fun removeCaptureLoop() {

        val root = rootView

        captureListener?.let { listener ->

            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)

        }

        captureListener = null

        backdrop?.recycle()

        backdrop = null

    }



    private fun installCaptureLoop() {

        if (config.solidBarEnabled || !config.backdropCapture) return

        if (captureListener != null) return

        val root = rootView

        captureListener = ViewTreeObserver.OnPreDrawListener {

            val now = SystemClock.uptimeMillis()

            // During a tap-driven page switch, let the native page transition
            // own the frame budget. Keep the already-rendered glass untouched;
            // only defer per-frame probing/capture preparation briefly.
            if (now < barAnimationPriorityUntil) return@OnPreDrawListener true

            if (targetAdapter?.usesDirectOpticalBackdropMode == true) {

                // JD composes its feeds from native Views AND H5/WebViews

                // (the 新品 page is a JDWebView-backed H5 surface; browse and

                // video also host independent media surfaces). A live RenderNode

                // alone cannot reach inside a WebView, so live sampling flashes

                // wherever H5 pixels are expected. Every JD page therefore uses

                // the compositor host path (worker PixelCopy + stable bitmap),

                // which reads WebView and native content alike.

                syncBackdropRendererMode()

                return@OnPreDrawListener true

            }

            if (appNavigationState?.pauseBackdropDuringBarExit == true &&
                barVisibilityAnimating && !barVisibilityTarget
            ) {

                return@OnPreDrawListener true

            }

            val animating = touching || pressProgress > 0.001f ||

                abs(indicatorPosition - dragTarget) > 0.001f

            if (context.packageName != QQ_PACKAGE &&

                appNavigationState?.deferThemeDetection(now) != true

            ) updateDetectedContentTheme()

            // The pre-draw listener also owns lightweight page/theme state.
            // Keep that listener alive in solid mode, but never walk the
            // backdrop scene or request a frame that cannot be displayed.
            appNavigationState?.onHostFrame(this)
            if (config.solidBarEnabled) {
                return@OnPreDrawListener true
            }

            if (!compositorBlurBehind) {
                appNavigationState?.requestVideoBackdrop(this, currentPageAllowsNavigation())
            }

            if (context.packageName == MEITUAN_TAKEOUT_PACKAGE) {

                // Every Meituan Takeout tab renders through the compositor

                // readback pipeline: the webview tabs (神券/活动) flicker

                // under live RenderNode sampling, and the home tab's live

                // scene walked all siblings and re-recorded on the UI thread

                // every preDraw, which read as insufficient bar frame rate.

                updateOpticalSurfaceSurfaceRenderer(true)

                return@OnPreDrawListener true

            }

            if (!compositorBlurBehind && prepareLiveBackdropScene()) return@OnPreDrawListener true

            val opticalSurface = isOpticalSurfaceSelected()

            if (!opticalSurface) opticalSurfaceSnapshotReady = false

            if (opticalSurface) {

                if (opticalSurfaceSurfacePipelineActive) {

                    // The probe loop owns the copy cadence for the promotion

                    // tab; re-requesting here as well would double-count the

                    // vsync tick and break the cadence stride.

                    return@OnPreDrawListener true

                }

                // The regular Meco WebView has no independently copyable

                // Surface. Its promotion path is compositor-only: retaining a

                // software frame here is the sole source of the visible ghost.

                if (usesOpticalSurfaceHybridBackdrop()) {

                    // Still need to capture the backdrop for the glass

                    // effect to have content to work with.

                    if (backdrop == null || backdrop?.isRecycled == true) {

                        val captureRoot = appNavigationState?.resolveBackdropScene(this)
                            ?: (navigationSource?.parent as? View) ?: root

                        captureBackdrop(captureRoot)

                    }

                    return@OnPreDrawListener true

                }

                if ((!animating || barVisibilityAnimating) &&

                    !opticalSurfacePixelCopyInFlight && isShown &&

                    width > 0 && height > 0 && now - lastCapture >= OPTICAL_SURFACE_CAPTURE_INTERVAL_MS

                ) {

                    lastCapture = now

                    val textureCopyStarted = captureOpticalSurfaceTextureBackdrop()

                    if (!textureCopyStarted && !opticalSurfaceSnapshotReady && !capturing) {

                        captureBackdrop(appNavigationState?.resolveBackdropScene(this)
                            ?: (navigationSource?.parent as? View) ?: root)

                        opticalSurfaceSnapshotReady = true

                        invalidate()

                    }

                }

                return@OnPreDrawListener true

            }

            // JD: with the compositor-readback pipeline active the PixelCopy

            // result owns the backdrop. The generic 96ms software capture

            // would re-draw the whole content tree (feed + WebView) on the UI

            // thread and swap in a differently-sized bitmap that fights the

            // surface pipeline.

            if (opticalSurfaceSurfacePipelineActive) return@OnPreDrawListener true

            val interval = when {

                backdrop == null -> 0L

                barVisibilityAnimating ->

                    OPTICAL_BAR_APPEAR_CAPTURE_INTERVAL_MS

                else -> CAPTURE_IDLE_INTERVAL_MS

            }

            if (!animating && !capturing && isShown &&

                width > 0 && height > 0 && now - lastCapture >= interval

            ) {

                lastCapture = now

                val captureSource = parent as? View

                captureBackdrop(captureSource ?: root)

            }

            true

        }.also { root.viewTreeObserver.addOnPreDrawListener(it) }

    }



    /**

     * Some app pages are rendered into a TextureView. Sampling the

     * WebView's RenderNode makes Meco draw the asynchronous page twice and is

     * the source of the former full-page flashing. PixelCopy reads only the

     * small texture strip behind the glass from the already composed surface,

     * so the existing refraction shaders remain live without repainting the

     * page or copying the whole screen.

     */

    private fun captureOpticalSurfaceTextureBackdrop(): Boolean {

        if (!isOpticalSurfaceSelected() || opticalSurfacePixelCopyInFlight) return false

        val retainedTexture = opticalSurfaceTextureView

            ?.takeIf { it.isAttachedToWindow && it.isAvailable && it.width > 0 && it.height > 0 }

        val now = SystemClock.uptimeMillis()

        val texture = retainedTexture ?: run {

            if (now - opticalSurfaceLastTextureProbe < OPTICAL_SURFACE_TEXTURE_PROBE_INTERVAL_MS) return false

            opticalSurfaceLastTextureProbe = now

            findOpticalSurfaceTextureView()?.also { opticalSurfaceTextureView = it }

        } ?: return false



        val textureLocation = IntArray(2).also(texture::getLocationInWindow)

        val hostLocation = IntArray(2).also(::getLocationInWindow)

        val desiredPadding = 24f * density

        val availablePadding = minOf(

            desiredPadding,

            (hostLocation[0] - textureLocation[0]).toFloat(),

            (textureLocation[0] + texture.width - hostLocation[0] - width).toFloat(),

            (hostLocation[1] - textureLocation[1]).toFloat(),

            (textureLocation[1] + texture.height - hostLocation[1] - height).toFloat(),

        ).coerceAtLeast(0f)

        if (abs(availablePadding - opticalSurfaceBackdropPadding) >= 1f) {

            opticalSurfaceBackdropPadding = availablePadding

            configureGlassEffects()

        }

        val padding = opticalSurfaceBackdropPadding.roundToInt()

        val sourceRect = Rect(

            hostLocation[0] - textureLocation[0] - padding,

            hostLocation[1] - textureLocation[1] - padding,

            hostLocation[0] - textureLocation[0] + width + padding,

            hostLocation[1] - textureLocation[1] + height + padding,

        )

        if (sourceRect.left < 0 || sourceRect.top < 0 ||

            sourceRect.right > texture.width || sourceRect.bottom > texture.height ||

            sourceRect.width() <= 0 || sourceRect.height() <= 0

        ) return false



        val targetWidth = max(1, sourceRect.width() / CAPTURE_DOWNSCALE.toInt())

        val targetHeight = max(1, sourceRect.height() / CAPTURE_DOWNSCALE.toInt())

        val target = opticalSurfacePixelCopyScratch

            ?.takeIf { !it.isRecycled && it.width == targetWidth && it.height == targetHeight }

            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        opticalSurfacePixelCopyScratch = null

        val surface = runCatching { Surface(texture.surfaceTexture) }.getOrNull() ?: return false

        opticalSurfacePixelCopyInFlight = true

        return runCatching {

            PixelCopy.request(

                surface,

                sourceRect,

                target,

                { result ->

                    surface.release()

                    opticalSurfacePixelCopyInFlight = false

                    if (result == PixelCopy.SUCCESS && isAttachedToWindow && isOpticalSurfaceSelected()) {

                        val previous = backdrop

                        backdrop = target

                        opticalSurfaceSnapshotReady = true

                        if (previous != null && !previous.isRecycled &&

                            previous.width == target.width && previous.height == target.height

                        ) {

                            opticalSurfacePixelCopyScratch = previous

                        } else {

                            previous?.takeUnless(Bitmap::isRecycled)?.recycle()

                        }

                        postInvalidateOnAnimation()

                    } else {

                        opticalSurfacePixelCopyScratch = target

                    }

                },

                opticalSurfacePixelCopyHandler,

            )

            true

        }.getOrElse {

            opticalSurfacePixelCopyInFlight = false

            opticalSurfacePixelCopyScratch = target

            surface.release()

            false

        }

    }



    private fun findOpticalSurfaceTextureView(): TextureView? {

        val root = navigationSource?.parent as? ViewGroup ?: return null

        val stack = ArrayDeque<View>()

        stack += root

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            if (view.javaClass == TextureView::class.java && view is TextureView &&

                view.visibility == View.VISIBLE && view.isAvailable &&

                view.width >= root.width * 0.9f && view.height >= root.height * 0.8f &&

                view.ancestorsAreVisible()

            ) return view

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index)

            }

        }

        return null

    }



    private fun captureBackdrop(root: View) {
        // Check every software-capture entry, including the idle fallback.
        // GPU-only app images may throw while an otherwise ordinary View draws.
        if (appNavigationState?.requiresSampledBackdrop() == false ||
            appNavigationState?.allowsSoftwareBackdropCapture == false) return

        val padding = backdropPaddingPx()

        val captureWidth = width + padding * 2f

        val captureHeight = height + padding * 2f

        val scaledWidth = max(1, (captureWidth / CAPTURE_DOWNSCALE).toInt())

        val scaledHeight = max(1, (captureHeight / CAPTURE_DOWNSCALE).toInt())

        val target = backdrop?.takeIf { it.width == scaledWidth && it.height == scaledHeight && !it.isRecycled }

            ?: Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888).also {

                backdrop?.recycle()

                backdrop = it

            }



        val rootLocation = IntArray(2)

        val hostLocation = IntArray(2)

        root.getLocationInWindow(rootLocation)

        getLocationInWindow(hostLocation)

        val x = hostLocation[0] - rootLocation[0]

        val y = hostLocation[1] - rootLocation[1]

        capturing = true

        try {

            val bitmapCanvas = Canvas(target)

            bitmapCanvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

            bitmapCanvas.scale(1f / CAPTURE_DOWNSCALE, 1f / CAPTURE_DOWNSCALE)

            bitmapCanvas.translate(-x.toFloat() + padding, -y.toFloat() + padding)

            root.draw(bitmapCanvas)

        } finally {

            capturing = false

        }

        // App-owned optical surfaces may need host traversal to refresh their
        // compositor snapshot while the page itself is static.
        if (targetAdapter?.needsContinuousOpticalSurfaceFrames(selectedIndex) != true) invalidate()

    }



    private fun isOpticalSurfaceSelected(): Boolean =

        targetAdapter?.isOpticalSurfaceTab(selectedIndex) == true



    private fun wantsRootSurfaceOpticalPipeline(): Boolean =

        targetAdapter?.usesDirectOpticalBackdropMode == true || isOpticalSurfaceSelected() ||

            context.packageName == MEITUAN_TAKEOUT_PACKAGE





    /** New Products must not reference its texture-backed RenderNodes from a

     *  second scene. MIUI native background blur also makes those textures

     *  enter a second compositor blur pass and visibly flash, so this page

     *  uses the narrow, downscaled, demand-sampled Surface path instead. */

    internal fun disableOpticalSurfaceNativeBlurForAdapter() = updateOpticalSurfaceNativeBlur(false)
    internal fun enableOpticalSurfaceRendererForAdapter() =
        updateOpticalSurfaceSurfaceRenderer(!config.solidBarEnabled && config.backdropCapture)
    internal fun disableOpticalSurfaceRendererForAdapter() = updateOpticalSurfaceSurfaceRenderer(false)
    internal fun clearLiveBackdropForAdapter() {
        liveBackdropActive = false
        liveBackdropSignature = Long.MIN_VALUE
        sharedOpticalBlurPrepared = false
    }
    /** Discard an adapter's previous optical frame after its page changes. */
    internal fun invalidateOpticalBackdropForAdapter() {
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
        opticalSurfaceSnapshotReady = false
        opticalSurfaceTextureView = null
        clearLiveBackdropForAdapter()
        postInvalidateOnAnimation()
    }

    private fun syncBackdropRendererMode() {
        if (appNavigationState?.updateBackdropRendererMode(this) == true) return
        if (targetAdapter?.usesDirectOpticalBackdropMode == true) updateOpticalSurfaceSurfaceRenderer(true)
    }



    /** Tabs served by the demand-sampled probe loop: copies are driven solely

     *  by scheduleCompositorSurfaceProbe() and delivered on the dedicated

     *  compositor thread, never requeued from the copy callback. */

    private fun usesCompositorSurfaceProbe(): Boolean =

        context.packageName == MEITUAN_TAKEOUT_PACKAGE ||

            targetAdapter?.usesDirectOpticalBackdropMode == true ||

            isOpticalSurfaceSelected()



    private fun usesOpticalSurfaceHybridBackdrop(): Boolean =

        opticalSurfaceNativeBlurActive && opticalSurfaceTextureView == null &&

            (isOpticalSurfaceSelected() || targetAdapter?.usesDirectOpticalBackdropMode == true)



    private fun backdropPaddingPx(): Float {
        // Blur needs real neighbouring pixels, not repeated CLAMP edge pixels.
        // Reserve three radii so a moving high-contrast edge outside the bar
        // enters the filter gradually even at the maximum configured radius.
        val radius = config.blurRadius * density * (targetAdapter?.secondaryBlurScale ?: 1f)
        val filterPadding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(radius, 24f * density)
        return if (isOpticalSurfaceSelected() && opticalSurfaceBackdropPadding > 0f) {
            // Surface samples publish their own rectangle and padding together;
            // do not change its geometry without requesting a matching sample.
            opticalSurfaceBackdropPadding
        } else {
            filterPadding
        }
    }



    private fun updateDetectedContentTheme() {

        if (config.themeMode != GlassConfig.THEME_SYSTEM) return

        val now = SystemClock.uptimeMillis()

        val probeInterval = targetAdapter?.themeProbeIntervalMs ?: THEME_PROBE_INTERVAL_MS

        if (detectedContentDark != null && now - lastThemeProbe < probeInterval) return

        lastThemeProbe = now

        val source = navigationSource ?: return

        val luminances = ArrayList<Float>(slotCount)

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += source to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.visibility != View.VISIBLE) continue

            if (view is TextView) {

                val label = view.text?.toString().orEmpty()

                if (label.any(Char::isLetter)) {

                    val color = view.currentTextColor

                    if (Color.alpha(color) > 32) {

                        luminances += (

                            0.2126f * Color.red(color) +

                                0.7152f * Color.green(color) +

                                0.0722f * Color.blue(color)

                            )

                    }

                }

            }

            if (view is ViewGroup && depth < 6) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        if (luminances.isNotEmpty()) {

            luminances.sort()

            detectedContentDark = luminances[luminances.size / 2] >= DARK_LABEL_LUMINANCE

        }

    }



internal fun isDarkGlass(): Boolean {

        appNavigationState?.forceDarkMode(selectedIndex)?.let { return it }

        if (targetAdapter?.forceLightMode == true || context.packageName == MEITUAN_TAKEOUT_PACKAGE) return false

        return when (config.themeMode) {

        GlassConfig.THEME_LIGHT -> false

        GlassConfig.THEME_DARK -> true

        else -> appNavigationState?.resolveAutoDarkMode(this) ?: if (

            targetAdapter?.usesContentDarkMode == true ||

            targetAdapter?.hideRedrawnNavigationSource == true

        ) {

            detectedContentDark ?: systemNightMode()

        } else {

            systemNightMode()

        }

    }

    }



    /**

     * One dark switch drives Weibo's glass fill, icon set and label colors �?

     * the same chain bilibili uses. In-process uiMode signals cover the normal

     * day/night flips; under HyperOS force-dark Weibo's process stays on the

     * day configuration, and the module-side provider's live device state

     * ([GlassConfig.systemDark]) is the signal that still reports dark.

     */
    internal fun adapterContentOrSystemDark(): Boolean =
        systemNightMode() || config.systemDark || detectedContentDark == true

    private fun systemNightMode(): Boolean {

        // 1. Check app's Configuration

        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==

            Configuration.UI_MODE_NIGHT_YES) return true

        // 2. Check system resources (not affected by per-app overrides)

        if (android.content.res.Resources.getSystem().configuration.uiMode and

            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) return true

        // 3. Check UiModeManager (standard Android API)

        val umm = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager

        if (umm?.nightMode == android.app.UiModeManager.MODE_NIGHT_YES) return true

        // 4. MIUI fallback: dark mode stored in Settings.Global

        return try {

            android.provider.Settings.Global.getInt(

                context.contentResolver, "dark_mode", 0

            ) == 1

        } catch (e: Exception) {

            false

        }

    }



    private fun selectedNavigationAccent(): Int = when {

        config.customAccentEnabled -> config.customAccentColor

        targetAdapter?.accentColor != null -> targetAdapter!!.accentColor!!

        else -> KERNEL_SU_ACCENT_COLOR

    }



    private fun enableMiuiBackgroundBlur(): Boolean {

        fun call(name: String, vararg args: Any): Boolean = runCatching {

            XposedHelpers.callMethod(this, name, *args)

            true

        }.getOrDefault(false)



        val dark = isDarkGlass()

        val mappedRadius = if (dark) {

            (config.blurRadius * 4f).toInt().coerceIn(1, 36)

        } else {

            (config.blurRadius * 2.75f).toInt().coerceIn(1, 24)

        }

        val viewMode = call("setMiViewBlurMode", 1)

        val backgroundMode = call("setMiBackgroundBlurMode", 1)

        val radius = call("setMiBackgroundBlurRadius", mappedRadius)

        return viewMode && backgroundMode && radius

    }



    private fun disableMiuiBackgroundBlur() {

        if (!nativeBlurActive) return

        runCatching { XposedHelpers.callMethod(this, "setMiBackgroundBlurMode", 0) }

        runCatching { XposedHelpers.callMethod(this, "setMiViewBlurMode", 0) }

        nativeBlurActive = false

    }



    private fun ViewGroup.getChildAtOrNull(index: Int): View? =

        if (index in 0 until childCount) getChildAt(index) else null



    private fun View.ancestorsAreVisible(): Boolean {

        var current: View? = this

        repeat(16) {

            val view = current ?: return true

            if (view.visibility != View.VISIBLE) return false

            current = view.parent as? View

        }

        return true

    }



    /**

     * Never stack MIUI's asynchronous window blur underneath our captured

     * backdrop pipeline. Both are valid alone, but they sample adjacent

     * compositor frames while a feed scrolls and the translucent result reads

     * as a vibrating/doubled background. The module RenderEffect retains the

     * configured blur and liquid refraction; native blur remains the fallback

     * when backdrop capture is explicitly disabled.

     */

    private fun shouldUseNativeBackgroundBlur(): Boolean =

        config.nativeBlur && !config.backdropCapture && !config.solidBarEnabled



    /**

     * JD keeps its full-screen video channel inside MainFrameActivity, so an

     * Activity-name check cannot distinguish it from Home. Detect the stable

     * combination actually present on that page: a screen-sized video surface

     * plus the top video-channel strip. The probe is throttled and bounded.

     */

    internal fun onAdapterScrollHideTimerChanged(active: Boolean, delayMs: Long) {
        removeCallbacks(msgScrollHideRunnable)
        msgScrolledToStop = false
        if (active) postDelayed(msgScrollHideRunnable, delayMs)
        postInvalidateOnAnimation()
    }

    internal fun onSystemBackPressed() {
        appNavigationState?.handleSystemBack(this)
    }

    private fun currentPageAllowsNavigation(): Boolean {

        appNavigationState?.hostPageAllowsNavigation(this, rootView)?.let { return it }

        if (targetAdapter?.isBlockingOverlayVisible() == true) return false

        // XHS mirrors Weibo's message-tab contract: the bar leaves 1.5s after

        // entering the message tab and returns as a 1.5s peek when system back

        // is pressed, while the page itself stays put. XHS's own selected flags

        // are stale across fragment switches, so the host's committed index is

        // the source of truth (see resolveSelectedFromViewState).

        if (targetAdapter?.resetScrollStopHideOnOtherTabs() == true) {

            if (targetAdapter?.scrollStopHideTab(selectedIndex) == true) {

                ensureMsgScrollStopHideListener()

                return !msgScrolledToStop

            }

            if (msgScrolledToStop) {

                msgScrolledToStop = false

                removeCallbacks(msgScrollHideRunnable)

            }

            return true

        }

        // Meituan Takeout profile: scroll-stop-hide. The orders tab keeps the

        // bar permanently (user request): hiding it there left the compositor

        // surface's last frame floating over the feed, because the surface

        // visibility never followed the host's hide animation.

        if (context.packageName == MEITUAN_TAKEOUT_PACKAGE &&

            selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX

        ) {

            ensureMsgScrollStopHideListener()

            return !msgScrolledToStop

        }

        if (context.packageName == MEITUAN_TAKEOUT_PACKAGE && msgScrolledToStop) {

            msgScrolledToStop = false

            removeCallbacks(msgScrollHideRunnable)

        }

        // Flash Sale follows the same low-overhead contract as Weibo Message:

        // show immediately while the page moves, then leave one second after

        // scrolling becomes idle. Do not resize its compositor surface.

        if (appNavigationState?.pageRequiresScrollStopHide(selectedIndex) == true) {

            ensureMsgScrollStopHideListener()

            return !msgScrolledToStop

        }

        if (context.packageName != QQ_PACKAGE && targetAdapter?.hasTransientPageVisibility != true) return true

        val now = SystemClock.uptimeMillis()

        if (now - lastPageVisibilityCheck < PAGE_VISIBILITY_INTERVAL_MS) return pageAllowsNavigation

        lastPageVisibilityCheck = now

        val activity = context.findActivity()

        pageAllowsNavigation = when (context.packageName) {

            QQ_PACKAGE -> !containsActiveQqChatFragment(activity) && !hasQqForegroundPageLayer(activity)

            else -> appNavigationState?.transientPageAllowsNavigation(activity) ?: true

        }

        return pageAllowsNavigation

    }



    /**

     * Attach a ViewTreeObserver.OnScrollChangedListener once. When any view

     * in the window scrolls while on an adapter-owned scroll-hide page, cancel the

     * hide timer and restart it (1s). When the timer fires, hide the bar.

     */

    internal fun resetMessageScrollStopState() {
        if (!msgScrolledToStop) return
        msgScrolledToStop = false
        removeCallbacks(msgScrollHideRunnable)
    }

    internal fun ensureMsgScrollStopHideListener() {

        if (msgScrollListenerAttached) return

        val root = rootView ?: return

        root.viewTreeObserver.addOnScrollChangedListener {

            val isMessageTab = (targetAdapter?.scrollStopHideTab(selectedIndex) == true) ||
                (appNavigationState?.pageRequiresScrollStopHide(selectedIndex) == true) ||

                (context.packageName == MEITUAN_TAKEOUT_PACKAGE &&

                    (selectedIndex == MEITUAN_TAKEOUT_ORDER_INDEX || selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX))

            if (isMessageTab) {

                removeCallbacks(msgScrollHideRunnable)

                if (msgScrolledToStop) {

                    msgScrolledToStop = false

                }

                postDelayed(msgScrollHideRunnable,

                    if (targetAdapter?.scrollStopHideTab(selectedIndex) == true)
                        targetAdapter?.scrollStopHideDelayMs(selectedIndex) ?: 1_000L
                    else if (context.packageName == MEITUAN_TAKEOUT_PACKAGE && selectedIndex == MEITUAN_TAKEOUT_ORDER_INDEX) 800L

                    else if (context.packageName == MEITUAN_TAKEOUT_PACKAGE && selectedIndex == MEITUAN_TAKEOUT_PROFILE_INDEX) 1500L

                    else 1000L)

            }

        }

        // Track touch state: don't hide while user is dragging

        root.setOnTouchListener { _, event ->

            msgUserTouching = event.actionMasked != android.view.MotionEvent.ACTION_UP &&

                event.actionMasked != android.view.MotionEvent.ACTION_CANCEL

            false

        }

        msgScrollListenerAttached = true

    }



    /**

     * WeChat keeps its main launcher and the pull-down mini-program surface in

     * the same Activity, so source visibility alone cannot distinguish them.

     * Identify the actual mini-program panel from its stable, user-visible

     * labels instead of relying on version-specific resource ids. The cached

     * state is cleared on the first frame those labels disappear, allowing the

     * reverse bar animation to begin immediately when the chat list returns.

     */




    /**

     * QQ keeps the home tab fragment alive underneath profile/settings pages.

     * Those pages are inserted as later full-screen siblings of the branch

     * containing QQTabLayout. Matching that actual draw order avoids both the

     * old chat-only special case and the unsafe parent-alpha workaround.

     */

    private fun hasQqForegroundPageLayer(activity: Activity?): Boolean {

        val source = navigationSource ?: return false

        val content = activity?.findViewById<ViewGroup>(android.R.id.content) ?: return false

        val contentRect = Rect()

        if (!content.getGlobalVisibleRect(contentRect) || contentRect.isEmpty) return false

        val descriptions = ArrayList<String>()

        var branch: View = source

        repeat(12) {

            val parent = branch.parent as? ViewGroup ?: return@repeat

            val branchIndex = parent.indexOfChild(branch)

            for (index in (branchIndex + 1).coerceAtLeast(0) until parent.childCount) {

                val sibling = parent.getChildAt(index)

                if (sibling === this || sibling in suppressedOriginalChrome) continue

                val group = sibling as? ViewGroup

                val visibleRect = Rect()

                val visiblyOnScreen = sibling.getGlobalVisibleRect(visibleRect) &&

                    !visibleRect.isEmpty && visibleRect.width() > 2f * density

                val fullScreenLayer = sibling.visibility == View.VISIBLE && sibling.alpha > 0.01f &&

                    visiblyOnScreen && visibleRect.height() >= contentRect.height() * 0.72f

                if (fullScreenLayer) {

                    descriptions += "${sibling.javaClass.name}#${sibling.id}:children=${group?.childCount ?: -1}"

                    if (sibling.containsViewClass(QQ_SETTING_ME_LAYOUT_CLASS, 5)) {

                        logQqLayerSignature(descriptions)

                        return true

                    }

                }

            }

            if (parent === content) {

                logQqLayerSignature(descriptions)

                return false

            }

            branch = parent

        }

        logQqLayerSignature(descriptions)

        return false

    }



    private fun View.containsViewClass(className: String, maxDepth: Int): Boolean {

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += this to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view.javaClass.name == className && view.visibility == View.VISIBLE && view.alpha > 0.01f) return true

            if (view is ViewGroup && depth < maxDepth) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return false

    }



    private fun logQqLayerSignature(descriptions: List<String>) {

        val signature = descriptions.joinToString("|").ifEmpty { "home" }

        if (signature == lastQqLayerSignature) return

        lastQqLayerSignature = signature

        XposedBridge.log("[OfflineGlass][QQLayers] $signature")

    }



    private fun containsActiveQqChatFragment(activity: Activity?): Boolean {

        if (activity == null) return false

        val manager = runCatching { XposedHelpers.callMethod(activity, "getSupportFragmentManager") }

            .getOrElse {

                runCatching { XposedHelpers.callMethod(activity, "getFragmentManager") }.getOrNull()

            } ?: return false

        return containsActiveQqChatFragment(manager, 0)

    }



    private fun containsActiveQqChatFragment(manager: Any, depth: Int): Boolean {

        if (depth > 5) return false

        val fragments = runCatching { XposedHelpers.callMethod(manager, "getFragments") as? Iterable<*> }

            .getOrNull() ?: return false

        for (fragment in fragments) {

            fragment ?: continue

            val hidden = runCatching { XposedHelpers.callMethod(fragment, "isHidden") as? Boolean }

                .getOrNull() == true

            if (hidden) continue

            val active = runCatching { XposedHelpers.callMethod(fragment, "isResumed") as? Boolean }

                .getOrNull() == true ||

                runCatching { XposedHelpers.callMethod(fragment, "isVisible") as? Boolean }

                    .getOrNull() == true ||

                ((runCatching { XposedHelpers.callMethod(fragment, "getView") }.getOrNull() as? View)?.isShown == true)

            if (!active) continue

            val name = fragment.javaClass.name

            if (

                name == "com.tencent.mobileqq.activity.ChatFragment" ||

                name.endsWith("AIOFragment") ||

                (name.contains(".aio.") && name.endsWith("Fragment"))

            ) {

                return true

            }

            val childManager = runCatching {

                XposedHelpers.callMethod(fragment, "getChildFragmentManager")

            }.getOrNull()

            if (childManager != null && containsActiveQqChatFragment(childManager, depth + 1)) return true

        }

        return false

    }



    internal fun Context.findActivity(): Activity? {

        var current: Context? = this

        repeat(12) {

            when (current) {

                is Activity -> return current as Activity

                is ContextWrapper -> current = (current as ContextWrapper).baseContext

                else -> return null

            }

        }

        return null

    }

    internal fun activityForAdapter(): Activity? = context.findActivity()



    companion object {

        fun lerp(start: Float, stop: Float, progress: Float): Float =

            start + (stop - start) * progress



        const val CORNER_RADIUS_DP = 32f

        const val PANEL_SHADOW_BLUR_DP = 10f

        const val PANEL_SHADOW_ALPHA = 0.14f

        const val PANEL_LIGHT_SHADOW_ALPHA = 0.19f

        const val PRESSED_SCALE = 78f / 56f

        const val CAPTURE_DOWNSCALE = 2f


        // Fallback sampling runs from pre-draw, so it must track rendered frames.
        // A 96ms timer produced visible stepped updates even on slow scrolling.
        const val CAPTURE_IDLE_INTERVAL_MS = 0L

        const val OPTICAL_SURFACE_CAPTURE_INTERVAL_MS = 16L

        const val OPTICAL_SURFACE_TEXTURE_PROBE_INTERVAL_MS = 1_000L

        const val CONFIG_POLL_INTERVAL_MS = 1_000L

        const val THEME_PROBE_INTERVAL_MS = 500L

        const val THEME_TRANSITION_GRACE_MS = 900L

        const val SOURCE_RESIZE_DELAY_MS = 180L

        const val SOURCE_POST_RESIZE_SETTLE_MS = 64L

        const val SOURCE_STABLE_FRAMES = 3

        const val BACKDROP_STABLE_FRAMES = 2

        const val NAVIGATION_SNAPSHOT_STABLE_FRAMES = 3

        const val DARK_LABEL_LUMINANCE = 145f

        // QQ's drawer/page layers animate independently while remaining

        // attached. Probe once per display frame so the home bar begins its

        // return animation on the first frame the foreground layer is gone.

        const val PAGE_VISIBILITY_INTERVAL_MS = 16L

        const val QQ_PACKAGE = "com.tencent.mobileqq"

        const val QQ_BOTTOM_BLUR_CLASS = "com.tencent.qui.quiblurview.QQBlurViewWrapper"

        const val QQ_BADGE_CLASS = "com.tencent.mobileqq.quibadge.QUIBadge"

        const val QQ_BADGE_MIN_HEIGHT_DP = 16f

        const val QQ_BOTTOM_CHROME_PROBE_INTERVAL_MS = 80L

        const val QQ_VISIBILITY_ANIMATION_Z_DP = 64f

        const val QQ_STEADY_TRANSLATION_Z_DP = 2f

        // Detection parameters for the pull-down "Recent" drawer we get when

        // the chat list is scrolled to top and pulled. Hides the liquid bar

        // while the drawer is open to avoid overlap.


        const val MI_THEME_PACKAGE = "com.miui.themestore"

        const val MI_THEME_PROXY_PACKAGE = "com.android.thememanager"


        const val FILE_MANAGER_PACKAGE = "com.android.fileexplorer"

        const val FILE_MANAGER_ACTION_BAR_ID = "split_action_bar"

        const val FILE_MANAGER_BOTTOM_NAV_ID = "bottom_navigation_container"

        const val MODULE_PACKAGE = "io.github.offlineglass"

        const val MEITUAN_TAKEOUT_PACKAGE = "com.sankuai.meituan.takeoutnew"


        const val MI_MESSAGES_PACKAGE = "com.android.mms"

        val SCENE_VISIBILITY_ANIMATION_PACKAGES = setOf(

            QQ_PACKAGE,

            MI_MESSAGES_PACKAGE,

        )

        const val OPTICAL_BAR_VISIBILITY_DURATION_MS = 300L

        const val OPTICAL_BAR_SHOW_EXPAND_MS = 300L

        const val OPTICAL_BAR_SHOW_SETTLE_MS = 300L

        const val OPTICAL_BAR_APPEAR_CAPTURE_INTERVAL_MS = 16L

        const val OPTICAL_BAR_HIDDEN_SCALE_X = 1f / 3f

        const val OPTICAL_BAR_HIDDEN_SCALE_Y = 0.72f

        const val OPTICAL_BAR_SHOW_OVERSHOOT_X = 1.018f

        const val OPTICAL_BAR_SHOW_OVERSHOOT_Y = 1.01f

        const val OPTICAL_BAR_SHOW_LIFT_DP = 1f

        const val OPTICAL_BAR_HIDDEN_TRANSLATION_FRACTION = 0.72f

        val OPTICAL_BAR_SHOW_INTERPOLATOR = PathInterpolator(0.16f, 0.82f, 0.24f, 1f)

        val OPTICAL_BAR_SETTLE_INTERPOLATOR = PathInterpolator(0.22f, 0f, 0.18f, 1f)

        val OPTICAL_BAR_HIDE_INTERPOLATOR = PathInterpolator(0.4f, 0f, 1f, 1f)

        const val MEITUAN_SELECTION_SETTLE_MS = 220L

        const val MEITUAN_TAKEOUT_ORDER_INDEX = 3

        const val MEITUAN_TAKEOUT_PROFILE_INDEX = 4

        // The curve remains translucent through most of its height, while the

        // last pixel reaches the exact navigation-surface colour for a seam-free join.

        // Softened transition removed: blend now spans from gesture surface

        // top to screen bottom (no solid zone), driven by inset geometry.



    


        const val QQ_TARGET_ICON_DP = 28f

        const val QQ_NATIVE_ICON_DP = 27f

        const val QQ_SOURCE_ROW_HEIGHT_DP = 54f

        const val QQ_ICON_LABEL_SPLIT_DP = 31.5f

        const val QQ_CANONICAL_ICON_CENTER_DP = 19.25f

        const val QQ_CANONICAL_LABEL_CENTER_DP = 40f

        const val DIAGNOSTIC_TAG = "OfflineGlassGeom"

        const val QQ_SETTING_ME_LAYOUT_CLASS = "com.tencent.mobileqq.widget.QQSettingMeRelativeLayout"

        const val KERNEL_SU_ACCENT_COLOR = 0xFF0A84FF.toInt()

        // Match Bilibili's compact icon/label silhouette. Xiaomi Community's

        // live drawable uses a padded 2x source box, so scale both paths in

        // lockstep or the server-provided icon would remain visibly smaller.

        // Amap home bar: its own tab row lays every cell out as icon(left)+label
        // (right) plus a persistent selection halo behind the icon, so the generic
        // projection shows a left-shifted icon with leftover native highlights.
        // We extract the default icon artwork and label per cell at runtime and
        // re-draw them centred in each slot over the clean liquid glass.

        // 1.15 -> *1.1: icons enlarged again to 1.1x of the previous size.

        // Icon pulled down and label pulled up symmetrically to tighten the gap.

        const val MEITUAN_TAKEOUT_SCROLL_PROBE_INTERVAL_MS = 160L

        const val QQ_DEFAULT_ACCENT_COLOR = 0xFF0099FF.toInt()

        const val QQ_DEFAULT_DARK_COLOR = 0xFF191919.toInt()

        val QQ_TAB_LABELS = arrayOf("消息", "联系人", "动态")

        val QQ_NORMAL_ICON_FILES = arrayOf(

            "message_normal.png",

            "contact_normal.png",

            "dynamic_normal.png",

        )

        val QQ_SELECTED_ICON_FILES = arrayOf(

            "message_selected.png",

            "contact_selected.png",

            "dynamic_selected.png",

        )

        val MEITUAN_TAKEOUT_LABELS = arrayOf("首页", "神券", "活动", "订单", "我的")



        const val ROUNDED_RECT_SDF = """

            float radiusAt(float2 coord, float4 radii) {

                if (coord.x >= 0.0) {

                    if (coord.y <= 0.0) return radii.y;

                    else return radii.z;

                } else {

                    if (coord.y <= 0.0) return radii.x;

                    else return radii.w;

                }

            }



            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {

                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));

                float outside = length(max(cornerCoord, 0.0)) - radius;

                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);

                return outside + inside;

            }



            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {

                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));

                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {

                    return sign(coord) * normalize(max(cornerCoord, 0.0));

                } else {

                    float gradX = step(cornerCoord.y, cornerCoord.x);

                    return sign(coord) * float2(gradX, 1.0 - gradX);

                }

            }

        """



        const val ROUNDED_RECT_REFRACTION_SHADER = """

            uniform shader content;

            uniform float2 size;

            uniform float2 offset;

            uniform float4 cornerRadii;

            uniform float refractionHeight;

            uniform float refractionAmount;

            uniform float depthEffect;



            $ROUNDED_RECT_SDF



            float circleMap(float x) {

                return 1.0 - sqrt(1.0 - x * x);

            }



            half4 main(float2 coord) {

                float2 halfSize = size * 0.5;

                float2 centeredCoord = (coord + offset) - halfSize;

                float radius = radiusAt(coord, cornerRadii);

                float sd = sdRoundedRect(centeredCoord, halfSize, radius);

                if (-sd >= refractionHeight) return content.eval(coord);

                sd = min(sd, 0.0);

                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;

                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));

                float2 grad = normalize(

                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +

                    depthEffect * normalize(centeredCoord)

                );

                return content.eval(coord + d * grad);

            }

        """



        const val ROUNDED_RECT_DISPERSION_SHADER = """

            uniform shader content;

            uniform float2 size;

            uniform float2 offset;

            uniform float4 cornerRadii;

            uniform float refractionHeight;

            uniform float refractionAmount;

            uniform float depthEffect;

            uniform float chromaticAberration;



            $ROUNDED_RECT_SDF



            float circleMap(float x) {

                return 1.0 - sqrt(1.0 - x * x);

            }



            half4 main(float2 coord) {

                float2 halfSize = size * 0.5;

                float2 centeredCoord = (coord + offset) - halfSize;

                float radius = radiusAt(coord, cornerRadii);

                float sd = sdRoundedRect(centeredCoord, halfSize, radius);

                if (-sd >= refractionHeight) return content.eval(coord);

                sd = min(sd, 0.0);



                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;

                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));

                float2 grad = normalize(

                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +

                    depthEffect * normalize(centeredCoord)

                );

                float2 refractedCoord = coord + d * grad;

                float dispersionIntensity = chromaticAberration *

                    ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));

                float2 dispersedCoord = d * grad * dispersionIntensity;



                half4 color = half4(0.0);

                half4 red = content.eval(refractedCoord + dispersedCoord);

                color.r += red.r / 3.5;

                color.a += red.a / 7.0;

                half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));

                color.r += orange.r / 3.5;

                color.g += orange.g / 7.0;

                color.a += orange.a / 7.0;

                half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));

                color.r += yellow.r / 3.5;

                color.g += yellow.g / 3.5;

                color.a += yellow.a / 7.0;

                half4 green = content.eval(refractedCoord);

                color.g += green.g / 3.5;

                color.a += green.a / 7.0;

                half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));

                color.g += cyan.g / 3.5;

                color.b += cyan.b / 3.0;

                color.a += cyan.a / 7.0;

                half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));

                color.b += blue.b / 3.0;

                color.a += blue.a / 7.0;

                half4 purple = content.eval(refractedCoord - dispersedCoord);

                color.r += purple.r / 7.0;

                color.b += purple.b / 3.0;

                color.a += purple.a / 7.0;

                return color;

            }

        """



        const val BLOOM_STROKE_DUAL_SHADER = """

            uniform float2 halfView;

            uniform float highlightAlpha;



            layout(color) uniform half4 strokeColor;

            uniform float strokeAlphaMul;



            uniform float3 lightDir1;

            layout(color) uniform half4 lightColor1;

            uniform float lightIntensity1;



            uniform float3 lightDir2;

            layout(color) uniform half4 lightColor2;

            uniform float lightIntensity2;



            half4 main(float2 fragCoord) {

                // Canvas strokes the actual MIUIX squircle Path. The shader is

                // responsible only for the moving dual-light intensity, so the

                // highlight can never fall back to a circular rounded-box SDF.

                float2 delta = fragCoord - halfView;

                float2 n = delta / max(length(delta), 0.001);

                half3 rgb = strokeColor.rgb * half(strokeAlphaMul);

                float l1 = dot(n, normalize(lightDir1.xy));

                rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;

                float l2 = dot(n, normalize(lightDir2.xy));

                rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;

                return half4(rgb * half(highlightAlpha), half(highlightAlpha));

            }

        """

    }

}






/** Main-thread spring integrator for navigation gestures and visibility transitions. */

private class SpringFloat(

    initialValue: Float,

    private val stiffness: Float,

    dampingRatio: Float,

    private val threshold: Float,

    private val update: (value: Float, velocity: Float) -> Unit,

) : Choreographer.FrameCallback {

    private val damping = 2f * dampingRatio * sqrt(stiffness)

    private val choreographer = Choreographer.getInstance()

    private var value = initialValue

    private var target = initialValue

    private var lastFrameNanos = 0L

    private var running = false

    var velocity = 0f

        private set



    fun animateTo(newTarget: Float) {

        target = newTarget

        if (!running) {

            running = true

            lastFrameNanos = 0L

            choreographer.postFrameCallback(this)

        }

    }



    fun snapTo(newValue: Float) {

        cancel()

        value = newValue

        target = newValue

        velocity = 0f

        update(value, velocity)

    }



    fun cancel() {

        if (running) choreographer.removeFrameCallback(this)

        running = false

        lastFrameNanos = 0L

    }



    override fun doFrame(frameTimeNanos: Long) {

        if (!running) return

        if (lastFrameNanos == 0L) {

            lastFrameNanos = frameTimeNanos

            update(value, velocity)

            choreographer.postFrameCallback(this)

            return

        }

        val totalDt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 20f)

        lastFrameNanos = frameTimeNanos

        val steps = ceil(totalDt / (1f / 120f)).toInt().coerceAtLeast(1)

        val dt = totalDt / steps

        repeat(steps) {

            val acceleration = -stiffness * (value - target) - damping * velocity

            velocity += acceleration * dt

            value += velocity * dt

        }

        update(value, velocity)

        if (abs(value - target) <= threshold && abs(velocity) <= threshold * 10f) {

            value = target

            velocity = 0f

            running = false

            lastFrameNanos = 0L

            update(value, velocity)

        } else {

            choreographer.postFrameCallback(this)

        }

    }

}



/**

 * Full-width, non-interactive contacts-page veil. It reuses the accepted shared

 * opacity curve but measures WeChat's own taller native navigation edge.

 */







internal class BottomBarSquircleOutlineProvider(

    private val radius: () -> Float,

    private val smoothing: () -> Float = { 0.5f },

) : android.view.ViewOutlineProvider() {

    private val rect = RectF()

    private val path = Path()



    override fun getOutline(view: View, outline: android.graphics.Outline) {

        rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())

        path.setBottomBarSquircle(rect, radius(), smoothing())

        if (!path.isEmpty) outline.setPath(path)

    }

}
