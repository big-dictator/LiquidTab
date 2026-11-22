package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.*
import io.github.offlineglass.hook.adapters.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object SpotifyLibraryTopBar {
    private val sessions=WeakHashMap<Activity,WeakReference<Session>>()
    fun attach(activity: Activity) {
        if(activity.isFinishing || activity.isDestroyed)return
        val content=activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        sessions[activity]?.get()?.takeIf { !it.stopped && it.content===content }?.let { return }
        sessions[activity]?.get()?.stop()
        Session(activity,content).also { sessions[activity]=WeakReference(it);it.start() }
    }
    fun release(activity: Activity) { sessions[activity]?.get()?.stop() }
    private class Layer(context: android.content.Context): SpotifyTopPageLayer(context,SpotifyTopContinuity.Page.LIBRARY) {
        val topGradient=SpotifyTopGradient()
        init { clipChildren=false;clipToPadding=false;translationZ=18f*resources.displayMetrics.density }
    }
    private class Session(val activity: Activity,val content: FrameLayout): ViewTreeObserver.OnPreDrawListener {
        private val decor=activity.window.decorView as? FrameLayout ?: content
        private val layer=Layer(activity)
        private val d=activity.resources.displayMetrics.density
        private fun dp(n: Float)=(n*d).roundToInt()
        var stopped=false;private set
        private var active=false
        private var data: SpotifyLibraryBinding.Result?=null
        private var nodes=emptyList<Any>()
        private var config=GlassConfig()
        private var configAt=0L
        private var scanAt=0L
        private var startupLayoutAttempts=0
        private val startupLayout=ViewTreeObserver.OnGlobalLayoutListener {
            // Coalesce bounded startup layout signals into the existing pre-draw.
            // No scans, scheduled retries or whole-window draw veto here.
            if(!active && startupLayoutAttempts<12) {
                startupLayoutAttempts++
                scanAt=0L
            }
        }
        private var signature=""
        private var builtConfig: GlassConfig?=null
        private var observer: ViewTreeObserver?=null
        private val panels=ArrayList<GlassHostLayout>()
        private var filters: HorizontalScrollView?=null
        private var strip: LinearLayout?=null
        private val filterViews=LinkedHashMap<String,GlassHostLayout>()
        private val selectionUpdates=HashMap<GlassHostLayout,()->Unit>()
        private var cachedScene=emptyList<View>()
        private var sceneAt=Long.MIN_VALUE
        private val protectionAlpha=WeakHashMap<View,Float>()
        private var oldStatus=0
        private var oldContrast=false
        private val attachState=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if(!stopped) { scanAt=0L;observe();content.postInvalidateOnAnimation() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this@Session);it.removeOnGlobalLayoutListener(startupLayout) };observer=null
                if(activity.isFinishing || activity.isDestroyed)stop()
            }
        }
        private val nativeIds=HashMap<String,Int>()
        private fun find(root: View,name: String): View? {
            val id=nativeIds.getOrPut(name) { activity.resources.getIdentifier(name,"id",activity.packageName) }
            if(id==0)return null
            return root.findViewById<View>(id)?.takeUnless { it is AdapterOwnedOverlay || it is GlassHostLayout }
        }

        fun start() {
            content.addOnAttachStateChangeListener(attachState)
            decor.addView(layer,FrameLayout.LayoutParams(-1,-1))
            layer.visibility=View.GONE
            observe()
        }
        private fun observe() {
            val next=content.viewTreeObserver
            if(next===observer || !next.isAlive)return
            observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this);it.removeOnGlobalLayoutListener(startupLayout) }
            next.addOnPreDrawListener(this);next.addOnGlobalLayoutListener(startupLayout);observer=next
        }
        private var retryAt=0L
        private var bindingFailures=0
        override fun onPreDraw(): Boolean {
            if(stopped)return true
            if(SystemClock.uptimeMillis()<retryAt)return true
            runCatching { update() }.onSuccess { bindingFailures=0 }.onFailure {
                bindingFailures++
                retryAt=SystemClock.uptimeMillis()+minOf(1000L,64L shl minOf(bindingFailures,4))
                android.util.Log.w("SpotifyGlass","Library binding temporarily unavailable; retain accepted toolbar",it)
                if(!config.enabled)restore()
            }
            if(layer.visibility==View.VISIBLE && layer.isLayoutRequested && decor.width>0) {
                layer.measure(View.MeasureSpec.makeMeasureSpec(decor.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(decor.height,View.MeasureSpec.EXACTLY))
                layer.layout(0,0,decor.width,decor.height)
            }
            return true
        }
        private fun update() {
            observe()
            val now=SystemClock.uptimeMillis()
            if(configAt==0L || now-configAt>=500L) { config=HookConfigReader.read(activity,activity.packageName);configAt=now }
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT) { restore();return }
            if(content.windowVisibility!=View.VISIBLE)return
            SpotifyLibraryCompose.maintain(nodes)
            SpotifyTopDrawer.bind(layer,activity)
            val drawer=find(content,"sidedrawer_compose_view")
            val drawerOpen=drawer?.isShown==true && drawer.alpha>.001f && drawer.getGlobalVisibleRect(Rect())
            if(!drawerOpen && (SpotifyTopContinuity.eager(activity,"library") || scanAt==0L || now-scanAt>=350L || (active && data?.owner?.isAttachedToWindow!=true))) {
                scanAt=now
                val fragment=find(content,"fragment_container_host") ?: run { restore();return }
                val next=SpotifyLibraryBinding.read(fragment)
                if(next==null || !next.owner.isShown || !next.owner.getGlobalVisibleRect(Rect())) {
                    if(active && data?.owner?.isAttachedToWindow==true && data?.owner?.isShown==true &&
                        SpotifyTopContinuity.accepts(activity,SpotifyTopContinuity.Page.LIBRARY)) {
                        SpotifyLibraryCompose.maintain(nodes);layout();return
                    }
                    restore();return
                }
                SpotifyProfileArtwork.ensure(activity,next.avatar) { scanAt=0L;content.invalidate() }
                val updated=SpotifyLibraryCompose.bind(next,this) ?: run { if(active) { SpotifyLibraryCompose.maintain(nodes);layout() };return }
                SpotifyLibraryCompose.forget(nodes.filter { old -> updated.none { it===old } },this);nodes=updated
                if(!active) { oldStatus=activity.window.statusBarColor;oldContrast=activity.window.isStatusBarContrastEnforced }
                val visualChanged=data!=next || builtConfig!=config
                active=true;data=next
                val key=next.filters.joinToString { it.label+":"+it.virtualId+":"+(it.virtualId in next.clearFilterIds) }
                if(builtConfig!=config || panels.isEmpty())build()
                else if(signature!=key)reconcileFilters()
                signature=key
                selectionUpdates.values.toList().forEach { it() }
                if(visualChanged)panels.forEach { it.postInvalidateOnAnimation() }
            }
            if(!active)return
            SpotifyLibraryCompose.maintain(nodes)
            layer.visibility=View.VISIBLE
            activity.window.statusBarColor=Color.TRANSPARENT;activity.window.isStatusBarContrastEnforced=false
            suppressProtection();layout()
        }
        private fun suppressProtection() {
            val parent=find(content,"system_bar_protection") as? ViewGroup ?: return
            for(i in 0 until parent.childCount) {
                val child=parent.getChildAt(i);val p=child.layoutParams as? FrameLayout.LayoutParams ?: continue
                if(child.javaClass==View::class.java && child.tag!=null && p.gravity and Gravity.VERTICAL_GRAVITY_MASK==Gravity.TOP) {
                    if(!protectionAlpha.containsKey(child))protectionAlpha[child]=child.alpha
                    if(child.alpha!=0f)child.alpha=0f
                }
            }
        }
        private fun layout() {
            val model=data ?: return
            if(panels.isEmpty())return
            val screen=decor.width.takeIf { it>0 } ?: return
            val side=((screen-glassHostWidthPx(content,5,config,d))/2).coerceAtLeast(0)
            val status=decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            layer.topGradient.setFadeHeight(status+dp(168f))
            val top=status+dp(6f);val size=dp(48f);val gap=dp(8f)
            val measure=Paint().apply { typeface=Typeface.DEFAULT_BOLD;textSize=24f*activity.resources.displayMetrics.scaledDensity }
            val wanted=dp(64f)+measure.measureText(model.title.label).roundToInt()
            val titleWidth=wanted.coerceAtMost((screen-2*side-2*size-2*gap).coerceAtLeast(size))
            fun place(view: View,x: Int,y: Int,w: Int,h: Int) {
                val p=view.layoutParams as? FrameLayout.LayoutParams
                if(p==null || p.leftMargin!=x || p.topMargin!=y || p.width!=w || p.height!=h)
                    view.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.TOP or Gravity.LEFT).apply { leftMargin=x;topMargin=y }
            }
            // Avatar/title and the rightmost action belong to the shared host.
            place(panels[0],screen-side-2*size-gap,top,size,size)
            filters?.let { place(it,side,top+size+gap,screen-side,dp(48f)) }
            SpotifyTopContinuity.show(activity,SpotifyTopContinuity.Page.LIBRARY,config,
                Rect(side,top,side+titleWidth,top+size),Rect(screen-side-size,top,screen-side,top+size),model.title.label,null,
                { data?.owner?.let { SpotifyLibraryBinding.read(it) }?.avatar?.click()==true },
                { data?.owner?.let { SpotifyLibraryBinding.read(it) }?.tools?.getOrNull(1)?.click()==true },model.owner)
        }
        private fun build() {
            layer.removeAllViews();panels.clear();filterViews.clear();selectionUpdates.clear();builtConfig=config
            val model=data ?: return
            for(kind in 1..1)layer.addView(button(kind,null),FrameLayout.LayoutParams(1,dp(48f)))
            val scroll=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false;clipToPadding=true;clipChildren=true }
            strip=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false
                layoutTransition=android.animation.LayoutTransition().apply {
                    setDuration(260L);setAnimateParentHierarchy(false)
                    for(type in intArrayOf(android.animation.LayoutTransition.APPEARING,android.animation.LayoutTransition.DISAPPEARING,android.animation.LayoutTransition.CHANGE_APPEARING,android.animation.LayoutTransition.CHANGE_DISAPPEARING,android.animation.LayoutTransition.CHANGING)) {
                        setStartDelay(type,0L);setInterpolator(type,android.view.animation.PathInterpolator(.2f,0f,0f,1f))
                    }
                    enableTransitionType(android.animation.LayoutTransition.CHANGING)
                }
            }
            scroll.addView(strip,FrameLayout.LayoutParams(-2,-1));filters=scroll;layer.addView(scroll,FrameLayout.LayoutParams(1,dp(48f)))
            reconcileFilters()
        }
        private fun reconcileFilters() {
            val model=data ?: return;val row=strip ?: return
            val wanted=model.filters.map { it.label }.toSet()
            filterViews.keys.filter { it !in wanted }.forEach { label ->
                filterViews.remove(label)?.let { view -> row.removeView(view);panels.remove(view);selectionUpdates.remove(view) }
            }
            val measure=Paint().apply { textSize=14f*activity.resources.displayMetrics.scaledDensity }
            model.filters.forEachIndexed { index,filter ->
                val view=filterViews[filter.label] ?: button(3,filter.label).also { filterViews[filter.label]=it }
                val width=if(filter.virtualId in model.clearFilterIds)dp(34f) else measure.measureText(filter.label).roundToInt()+dp(24f)
                if(view.parent==null)row.addView(view,index,LinearLayout.LayoutParams(width,dp(34f)).apply { rightMargin=dp(8f) })
                else if(row.indexOfChild(view)!=index) { row.removeView(view);row.addView(view,index) }
            }
        }

        private fun button(kind: Int,label: String?): GlassHostLayout {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            val path=Path()
            fun entry(): SpotifyHeaderBinding.Button? {
                val model=data ?: return null
                return when(kind) { 0->model.avatar;1,2->model.tools.getOrNull(kind-1);else->model.filters.singleOrNull { it.label==label } }
            }
            var selection=if(kind==3 && entry()?.selected==true)1f else 0f
            var selectionTarget=selection
            var selectionAnimation: android.animation.ValueAnimator?=null
            fun blend(a: Int,b: Int,t: Float): Int=android.graphics.Color.rgb(
                (Color.red(a)+(Color.red(b)-Color.red(a))*t).roundToInt(),
                (Color.green(a)+(Color.green(b)-Color.green(a))*t).roundToInt(),
                (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).roundToInt())
            val state=object: AppNavigationState {
                override val allowsSoftwareBackdropCapture=false
                override val backdropSceneViewsAreDescendants=true
                override fun resolveBackdropScene(host: View): View=content
                override fun backdropSceneViews(content: ViewGroup,hostIndex: Int)=scene()
                override fun backdropSceneBaseColor(scene: View,fallback: Int)=0xFF121212.toInt()
                override fun forceLiveBackdropFrame(selectedIndex: Int)=true
                override fun resolveAutoDarkMode(host: View)=true
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int)=true
                override fun suppressRedrawnSource()=false
                override fun forceNavigationBarImmersed(host: GlassHostLayout)=false
                override fun pauseBackdropWork(host: View)=stopped || !active || !activity.hasWindowFocus() || !host.isShown
                override fun allowsHostTouch()=!SpotifyTopDrawer.isOpen(activity)
                override fun performTap(host: View,source: ViewGroup?,index: Int,slotCount: Int): Boolean {
                    if(!active || data?.owner?.isAttachedToWindow!=true || !activity.hasWindowFocus())return false
                    val owner=data?.owner ?: return false
                    data=SpotifyLibraryBinding.read(owner) ?: return false
                    val result=entry()?.click()==true
                    scanAt=0;content.invalidate()
                    if(result)for(delay in longArrayOf(0L,32L,96L,192L,352L))layer.postDelayed({
                        if(!stopped && active) { scanAt=0;content.invalidate() }
                    },delay)
                    return result
                }
                override fun draw(canvas: Canvas,frame: AdapterNavigationFrame) {
                    val model=data ?: return
                    if(kind==0) {
                        val side=dp(40f)*.72f;val center=frame.height/2f
                        val save=canvas.save();canvas.translate(center-side/2,center-side/2)
                        path.reset();path.addCircle(side/2,side/2,side/2,Path.Direction.CW);canvas.clipPath(path)
                        SpotifyProfileArtwork.draw(activity,canvas,side);canvas.restoreToCount(save)
                        paint.color=Color.WHITE;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=24f*frame.scaledDensity
                        val text=android.text.TextUtils.ellipsize(model.title.label,android.text.TextPaint(paint),
                            (frame.width-60f*frame.density).coerceAtLeast(0f),android.text.TextUtils.TruncateAt.END).toString()
                        canvas.drawText(text,48f*frame.density,frame.height/2f-(paint.ascent()+paint.descent())/2,paint)
                    } else if(kind==1 || kind==2) {
                        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=2f*frame.density
                        val x=frame.width/2f;val y=frame.height/2f;val r=8f*frame.density
                        if(kind==1) { canvas.drawCircle(x-r*.15f,y-r*.15f,r,paint);canvas.drawLine(x+r*.55f,y+r*.55f,x+r*1.4f,y+r*1.4f,paint) }
                        else { canvas.drawLine(x-r,y,x+r,y,paint);canvas.drawLine(x,y-r,x,y+r,paint) }
                        paint.style=Paint.Style.FILL
                    } else {
                        paint.color=blend(Color.WHITE,Color.BLACK,selection)
                        paint.typeface=Typeface.DEFAULT;paint.textSize=14f*frame.scaledDensity;paint.textAlign=Paint.Align.CENTER
                        canvas.drawText(label.orEmpty(),frame.width/2f,frame.height/2f-(paint.ascent()+paint.descent())/2,paint)
                    }
                }
            }
            val adapter=object: TargetAdapter {
                override val key="spotify-library-button"
                override val outerPanelPressScale=1f+4f/48f
                override val pressedIndicatorScale=1f+4f/48f
                override val ownsNavigationDrawing=true
                override val ownsNavigationTap=true
                override val managesHostSiblingOrder=true
                override val skipNavigationSnapshot=true
                override val hasPerFrameScene=true
                override val allowRenderNodeRefresh=false
                override val retainsExistingBackdropRenderNode=true
                override val skipBackdropStableDelay=true
                override val allowsSystemBackgroundBlur=false
                private val matrix=Matrix()
                override fun liveBackdropTransform(parent: View,host: View,out: Matrix)=SpotifyBackdropSampling.transform(parent,host,out,matrix)
                override fun backdropBlurEffect(radius: Float)=SpotifyBackdropSampling.effect(radius)
                override fun createNavigationState(context: android.content.Context)=state
                override fun glassSurfaceFillColor(dark: Boolean,selectedIndex: Int,defaultColor: Int): Int {
                    val color=blend(0xFF292929.toInt(),0xFF1ED760.toInt(),selection)
                    return (color and 0xFFFFFF) or (Color.alpha(defaultColor) shl 24)
                }
                override fun surfaceOpacityScale(config: GlassConfig)=config.spotifyBarOpacity+(1f-config.spotifyBarOpacity)*selection
                override fun normalizeConfig(config: GlassConfig)=config.copy(detachedPublish=false,hiddenMask=0,iconOnly=true,
                    barHeight=if(kind==3)34f else 48f,cornerRadiusPercent=50f,selectedAccent=false)
            }
            return GlassHostLayout(activity,config,1,navigationSource=null,sliderEnabled=false,adapterOverride=adapter).apply {
                isClickable=true;isFocusable=false;elevation=0f;panels+=this
                val host=this
                if(kind==3)selectionUpdates[host]={
                    val target=if(entry()?.selected==true)1f else 0f
                    if(selectionTarget!=target) {
                        selectionTarget=target;selectionAnimation?.cancel()
                        selectionAnimation=android.animation.ValueAnimator.ofFloat(selection,target).apply {
                            duration=220L;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
                            addUpdateListener { selection=it.animatedValue as Float;host.postInvalidateOnAnimation() };start()
                        }
                    }
                }
                addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { selectionAnimation?.cancel();selectionAnimation=null }
                })
                contentDescription=label ?: when(kind) { 0->data?.title?.label;1->"Search Your Library";else->"Create playlist" }
            }
        }
        private fun scene(): List<View> = SpotifyBackdropScene.views(content)
        private fun restore() {
            SpotifyTopContinuity.leave(activity,SpotifyTopContinuity.Page.LIBRARY)
            if(!active && nodes.isEmpty()) { layer.visibility=View.GONE;return }
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped) {
                SpotifyLibraryCompose.clearRetired()
                SpotifyLibraryCompose.clear(nodes,this);nodes=emptyList();data?.owner?.let {
                    SpotifyLibraryViewport.clear(it);SpotifyLibraryCompose.invalidate(it)
                }
            } else SpotifyLibraryCompose.maintain(nodes)
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped) {
                protectionAlpha.forEach { (view,alpha) -> if(view.alpha==0f)view.alpha=alpha };protectionAlpha.clear()
            }
            if(active) { activity.window.statusBarColor=oldStatus;activity.window.isStatusBarContrastEnforced=oldContrast }
            active=false;layer.visibility=View.GONE;sceneAt=Long.MIN_VALUE

        }
        fun stop() {
            if(stopped)return
            stopped=true;content.removeOnAttachStateChangeListener(attachState)
            observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this);it.removeOnGlobalLayoutListener(startupLayout) };observer=null
            if(sessions[activity]?.get()===this)sessions.remove(activity)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                restore();(layer.parent as? ViewGroup)?.removeView(layer);panels.clear()
            }
        }
    }
}
