package io.github.offlineglass.hook.adapters.x

import io.github.offlineglass.rendering.setFloatUniformExact

import android.graphics.*
import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.ContinuousControlOptics
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.PostureGlass
import kotlin.math.ceil
import kotlin.math.min

/** Native-position material: one contour drives the mask, refraction normals and rim. */
internal class XReplyMaterial(private val anchor: View,private val source: XReplyPageSource,bridge: XComposeBridge) {
    private companion object {
        const val LENS_HEIGHT_DP=16f
        const val LENS_AMOUNT_DP=28f
        const val EXPANDED_LENS_HEIGHT_DP=20f
        const val EXPANDED_LENS_AMOUNT_DP=34f
    }
    private val halo=XReplyHalo(anchor,bridge)
    private var exteriorVisible=false
    private var sample=RenderNode("LiquidTab-X-reply-glass-front")
    private var staging=RenderNode("LiquidTab-X-reply-glass-staging")
    private var sampleWidth=0f
    private var sampleHeight=0f
    private var samplePadding=0f
    private var sampleInputs: List<XReplyPageSource.Input>?=null
    private var currentEffect: RenderEffect?=null
    private var sampleEffect: RenderEffect?=null
    private var liveFrames=0L
    private var retainedFrames=0L
    private var waitingFrames=0L
    private var sceneRecords=0L
    private var sceneIssue=""
    private var shaderFailure=""
    private val shader=runCatching { PostureGlass.shader(XReplyOptics.source) }.getOrElse {
        shaderFailure=it.stackTraceToString();null
    }
    private val highlight=runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    val contour=Path()
    private var geometry=""
    private var effectKey=""
    var diagnostic="native fallback"
        private set
    fun prepareContour(frame: XComposeBridge.Frame,shape: XComposeBridge.Shape): Path {
        val density=anchor.resources.displayMetrics.density
        val radius=if(shape.radius>0) min(shape.radius,frame.height/2f) else min(28f*density,frame.height/2f)
        val next="${frame.width}/${frame.height}/$radius"
        if(geometry!=next) {
            geometry=next
            contour.setBottomBarSquircle(RectF(0f,0f,frame.width,frame.height),radius,-1f)
            shader?.let { ContinuousControlOptics.bind(it,contour,radius) }
        }
        return contour
    }
    fun setExteriorVisible(visible: Boolean) {
        exteriorVisible=visible
        if(!visible) halo.hide()
    }
    fun syncExterior(): Boolean = if(exteriorVisible) halo.syncPosition() else true
    fun hideExterior() = halo.hide()

    fun draw(canvas: Canvas,node: Any,coordinate: Any,frame: XComposeBridge.Frame,shape: XComposeBridge.Shape): Boolean {
        val cfg=HookConfigReader.read(anchor.context,XAdapter.PACKAGE_NAME)
        if (!cfg.enabled || !cfg.backdropCapture) { halo.hide();return false }
        val density=anchor.resources.displayMetrics.density
        val w=frame.width;val h=frame.height
        val radius=if(shape.radius>0) min(shape.radius,h/2f) else min(28f*density,h/2f)
        val next="$w/$h/$radius"
        val lens=shader
        prepareContour(frame,shape)
        val blur=cfg.blurRadius*density
        // Follow measured native height continuously, including IME/line-count animations.
        // No dependency on private expanded-state fields or version-specific class names.
        val expansion=((h/density-64f)/64f).coerceIn(0f,1f)
        val blend=expansion*expansion*(3f-2f*expansion)
        val lensHeight=(LENS_HEIGHT_DP+(EXPANDED_LENS_HEIGHT_DP-LENS_HEIGHT_DP)*blend)*density
        val lensAmount=(LENS_AMOUNT_DP+(EXPANDED_LENS_AMOUNT_DP-LENS_AMOUNT_DP)*blend)*density
        val pad=ceil(NativeBackdropBlur.padding(blur,lensAmount * cfg.refractionPaddingScale)).toInt()
        val width=ceil(w+pad*2).toInt();val height=ceil(h+pad*2).toInt()
        // Publish complete recordings atomically. A temporary native source
        // gap must not erase the last valid material or reveal native styling.
        sceneIssue=""
        val inputs=runCatching { source.resolve(node,coordinate) }.getOrElse {
            sceneIssue="${it.javaClass.simpleName}: ${it.message}"
            null
        }
        val reuse=inputs!=null && sample.hasDisplayList() && sampleInputs==inputs &&
            sampleWidth==w && sampleHeight==h && samplePadding==pad.toFloat()
        val recorded=inputs!=null && !reuse && runCatching {
            staging.setPosition(0,0,width,height)
            val recording=staging.beginRecording(width,height)
            try { source.draw(recording,inputs,pad.toFloat()) } finally { staging.endRecording() }
        }.onFailure { sceneIssue="${it.javaClass.simpleName}: ${it.message}" }.isSuccess
        val valid=reuse || recorded
        if(valid) {
            if(recorded) {
                val previous=sample;sample=staging;staging=previous
                sampleWidth=w;sampleHeight=h;samplePadding=pad.toFloat();sampleInputs=inputs
                sceneRecords++
            }
            liveFrames++
        } else if(sample.hasDisplayList()) retainedFrames++ else waitingFrames++
        val key="$next/$blur/$pad/$lensHeight/$lensAmount/${cfg.liquidGlassEnabled}/${cfg.postureEffectsEnabled}:${cfg.refractionStrength}"
        if(effectKey!=key || cfg.postureEffectsEnabled) {
            effectKey=key
            val optical=if(cfg.liquidGlassEnabled) lens?.let {
                it.setFloatUniform("size",w,h)
                it.setFloatUniform("offset",-pad.toFloat(),-pad.toFloat())
                it.setFloatUniform("cornerRadii",radius,radius,radius,radius)
                it.setFloatUniform("detachedGeometry",0f,0f,0f)
                it.setFloatUniform("safeSampleBounds",.5f,.5f,width-.5f,height-.5f)
                it.setFloatUniform("refractionHeight",min(h/3f,lensHeight))
                it.setFloatUniform("refractionAmount",-lensAmount * cfg.refractionScale)
                it.setFloatUniform("depthEffect",0f)
                it.setFloatUniformExact("unifiedDropletPilot",0f)
                it.setFloatUniformExact("directionalRimPilot",0f)
                it.setFloatUniformExact("monotoneFlowPilot",0f)
                PostureGlass.sync(it,anchor,cfg.postureEffectsEnabled,cfg.liquidViscosity,cfg.flowHorizontalStrength,cfg.flowVerticalStrength)
                RenderEffect.createRuntimeShaderEffect(it,"content")
            } else null
            val gaussian=if(blur>0) NativeBackdropBlur.effect(blur,1.25f) else null
            currentEffect=if(optical!=null && gaussian!=null) RenderEffect.createChainEffect(optical,gaussian) else optical?:gaussian
        }
        if(valid && (recorded || sampleEffect!==currentEffect)) {
            sample.setRenderEffect(currentEffect)
            sampleEffect=currentEffect
        }
        val dark=anchor.resources.configuration.uiMode and 0x30==0x20
        val base=if(dark) Color.rgb(32,32,34) else Color.WHITE
        if(exteriorVisible) halo.update(coordinate,contour,w,h,density,dark,next)
        paint.color=base
        // The complete native pass is clipped to this same contour upstream.
        // No rectangular refill may reintroduce the old native boundary.
        canvas.drawPath(contour,paint)
        val save=canvas.save()
        try {
            canvas.clipPath(contour)
            if(sample.hasDisplayList() && sampleWidth>0 && sampleHeight>0) {
                // Retained optics remain in their recorded coordinate system;
                // only the temporary scene is fitted to the current native size.
                canvas.scale(w/sampleWidth,h/sampleHeight)
                canvas.translate(-samplePadding,-samplePadding)
                canvas.drawRenderNode(sample)
            }
        } finally { canvas.restoreToCount(save) }
        paint.color=(base and 0x00ffffff) or (((if(dark) cfg.darkAlpha else cfg.lightAlpha)*255).toInt().coerceIn(0,255) shl 24)
        canvas.drawPath(contour,paint)
        if(cfg.outlineEnabled) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=density
            paint.color=if(dark) 0x55ffffff else 0x77ffffff
            highlight?.let {
                var gx=if(cfg.postureEffectsEnabled) BloomTiltTracker.gravityX else 0f
                var gy=if(cfg.postureEffectsEnabled) BloomTiltTracker.gravityY else 0f
                when(anchor.display?.rotation) {
                    android.view.Surface.ROTATION_90 -> { val x=gx;gx=-gy;gy=x }
                    android.view.Surface.ROTATION_180 -> { gx=-gx;gy=-gy }
                    android.view.Surface.ROTATION_270 -> { val x=gx;gx=gy;gy=-x }
                }
                val length=kotlin.math.sqrt(gx*gx+gy*gy)
                if(length>.1f) { gx/=length;gy/=length } else { gx=0f;gy=-1f }
                val c=.70710677f
                it.setFloatUniform("halfView",w/2f,h/2f)
                it.setFloatUniform("highlightAlpha", .50f * cfg.darkBarHighlightStrength)
                it.setColorUniform("strokeColor",Color.WHITE)
                it.setFloatUniform("strokeAlphaMul",0f)
                it.setFloatUniform("lightDir1",c*gx+c*gy,-c*gx+c*gy,-.05f)
                it.setColorUniform("lightColor1",Color.WHITE)
                it.setFloatUniform("lightIntensity1",1f)
                it.setFloatUniform("lightDir2",0f,.19611613f,-.9805807f)
                it.setColorUniform("lightColor2",Color.WHITE)
                it.setFloatUniform("lightIntensity2",.4f)
                paint.alpha=255;paint.shader=it;paint.blendMode=BlendMode.SCREEN
            }
            val rim=canvas.save()
            canvas.clipPath(contour)
            val edgeShader = highlight
            if (cfg.liquidGlassEnabled && !cfg.solidBarEnabled && edgeShader != null)
                io.github.offlineglass.rendering.GlassEdgeHighlight.draw(canvas, contour, paint, edgeShader, density, dark, cfg.darkBarHighlightStrength > 0f)
            else canvas.drawPath(contour,paint)
            canvas.restoreToCount(rim)
            paint.shader=null;paint.blendMode=BlendMode.SRC_OVER;paint.style=Paint.Style.FILL
        }
        diagnostic="continuous-contour ${w.toInt()}x${h.toInt()} r=$radius; material=${if(valid) "live" else if(sample.hasDisplayList()) "retained" else "awaiting scene"}; frames=$liveFrames/$retainedFrames/$waitingFrames; records=$sceneRecords; exterior=${if(dark) "glow" else "shadow"}/${halo.diagnostic}; ${if(cfg.liquidGlassEnabled && lens==null) "shader unavailable: ${shaderFailure.take(160)}" else sceneIssue.ifEmpty { source.diagnostic }}"
        return true
    }
    fun close() {
        halo.close();sample.discardDisplayList();staging.discardDisplayList()
        sampleWidth=0f;sampleHeight=0f;samplePadding=0f;sampleInputs=null;sampleEffect=null;effectKey=""
        PostureGlass.track(anchor,false)
    }
}
