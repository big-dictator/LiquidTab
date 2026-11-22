package io.github.offlineglass.hook.adapters.spotify

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference

/** One native UI binding for progress, media projection and tint; no app-private symbols. */
internal object SpotifyPlaybackHooks {
    data class Clock(val position: Long,val duration: Long,val speed: Float,val sampledAt: Long) {
        fun fraction(): Float = if(duration>0L)
            ((position+(SystemClock.elapsedRealtime()-sampledAt)*speed)/duration.toDouble()).toFloat().coerceIn(0f,1f) else 0f
    }
    private class Binding(player: View,bar: ProgressBar) {
        val bar=WeakReference(bar)
        val player=WeakReference(player)
        var clock: Clock?=null
        var color: Int?=null
        var position=-1L
        var at=0L
        var duration=0L
        var millisecondsVerified=false
        var wake: (() -> Unit)?=null
    }
    private val bindings=java.util.WeakHashMap<View,Binding>()
    private val bars=java.util.WeakHashMap<ProgressBar,WeakReference<Binding>>()
    private val main=android.os.Handler(Looper.getMainLooper())
    private var installed=false
    private var playing: Boolean?=null
    fun playingState(): Boolean?=playing
    fun clock(view: View): Clock?=bindings[view]?.clock
    fun color(view: View): Int?=bindings[view]?.color
    fun bind(player: View,bar: ProgressBar?) {
        if(bar==null) { unbind(player);return }
        if(bindings[player]?.bar?.get()===bar)return
        val res=player.resources
        val shell=res.getIdentifier("now_playing_bar_layout","id",player.context.packageName)
        val progress=res.getIdentifier("progress_bar","id",player.context.packageName)
        val cover=res.getIdentifier("cover_image","id",player.context.packageName)
        if(shell==0 || player.id!=shell || progress==0 || bar.id!=progress || cover==0 ||
            player.findViewById<View>(progress)!==bar || player.findViewById<View>(cover)==null)return
        unbind(player)
        val binding=Binding(player,bar)
        bindings[player]=binding;bars[bar]=WeakReference(binding)
        sample(binding)
    }
    fun observe(player: View,bar: ProgressBar?,wake: () -> Unit) {
        bind(player,bar);bindings[player]?.wake=wake
    }
    fun resetClock() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { resetClock() };return }
        playing=null
        bindings.values.forEach { it.clock=null;it.position=-1L;it.millisecondsVerified=false }
    }
    fun unbind(player: View) { bindings.remove(player)?.let { bars.remove(it.bar.get()) } }
    fun tint(player: View,color: Int) { bindings[player]?.color=color }
    fun playback(knownPlaying: Boolean?) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { playback(knownPlaying) };return }
        playing=knownPlaying
        bindings.values.toList().forEach { value ->
            val clock=value.clock ?: return@forEach
            val now=SystemClock.elapsedRealtime()
            val position=(clock.position+(now-clock.sampledAt)*clock.speed).toLong().coerceIn(0L,clock.duration)
            value.clock=Clock(position,clock.duration,if(knownPlaying==false)0f else clock.speed,now)
        }
    }
    private fun sample(value: Binding) {
        val player=value.player.get() ?: return
        val bar=value.bar.get() ?: return
        if(!player.isAttachedToWindow || !bar.isAttachedToWindow || bar.isIndeterminate)return
        value.wake?.invoke()
        val now=SystemClock.elapsedRealtime()
        val duration=(bar.max-bar.min).toLong()
        val position=(bar.progress-bar.min).toLong()
        if(duration<=0 || position !in 0..duration)return
        if(value.duration!=duration) { value.millisecondsVerified=false;value.position=-1;value.duration=duration;value.clock=null }
        val elapsed=now-value.at
        val delta=position-value.position
        // The decompiled native pipeline projects milliseconds. Verify the unit
        // against actual advancing updates before exporting an absolute media clock.
        val speed=if(value.position>=0 && elapsed in 40..2000 && delta>0)delta.toFloat()/elapsed else 0f
        if(speed in .25f..4f)value.millisecondsVerified=true
        if(delta!=0L || value.position<0) { value.position=position;value.at=now }
        if(!value.millisecondsVerified)return
        val rate=when {
            playing==false -> 0f
            speed in .25f..4f -> speed
            delta<0 -> value.clock?.speed ?: 0f
            else -> value.clock?.speed ?: 0f
        }
        value.clock=Clock(position,duration,rate,now)
        player.postInvalidateOnAnimation()
    }
    fun install(loader: ClassLoader) {
        if(installed)return
        installed=true
        for(name in arrayOf("setProgress","setMax","setMin","setIndeterminate")) {
            XposedBridge.hookAllMethods(ProgressBar::class.java,name,object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val value=bars[param.thisObject]?.get() ?: return
                    val player=value.player.get() ?: return
                    if(Looper.myLooper()==Looper.getMainLooper())sample(value)
                    else player.post { if(bindings[player]===value)sample(value) }
                }
            })
        }
    }
}
