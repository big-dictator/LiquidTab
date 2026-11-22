package io.github.offlineglass.hook.adapters.x

import io.github.offlineglass.rendering.setFloatUniformExact

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * X 自绘发布键：右下角悬浮于液态玻璃底栏上方。
 *
 * 视觉（与 X 原生 FAB 同位同构，符合液态玻璃规范）：
 *  - 背后内容实时模糊：RenderNode 采样全屏 ComposeView 在本键区域的
 *    内容，套 RenderEffect 高斯模糊（宿主玻璃条同款机制，真·backdrop
 *    blur，非模拟噪点）。
 *  - 半透明 X 主题蓝叠底 + 顶部径向高光 + 1px 边缘光 + 白色加号。
 *  - 按压时 0.94 缩放 + 高光增强，松开恢复。
 *
 * 交互：点击回调由 [XBottomBarHooks] 按参数结构识别并保存的原生发推
 * Function0 原样触发，Decompose 导航/analytics 与原生完全一致。
 *
 * 页面显隐由 XAdapter 管理；信息流滚动由同一收拢进度控制尺寸与位置，
 * 不缩放已完成的玻璃采样，不改变原生点击动作。
 */
internal class XFloatingFab(
    context: Context,
    /** 采样源：X 的全屏 ComposeView（FAB 与其平级，采样不含 FAB 自身）。 */
    behind: View,
) : View(context) {

    companion object {
        const val SIZE_DP = 56f
        /** 与玻璃条之间的竖直间距。 */
        const val GAP_DP = 24f
        /** 半透明 X 主题蓝底色（35% alpha：液态玻璃以透为主，蓝只做色相）。 */
        const val BLUE_ALPHA = 0x59
        /** 配置轮询节流；对齐宿主 GlassHostLayout.CONFIG_POLL_INTERVAL_MS。 */
        private const val CONFIG_POLL_INTERVAL_MS = 1_000L

        /** View.mRenderNode：Android 正常遍历记录的 display list 持有者。 */
        private val renderNodeField by lazy(LazyThreadSafetyMode.NONE) {
            runCatching {
                View::class.java.getDeclaredField("mRenderNode")
                    .apply { isAccessible = true }
            }.getOrNull()
        }
    }

    private val density = resources.displayMetrics.density
    /** backdrop 采样外扩边距：折射位移的采样落点仍落在录制的区域内。 */
    private val backdropPad = 12f * density
    private val sourceLocation = IntArray(2)
    private val actionLocation = IntArray(2)
    private var behindRef = WeakReference(behind)
    private val blurNode = RenderNode("XFloatingFabBlur")
    private var recordedSource: RenderNode? = null
    private var recordedX = 0
    private var recordedY = 0

    fun setBackdropSource(source: View) {
        if (behindRef.get() === source) return
        behindRef = WeakReference(source)
        recordedSource = null
        blurNode.discardDisplayList()
        invalidate()
    }
    /** 液态透镜 shader（宿主 ROUNDED_RECT_REFRACTION_SHADER 同源圆形配置）。 */
    private var lensShader: RuntimeShader? = null
    private var lensEffectApplied = false
    /** 当前生效的模块配置（设置页滑块）；变化时重建模糊/透镜效果。 */
    private var liveConfig: io.github.offlineglass.config.GlassConfig? = null
    private var lastConfigPoll = 0L
    private var collapseHost = WeakReference<View>(null)
    private var collapseProgress = 0f
    private var expandedPanelWidth = 0
    private var currentPanelHeight = 0

    internal fun setCollapseGeometry(host: View, progress: Float, fullWidth: Int, panelHeight: Int) {
        if (collapseHost.get() !== host) collapseHost = WeakReference(host)
        collapseProgress = progress
        expandedPanelWidth = fullWidth
        currentPanelHeight = panelHeight
        alignAboveGlassBar()
    }
    private val circlePath = Path()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEdge = io.github.offlineglass.rendering.NativeGlassEdge()
    private val edgeContour = android.graphics.Path()
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val plusArmDp = 9.5f
    private val plusStrokeDp = 3.2f

    @Volatile private var pressed = false

    /** 点击回调；创建时由 [XAdapter] 注入原生偷取的发推回调。 */
    @Volatile internal var onClickAction: (() -> Unit)? = null

    init {
        // 可点击控件必须声明 clickable：既修正无障碍语义（不声明时
        // a11y 树不收录本键，也挡住系统对装饰 View 的触摸忽略），
        // 也让 uiautomator 可观测。
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 宿主扫描器 install 成功后停止轮询，findNavigation 内的每轮
        // 锚定不再执行；host 又在本键之后落位（onLayout 因位置未变
        // 不重入）。借 preDraw 在 host 完成首次布局的当帧锚定一次，
        // 成功后自移除，无轮询。
        viewTreeObserver.addOnPreDrawListener(anchorOnPreDraw)
        // 切回 X 即读配置：本键隐藏态（INVISIBLE）不走 draw，window
        // focus 回调不依赖可见性，保证设置页开关/滑块变更切回即生效。
        viewTreeObserver.addOnWindowFocusChangeListener(windowFocusPoll)
    }

    override fun onDetachedFromWindow() {
        runCatching { viewTreeObserver.removeOnPreDrawListener(anchorOnPreDraw) }
        runCatching { viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusPoll) }
        recordedSource = null
        blurNode.discardDisplayList()
        super.onDetachedFromWindow()
    }

    private val windowFocusPoll =
        android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) pollGlassConfig()
        }

    private val anchorOnPreDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            alignAboveGlassBar()
            val content = parent as? android.view.ViewGroup
            var anchored = false
            if (content != null) {
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i)
                    if (child !== this@XFloatingFab &&
                        child is io.github.offlineglass.hook.GlassHostLayout &&
                        child.bottom > 0 && child.right > 0
                    ) {
                        anchored = true
                        break
                    }
                }
            }
            if (anchored) runCatching { viewTreeObserver.removeOnPreDrawListener(this) }
            return true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = w / 2f
        circlePath.reset()
        circlePath.addCircle(r, r, r, Path.Direction.CW)
        // shader uniform 含 size：尺寸变化后效果必须重建。
        lensEffectApplied = false
    }

    /**
     * 节流读取模块设置页配置（模糊半径滑块、液态玻璃开关）。对齐宿主
     * maybeReloadConfig 的轮询节奏：draw 路径 1s 读一次 HookConfigReader；
     * 配置变化时重建效果并 invalidate 下一帧生效（切回 X 必然重绘，
     * 与宿主 cfgApply 同一驱动时机）。
     */
    private fun pollGlassConfig() {
        val now = android.os.SystemClock.uptimeMillis()
        val cached = liveConfig
        if (cached != null && now - lastConfigPoll < CONFIG_POLL_INTERVAL_MS) return
        lastConfigPoll = now
        val updated = runCatching {
            io.github.offlineglass.hook.HookConfigReader.readFresh(context, XAdapter.PACKAGE_NAME)
        }.getOrNull() ?: return
        if (updated != cached) {
            liveConfig = updated
            lensEffectApplied = false
            // 设置页“自动隐藏底栏”等 X 开关变化即时转发（本键隐藏态不
            // draw，靠 window focus 回调兜底触发本方法）。
            XAdapter.onConfigRefreshed(updated)
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        alignAboveGlassBar()
    }

    /**
     * 宿主玻璃条在本键之后创建（findNavigation 先于 GlassInstaller 的
     * host 装载），host addView 会触发 content 重新布局——本键借 onLayout
     * 自锚定，无需依赖外部扫描时机：
     *  - 底边距 = 玻璃条顶 + 间距（严格高于底栏上边界，不被遮挡）；
     *  - 右边距 = 玻璃条右边到屏幕的留白（与底栏相同）；
     *  - XAdapter 统一维护底栏和发布键的相对层级。
     * host 尚未完成 layout（bottom/right 为 0）时视为不存在，跳过锚定
     * ——否则会把本键推到屏幕外。全部幂等。
     */
    internal fun alignAboveGlassBar() {
        val content = parent as? android.view.ViewGroup ?: return
        var host: View? = collapseHost.get()?.takeIf { it.parent === content }
        if (host == null) for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child !== this && child is io.github.offlineglass.hook.GlassHostLayout &&
                child.bottom > 0 && child.right > 0
            ) {
                host = child
                break
            }
        }
        if (host == null) return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        // 视觉药丸 = host 全 bounds（宿主 outerRect=(0,0,w,h)）。锚定
        // 目标是药丸顶边：host.top（v10 曾误用 host.bottom 把本键拉进
        // 玻璃条区域）。底边距 = 药丸顶 + 间距；右边距与药丸一致。
        val p = collapseProgress
        val fullWidth = expandedPanelWidth.takeIf { it > 0 } ?: host.width
        // The right edge remains at the expanded bar edge, never follows its shrinking right edge.
        val targetEnd = (content.width - fullWidth) / 2
        val panelHeight = currentPanelHeight.takeIf { it > 0 } ?: host.height
        val panelLp = host.layoutParams as? FrameLayout.LayoutParams
        val panelBottom = panelLp?.bottomMargin ?: (content.height - host.bottom)
        val targetBottom = panelBottom + ((panelHeight + GAP_DP * density) * (1f-p)).toInt()
        val size = XChromeCollapsePolicy.extent(SIZE_DP * density,
            minOf(SIZE_DP * density, maxOf(48f * density, SIZE_DP * density * 0.84f)), p).toInt()
        if (lp.bottomMargin != targetBottom || lp.rightMargin != targetEnd || lp.width != size || lp.height != size) {
            lp.width = size; lp.height = size
            lp.bottomMargin = targetBottom
            // FrameLayout 测量直接读 rightMargin 字段；marginEnd 只在
            // layoutDirection resolve 时才拷贝过去——直接改 marginEnd
            // 不生效。两个字段必须同设。
            lp.rightMargin = targetEnd
            lp.marginEnd = targetEnd
            layoutParams = lp
        }
    }

    override fun draw(canvas: Canvas) {
        val actionLabel = if (XBottomBarHooks.selectedIndex == 4) "新建聊天" else "发布"
        if (contentDescription != actionLabel) contentDescription = actionLabel
        pollGlassConfig()
        super.draw(canvas)
        val r = width / 2f
        if (r <= 0f) return
        val cx = r
        val cy = r

        // 1) backdrop：采样背后内容并实时模糊（宿主玻璃条同款机制）。
        drawBlurredBackdrop(canvas)

        // 2) 半透明 X 主题蓝底（径向渐变增强液态感：上亮下暗）。
        val base = (BLUE_ALPHA shl 24) or (XAdapter.ACCENT_COLOR and 0xFFFFFF)
        val lifted = ((BLUE_ALPHA + 0x18).coerceAtMost(0xFF) shl 24) or (XAdapter.ACCENT_COLOR and 0xFFFFFF)
        tintPaint.shader = RadialGradient(
            cx, cy - r * 0.45f, r * 1.9f,
            intArrayOf(lifted, base),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, tintPaint)

        // 3) 顶部内侧高光（液态玻璃光带）。
        val highlightAlpha = if (pressed) 0x42 else 0x2C
        tintPaint.shader = RadialGradient(
            cx, cy - r * 0.55f, r * 1.15f,
            intArrayOf((highlightAlpha shl 24) or 0xFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, tintPaint)

        val edgeConfig = liveConfig
        if (edgeConfig?.liquidGlassEnabled == true && !edgeConfig.solidBarEnabled) {
            if (edgeConfig.outlineEnabled) {
                edgeContour.rewind()
                edgeContour.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
                glassEdge.draw(canvas, edgeContour, density, edgeConfig.systemDark, (if (pressed) .50f else .36f) * 2f * edgeConfig.darkBarHighlightStrength)
            }
        } else {
            rimPaint.color = (0x38 shl 24) or 0xFFFFFF
            rimPaint.style = Paint.Style.STROKE
            rimPaint.strokeWidth = Math.max(1f, density)
            canvas.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2f, rimPaint)
        }

        // Same glass component, separate native actions: envelope in Chat, plus elsewhere.
        plusPaint.color = Color.WHITE
        val iconScale = width / (SIZE_DP * density)
        plusPaint.strokeWidth = plusStrokeDp * density * iconScale
        val arm = plusArmDp * density * iconScale
        if (XBottomBarHooks.selectedIndex == 4) {
            val halfH = arm * 0.72f
            canvas.drawRoundRect(cx - arm, cy - halfH, cx + arm, cy + halfH,
                2f * density, 2f * density, plusPaint)
            canvas.drawLine(cx - arm, cy - halfH, cx, cy + halfH * 0.15f, plusPaint)
            canvas.drawLine(cx, cy + halfH * 0.15f, cx + arm, cy - halfH, plusPaint)
        } else {
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plusPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plusPaint)
        }
    }

    /**
     * 液态玻璃 backdrop：采样源（全屏 ComposeView）在本键背后的内容，
     * 走宿主玻璃条同款渲染管线——高斯模糊先行、再过
     * ROUNDED_RECT_REFRACTION_SHADER 液态透镜（Snell 折射、边缘光带、
     * 内部放大），圆形 cornerRadii 配置复刻宿主 drawLocalActionGlass。
     *
     * 采样机制与宿主 viewRenderNode 相同：反射取源 View 的
     * mRenderNode，复用 Android 正常绘制遍历记录的 display list（对
     * ComposeView 重画会得到空内容——微信同款坑），像素随 app 滚动
     * 自动更新。
     */
    private fun drawBlurredBackdrop(canvas: Canvas) {
        val src = behindRef.get() ?: return
        if (src.width <= 0 || src.height <= 0 || width <= 0) return
        // TODO(probe) 三态角标：红=采样链异常，洋红=透镜构建失败（回退
        // 纯模糊），无角标=液态透镜已应用。验证后移除。
        var probeColor = 0
        runCatching {
            val srcNode = renderNodeField?.get(src) as? RenderNode
            if (srcNode != null && srcNode.hasDisplayList()) {
                val pad = backdropPad
                val nodeW = width + pad.toInt() * 2
                val nodeH = height + pad.toInt() * 2
                src.getLocationInWindow(sourceLocation)
                getLocationInWindow(actionLocation)
                val x = sourceLocation[0] - actionLocation[0] - src.left
                val y = sourceLocation[1] - actionLocation[1] - src.top
                // The retained display list reads the current native child. Only
                // source replacement, geometry or movement needs a new wrapper.
                if (!blurNode.hasDisplayList() || recordedSource !== srcNode ||
                    recordedX != x || recordedY != y || blurNode.width != nodeW || blurNode.height != nodeH) {
                    blurNode.setPosition(0, 0, nodeW, nodeH)
                    val recording = blurNode.beginRecording()
                    try {
                        recording.clipRect(0, 0, nodeW, nodeH)
                        recording.translate(x + pad, y + pad)
                        recording.drawRenderNode(srcNode)
                    } finally {
                        blurNode.endRecording()
                    }
                    recordedSource = srcNode
                    recordedX = x
                    recordedY = y
                }
                if (!lensEffectApplied) {
                    // 宿主同款分支（applyEffectsForConfig）：模糊半径取设置页
                    // 滑块 config.blurRadius（dp → px，X 无 secondaryBlurScale
                    // 折减）；液态玻璃开关开着则在模糊上叠加透镜链，关着只做
                    // 模糊，两者皆关（radius=0 且无透镜）时纯采样不加效果。
                    val cfg = liveConfig
                    val radiusPx = (cfg?.blurRadius ?: 0f) * density
                    val liquidEnabled = cfg?.liquidGlassEnabled == true
                    val blur = if (radiusPx > 0f || liquidEnabled) runCatching {
                        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                    }.getOrNull() else null
                    val effect = if (liquidEnabled) {
                        val chain = runCatching {
                            buildLiquidGlassEffect(width, height, pad, radiusPx)
                        }.getOrNull()
                        if (chain == null) probeColor = 0xFFFF00FF.toInt()
                        chain ?: blur
                    } else blur
                    blurNode.setRenderEffect(effect)
                    lensEffectApplied = true
                }
                canvas.save()
                // 先在 view 自身坐标系裁圆（与蓝圆同圆心同半径），再平移。
                // 顺序不能反：clipPath 在 translate 之后会随坐标系偏移 pad，
                // 把模糊区域裁进左上偏移的圆里，与蓝色控件错位。
                canvas.clipPath(circlePath)
                canvas.translate(-pad, -pad)
                canvas.drawRenderNode(blurNode)
                canvas.restore()
            }
        }.onFailure {
            probeColor = 0xFFFF0000.toInt()
            // 采样失败时保留纯半透明蓝底，不至于空白。
        }
        if (probeColor != 0) {
            val s = 8f * density
            plusPaint.style = Paint.Style.FILL
            plusPaint.color = probeColor
            plusPaint.strokeWidth = 0f
            canvas.drawRect(width - s, height - s, width.toFloat(), height.toFloat(), plusPaint)
            plusPaint.style = Paint.Style.STROKE
            plusPaint.strokeWidth = plusStrokeDp * density
        }
    }

    /**
     * blur -> 液态透镜 链式效果（与宿主 action glass 相同次序）。
     * 透镜 uniform 全套对齐宿主圆形配置：无 flow/tilt/posture。
     */
    private fun buildLiquidGlassEffect(
        w: Int, h: Int, pad: Float, blurRadiusPx: Float,
    ): RenderEffect? {
        val lens = lensShader ?: runCatching {
            RuntimeShader(io.github.offlineglass.hook.GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        }.getOrNull()?.also { lensShader = it } ?: return null
        val r = minOf(w, h) / 2f
        lens.setFloatUniform("size", w.toFloat(), h.toFloat())
        lens.setFloatUniform("detachedGeometry", 0f, 0f, 0f)
        lens.setFloatUniform("offset", -pad, -pad)
        lens.setFloatUniform("cornerRadii", r, r, r, r)
        lens.setFloatUniform("safeSampleBounds",
            pad + 0.5f, pad + 0.5f, pad + w - 0.5f, pad + h - 0.5f)
        // 宿主 setOuterLensUniforms 默认 24dp；小圆上加大位移让折射可感知。
        lens.setFloatUniform("refractionHeight", 24f * density)
        lens.setFloatUniform("refractionAmount", -30f * density * (liveConfig?.refractionScale ?: 1f))
        lens.setFloatUniform("depthEffect", 0f)
        lens.setFloatUniform("tiltBias", 0f, 0f)
        lens.setFloatUniformExact("flowingLiquidEnabled", 0f)
        lens.setFloatUniform("fullSurfaceWeight", 0f)
        lens.setFloatUniformExact("unifiedDropletPilot", 0f)
        lens.setFloatUniformExact("directionalRimPilot", 0f)
        lens.setFloatUniformExact("monotoneFlowPilot", 0f)
        lens.setFloatUniform("flowRipple", 0f, 0f, 1f, 0f)
        lens.setFloatUniform("flowRebound", 0f, 0f)
        lens.setFloatUniformExact("flowMotionEnabled", 0f)
        io.github.offlineglass.rendering.PostureGlass.sync(lens, this, false)
        val lensEffect = RenderEffect.createRuntimeShaderEffect(lens, "content")
        // 模糊半径来自设置页滑块（radius=0 时为 identity，与宿主同款用法）。
        val blurEffect = RenderEffect.createBlurEffect(
            blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP,
        )
        // createChainEffect(outer, inner)：inner 先应用——模糊先行，
        // 透镜在模糊结果上折射（与宿主 action glass 相同）。
        return RenderEffect.createChainEffect(lensEffect, blurEffect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (XAdapter.isDrawerOpen) {
            if (pressed) { pressed = false; invalidate() }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                pressed = false
                invalidate()
                if (wasPressed && isInsideCircle(event)) {
                    onClickAction?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInsideCircle(event: MotionEvent): Boolean {
        val r = width / 2f
        val dx = event.x - r
        val dy = event.y - r
        return dx * dx + dy * dy <= r * r
    }

    /** 初始布局参数；host 落位后由 [alignAboveGlassBar] 自锚定精确对齐。 */
    internal fun desiredLayoutParams(): FrameLayout.LayoutParams {
        val size = (SIZE_DP * density).toInt()
        return FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = ((XAdapter.TAB_ROW_DP + GAP_DP) * density).toInt()
            rightMargin = (14f * density).toInt()
            marginEnd = rightMargin
        }
    }
}

