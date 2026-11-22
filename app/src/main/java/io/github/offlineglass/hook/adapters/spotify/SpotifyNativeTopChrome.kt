package io.github.offlineglass.hook.adapters.spotify

import android.graphics.drawable.Drawable
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Suppression survives native alpha animations and transient page rebinding. */
internal class SpotifyNativeTopChrome {
    private data class Original(val alpha: Float,val background: Drawable?,val elevation: Float)
    companion object {
        private val owned=SpotifyVisualLeases<View,Original>()
        private var installed=false
        private fun install() {
            if(installed)return
            XposedBridge.hookAllMethods(View::class.java,"setAlpha",object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if(!owned.owns(param.thisObject as? View ?: return))return
                    // Keep the first native baseline: another glass owner writing
                    // zero must never become the value restored on explicit disable.
                    param.args[0]=0f
                }
            })
            installed=true
        }
    }
    private val originals=WeakHashMap<View,Original>()
    fun hide(view: View) {
        install()
        originals.getOrPut(view) { owned.acquire(view,this) { Original(view.alpha,view.background,view.elevation) } }
        if(view.alpha!=0f)view.alpha=0f
        if(view.background!=null)view.background=null
        if(view.elevation!=0f)view.elevation=0f
    }
    fun maintain() {
        originals.keys.forEach { view ->
            if(view.alpha!=0f)view.alpha=0f
            if(view.background!=null)view.background=null
            if(view.elevation!=0f)view.elevation=0f
        }
    }
    fun restore() {
        originals.keys.forEach { view ->
            owned.release(view,this)?.let { old ->
                view.alpha=old.alpha;view.background=old.background;view.elevation=old.elevation
            }
        }
        originals.clear()
    }
}
