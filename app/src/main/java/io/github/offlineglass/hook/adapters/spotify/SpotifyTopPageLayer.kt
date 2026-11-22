package io.github.offlineglass.hook.adapters.spotify

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Fade the complete control subtree, independently of native drawer dimming. */
internal open class SpotifyTopPageLayer(context: Context,private val page: SpotifyTopContinuity.Page): FrameLayout(context),AdapterOwnedOverlay {
    private var wanted=false
    private var contentOpacity=1f
    private var fade: ValueAnimator?=null
    override fun onViewAdded(child: View) { super.onViewAdded(child);child.alpha=contentOpacity }
    private fun opacity(value: Float) {
        contentOpacity=value
        for(i in 0 until childCount)getChildAt(i).alpha=value
    }
    override fun setVisibility(value: Int) {
        val activity=context as? Activity
        if(value==View.VISIBLE && activity!=null && !SpotifyTopContinuity.accepts(activity,page)) {
            setVisibility(View.GONE);return
        }
        val show=value==View.VISIBLE
        if(show==wanted) {
            if(fade==null && !show)super.setVisibility(value)
            return
        }
        wanted=show
        val interrupted=fade?.isRunning==true
        fade?.cancel();fade=null
        if(!isAttachedToWindow || childCount==0 || (!show && !isShown)) {
            opacity(if(show)1f else 0f);super.setVisibility(value);return
        }
        if(show)super.setVisibility(View.VISIBLE)
        val from=if(show && contentOpacity>=.999f && !interrupted)0f else contentOpacity
        opacity(from)
        val animation=ValueAnimator.ofFloat(from,if(show)1f else 0f)
        fade=animation
        animation.duration=280L
        animation.interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
        animation.addUpdateListener { opacity(it.animatedValue as Float) }
        animation.addListener(object: android.animation.AnimatorListenerAdapter() {
            private var cancelled=false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled=true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if(cancelled)return
                fade=null
                if(!wanted)finishHide()
            }
        })
        animation.start()
        if(!interrupted && activity!=null) {
            val elapsed=SpotifyTopContinuity.elapsed(activity)
            if(elapsed in 0L..279L)animation.currentPlayTime=elapsed
        }
    }
    private fun finishHide() { super.setVisibility(View.GONE) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? Activity)?.let { SpotifyTopContinuity.register(it,page,this) }
    }
    override fun onDetachedFromWindow() { fade?.end();fade=null;super.onDetachedFromWindow() }
}
