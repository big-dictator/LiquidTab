package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.glassHostWidthPx
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.adapters.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Home-only native filter actions, presented by the same button material as XHS. */
internal object SpotifyHomeTopBar {
    private val sessions = WeakHashMap<Activity, WeakReference<Session>>()
    fun attach(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        sessions[activity]?.get()?.takeIf { it.canReuse(content) }?.let { return }
        sessions[activity]?.get()?.stop()
        Session(activity, content).also { sessions[activity] = WeakReference(it); it.start() }
    }
    fun release(activity: Activity) { sessions[activity]?.get()?.stop() }
    private class Overlay(context: Context) : SpotifyTopPageLayer(context,SpotifyTopContinuity.Page.HOME) {
        val topGradient=SpotifyTopGradient()
        var focusChanged: (() -> Unit)? = null
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) focusChanged?.invoke()
        }
        init { clipChildren = false; clipToPadding = false; translationZ = 18f * resources.displayMetrics.density }
    }
    private class FadingFilters(context: Context) : FrameLayout(context) {
        val scroller=HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled=false;clipChildren=true;clipToPadding=true
        }
        private val fade=Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        init {
            clipChildren=true;clipToPadding=true
            addView(scroller,LayoutParams(-1,-1))
            scroller.setOnScrollChangeListener { _,_,_,_,_ -> invalidate() }
        }
        override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
            super.onSizeChanged(w,h,oldw,oldh)
            val span=32f*resources.displayMetrics.density
            fade.shader=LinearGradient(0f,0f,span,0f,
                intArrayOf(Color.TRANSPARENT,0x18000000,0xB8000000.toInt(),Color.BLACK),
                floatArrayOf(0f,.25f,.7f,1f),Shader.TileMode.CLAMP)
        }
        override fun dispatchDraw(canvas: Canvas) {
            if(scroller.scrollX<=0) { super.dispatchDraw(canvas);return }
            // Apply alpha after replaying the scrolling subtree, in this fixed
            // viewport's coordinates. The mask must never inherit scrollX.
            val layer=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null)
            super.dispatchDraw(canvas)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),fade)
            canvas.restoreToCount(layer)
        }
    }
    private class Session(val activity: Activity, val content: FrameLayout) : ViewTreeObserver.OnPreDrawListener {
        private val d = activity.resources.displayMetrics.density
        private fun dp(n: Float) = (n*d).roundToInt()
        private val decor = activity.window.decorView as? FrameLayout ?: content
        private val overlay = Overlay(activity)
        private var row: LinearLayout? = null
        private var strip: LinearLayout? = null
        private val keyedPanels=LinkedHashMap<String,GlassHostLayout>()
        private var builtConfig: GlassConfig? = null
        private var config = GlassConfig()
        private var configAt = 0L
        private val nativeChrome=SpotifyNativeTopChrome()
        private var scanAt = 0L
        private var startupLayoutAttempts=0
        private val startupLayout=ViewTreeObserver.OnGlobalLayoutListener {
            // Coalesce bounded startup layout signals into the existing pre-draw.
            // No scans, scheduled retries or whole-window draw veto here.
            if(!active && startupLayoutAttempts<12) {
                startupLayoutAttempts++
                scanAt=0L
            }
        }
        private var header: ViewGroup? = null
        private var appbar: ViewGroup? = null
        private var binding: SpotifyHeaderBinding? = null
        private var signature = ""
        private var active = false
        var stopped = false; private set
        private val panels = ArrayList<GlassHostLayout>()
        private val updateControls=ArrayList<(SpotifyHeaderBinding)->Unit>()
        private val anticipateControls=ArrayList<(String)->Unit>()
        private var intentUntil=0L
        private var feed: ViewGroup? = null
        private var feedTop = 0
        private var feedClip = true
        private var feedBounds: Rect? = null
        private var appliedTop = -1
        private var initialScroll = false
        private data class Behavior(val view: View, val params: ViewGroup.LayoutParams,
            val field: java.lang.reflect.Field, val original: Any, val setter: java.lang.reflect.Method)
        private val behaviors=ArrayList<Behavior>(2)
        private var originalAlpha = 1f
        private var originalBackground: Drawable? = null
        private var originalElevation = 0f
        private var originalTranslation = 0f
        private var originalTop = 0
        private val oldStatusColor = activity.window.statusBarColor
        private val oldStatusContrast = activity.window.isStatusBarContrastEnforced
        private var observer: ViewTreeObserver? = null
        private var cachedSceneTime = Long.MIN_VALUE
        private var sceneViews = emptyList<View>()
        private val nativeGreen by lazy { nativeColor("dark_brightaccent_background_base", 0xFF1ED760.toInt()) }
        private val nativeHighlight by lazy { nativeColor("dark_brightaccent_background_highlight", nativeGreen) }
        private val nativeIdle by lazy { nativeColor("dark_base_background_elevated_base", 0xFF292929.toInt()) }
        private fun nativeColor(name: String, fallback: Int): Int = runCatching {
            val id = activity.resources.getIdentifier(name, "color", activity.packageName)
            if (id != 0) activity.getColor(id) else fallback
        }.getOrDefault(fallback)
        private val nativeIds=HashMap<String,Int>()
        private fun find(root: View,name: String): View? {
            val id=nativeIds.getOrPut(name) { activity.resources.getIdentifier(name,"id",activity.packageName) }
            if(id==0)return null
            return root.findViewById<View>(id)?.takeUnless { it is AdapterOwnedOverlay || it is GlassHostLayout }
        }

        private val attachState = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if(!stopped) { scanAt=0L;observeTree();content.postInvalidateOnAnimation() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this@Session);it.removeOnGlobalLayoutListener(startupLayout) };observer=null
                if(activity.isFinishing || activity.isDestroyed)stop()
            }
        }
        fun start() {
            overlay.focusChanged = {
                overlay.post {
                    if (!stopped) {
                        scanAt=0L;cachedSceneTime=Long.MIN_VALUE
                        panels.forEach { it.invalidateProjectionCache();it.invalidateNavigationSnapshot() }
                        onPreDraw()
                    }
                }
            }
            content.addOnAttachStateChangeListener(attachState)
            decor.addView(overlay, FrameLayout.LayoutParams(-1,-1))
            overlay.visibility = View.GONE
            observeTree()
        }
        fun canReuse(current: FrameLayout)=!stopped && content===current && overlay.parent===decor
        private fun observeTree() {
            val current=content.viewTreeObserver
            if(current===observer || !current.isAlive)return
            observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this);it.removeOnGlobalLayoutListener(startupLayout) }
            current.addOnPreDrawListener(this);current.addOnGlobalLayoutListener(startupLayout);observer=current
        }
        private var retryAt=0L
        private var bindingFailures=0
        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if(SystemClock.uptimeMillis()<retryAt)return true
            runCatching { update() }.onSuccess { bindingFailures=0 }.onFailure {
                bindingFailures++
                retryAt=SystemClock.uptimeMillis()+minOf(1000L,64L shl minOf(bindingFailures,4))
                android.util.Log.w("SpotifyGlass","home binding temporarily unavailable; retain accepted toolbar",it)
                if(!config.enabled)restore()
            }
            if(overlay.visibility==View.VISIBLE && overlay.isLayoutRequested && decor.width>0) {
                overlay.measure(View.MeasureSpec.makeMeasureSpec(decor.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(decor.height,View.MeasureSpec.EXACTLY))
                overlay.layout(0,0,decor.width,decor.height)
            }
            return true
        }
        private fun update() {
            observeTree()
            val now = SystemClock.uptimeMillis()
            if (configAt==0L || now-configAt >= 500L) { config = HookConfigReader.read(activity, activity.packageName); configAt=now }
            val visible = config.enabled &&
                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            if (!visible) { deactivate(); return }
            nativeChrome.maintain()
            if (content.windowVisibility!=View.VISIBLE) return
            SpotifyTopDrawer.bind(overlay,activity)
            if (active) appbar?.let { it.translationY=0f;if(it.top!=0)it.offsetTopAndBottom(-it.top) }
            val drawer = find(content, "sidedrawer_compose_view")
            if (drawer?.isShown == true && drawer.alpha>.001f && drawer.getGlobalVisibleRect(Rect())) {
                // The drawer is an occluder, not a home-page exit. Keep native
                // chrome suppressed and retain controls, scroll and bindings.
                if(active) {
                    appbar?.let { nativeChrome.hide(it) }
                    overlay.visibility=View.VISIBLE
                } else overlay.visibility=View.GONE
                return
            }
            if(intentUntil!=0L && now>=intentUntil) { intentUntil=0L;signature="";scanAt=0L }
            val nativeHeader=find(content,"filters_container") as? ViewGroup
            if(nativeHeader?.isShown==true) (find(content,"app_bar") as? ViewGroup)?.let { nativeChrome.hide(it) }
            if (SpotifyTopContinuity.eager(activity,"home") || scanAt==0L || now-scanAt >= 350L || (nativeHeader!=null && nativeHeader!==header && nativeHeader.isShown) || (active && header?.isAttachedToWindow != true)) {
                scanAt=now
                val next = nativeHeader
                if (next == null || !next.isShown || !next.getGlobalVisibleRect(Rect())) { deactivate(); return }
                if (header !== next) {
                    restore(); active=false; header=next; appbar=find(content,"app_bar") as? ViewGroup
                    binding=SpotifyHeaderBinding(next) { active && activity.hasWindowFocus() }; signature=""
                }
                val owner = binding ?: return
                if (!owner.refresh()) { if (!active) overlay.visibility=View.GONE; return }
                owner.avatar?.let { SpotifyProfileArtwork.ensure(activity,it) { scanAt=0L;content.invalidate() } }
                val bar = appbar ?: return
                if (!active) {
                    originalAlpha=bar.alpha; originalBackground=bar.background
                    originalElevation=bar.elevation; originalTranslation=bar.translationY
                    originalTop=bar.top
                }
                active=true
                val key=owner.buttons.joinToString { "${it.label}:${System.identityHashCode(it.source)}:${it.virtualId}" } +
                    ":${System.identityHashCode(owner.avatar?.source)}:${owner.avatar?.virtualId}:${config.hashCode()}"
                if (key != signature) { signature=key; rebuild(owner) }
                else updateControls.forEach { it(owner) }
            }
            if (!active) return
            appbar?.let { nativeChrome.hide(it); it.translationY=0f }
            binding?.discoverHidden()
            overlay.visibility=View.VISIBLE
            val top=activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            overlay.topGradient.setFadeHeight(top+dp(112f))
            val params = row?.layoutParams as? FrameLayout.LayoutParams ?: return
            val screenWidth=decor.width.takeIf { it>0 } ?: activity.resources.displayMetrics.widthPixels
            // Same five-tab host width and screen origin as Spotify's bottom
            // glass; detached Create changes internal slots, not this inset.
            val left=((screenWidth-glassHostWidthPx(content,5,config,d))/2).coerceAtLeast(0)
            if (params.topMargin != top+dp(6f) || params.leftMargin!=left || params.rightMargin!=0) {
                params.topMargin=top+dp(6f);params.leftMargin=left;params.rightMargin=0
                row?.layoutParams=params
            }
            activity.window.statusBarColor=Color.TRANSPARENT
            activity.window.isStatusBarContrastEnforced=false
            layoutFeed(top+dp(60f))
            val side=params.leftMargin
            SpotifyTopContinuity.show(activity,SpotifyTopContinuity.Page.HOME,config,
                Rect(side+dp(4f),top+dp(10f),side+dp(44f),top+dp(50f)),null,"",null,
                { binding?.avatar?.let { binding?.click(it.label) }==true },{ false },header ?: return)
        }
        private fun beginNativeFilterClick(label: String) {
            val role=SpotifyHeaderBinding.role(label)
            if(role=="secondary")return
            intentUntil=SystemClock.uptimeMillis()+320L
            anticipateControls.forEach { it(role) }
            // Bounded native reconciliation, never a background polling loop.
            for(delay in longArrayOf(0L,32L,96L,192L,336L))overlay.postDelayed({
                if(active && !stopped) { scanAt=0L;content.invalidate() }
            },delay)
        }
        private fun rebuild(owner: SpotifyHeaderBinding) {
            if(row==null || builtConfig!=config) {
                panels.forEach { (it.parent as? ViewGroup)?.removeView(it) };panels.clear();updateControls.clear();anticipateControls.clear();keyedPanels.clear()
                overlay.removeAllViews();builtConfig=config
                val layout=LinearLayout(activity).apply {
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                    clipChildren=false;clipToPadding=false
                }
                row=layout
                overlay.addView(layout,FrameLayout.LayoutParams(-1,dp(48f),Gravity.TOP).apply {
                    leftMargin=0;rightMargin=0
                })
                // The persistent shared host is the sole avatar renderer.
                // Keep geometry without a second GlassHost lifecycle/sampler.
                layout.addView(android.widget.Space(activity),
                    LinearLayout.LayoutParams(dp(40f),dp(40f)).apply { rightMargin=dp(8f) })
                val scroll=FadingFilters(activity)
                layout.addView(scroll,LinearLayout.LayoutParams(0,dp(48f),1f))
                strip=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false }
                scroll.scroller.addView(strip,FrameLayout.LayoutParams(-2,-1))
            }
            val buttons=owner.buttons
            val groups=ArrayList<List<SpotifyHeaderBinding.Button>>()
            for (native in buttons) {
                if (SpotifyHeaderBinding.role(native.label)=="secondary" && groups.lastOrNull()?.firstOrNull()?.let {
                    SpotifyHeaderBinding.role(it.label) in setOf("music","podcasts") } == true)
                    groups[groups.lastIndex]=groups.last()+native
                else groups+=listOf(native)
            }
            val measure=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=16f*activity.resources.displayMetrics.scaledDensity }
            groups.forEach { group ->
                val cell=group.maxOf { measure.measureText(it.label) }+dp(28f)
                val width=maxOf(dp(48f),cell.roundToInt())*group.size
                val key=SpotifyHeaderBinding.role(group.first().label)
                val existing=keyedPanels[key]
                if(existing==null) {
                    val view=button(group,false)
                    keyedPanels[key]=view
                    strip?.addView(view,LinearLayout.LayoutParams(width,dp(42f)).apply { rightMargin=dp(8f) })
                } // Existing controls have one geometry owner in updateControls.
            }
            updateControls.forEach { it(owner) }
        }
        private fun button(initialCells: List<SpotifyHeaderBinding.Button>, avatar: Boolean): GlassHostLayout {
            var cells=initialCells
            var drawnCells=initialCells
            var panel: GlassHostLayout?=null
            var tapX=Float.NaN
            val primaryRole=SpotifyHeaderBinding.role(initialCells.first().label)
            val expandable=primaryRole in setOf("music","podcasts")
            val measure=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=16f*activity.resources.displayMetrics.scaledDensity }
            fun cellWidth(items: List<SpotifyHeaderBinding.Button>)=
                maxOf(dp(48f),(items.maxOf { measure.measureText(it.label) }+dp(28f)).roundToInt())
            var primaryWidth=cellWidth(listOf(initialCells.first()))
            var expandedWidth=if(initialCells.size>1)cellWidth(initialCells)*initialCells.size else primaryWidth
            var knownExpandedWidth=expandedWidth.takeIf { initialCells.size>1 }
            var geometryLabel=initialCells.first().label
            var presentationExpanded=expandable && initialCells.first().selected
            var tintColor=if(initialCells.drop(1).any { it.selected })nativeHighlight else nativeGreen
            fun wholeSelected(items: List<SpotifyHeaderBinding.Button>)=
                if(expandable)items.size>1 && items.drop(1).any { it.selected } else items.first().selected
            val motion=SpotifyFilterTransition(
                (if(cells.size>1)expandedWidth else primaryWidth).toDouble(),
                if(expandable && cells.first().selected)1.0 else 0.0,
                if(!avatar && wholeSelected(cells))1.0 else 0.0,SystemClock.uptimeMillis())
            var clock: android.animation.ValueAnimator?=null
            var localIntent: Boolean?=null
            fun drive(width: Int,selected: Boolean,whole: Boolean) {
                val now=SystemClock.uptimeMillis()
                if(!motion.retarget(width.toDouble(),if(selected)1.0 else 0.0,if(whole)1.0 else 0.0,now))return
                clock?.cancel()
                clock=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                    duration=800L
                    addUpdateListener {
                        motion.sample(SystemClock.uptimeMillis())
                        panel?.let { view ->
                            val wanted=motion.width().roundToInt().coerceAtLeast(primaryWidth)
                            if(view.layoutParams.width!=wanted) {
                                view.layoutParams=view.layoutParams.apply { this.width=wanted }
                                view.invalidateProjectionCache()
                            }
                            view.postInvalidateOnAnimation()
                        }
                    }
                    addListener(object: android.animation.AnimatorListenerAdapter() {
                        private var cancelled=false
                        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled=true }
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if(!cancelled && (!presentationExpanded || cells.size>1))drawnCells=cells
                        }
                    });start()
                }
            }
            if(!avatar)anticipateControls += { clickedRole ->
                val selected=clickedRole==primaryRole
                localIntent=selected
                presentationExpanded=expandable && selected
                // Do not invent a second label or a provisional doubled width.
                // Reuse a verified previous expanded measurement if available;
                // otherwise the first fresh native secondary label owns geometry.
                val width=if(selected && expandable)knownExpandedWidth ?: primaryWidth else primaryWidth
                drive(width,selected && expandable,selected && !expandable)
            }
            updateControls += { owner ->
                val index=owner.buttons.indexOfFirst { SpotifyHeaderBinding.role(it.label)==primaryRole }
                val next=if(avatar)listOfNotNull(owner.avatar) else if(index<0)emptyList() else
                    owner.buttons.drop(index).take(1)+owner.buttons.drop(index+1).takeWhile { SpotifyHeaderBinding.role(it.label)=="secondary" }
                if(next.isNotEmpty()) {
                    if(geometryLabel!=next.first().label) {
                        knownExpandedWidth=null
                        geometryLabel=next.first().label
                        drawnCells=next
                    }
                    cells=next
                    primaryWidth=cellWidth(listOf(next.first()))
                    val pending=localIntent.takeIf { SystemClock.uptimeMillis()<intentUntil }
                    if(pending==null)localIntent=null
                    val selected=pending ?: next.first().selected
                    presentationExpanded=expandable && selected
                    if(next.size>1) {
                        expandedWidth=cellWidth(next)*next.size
                        knownExpandedWidth=expandedWidth
                        drawnCells=next
                    } else if(!presentationExpanded && clock?.isRunning!=true)drawnCells=next
                    val visualCells=if(presentationExpanded && next.size==1 && drawnCells.size>1)drawnCells else next
                    tintColor=if(visualCells.drop(1).any { it.selected })nativeHighlight else nativeGreen
                    val width=SpotifyFilterWidth.resolve(expandable,next.first().selected,pending,primaryWidth,
                        if(next.size>1)expandedWidth else null,knownExpandedWidth)
                    // An expandable primary's transient singleton selection must
                    // never paint the whole capsule green. Only its own segment.
                    drive(width,expandable && selected,if(pending!=null)!expandable && pending else wholeSelected(visualCells))
                    // Keep two native action slots for expandable controls without
                    // letting a slot-count rewrite overwrite the animation width.
                }
            }
            val state=object : AppNavigationState {
                val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER }
                val fill=Paint(Paint.ANTI_ALIAS_FLAG)
                val path=Path()
                override val allowsSoftwareBackdropCapture=false
                override val backdropSceneViewsAreDescendants=true
                override fun resolveBackdropScene(host: View): View=content
                override fun backdropSceneBaseColor(scene: View, fallback: Int)=0xFF121212.toInt()
                override fun forceLiveBackdropFrame(selectedIndex: Int)=true
                override fun backdropSceneViews(content: ViewGroup, hostIndex: Int)=scene()
                override fun allowsHostTouch()=!SpotifyTopDrawer.isOpen(activity)
                override fun resolveAutoDarkMode(host: View)=true
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int)=true
                override fun suppressRedrawnSource()=false
                override fun forceNavigationBarImmersed(host: GlassHostLayout)=false
                override fun pauseBackdropWork(host: View)=!active || !activity.hasWindowFocus() || !host.isShown
                override fun interceptNavigationTouch(host: View,event: MotionEvent): Boolean? {
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP -> tapX=event.x
                        MotionEvent.ACTION_CANCEL -> tapX=Float.NaN
                    }
                    // GlassHost dispatches through this callback before its normal
                    // gesture handling; returning null preserves scroll/press/cancel.
                    return null
                }
                override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
                    val segment=SpotifyFilterTransition.segment(host.width.toFloat(),primaryWidth,expandedWidth)
                    val action=when {
                        !expandable -> 0
                        tapX.isFinite() -> if(tapX>=segment)1 else 0
                        else -> index.coerceIn(0,1)
                    }
                    tapX=Float.NaN
                    // An accepted visible child can temporarily leave the native
                    // lazy-row snapshot. Refresh/reveal its label via the binding;
                    // never turn that right-side tap into a primary-button click.
                    val target=if(action==0)cells.firstOrNull() else
                        drawnCells.getOrNull(1)?.takeIf { presentationExpanded }
                    if(action>0)localIntent=null
                    val result=target?.let {
                        if(!avatar)beginNativeFilterClick(it.label)
                        binding?.click(it.label)
                    } == true
                    scanAt=0L;host.postInvalidateOnAnimation();host.postDelayed({ scanAt=0L;content.invalidate() },100L)
                    return result
                }
                override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                    if (avatar) {
                        val side=minOf(frame.width,frame.height)*.72f
                        val saved=canvas.save();canvas.translate((frame.width-side)/2f,(frame.height-side)/2f)
                        path.reset();path.addOval(0f,0f,side,side,Path.Direction.CW);canvas.clipPath(path)
                        cells.first().drawAvatar(canvas,side);canvas.restoreToCount(saved);return
                    }
                    val selected=motion.selected().toFloat().coerceIn(0f,1f)
                    val expansion=SpotifyFilterTransition.reveal(frame.width.toFloat(),primaryWidth,expandedWidth)
                    val segment=SpotifyFilterTransition.segment(frame.width.toFloat(),primaryWidth,expandedWidth)
                    val extra=(frame.width-segment).coerceAtLeast(0f)
                    if(expandable && selected>0f) {
                        val inset=3f*frame.density
                        path.setBottomBarSquircle(RectF(inset,inset,minOf(frame.width.toFloat(),segment)-inset,frame.height-inset),
                            (frame.height-2*inset)/2f,config.cornerSmoothing)
                        fill.color=(nativeGreen and 0xffffff) or ((config.darkAlpha*255*selected).roundToInt().coerceIn(0,255) shl 24)
                        canvas.drawPath(path,fill)
                    }
                    text.textSize=16f*frame.scaledDensity
                    drawnCells.forEachIndexed { i,cell ->
                        text.color=if(if(i==0 && expandable)selected>.5f else cell.selected)Color.BLACK else Color.WHITE
                        text.alpha=if(i==0)255 else (255*expansion).roundToInt().coerceIn(0,255)
                        val center=if(!expandable)frame.width/2f else if(i==0)minOf(frame.width.toFloat(),segment)/2f else segment+extra/2f
                        canvas.drawText(cell.label,center,frame.height/2f-(text.ascent()+text.descent())/2f,text)
                    }
                }
            }
            val adapter=object : TargetAdapter {
                override val key="spotify-home-button"
                // Catalog/XHS button feedback: 4dp growth at the 48dp reference,
                // rather than the navigation bar's 16dp whole-panel expansion.
                override val outerPanelPressScale=1f+4f/48f
                override val pressedIndicatorScale=1f+4f/48f
                override val ownsNavigationDrawing=true
                override val ownsNavigationTap=true
                override val managesHostSiblingOrder=true
                override val skipNavigationSnapshot=true
                override val hasPerFrameScene=true
                override val allowRenderNodeRefresh=true
                override val skipBackdropStableDelay=true
                override val allowsSystemBackgroundBlur=false
                private val targetMatrix=Matrix()
                override fun liveBackdropTransform(parent: View,host: View,out: Matrix)=
                    SpotifyBackdropSampling.transform(parent,host,out,targetMatrix)
                override fun backdropBlurEffect(radius: Float)=SpotifyBackdropSampling.effect(radius)
                override fun createNavigationState(context: Context)=state
                override fun surfaceOpacityScale(config: GlassConfig): Float =
                    config.spotifyBarOpacity+(1f-config.spotifyBarOpacity)*motion.tint().toFloat().coerceIn(0f,1f)
                override fun glassSurfaceFillColor(dark: Boolean,selectedIndex: Int,defaultColor: Int): Int {
                    val mix=motion.tint().toFloat().coerceIn(0f,1f)
                    fun channel(idle: Int,green: Int)=(idle+(green-idle)*mix).roundToInt()
                    val color=Color.rgb(channel(Color.red(nativeIdle),Color.red(tintColor)),
                        channel(Color.green(nativeIdle),Color.green(tintColor)),channel(Color.blue(nativeIdle),Color.blue(tintColor)))
                    return (color and 0xffffff) or (Color.alpha(defaultColor) shl 24)
                }
                override fun normalizeConfig(config: GlassConfig)=config.copy(detachedPublish=false,hiddenMask=0,
                    iconOnly=true,barHeight=42f,cornerRadiusPercent=50f,selectedAccent=false)
            }
            val created=GlassHostLayout(activity,config,if(expandable)2 else 1,navigationSource=null,sliderEnabled=false,adapterOverride=adapter)
                .apply { isClickable=true;isFocusable=false;elevation=0f;contentDescription=cells.joinToString { it.label };panels+=this }
            panel=created
            created.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View)=Unit
                override fun onViewDetachedFromWindow(v: View) { clock?.cancel() }
            })
            return created
        }
        private fun scene(): List<View> = SpotifyBackdropScene.views(content)
        private fun layoutFeed(space: Int) {
            val parent=appbar?.parent as? ViewGroup ?: return
            val current=find(parent,"recycler_view") as? ViewGroup ?: return
            if(feed !== current) {
                restoreFeed();feed=current;feedTop=current.paddingTop;feedClip=current.clipToPadding
                feedBounds=Rect(current.left,current.top,current.right,current.bottom);initialScroll=false
            }
            if(behaviors.isEmpty()) detachNativeScroll(parent,current)
            val padding=feedTop+space
            if(current.paddingTop!=padding) current.setPadding(current.paddingLeft,padding,current.paddingRight,current.paddingBottom)
            appliedTop=padding;current.clipToPadding=false
            if(parent.width>0 && parent.height>0 && (current.top!=0 || current.height!=parent.height)) {
                current.measure(View.MeasureSpec.makeMeasureSpec(parent.width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(parent.height,View.MeasureSpec.EXACTLY))
                current.layout(0,0,parent.width,parent.height)
            }
            if(!initialScroll) {
                initialScroll=true
                runCatching {
                    val manager=current.javaClass.getMethod("getLayoutManager").invoke(current)
                    val offset=manager?.javaClass?.methods?.firstOrNull {
                        it.name=="scrollToPositionWithOffset" && it.parameterCount==2 }
                    if(offset!=null)offset.invoke(manager,0,0)
                    else current.javaClass.getMethod("scrollToPosition",Int::class.javaPrimitiveType).invoke(current,0)
                }
            }
        }
        private fun detachNativeScroll(coordinator: ViewGroup, recycler: View) {
            // funkis_home.xml attaches ScrollingViewBehavior to a FrameLayout
            // around RecyclerView. Lift that actual coordinator child, not just
            // the RecyclerView inside its still-offset/clipped parent.
            var branch: View=recycler
            while(branch.parent is View && branch.parent!==coordinator) branch=branch.parent as View
            val bar=appbar ?: return
            fun read(view: View): Behavior? {
                val params=view.layoutParams ?: return null
                val field=params.javaClass.fields.filter { f ->
                    !java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type.methods.any { m ->
                        m.returnType==Boolean::class.javaPrimitiveType && m.parameterCount==3 &&
                            m.parameterTypes[0].isInstance(coordinator) &&
                            ViewGroup::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                            m.parameterTypes[0]!=ViewGroup::class.java && m.parameterTypes[1]==View::class.java &&
                            m.parameterTypes[2]==Int::class.javaPrimitiveType
                    }
                }.singleOrNull()?.apply { isAccessible=true } ?: return null
                val value=field.get(params) ?: return null
                val setter=params.javaClass.methods.singleOrNull { m ->
                    m.parameterCount==1 && m.parameterTypes[0]==field.type && m.returnType==Void.TYPE
                } ?: return null
                return Behavior(view,params,field,value,setter)
            }
            val pair=listOfNotNull(read(bar),read(branch))
            if(pair.size!=2)return
            try {
                pair.forEach { it.setter.invoke(it.params,null);it.view.requestLayout() }
                behaviors.addAll(pair)
            } catch(error: Throwable) {
                pair.forEach { runCatching { it.setter.invoke(it.params,it.original) } }
                throw error
            }
        }
        private fun restoreFeed() {
            behaviors.forEach { saved -> runCatching {
                if(saved.view.layoutParams===saved.params && saved.field.get(saved.params)==null) {
                    saved.setter.invoke(saved.params,saved.original);saved.view.requestLayout()
                }
            } };behaviors.clear()
            feed?.let { view ->
                if(view.paddingTop==appliedTop) view.setPadding(view.paddingLeft,feedTop,view.paddingRight,view.paddingBottom)
                // A false baseline may already belong to the bottom-bar
                // adapter. Do not overwrite a later restoration by its owner.
                if(feedClip && !view.clipToPadding)view.clipToPadding=true
                feedBounds?.let { if(view.isAttachedToWindow) view.layout(it.left,it.top,it.right,it.bottom) }
                view.requestLayout()
            };feed=null;feedBounds=null;appliedTop=-1
        }
        private fun deactivate() { restore();active=false;overlay.visibility=View.GONE }
        private fun restore() {
            SpotifyTopContinuity.leave(activity,SpotifyTopContinuity.Page.HOME)
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped)nativeChrome.restore()
            else nativeChrome.maintain()
            restoreFeed()
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped) {
                activity.window.statusBarColor=oldStatusColor
                activity.window.isStatusBarContrastEnforced=oldStatusContrast
            }
            cachedSceneTime=Long.MIN_VALUE
        }
        fun stop() {
            if(stopped)return
            stopped=true;restore();active=false
            overlay.focusChanged=null
            content.removeOnAttachStateChangeListener(attachState)
            observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this);it.removeOnGlobalLayoutListener(startupLayout) };observer=null
            if(sessions[activity]?.get()===this)sessions.remove(activity)
            overlay.post { (overlay.parent as? ViewGroup)?.removeView(overlay);panels.clear();updateControls.clear();anticipateControls.clear() }
        }
    }
}
