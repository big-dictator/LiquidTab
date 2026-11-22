package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.*
import io.github.offlineglass.hook.adapters.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Search browse page only. Native input/results screens retain their own toolbar. */
internal object SpotifySearchTopBar {
    private val sessions=WeakHashMap<Activity,WeakReference<Session>>()
    fun attach(activity: Activity) {
        val content=activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        if(activity.isFinishing || activity.isDestroyed)return
        sessions[activity]?.get()?.takeIf { !it.stopped && it.content===content }?.let { return }
        sessions[activity]?.get()?.stop()
        Session(activity,content).also { sessions[activity]=WeakReference(it);it.start() }
    }
    fun release(activity: Activity) { sessions[activity]?.get()?.stop() }
    private class Layer(context: android.content.Context): SpotifyTopPageLayer(context,SpotifyTopContinuity.Page.SEARCH) {
        val topGradient=SpotifyTopGradient()
        init { clipChildren=false;clipToPadding=false;translationZ=18f*resources.displayMetrics.density }
    }
    private class Session(val activity: Activity,val content: FrameLayout): ViewTreeObserver.OnPreDrawListener {
        private val decor=activity.window.decorView as? FrameLayout ?: content
        private val layer=Layer(activity)
        private val density=activity.resources.displayMetrics.density
        private fun dp(value: Float)=(value*density).roundToInt()
        var stopped=false;private set
        private var config=GlassConfig()
        private var configAt=0L
        private val nativeChrome=SpotifyNativeTopChrome()
        private var composeNodes=emptyList<Any>()
        private var composeField=false
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
        private var root: ViewGroup?=null
        private var bar: ViewGroup?=null
        private var face: ViewGroup?=null
        private var field: ViewGroup?=null
        private var camera: View?=null
        private var bound: SpotifySearchBinding.Result?=null
        private var active=false
        private var savedAlpha=1f
        private var savedBackground: Drawable?=null
        private var savedElevation=0f
        private var savedTranslation=0f
        private var savedTop=0
        private var oldStatus=0
        private var oldContrast=false
        private var observer: ViewTreeObserver?=null
        private data class Behavior(val view: View,val params: ViewGroup.LayoutParams,val field: java.lang.reflect.Field,
            val original: Any,val setter: java.lang.reflect.Method)
        private val savedBehaviors=ArrayList<Behavior>(2)
        private var feed: ViewGroup?=null
        private var feedTop=0
        private var feedClip=true
        private var feedBounds: Rect?=null
        private var appliedFeedTop=-1
        private var rootTranslation=0f
        private var appliedRootTranslation: Float?=null
        private var viewportLift=0
        private data class Clip(val view: ViewGroup,val children: Boolean,val padding: Boolean)
        private val viewportClips=ArrayList<Clip>()
        private val rootLocation=IntArray(2)
        private var protection: ViewGroup?=null
        private val protectionAlpha=WeakHashMap<View,Float>()
        private var attemptedPin=false
        private val panels=ArrayList<GlassHostLayout>()
        private var builtConfig: GlassConfig?=null
        private var sceneAt=Long.MIN_VALUE
        private var cachedScene=emptyList<View>()
        private var focused=false
        private val attachListener=object: View.OnAttachStateChangeListener {
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
            content.addOnAttachStateChangeListener(attachListener)
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
                android.util.Log.w("SpotifyGlass","Search binding temporarily unavailable; retain accepted toolbar",it)
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
            nativeChrome.maintain()
            val hasFocus=activity.hasWindowFocus()
            if(hasFocus && !focused) { scanAt=0;sceneAt=Long.MIN_VALUE;panels.forEach { it.invalidateProjectionCache();it.invalidateNavigationSnapshot() } }
            focused=hasFocus
            if(content.windowVisibility!=View.VISIBLE)return
            SpotifyTopDrawer.bind(layer,activity)
            val drawer=find(content,"sidedrawer_compose_view")
            val drawerOpen=drawer?.isShown==true && drawer.alpha>.001f && drawer.getGlobalVisibleRect(Rect())
            SpotifyLibraryCompose.maintain(composeNodes)
            val nativeRoot=find(content,"browse_content_state") as? ViewGroup
            if(!drawerOpen && (SpotifyTopContinuity.eager(activity,"search") || scanAt==0L || now-scanAt>=350L || (nativeRoot!=null && nativeRoot!==root && nativeRoot.isShown) || (active && root?.isAttachedToWindow!=true))) {
                scanAt=now
                val query=find(content,"query")
                if(query?.isShown==true && query.getGlobalVisibleRect(Rect())) { restore();return }
                val discovered=SpotifySearchBinding.layout(content) ?: run { restore();return }
                val next=discovered.root
                val nextBar=discovered.bar
                val nextFace=discovered.face
                val nextField=discovered.field
                val nextCamera=discovered.camera
                val data=discovered.data
                if(discovered.composeField) {
                    val masked=SpotifyLibraryCompose.bindVisual(data.search.source,listOf(data.search.virtualId),this) ?: return
                    SpotifyLibraryCompose.forget(composeNodes.filter { old -> masked.none { it===old } },this)
                    composeNodes=masked
                }
                SpotifyProfileArtwork.ensure(activity,data.avatar) { scanAt=0L;content.invalidate() }
                if(!active || bar!==nextBar || root!==next) {
                    restore();root=next;bar=nextBar;face=nextFace;field=nextField;camera=nextCamera
                    savedAlpha=nextBar.alpha;savedBackground=nextBar.background;savedElevation=nextBar.elevation
                    savedTranslation=nextBar.translationY;savedTop=nextBar.top
                    oldStatus=activity.window.statusBarColor;oldContrast=activity.window.isStatusBarContrastEnforced
                    rootTranslation=next.translationY
                    active=true
                }
                face=nextFace;field=nextField;camera=nextCamera;bound=data;composeField=discovered.composeField
                if(panels.isEmpty() || builtConfig!=config)build()
            }
            if(!active)return
            pin()

            bar?.let { nativeChrome.hide(it);it.translationY=0f
                if(!composeField && it.top!=0)it.offsetTopAndBottom(-it.top) }
            layer.visibility=View.VISIBLE
            activity.window.statusBarColor=Color.TRANSPARENT;activity.window.isStatusBarContrastEnforced=false
            layout();extendViewport();layoutFeed()
        }
        private fun pin() {
            if(attemptedPin)return
            attemptedPin=true
            if(composeField)return
            val view=bar ?: return
            val coordinator=view.parent as? ViewGroup ?: return
            fun read(target: View): Behavior? {
                val params=target.layoutParams ?: return null
                val field=params.javaClass.fields.filter { f ->
                    !java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type.methods.any { m ->
                        m.returnType==Boolean::class.javaPrimitiveType && m.parameterCount==3 &&
                        m.parameterTypes[0].isInstance(coordinator) && ViewGroup::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        m.parameterTypes[0]!=ViewGroup::class.java && m.parameterTypes[1]==View::class.java && m.parameterTypes[2]==Int::class.javaPrimitiveType
                    }
                }.singleOrNull()?.apply { isAccessible=true } ?: return null
                val original=field.get(params) ?: return null
                val setter=params.javaClass.methods.singleOrNull { it.parameterCount==1 && it.parameterTypes[0]==field.type && it.returnType==Void.TYPE } ?: return null
                return Behavior(target,params,field,original,setter)
            }
            val recycler=find(coordinator,"browse_recycler_view") as? ViewGroup ?: return
            val pair=listOfNotNull(read(view),read(recycler))
            if(pair.size!=2)return
            try {
                pair.forEach { it.setter.invoke(it.params,null);it.view.requestLayout() }
                savedBehaviors.addAll(pair)
                feed=recycler;feedTop=recycler.paddingTop;feedClip=recycler.clipToPadding
                feedBounds=Rect(recycler.left,recycler.top,recycler.right,recycler.bottom)
            } catch(error: Throwable) {
                pair.forEach { runCatching { it.setter.invoke(it.params,it.original) } };throw error
            }
        }
        private fun extendViewport() {
            val page=root ?: return
            // Compose's AndroidView holder starts below the status inset. Move
            // the actual native page, not only the color of the status bar.
            page.getLocationInWindow(rootLocation)
            val unshiftedTop=rootLocation[1]-page.translationY+rootTranslation
            val status=decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            viewportLift=unshiftedTop.roundToInt().coerceIn(0,status)
            val desired=rootTranslation-viewportLift
            if(page.translationY!=desired)page.translationY=desired
            appliedRootTranslation=desired
            if(protection?.isAttachedToWindow!=true)protection=find(content,"system_bar_protection") as? ViewGroup
            // ProtectionLayout adds tagged plain Views above main_content for
            // each system-bar side. Suppress only its TOP protection, retaining
            // its insets callbacks and all bottom/side protections.
            protection?.let { parent ->
                for(i in 0 until parent.childCount) {
                    val child=parent.getChildAt(i)
                    val params=child.layoutParams as? FrameLayout.LayoutParams ?: continue
                    if(child.javaClass==View::class.java && child.tag!=null &&
                        params.gravity and Gravity.VERTICAL_GRAVITY_MASK==Gravity.TOP) {
                        if(!protectionAlpha.containsKey(child))protectionAlpha[child]=child.alpha
                        if(child.alpha!=0f)child.alpha=0f
                    }
                }
            }
            if(viewportClips.isEmpty()) {
                var ancestor: View?=page
                while(ancestor!=null) {
                    if(ancestor is ViewGroup) {
                        viewportClips+=Clip(ancestor,ancestor.clipChildren,ancestor.clipToPadding)
                        ancestor.clipChildren=false;ancestor.clipToPadding=false
                    }
                    if(ancestor===content)break
                    ancestor=ancestor.parent as? View
                }
            }
        }
        private fun layoutFeed() {
            val recycler=feed ?: return
            val parent=root ?: return
            val space=bar?.height ?: return
            val top=feedTop+space+viewportLift
            if(recycler.paddingTop!=top)recycler.setPadding(recycler.paddingLeft,top,recycler.paddingRight,recycler.paddingBottom)
            appliedFeedTop=top;recycler.clipToPadding=false
            val height=parent.height+viewportLift
            if(parent.width>0 && height>0 && (recycler.top!=0 || recycler.height!=height)) {
                recycler.measure(View.MeasureSpec.makeMeasureSpec(parent.width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                recycler.layout(0,0,parent.width,height)
            }
        }
        private fun layout() {
            if(panels.size!=1)return
            val screen=decor.width.takeIf { it>0 } ?: return
            val side=((screen-glassHostWidthPx(content,5,config,density))/2).coerceAtLeast(0)
            val inset=decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            layer.topGradient.setFadeHeight(inset+dp(168f))
            val top=inset+dp(6f);val size=dp(48f)
            val text=Paint().apply { textSize=24f*activity.resources.displayMetrics.scaledDensity;typeface=Typeface.DEFAULT_BOLD }
            val wanted=dp(64f)+(text.measureText(bound?.title?.label.orEmpty())).roundToInt()
            val titleWidth=wanted.coerceAtMost((screen-2*side-size-dp(12f)).coerceAtLeast(size)).coerceAtLeast(size)
            val positions=listOf(intArrayOf(side,top,titleWidth,size),intArrayOf(screen-side-size,top,size,size),
                intArrayOf(side,top+size+dp(8f),screen-2*side,dp(48f)))
            SpotifyTopContinuity.show(activity,SpotifyTopContinuity.Page.SEARCH,config,
                Rect(side,top,side+titleWidth,top+size),Rect(screen-side-size,top,screen-side,top+size),
                bound?.title?.label.orEmpty(),(camera as? ImageView)?.drawable,
                { face?.let { f -> field?.let { s -> SpotifySearchBinding.read(f,s) } }?.avatar?.click()==true },
                { camera?.takeIf { it.isAttachedToWindow }?.performClick()==true },root ?: return)
            panels.forEachIndexed { index,view ->
                val values=positions[2];val old=view.layoutParams as? FrameLayout.LayoutParams
                if(old==null || old.leftMargin!=values[0] || old.topMargin!=values[1] || old.width!=values[2] || old.height!=values[3])
                    view.layoutParams=FrameLayout.LayoutParams(values[2],values[3],Gravity.TOP or Gravity.LEFT).apply { leftMargin=values[0];topMargin=values[1] }
            }
        }
        private fun build() {
            layer.removeAllViews();panels.clear();builtConfig=config
            for(kind in 2..2) {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                val path=Path()
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
                        if(!active || !activity.hasWindowFocus() || root?.isShown!=true ||
                            face?.isAttachedToWindow!=true || field?.isAttachedToWindow!=true)return false
                        return when(kind) {
                            0 -> face?.let { f -> field?.let { s -> SpotifySearchBinding.read(f,s) } }?.avatar?.click()==true
                            1 -> camera?.takeIf { it.isAttachedToWindow }?.performClick()==true
                            else -> face?.let { f -> field?.let { s -> SpotifySearchBinding.read(f,s) } }?.search?.click()==true
                        }
                    }
                    override fun draw(canvas: Canvas,frame: AdapterNavigationFrame) {
                        val data=bound ?: return
                        if(kind==0) {
                            val image=dp(40f)*.72f;val center=frame.height/2f
                            val save=canvas.save();canvas.translate(center-image/2,center-image/2)
                            path.reset();path.addCircle(image/2,image/2,image/2,Path.Direction.CW);canvas.clipPath(path)
                            SpotifyProfileArtwork.draw(activity,canvas,image);canvas.restoreToCount(save)
                            paint.color=Color.WHITE;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=24f*frame.scaledDensity
                            canvas.drawText(data.title.label,dp(48f).toFloat(),frame.height/2-(paint.ascent()+paint.descent())/2,paint)
                        } else if(kind==1) {
                            val drawable=(camera as? ImageView)?.drawable
                            if(drawable!=null) {
                                val old=Rect(drawable.bounds);val side=dp(24f);val x=(frame.width-side)/2;val y=(frame.height-side)/2
                                drawable.setBounds(x,y,x+side,y+side)
                                drawable.draw(canvas);drawable.bounds=old
                            }
                        } else {
                            paint.color=Color.BLACK;paint.typeface=Typeface.DEFAULT;paint.textSize=16f*frame.scaledDensity
                            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f*frame.density
                            val x=22f*frame.density;val y=frame.height/2;val r=7f*frame.density
                            canvas.drawCircle(x,y-r*.2f,r,paint);canvas.drawLine(x+r*.7f,y+r*.5f,x+r*1.5f,y+r*1.3f,paint)
                            paint.style=Paint.Style.FILL
                            val label=android.text.TextUtils.ellipsize(data.search.label,android.text.TextPaint(paint),
                                (frame.width-56f*frame.density).coerceAtLeast(0f),android.text.TextUtils.TruncateAt.END).toString()
                            canvas.drawText(label,44f*frame.density,frame.height/2-(paint.ascent()+paint.descent())/2,paint)
                        }
                    }
                }
                val adapter=object: TargetAdapter {
                    override val key="spotify-search-button"
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
                    override fun glassSurfaceFillColor(dark: Boolean,selectedIndex: Int,defaultColor: Int)=
                        ((if(kind==2)Color.WHITE else 0xFF292929.toInt()) and 0xFFFFFF) or (Color.alpha(defaultColor) shl 24)
                    override fun surfaceOpacityScale(config: GlassConfig)=if(kind==2)1f else config.spotifyBarOpacity
                    override fun normalizeConfig(config: GlassConfig)=config.copy(detachedPublish=false,hiddenMask=0,iconOnly=true,
                        barHeight=48f,cornerRadiusPercent=50f,selectedAccent=false)
                }
                val host=GlassHostLayout(activity,config,1,navigationSource=null,sliderEnabled=false,adapterOverride=adapter)
                host.isClickable=true;host.isFocusable=false;host.elevation=0f
                host.contentDescription=when(kind) { 0->bound?.title?.label;1->camera?.contentDescription;else->bound?.search?.label }
                panels+=host;layer.addView(host,FrameLayout.LayoutParams(1,dp(48f)))
            }
        }
        private fun scene(): List<View> = SpotifyBackdropScene.views(content)
        private fun restore() {
            SpotifyTopContinuity.leave(activity,SpotifyTopContinuity.Page.SEARCH)
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped)nativeChrome.restore()
            else nativeChrome.maintain()
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped) {
                SpotifyLibraryCompose.clear(composeNodes,this);composeNodes=emptyList()
            } else SpotifyLibraryCompose.maintain(composeNodes)
            savedBehaviors.forEach { saved -> runCatching {
                if(saved.view.layoutParams===saved.params && saved.field.get(saved.params)==null)
                    saved.setter.invoke(saved.params,saved.original)
                saved.view.requestLayout()
            } };savedBehaviors.clear()
            feed?.let { recycler ->
                if(recycler.paddingTop==appliedFeedTop)recycler.setPadding(recycler.paddingLeft,feedTop,recycler.paddingRight,recycler.paddingBottom)
                if(feedClip && !recycler.clipToPadding)recycler.clipToPadding=true
                feedBounds?.let { if(recycler.isAttachedToWindow)recycler.layout(it.left,it.top,it.right,it.bottom) }
                recycler.requestLayout()
            };feed=null;feedBounds=null;appliedFeedTop=-1
            root?.let { page -> if(page.translationY==appliedRootTranslation)page.translationY=rootTranslation }
            appliedRootTranslation=null;viewportLift=0
            if(!config.enabled || activity.resources.configuration.orientation!=Configuration.ORIENTATION_PORTRAIT || stopped) {
                protectionAlpha.forEach { (view,alpha) -> if(view.alpha==0f)view.alpha=alpha }
                protectionAlpha.clear()
            }
            protection=null
            viewportClips.forEach { saved ->
                if(saved.children && !saved.view.clipChildren)saved.view.clipChildren=true
                if(saved.padding && !saved.view.clipToPadding)saved.view.clipToPadding=true
            };viewportClips.clear()
            if(active) { activity.window.statusBarColor=oldStatus;activity.window.isStatusBarContrastEnforced=oldContrast }
            attemptedPin=false;active=false;layer.visibility=View.GONE;sceneAt=Long.MIN_VALUE
        }
        fun stop() {
            if(stopped)return
            stopped=true;content.removeOnAttachStateChangeListener(attachListener)
            observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this);it.removeOnGlobalLayoutListener(startupLayout) };observer=null
            if(sessions[activity]?.get()===this)sessions.remove(activity)
            // content's detach callback runs inside DecorView's child-array
            // traversal. Mutating that array here invalidates its captured count
            // and crashes ViewGroup.dispatchDetachedFromWindow on relaunch.
            // A main-queue task also runs if the old layer is already detached.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                restore()
                (layer.parent as? ViewGroup)?.removeView(layer);panels.clear()
            }
        }
    }
}
