package io.github.offlineglass.hook.adapters.spotify

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.*
import io.github.offlineglass.hook.adapters.*
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Two persistent glass hosts shared by Home/Search/Library. Routing stays native. */
internal object SpotifyTopContinuity {
    enum class Page { HOME,SEARCH,LIBRARY }
    private val sessions=WeakHashMap<Activity,WeakReference<Session>>()
    fun prepare(activity: Activity) {
        sessions[activity]?.get()?.takeUnless { it.stopped }?.let { return }
        val id=activity.resources.getIdentifier("main_content","id",activity.packageName)
        if(id==0 || activity.findViewById<View>(id)==null)return
        Session(activity).also { sessions[activity]=WeakReference(it);it.start() }
    }
    fun show(activity: Activity,page: Page,config: GlassConfig,left: Rect,right: Rect?,title: String,
             camera: Drawable?,avatar: ()->Boolean,action: ()->Boolean,source: View) {
        val session=sessions[activity]?.get()?.takeUnless { it.stopped } ?: Session(activity).also {
            sessions[activity]=WeakReference(it);it.start()
        }
        session.offer(page,config,left,right,title,camera,avatar,action,source)
    }
    // Artwork is decoded asynchronously by the page binding. The actual
    // avatar lives in a separate DecorView sibling, not in that native page.
    fun artworkChanged(activity: Activity) { sessions[activity]?.get()?.artworkChanged() }
    fun release(activity: Activity) { SpotifyProfileArtwork.endSearchHandoff(activity);sessions[activity]?.get()?.stop() }
    fun leave(activity: Activity,page: Page) { sessions[activity]?.get()?.leave(page) }
    fun eager(activity: Activity,key: String): Boolean=sessions[activity]?.get()?.eager(key)==true
    fun register(activity: Activity,page: Page,controls: SpotifyTopPageLayer) {
        prepare(activity);sessions[activity]?.get()?.register(page,controls)
    }
    fun accepts(activity: Activity,page: Page): Boolean=sessions[activity]?.get()?.accepts(page) ?: true
    fun elapsed(activity: Activity): Long=sessions[activity]?.get()?.elapsed() ?: 280L
    fun begin(source: View,index: Int) {
        var context: Context?=source.context
        while(context is ContextWrapper && context !is Activity)context=context.baseContext
        val activity=(context as? Activity) ?: sessions.keys.firstOrNull { it.window.decorView===source.rootView } ?: return
        if(index in 0..2) {
            SpotifyChromeOwnership.entering(activity)
            sessions[activity]?.get()?.begin(Page.entries[index])
        }
    }
    private class Layer(context: Context): FrameLayout(context),AdapterOwnedOverlay {
        init { clipChildren=false;clipToPadding=false;translationZ=20f*resources.displayMetrics.density }
    }
    private data class Offer(val page: Page,val config: GlassConfig,val left: Rect,val right: Rect?,val title: String,
        val camera: Drawable?,val avatar: ()->Boolean,val action: ()->Boolean,val source: WeakReference<View>)
    private class Session(val activity: Activity): ViewTreeObserver.OnPreDrawListener {
        private val content=activity.findViewById<ViewGroup>(android.R.id.content)
        private val decor=activity.window.decorView as FrameLayout
        private val layer=Layer(activity)
        private val d=activity.resources.displayMetrics.density
        private val cache=HashMap<Page,Offer>()
        private var current: Offer?=null
        private var intended: Page?=null
        private var intentAt=0L
        private val poll=HashMap<String,Int>()
        private var lastOffer=0L
        private var leavingAt=0L
        private var animator: ValueAnimator?=null
        private var config=HookConfigReader.read(activity,activity.packageName)
        private val controls=java.util.EnumMap<Page,WeakReference<SpotifyTopPageLayer>>(Page::class.java)
        private var destination: Page?=null
        private var animationAt=0L
        private var configAt=0L
        private val gradient=SpotifyTopGradient()
        private val veil=object: View(activity),AdapterOwnedOverlay {}.apply {
            background=gradient;translationZ=17f*resources.displayMetrics.density
            visibility=View.GONE
        }
        private var leftBox=RectF()
        private var rightBox=RectF()
        private var anchorX=0f
        private var anchorY=0f
        private var title=""
        private var oldTitle=""
        private var oldPage=Page.HOME
        private var page=Page.HOME
        private var camera: Drawable?=null
        private var oldCamera: Drawable?=null
        private val cameraCopies=WeakHashMap<Drawable,Drawable>()
        private fun cameraCopy(source: Drawable?): Drawable? {
            source ?: return null
            cameraCopies[source]?.let { return it }
            val copy=source.constantState?.newDrawable()?.mutate() ?: run {
                val size=(24f*d).roundToInt().coerceAtLeast(1)
                val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
                val old=Rect(source.bounds)
                try { source.setBounds(0,0,size,size);source.draw(Canvas(bitmap)) }
                finally { source.bounds=old }
                android.graphics.drawable.BitmapDrawable(activity.resources,bitmap)
            }
            cameraCopies[source]=copy;return copy
        }
        private val nativeTexts=HashMap<String,String>()
        private fun nativeText(name: String,fallback: String): String = nativeTexts.getOrPut(name) {
            val id=activity.resources.getIdentifier(name,"string",activity.packageName)
            if(id!=0)activity.getString(id) else fallback
        }
        private var progress=1f
        private var rightOpacity=0f
        private var titleOpacity=0f
        private var observer: ViewTreeObserver?=null
        private var sceneAt=Long.MIN_VALUE
        private var cachedScene=emptyList<View>()
        private var leftHost: GlassHostLayout?=null
        private var rightHost: GlassHostLayout?=null
        var stopped=false;private set
        private val attach=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if(stopped)return
                observer=content.viewTreeObserver.also { it.addOnPreDrawListener(this@Session) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                animator?.end()
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this@Session);observer=null
                if(activity.isFinishing || activity.isDestroyed)stop()
            }
        }
        fun stop() {
            if(stopped)return
            stopped=true;animator?.cancel();content.removeOnAttachStateChangeListener(attach)
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this);observer=null
            if(sessions[activity]?.get()===this)sessions.remove(activity)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                (layer.parent as? ViewGroup)?.removeView(layer);(veil.parent as? ViewGroup)?.removeView(veil)
            }
        }
        fun start() {
            decor.addView(veil,FrameLayout.LayoutParams(-1,-1))
            decor.addView(layer,FrameLayout.LayoutParams(-1,-1));layer.visibility=View.GONE
            content.addOnAttachStateChangeListener(attach)
            observer=content.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        fun eager(key: String): Boolean {
            if(intended?.name?.lowercase(java.util.Locale.ROOT)!=key)return false
            val n=poll[key] ?: 0
            val times=longArrayOf(0,16,40,80,160,320)
            if(n>=times.size || SystemClock.uptimeMillis()-intentAt<times[n])return false
            poll[key]=n+1;return true
        }
        fun begin(target: Page) {
            if(current!=null && page==target)return
            if(target==Page.SEARCH)SpotifyProfileArtwork.beginSearchHandoff(activity)
            else SpotifyProfileArtwork.endSearchHandoff(activity)
            intended=target;destination=target;intentAt=SystemClock.uptimeMillis();animationAt=intentAt;poll.clear();leavingAt=0L
            cache[target]?.let { current=it;transition(it) }
            controls.forEach { (page,ref) -> ref.get()?.visibility=if(page==target)View.VISIBLE else View.GONE }
            layer.visibility=View.VISIBLE;content.invalidate()
        }
        fun leave(source: Page) {
            if(current?.page==source && leavingAt==0L) { leavingAt=SystemClock.uptimeMillis() }
        }
        fun offer(page: Page,config: GlassConfig,left: Rect,right: Rect?,title: String,camera: Drawable?,avatar: ()->Boolean,action: ()->Boolean,source: View) {
            val offer=Offer(page,config,Rect(left),right?.let { Rect(it) },title,camera,avatar,action,WeakReference(source))
            cache[page]=offer
            if(!accepts(page))return
            intended=null;lastOffer=SystemClock.uptimeMillis();leavingAt=0L
            this.config=config
            if(leftHost==null) {
                leftHost=host(false).also { layer.addView(it,FrameLayout.LayoutParams(1,1)) }
                rightHost=host(true).also { layer.addView(it,FrameLayout.LayoutParams(1,1)) }
            }
            val previous=current
            current=offer
            if(previous?.page!=page) controls.forEach { (key,ref) ->
                if(key!=page)ref.get()?.visibility=View.GONE
            }
            if(previous==null || previous.page!=page || previous.left!=left || previous.right!=right || previous.title!=title)transition(offer)
            else if(previous.camera!==camera) { this.camera=cameraCopy(camera) }
            leftHost?.contentDescription=title.ifBlank { nativeText("library_all_header_profile_button_content_description","Profile and settings") }
            rightHost?.contentDescription=if(page==Page.SEARCH)nativeText("camera_button_content_description","Open camera")
                else nativeText("library_all_header_create_playlist_icon_content_description","Create playlist")
            SpotifyTopDrawer.bind(layer,activity)
            layer.visibility=View.VISIBLE
            if(layer.isLayoutRequested && decor.width>0) {
                layer.measure(View.MeasureSpec.makeMeasureSpec(decor.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(decor.height,View.MeasureSpec.EXACTLY))
                layer.layout(0,0,decor.width,decor.height);place()
            }
            veil.visibility=View.VISIBLE
            gradient.setFadeHeight((left.bottom+if(page==Page.HOME)62*d else 110*d).roundToInt())
            SpotifyTopDrawer.bind(veil,activity)
        }
        private fun transition(next: Offer) {
            animator?.cancel()
            animationAt=SystemClock.uptimeMillis()
            oldTitle=title;title=next.title
            oldPage=page;page=next.page;oldCamera=camera
            camera=cameraCopy(next.camera)
            val start=RectF(leftBox);val startRight=RectF(rightBox)
            val target=RectF(next.left)
            // Both capsules share a fixed photo centre; Home's 40dp circle is
            // inset 4dp inside the same 48dp anchor used by Search/Library.
            anchorX=if(next.page==Page.HOME)target.centerX() else target.left+24f*d
            anchorY=target.centerY()
            val targetRight=next.right?.let { RectF(it) } ?: startRight
            val fromRight=rightOpacity;val toRight=if(next.right==null)0f else 1f
            val fromTitle=titleOpacity;val toTitle=if(next.page==Page.HOME)0f else 1f
            if(start.isEmpty) {
                leftBox.set(target);rightBox.set(targetRight);rightOpacity=toRight;titleOpacity=toTitle;progress=1f;place();return
            }
            progress=0f
            animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=280L;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
                addUpdateListener {
                    val t=it.animatedValue as Float;progress=t
                    leftBox.set(start.left+(target.left-start.left)*t,start.top+(target.top-start.top)*t,
                        start.right+(target.right-start.right)*t,start.bottom+(target.bottom-start.bottom)*t)
                    rightBox.set(startRight.left+(targetRight.left-startRight.left)*t,startRight.top+(targetRight.top-startRight.top)*t,
                        startRight.right+(targetRight.right-startRight.right)*t,startRight.bottom+(targetRight.bottom-startRight.bottom)*t)
                    rightOpacity=fromRight+(toRight-fromRight)*t;titleOpacity=fromTitle+(toTitle-fromTitle)*t
                    place();leftHost?.postInvalidateOnAnimation();rightHost?.postInvalidateOnAnimation()
                };start()
            }
        }
        private fun place() {
            fun one(view: View?,rect: RectF) {
                view ?: return
                val old=view.layoutParams as? FrameLayout.LayoutParams
                val w=rect.width().roundToInt().coerceAtLeast(1);val h=rect.height().roundToInt().coerceAtLeast(1)
                val x=rect.left.roundToInt();val y=rect.top.roundToInt()
                if(old!=null && (old.width!=w || old.height!=h || old.leftMargin!=x || old.topMargin!=y)) {
                    old.width=w;old.height=h;old.leftMargin=x;old.topMargin=y
                    view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                    view.layout(x,y,x+w,y+h)
                }
            }
            one(leftHost,leftBox);one(rightHost,rightBox)
            rightHost?.alpha=rightOpacity;rightHost?.visibility=if(rightOpacity>.001f)View.VISIBLE else View.INVISIBLE
        }
        fun artworkChanged() {
            if(!stopped)leftHost?.invalidateNavigationSnapshot()
        }
        fun register(page: Page,view: SpotifyTopPageLayer) { controls[page]=WeakReference(view) }
        fun accepts(value: Page): Boolean = destination==null || destination==value || SystemClock.uptimeMillis()-intentAt>1200L
        fun elapsed()=if(animationAt==0L)280L else SystemClock.uptimeMillis()-animationAt
        override fun onPreDraw(): Boolean {
            if(stopped)return true
            // Never cancel the window's frame to wait for an injected toolbar.
            // Each page binds in its own pre-draw; discovery is bounded by eager().
            val now=SystemClock.uptimeMillis()
            if(now-configAt>=500L) { config=HookConfigReader.read(activity,activity.packageName);configAt=now }
            if(intended!=null && now-intentAt>1200L)intended=null
            if((leavingAt!=0L && intended==null && current?.source?.get()?.let { !it.isAttachedToWindow || !it.isShown }!=false) || !config.enabled ||
                activity.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                layer.visibility=View.GONE;veil.visibility=View.GONE
            }
            return true
        }
        private fun host(right: Boolean): GlassHostLayout {
            val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.DEFAULT_BOLD }
            val ink=Paint(Paint.ANTI_ALIAS_FLAG)
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
                override fun pauseBackdropWork(host: View)=stopped || !activity.hasWindowFocus() || !host.isShown
                override fun allowsHostTouch()=intended==null && !SpotifyTopDrawer.isOpen(activity)
                override fun performTap(host: View,source: ViewGroup?,index: Int,slotCount: Int): Boolean =
                    if(intended!=null || SpotifyTopDrawer.isOpen(activity))false else if(right)current?.action?.invoke()==true else current?.avatar?.invoke()==true
                override fun draw(canvas: Canvas,frame: AdapterNavigationFrame) {
                    if(right) {
                        fun icon(value: Page,image: Drawable?,alpha: Float) {
                            if(alpha<=.001f)return
                            val cx=frame.width/2f;val cy=frame.height/2f;val radius=8f*d
                            if(value==Page.SEARCH && image!=null) {
                                val size=(24f*d).roundToInt();val x=(cx-size/2).roundToInt();val y=(cy-size/2).roundToInt()
                                image.setBounds(x,y,x+size,y+size);image.alpha=(255*alpha).roundToInt().coerceIn(0,255);image.draw(canvas)
                            } else if(value==Page.LIBRARY) {
                                ink.color=Color.WHITE;ink.alpha=(255*alpha).roundToInt().coerceIn(0,255);ink.strokeWidth=2f*d
                                canvas.drawLine(cx-radius,cy,cx+radius,cy,ink);canvas.drawLine(cx,cy-radius,cx,cy+radius,ink)
                            }
                        }
                        icon(oldPage,oldCamera,1f-progress);icon(page,camera,progress);return
                    }
                    val host=leftHost ?: return
                    val cx=anchorX-host.left;val cy=anchorY-host.top;val size=28.8f*d
                    val save=canvas.save();canvas.translate(cx-size/2,cy-size/2)
                    path.reset();path.addCircle(size/2,size/2,size/2,Path.Direction.CW);canvas.clipPath(path)
                    SpotifyProfileArtwork.draw(activity,canvas,size);canvas.restoreToCount(save)
                    text.textSize=24f*frame.scaledDensity;text.color=Color.WHITE
                    val baseline=frame.height/2f-(text.ascent()+text.descent())/2f
                    val x=cx+24f*d
                    fun label(value: String,opacity: Float) {
                        if(value.isBlank() || opacity<=.001f)return
                        text.alpha=(255*opacity).roundToInt().coerceIn(0,255)
                        val line=android.text.TextUtils.ellipsize(value,android.text.TextPaint(text),
                            (frame.width-x-12f*d).coerceAtLeast(0f),android.text.TextUtils.TruncateAt.END).toString()
                        canvas.drawText(line,x,baseline,text)
                    }
                    label(oldTitle,(1f-progress)*titleOpacity);label(title,progress*titleOpacity)
                }
            }
            val adapter=object: TargetAdapter {
                override val key="spotify-continuous-top"
                override val ownsHostLayout=true
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
                override fun createNavigationState(context: Context)=state
                override fun surfaceOpacityScale(config: GlassConfig)=config.spotifyBarOpacity
                override fun glassSurfaceFillColor(dark: Boolean,selectedIndex: Int,defaultColor: Int)=(0x292929) or (Color.alpha(defaultColor) shl 24)
                override fun normalizeConfig(config: GlassConfig)=config.copy(detachedPublish=false,hiddenMask=0,iconOnly=true,barHeight=48f,cornerRadiusPercent=50f,selectedAccent=false)
            }
            return GlassHostLayout(activity,config,1,navigationSource=null,sliderEnabled=false,adapterOverride=adapter).apply { isClickable=true;isFocusable=false;elevation=0f }
        }
        private fun scene(): List<View> = SpotifyBackdropScene.views(content)
    }
}
