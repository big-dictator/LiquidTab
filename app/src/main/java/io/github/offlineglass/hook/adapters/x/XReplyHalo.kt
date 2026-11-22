package io.github.offlineglass.hook.adapters.x

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.ceil

/** Exterior-only decoration using the current native coordinate transform.
 * Position is refreshed even when Compose moves a cached layer without redrawing it. */
internal class XReplyHalo(private val anchor: View,private val bridge: XComposeBridge): Drawable() {
    private val node=RenderNode("X-reply-exterior").apply { setClipToBounds(false) }
    private val local=Path()
    private val outer=Path()
    private val matrix=Matrix()
    private val matrixValues=FloatArray(9)
    private val footprint=RectF()
    private val dirtyBounds=Rect()
    private var coordinate=WeakReference<Any>(null)
    private var host: View?=null
    private var key=""
    private var padding=0f
    private var width=0f
    private var height=0f
    private var visible=false
    var diagnostic="hidden"
        private set
    fun update(coordinate: Any,contour: Path,width: Float,height: Float,density: Float,dark: Boolean,geometry: String) {
        val root=anchor.rootView
        if(!anchor.isShown || !root.isAttachedToWindow) { hide();return }
        this.coordinate=WeakReference(coordinate)
        this.width=width;this.height=height
        val next="$geometry/$density/$dark"
        if(key!=next || !node.hasDisplayList()) {
            key=next;local.set(contour)
            val sigma=(if(dark) 6f else 10f)*density
            padding=ceil(sigma*3)
            val offset=if(dark) 0f else 3f*density
            val w=ceil(width+padding*2).toInt();val h=ceil(height+padding*2+offset).toInt()
            node.setPosition(0,0,w,h)
            node.setRenderEffect(RenderEffect.createBlurEffect(sigma,sigma,Shader.TileMode.DECAL))
            val canvas=node.beginRecording(w,h)
            try {
                canvas.translate(padding,padding+offset)
                canvas.drawPath(local,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=if(dark) Color.argb(48,225,235,255) else Color.argb(88,0,0,0)
                })
            } finally { node.endRecording() }
            visible=false
        }
        if(host!==root) {
            host?.overlay?.remove(this)
            host=root;root.overlay.add(this)
            visible=false
        }
        syncPosition()
    }
    /** false requests a native material redraw after a geometry change. */
    fun syncPosition(): Boolean {
        val root=host ?: return true
        val position=coordinate.get() ?: run { hide();return true }
        if(!anchor.isShown || !anchor.isAttachedToWindow || root!==anchor.rootView || !root.isAttachedToWindow) {
            hide();return true
        }
        return runCatching {
            val rect=bridge.bounds(position) ?: run { hide();return@runCatching true }
            val (nativeWidth,nativeHeight)=bridge.size(position)
            // Never stretch an old shadow to a newly measured editor. Wait for
            // the native material to supply the contour for that exact size.
            if(kotlin.math.abs(nativeWidth-width)>.5f || kotlin.math.abs(nativeHeight-height)>.5f) {
                diagnostic="awaiting native resize"
                suppress();return@runCatching false
            }
            if(rect.isEmpty || width<=0f || height<=0f) { suppress();return@runCatching true }
            val current=bridge.overlayTransform(position,anchor,root)
            current.getValues(matrixValues)
            if(!matrixValues.all { it.isFinite() }) { suppress();return@runCatching true }
            var opacity=1f
            var parent: View?=anchor
            while(parent!=null && parent!==root) { opacity*=parent.alpha;parent=parent.parent as? View }
            val alpha=opacity.coerceIn(0f,1f)
            if(!visible || current!=matrix || node.alpha!=alpha) {
                matrix.set(current)
                outer.set(local);outer.transform(matrix)
                node.alpha=alpha
                visible=true
                footprint.set(-padding,-padding,node.width-padding,node.height-padding)
                matrix.mapRect(footprint)
                footprint.roundOut(dirtyBounds)
                setBounds(dirtyBounds)
                invalidateSelf()
            }
            diagnostic="live ${nativeWidth}x${nativeHeight}"
            true
        }.getOrElse { suppress();true }
    }
    private fun suppress() {
        if(visible) { visible=false;invalidateSelf() }
    }
    override fun draw(canvas: Canvas) {
        // Compose's graphics layer may have moved after pre-draw; read its live
        // transform again before recording the overlay's clipping and shadow.
        syncPosition()
        if(!visible || !canvas.isHardwareAccelerated || !node.hasDisplayList()) return
        val save=canvas.save()
        try {
            canvas.clipOutPath(outer)
            canvas.concat(matrix)
            canvas.translate(-padding,-padding)
            canvas.drawRenderNode(node)
        } finally { canvas.restoreToCount(save) }
    }
    fun hide() { visible=false;diagnostic="hidden";host?.overlay?.remove(this);host=null;coordinate.clear() }
    fun close() { hide();node.discardDisplayList();key="" }
    override fun setAlpha(alpha: Int) { node.alpha=alpha/255f;invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
