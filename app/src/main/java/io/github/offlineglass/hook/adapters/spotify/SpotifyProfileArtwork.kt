package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Cache the native decoded photo only. Never cache a composited avatar/initials frame. */
internal object SpotifyProfileArtwork {
    private data class Photo(val source: WeakReference<View>,val id: Int,val bitmap: Bitmap)
    private val photos=WeakHashMap<Activity,Photo>()
    private data class SearchHandoff(val bitmap: Bitmap,val until: Long)
    private val searchHandoffs=WeakHashMap<Activity,SearchHandoff>()
    fun beginSearchHandoff(activity: Activity) {
        val photo=photos[activity]?.takeUnless { it.bitmap.isRecycled } ?: return
        val handoff=SearchHandoff(photo.bitmap,SystemClock.uptimeMillis()+1200L)
        searchHandoffs[activity]=handoff
        val weak=WeakReference(activity)
        photo.source.get()?.rootView?.postDelayed({
            val owner=weak.get()
            if(owner!=null && searchHandoffs[owner]===handoff)endSearchHandoff(owner)
        },1200L)
    }
    fun endSearchHandoff(activity: Activity) {
        if(searchHandoffs.remove(activity)!=null && photos[activity]?.bitmap?.isRecycled!=false)
            SpotifyTopContinuity.artworkChanged(activity)
    }
    private fun handoff(activity: Activity): Bitmap? {
        val cached=searchHandoffs[activity] ?: return null
        if(SystemClock.uptimeMillis()>=cached.until || cached.bitmap.isRecycled) {
            searchHandoffs.remove(activity);return null
        }
        return cached.bitmap
    }
    private data class Probe(val id: Int,var attempts: Int=0,var at: Long=0L,var resolved: Boolean=false,
        var decoded: WeakReference<Bitmap> = WeakReference(null))
    private val probes=WeakHashMap<View,Probe>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun publish(activity: Activity,avatar: SpotifyHeaderBinding.Button,bitmap: Bitmap): Boolean {
        val changed=photos[activity]?.bitmap!==bitmap
        photos[activity]=Photo(WeakReference(avatar.source),avatar.artworkId,bitmap)
        if(avatar.profileHeaderOwner)searchHandoffs.remove(activity)
        if(changed)SpotifyTopContinuity.artworkChanged(activity)
        return changed
    }
    fun retry() { val affected=(photos.keys+searchHandoffs.keys).distinct();photos.clear();searchHandoffs.clear();affected.forEach { SpotifyTopContinuity.artworkChanged(it) };probes.values.forEach { it.attempts=0;it.at=0L;it.resolved=false;it.decoded.clear() } }
    fun ensure(activity: Activity,avatar: SpotifyHeaderBinding.Button,ready: ()->Unit): Boolean {
        val previous=photos[activity]
        if(previous!=null && (previous.source.get()!==avatar.source || previous.id!=avatar.artworkId))photos.remove(activity)
        fun available()=photos[activity]?.bitmap?.isRecycled==false
        if(activity.isDestroyed || !avatar.source.isAttachedToWindow)return false
        val state=probes[avatar.source]?.takeIf { it.id==avatar.artworkId } ?: Probe(avatar.artworkId).also { probes[avatar.source]=it }
        val now=SystemClock.uptimeMillis()
        if(now<state.at) {
            // Restore only this same native source/id's accepted photo after a
            // quick page round-trip; do not wait for the next decode deadline.
            val accepted=state.decoded.get()?.takeUnless { it.isRecycled }
            if(accepted!=null) {
                publish(activity,avatar,accepted)
                return true
            }
            if(!state.resolved)return available()
            // A reclaimed/recycled successful result needs fresh decoding, not
            // a blank view until its old success deadline expires.
            state.at=0L
        }
        if(state.attempts>=12)return available()
        state.attempts++;state.at=now+400L
        val bitmap=runCatching {
            val direct=(avatar.source as? ImageView)?.drawable as? android.graphics.drawable.BitmapDrawable
            direct?.bitmap ?: SpotifyAvatarPhoto.read(avatar)
        }.onFailure { android.util.Log.w("SpotifyGlass","Avatar photo binding failed",it) }.getOrNull()?.takeUnless { it.isRecycled }
        if(bitmap!=null) {
            if(avatar.profileHeaderOwner && (!state.resolved || state.decoded.get()!==bitmap)) {
                val report="time=${System.currentTimeMillis()} id=${avatar.artworkId} decoded=${bitmap.width}x${bitmap.height} ${SpotifyAvatarPhoto.failure}"
                runCatching { java.io.File(activity.getExternalFilesDir(null),"spotify_avatar_binding.txt").writeText(report) }
            }
            val changed=publish(activity,avatar,bitmap)
            state.decoded=WeakReference(bitmap)
            state.resolved=true;state.attempts=0;state.at=now+2000L
            if(changed)avatar.source.post { ready() };return true
        }
        photos.remove(activity)
        if(previous!=null)SpotifyTopContinuity.artworkChanged(activity)
        state.decoded.clear();state.resolved=false
        if(state.attempts==1 || state.attempts==12) {
            val report="time=${System.currentTimeMillis()} id=${avatar.artworkId} attempt=${state.attempts} ${SpotifyAvatarPhoto.failure}"
            android.util.Log.w("SpotifyGlass","Avatar decoded photo unresolved: $report")
            runCatching { java.io.File(activity.getExternalFilesDir(null),"spotify_avatar_binding.txt").writeText(report) }
        }
        if(state.attempts<12) {
            val source=WeakReference(avatar.source)
            avatar.source.postDelayed({ if(source.get()?.isAttachedToWindow==true && !activity.isDestroyed)ready() },400L)
        }
        return available()
    }
    fun draw(activity: Activity,canvas: Canvas,side: Float) {
        val bitmap=photos[activity]?.bitmap?.takeUnless { it.isRecycled } ?: handoff(activity) ?: return
        val edge=minOf(bitmap.width,bitmap.height)
        val left=(bitmap.width-edge)/2;val top=(bitmap.height-edge)/2
        canvas.drawBitmap(bitmap,Rect(left,top,left+edge,top+edge),RectF(0f,0f,side,side),paint)
    }
}
