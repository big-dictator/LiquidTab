package io.github.offlineglass.hook.adapters.quora

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.*
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole

/** Quora-owned binding, geometry and continuity. All material/press/posture is shared. */
internal class QuoraTopBar(private val backdrop: QuoraPageBackdrop) {
    private data class Binding(val bar: ViewGroup, val profile: View, val avatar: ImageView,
        val title: TextView, val search: View, val searchImage: ImageView,
        val add: View, val addImage: ImageView)
    private var owner: ViewGroup? = null
    private var bottom: GlassHostLayout? = null
    private var content: FrameLayout? = null
    private var buttons = emptyList<GlassHostLayout>()
    private val suppressed = java.util.WeakHashMap<View, Float>()
    private var nextProbe = 0L
    private var title = ""
    private var previousTitle = ""
    private var transition = 1f
    private var animator: ValueAnimator? = null
    private var desiredWidth = 0
    private var avatar: Drawable? = null
    private var avatarSource: ImageView? = null
    private var nativeAvatar: Drawable? = null
    private var searchIcon: Drawable? = null
    private var addIcon: Drawable? = null
    private var nativeSearchIcon: Drawable? = null
    private var nativeAddIcon: Drawable? = null
    private val ink = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val avatarClip = Path()
    private val origin = IntArray(2)
    private val sourcePosition = IntArray(2)
    private val bottomPosition = IntArray(2)
    private var labelWidth = Float.NaN
    private var labelSize = Float.NaN
    private var labelTypeface: android.graphics.Typeface? = null
    private var labelTitle = ""
    private var labelPrevious = ""
    private var clippedTitle = ""
    private var clippedPrevious = ""
    private var titleColor = android.graphics.Color.BLACK
    private var addVisible = false
    private var nativeVisible = false
    private var sidebarRequested = false
    private var lastTitleColor: Int? = null
    private var lastAvatar: Drawable? = null
    private var lastSearch: Drawable? = null
    private var lastAdd: Drawable? = null
    private var extendedContent: View? = null
    private var originalContentTop = 0
    private var appliedContentTop: Int? = null
    private var originalContentHeight = 0
    private var originalContentWeight = 0f
    private var appliedContentHeight: Int? = null
    private var safetyBottom = 0
    fun contentSafetyBottom(): Int = if(nativeVisible) safetyBottom else 0
    fun visibilityHost(): GlassHostLayout? = buttons.firstOrNull()
    fun glassHosts(): List<GlassHostLayout> = buttons

    private fun extendContent(b: Binding) {
        val root=owner ?: return
        val header=b.bar.parent as? ViewGroup ?: return
        if(header.parent!==root)return
        val page=QuoraViews.child(root,"content_wrapper") ?: return
        val p=page.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if(extendedContent!==page) {
            restoreContent();extendedContent=page;originalContentTop=p.topMargin
            originalContentHeight=p.height;originalContentWeight=p.weight
        }
        // Cancel the verified preceding header's layout contribution. The
        // native header keeps its measured geometry/click semantics, while
        // the weighted content fills its old area including status-bar padding.
        val desired=originalContentTop-header.height
        val fullHeight=root.height-root.paddingTop-root.paddingBottom
        // LinearLayout's weighted measurement does not reclaim this negative
        // margin. Explicitly give the translated content the full viewport;
        // otherwise moving its top upward leaves a header-sized hole below it.
        if(header.height>0&&fullHeight>0&&
            (p.topMargin!=desired||p.height!=fullHeight||p.weight!=0f)) {
            p.topMargin=desired;p.height=fullHeight;p.weight=0f
            appliedContentTop=desired;appliedContentHeight=fullHeight;page.layoutParams=p
        }
    }

    private fun restoreContent() {
        extendedContent?.let { page ->
            val p=page.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return@let
            var changed=false
            if(p.topMargin==appliedContentTop) { p.topMargin=originalContentTop;changed=true }
            if(p.height==appliedContentHeight&&p.weight==0f) {
                p.height=originalContentHeight;p.weight=originalContentWeight;changed=true
            }
            if(changed)page.layoutParams=p
        }
        extendedContent=null;appliedContentTop=null;appliedContentHeight=null
    }

    /** Bounded, unique resource contract inside the verified main tab container. */
    private fun binding(): Binding? {
        val root = owner ?: return null
        val queue = java.util.ArrayDeque<Pair<View, Int>>()
        queue.add(root to 0)
        val bars = ArrayList<ViewGroup>()
        var budget = 128
        while (queue.isNotEmpty() && budget-- > 0) {
            val (view, depth) = queue.removeFirst()
            if (QuoraAdapter.name(view) == "q_navbar" && view is ViewGroup) bars.add(view)
            // The header lives above content_wrapper; never scan page/DOM children.
            if (view is ViewGroup && depth < 3 && QuoraAdapter.name(view) != "content_wrapper")
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
        }
        val bar = bars.singleOrNull()?.takeIf { queue.isEmpty() } ?: return null
        if(buttons.isNotEmpty()) {
            if(!suppressed.containsKey(bar)) suppressed[bar]=bar.alpha
            if(bar.alpha!=0f)bar.alpha=0f
        }
        val nodes=QuoraNavigationContract.topControls(QuoraAdapter.ViewNode(bar)) ?: return null
        fun view(i: Int)=(nodes[i] as QuoraAdapter.ViewNode).view
        val profile=view(0);val image=view(1) as? ImageView ?: return null
        val label=view(2) as? TextView ?: return null
        val search=view(3);val searchImage=view(4) as? ImageView ?: return null
        val add=view(5);val addImage=view(6) as? ImageView ?: return null
        return Binding(bar, profile, image, label, search, searchImage, add, addImage)
    }

    fun update(host: GlassHostLayout, tabOwner: ViewGroup?,sidebarOpen: Boolean) {
        bottom = host; owner = tabOwner
        // Visibility belongs to the shared reversible scene animation, not to
        // immediate visibility/alpha setters on sidebar state changes.
        sidebarRequested=sidebarOpen
        syncVisibility()
        val now = SystemClock.uptimeMillis()
        if (now < nextProbe) return
        nextProbe = now + 100
        val b = binding()
        // A temporary missing binding never relinquishes native visual suppression.
        if (b == null) return
        val nativeTabs = tabOwner?.let { QuoraViews.child(it,"tab_bar_tabs") }
        val visible = nativeTabs?.isShown==true && b.bar.isShown && b.profile.visibility == View.VISIBLE && b.search.visibility == View.VISIBLE
        nativeVisible = visible
        if (!visible) { buttons.forEach { it.visibility = View.INVISIBLE }; return }
        if (buttons.isEmpty()) {
            val activity = host.context as? Activity ?: return
            val parent = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
            content = parent
            buttons = (0..2).map { role -> createButton(activity, role).also {
                parent.addView(it, FrameLayout.LayoutParams(1, 1))
            } }
        }
        if (!suppressed.containsKey(b.bar)) suppressed[b.bar] = b.bar.alpha
        if (b.bar.alpha != 0f) b.bar.alpha = 0f
        extendContent(b)
        // Retain one avatar across native page/control rebuilds; update only when
        // the same native image actually changes (e.g. asynchronous profile load).
        if (avatar == null || (avatarSource === b.avatar && nativeAvatar !== b.avatar.drawable)) {
            nativeAvatar = b.avatar.drawable
            avatar = nativeAvatar?.constantState?.newDrawable(b.avatar.resources)?.mutate()
        }
        avatarSource = b.avatar
        if (nativeSearchIcon !== b.searchImage.drawable) {
            nativeSearchIcon=b.searchImage.drawable
            searchIcon = nativeSearchIcon?.constantState?.newDrawable(b.searchImage.resources)?.mutate()
        }
        if (nativeAddIcon !== b.addImage.drawable) {
            nativeAddIcon=b.addImage.drawable
            addIcon = nativeAddIcon?.constantState?.newDrawable(b.addImage.resources)?.mutate()
        }
        titleColor = b.title.currentTextColor
        ink.typeface = b.title.typeface
        val d = host.resources.displayMetrics.density
        val parent = content ?: return
        parent.getLocationInWindow(origin)
        b.bar.getLocationInWindow(sourcePosition)
        host.getLocationInWindow(bottomPosition)
        val left = bottomPosition[0] - origin[0]
        val right = parent.width - (bottomPosition[0] - origin[0] + host.width)
        val size = (44*d).toInt()
        val y = sourcePosition[1]-origin[1]+(b.bar.height-size)/2
        safetyBottom=sourcePosition[1]+(b.bar.height-size)/2+size+(12*d).toInt()
        val nativeRow=(nativeTabs as? ViewGroup)?.let(QuoraAdapter::row)
        val selected=QuoraViews.selected(nativeRow)
        // Verified QTabBar order: FEED, FOLLOWING, ANSWER, SPACES, NOTIFS.
        // The final two native pages expose search only, never the ask action.
        addVisible = b.add.visibility == View.VISIBLE && nativeRow?.childCount==5 && selected in 0..2
        val searchX = parent.width-right-size-(if(addVisible) size+(10*d).toInt() else 0)
        place(buttons[1], searchX, y, size, size)
        place(buttons[2], parent.width-right-size, y, size, size)
        // Do not rewrite visibility here: the scene animator owns both the
        // transitional state and the final GONE state, including the add role.
        ink.textSize = 19*host.resources.displayMetrics.scaledDensity
        val text = b.title.text?.toString().orEmpty()
        val width = ((28+36)*d+ink.measureText(text)).toInt().coerceAtLeast(size)
            .coerceAtMost((searchX-left-12*d).toInt().coerceAtLeast(size))
        if (text != title || width != desiredWidth) {
            previousTitle = title; title = text; desiredWidth = width
            animator?.cancel()
            val from = buttons[0].width.takeIf { it > 1 } ?: width
            transition = if(previousTitle.isEmpty()) 1f else 0f
            animator = ValueAnimator.ofFloat(0f,1f).apply {
                duration = 240
                interpolator = android.view.animation.PathInterpolator(.2f,0f,0f,1f)
                addUpdateListener {
                    val p = it.animatedValue as Float; transition = p
                    place(buttons[0], left,y,(from+(width-from)*p).toInt(),size)
                    buttons[0].invalidate()
                }; start()
            }
        } else if(animator?.isRunning != true) place(buttons[0],left,y,width,size)
        if(titleColor!=lastTitleColor||avatar!==lastAvatar||searchIcon!==lastSearch||addIcon!==lastAdd) {
            lastTitleColor=titleColor;lastAvatar=avatar;lastSearch=searchIcon;lastAdd=addIcon
            buttons.forEach { it.invalidate() }
        }
        syncVisibility()
    }

    private fun syncVisibility() {
        // These source-less controls have no native-row visibility driver.
        // Drive the existing scene animator explicitly; never replace it with
        // direct alpha/visibility writes or animate the native page layout.
        buttons.forEachIndexed { role,button ->
            button.updateAnimatedVisibility(nativeVisible&&!sidebarRequested&&(role!=2||addVisible))
            button.syncOpticalVisibilityCompanions()
        }
    }

    private fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
        val p = v.layoutParams as FrameLayout.LayoutParams
        if(p.width==w&&p.height==h&&p.leftMargin==x&&p.topMargin==y)return
        p.width=w;p.height=h;p.leftMargin=x;p.topMargin=y;v.layoutParams=p
    }

    private fun createButton(activity: Activity, role: Int): GlassHostLayout {
        val state = object : QuoraChromeState {
            override val pageBackdrop = backdrop
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                val d=frame.density;val cy=frame.height/2f
                fun icon(drawable: Drawable?,cx: Float,size: Float) {
                    drawable ?: return
                    drawable.setBounds((cx-size/2).toInt(),(cy-size/2).toInt(),(cx+size/2).toInt(),(cy+size/2).toInt())
                    drawable.draw(canvas)
                }
                if(role!=0){icon(if(role==1) searchIcon else addIcon,frame.width/2f,24*d);return}
                val save=canvas.save();avatarClip.rewind()
                avatarClip.addCircle(24*d,cy,14*d,Path.Direction.CW);canvas.clipPath(avatarClip)
                icon(avatar,24*d,28*d);canvas.restoreToCount(save)
                ink.textSize=19*frame.scaledDensity;ink.color=titleColor
                val baseline=cy-(ink.ascent()+ink.descent())/2
                val available=(frame.width-52*d).coerceAtLeast(0f)
                if(labelWidth!=available||labelSize!=ink.textSize||labelTypeface!==ink.typeface||
                    labelTitle!=title||labelPrevious!=previousTitle) {
                    fun clip(s: String)=if(ink.measureText(s)<=available)s else
                        android.text.TextUtils.ellipsize(s,ink,available,android.text.TextUtils.TruncateAt.END).toString()
                    clippedTitle=clip(title);clippedPrevious=clip(previousTitle)
                    labelWidth=available;labelSize=ink.textSize;labelTypeface=ink.typeface
                    labelTitle=title;labelPrevious=previousTitle
                }
                fun label(s: String,alpha: Float) {
                    if(alpha<=0f)return
                    ink.alpha=(255*alpha).toInt().coerceIn(0,255)
                    canvas.drawText(s,46*d,baseline,ink)
                }
                label(clippedPrevious,1-transition);label(clippedTitle,transition)
            }
            override fun performTap(host: View,source: ViewGroup?,index: Int,slotCount: Int): Boolean {
                val b=binding() ?: return false
                if(sidebarRequested||!nativeVisible||!b.bar.isShown)return false
                val action=when(role){0->b.profile;1->b.search;else->b.add}
                return action.isAttachedToWindow&&action.visibility==View.VISIBLE&&action.performClick()
            }
            override fun pageAllowsNavigation(root: View?) = nativeVisible&&!sidebarRequested&&(role!=2||addVisible)
            override fun suppressRedrawnSource() = false
        }
        val material=TopBarMaterialPolicy(TopBarRole.ACTION,heightDp=44f,iconOnly=true,selectedAccent=false)
        val adapter=object: TargetAdapter {
            override val key="quora-top-$role"
            override val ownsHostLayout=true
            override val sceneVisibilityAnimation=true
            override val simpleSceneVisibilityAnimation=true
            override fun sceneExitTranslationPx(hostHeight: Int,density: Float): Float =
                -(hostHeight+(buttons.firstOrNull()?.top ?: 0)+16*density)
            override val ownsNavigationDrawing=true
            override val ownsNavigationTap=true
            override val skipNavigationSnapshot=true
            override val managesHostSiblingOrder=true
            override val retainsExistingBackdropRenderNode=true
            override val allowsSystemBackgroundBlur=false
            override fun createNavigationState(context: android.content.Context)=state
            override fun normalizeConfig(config: io.github.offlineglass.config.GlassConfig)=material.apply(config)
            override fun pushedConfig()=bottom?.config?.let(material::apply)
        }
        return GlassHostLayout(activity,bottom!!.config,1,navigationSource=null,sliderEnabled=false,adapterOverride=adapter)
            .apply { isClickable=true;elevation=0f;clipChildren=false;clipToPadding=false }
    }

    fun dispose() {
        animator?.cancel();animator=null
        restoreContent()
        buttons.forEach { button ->
            button.visibility=View.INVISIBLE
            (button.parent as? ViewGroup)?.let { parent ->
                parent.post { if(button.parent===parent) parent.removeView(button) }
            }
        };buttons=emptyList()
        suppressed.forEach { (v,alpha)->if(v.alpha==0f)v.alpha=alpha };suppressed.clear()
        owner=null;bottom=null;content=null;avatar=null;searchIcon=null;addIcon=null
        lastAvatar=null;lastSearch=null;lastAdd=null
        avatarSource=null;nativeAvatar=null;nativeSearchIcon=null;nativeAddIcon=null
    }
}
