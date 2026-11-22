package io.github.offlineglass.hook.adapters.spotify

import android.app.Activity
import android.graphics.*
import android.view.*
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Follow the native push-content transform, clipping and scrim; no extra blur. */
internal object SpotifyTopDrawer {
    private val states=WeakHashMap<ViewGroup,State>()
    private val activities=WeakHashMap<Activity,WeakReference<State>>()
    private val hooked=HashSet<Class<*>>()
    fun isOpen(activity: Activity)=activities[activity]?.get()?.open==true
    fun bind(layer: View,activity: Activity) {
        activities[activity]?.get()?.takeIf { it.alive() }?.let { state -> state.add(layer);return }
        val id=activity.resources.getIdentifier("content","id",activity.packageName)
        val source=activity.findViewById<View>(id) ?: return
        var parent=source.parent as? ViewGroup
        while(parent!=null && parent!==activity.window.decorView) {
            val method=parent.javaClass.methods.firstOrNull { it.name=="getContentView" && it.parameterCount==0 && View::class.java.isAssignableFrom(it.returnType) }
            if(method!=null && runCatching { method.invoke(parent)===source }.getOrDefault(false)) {
                val drawer=parent
                val child=(0 until drawer.childCount).map { drawer.getChildAt(it) }.firstOrNull { view ->
                    view!==source && view.layoutParams.javaClass.fields.count { !Modifier.isStatic(it.modifiers) && it.type==Float::class.javaPrimitiveType }==1
                } ?: return
                val state=State(drawer,source,child,activity.window.decorView)
                states[drawer]=state;activities[activity]=WeakReference(state);state.add(layer)
                if(drawer.javaClass !in hooked) {
                    // ux41's push-content callback is uniquely void(float).
                    // Its name is intentionally not part of the compatibility contract.
                    drawer.javaClass.declaredMethods.singleOrNull { it.returnType==Void.TYPE &&
                        !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
                    }?.let { slide ->
                        XposedBridge.hookMethod(slide,object: XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) { states[param.thisObject]?.update() }
                        });hooked+=drawer.javaClass
                    }
                }
                return
            }
            parent=parent.parent as? ViewGroup
        }
    }
    private class State(drawer: ViewGroup,source: View,child: View,decor: View) {
        private val drawer=WeakReference(drawer)
        private val source=WeakReference(source)
        private val child=WeakReference(child)
        private val decor=WeakReference(decor)
        private val layers=WeakHashMap<View,Boolean>()
        private val progress=child.layoutParams.javaClass.fields.single { !Modifier.isStatic(it.modifiers) && it.type==Float::class.javaPrimitiveType }
        private val scrim=runCatching {
            drawer.javaClass.getMethod("setScrimColor",Int::class.javaPrimitiveType).declaringClass.declaredFields.singleOrNull { field ->
                !Modifier.isStatic(field.modifiers) && field.type==Int::class.javaPrimitiveType && runCatching {
                    field.isAccessible=true
                    val value=field.getInt(drawer);value<0 && value and 0xFFFFFF==0
                }.getOrDefault(false)
            }
        }.getOrNull()
        private val nativePaint=drawer.javaClass.fields.singleOrNull { it.type==Paint::class.java }
        private val matrix=Matrix();private val rootMatrix=Matrix();private val inverse=Matrix()
        private val visible=Rect();private val clip=RectF();private val clipInt=Rect()
        var open=false;private set
        private var lastOffset=-1f
        fun alive()=source.get()?.isAttachedToWindow==true
        fun add(view: View) {
            val added=layers.put(view,true)==null
            val current=child.get()?.let { runCatching { progress.getFloat(it.layoutParams) }.getOrDefault(0f) } ?: 0f
            if(added || current!=lastOffset)update()
        }
        fun update() {
            val root=decor.get() ?: return
            val native=source.get() ?: return
            if(!native.isAttachedToWindow)return
            val panel=child.get() ?: return
            val drawer=drawer.get() ?: return
            val offset=runCatching { progress.getFloat(panel.layoutParams) }.getOrDefault(0f).coerceIn(0f,1f)
            lastOffset=offset;open=offset>.001f
            if(!open) {
                layers.keys.forEach { it.setAnimationMatrix(null);it.clipBounds=null;it.alpha=1f }
                return
            }
            matrix.reset();rootMatrix.reset()
            native.transformMatrixToGlobal(matrix);root.transformMatrixToGlobal(rootMatrix)
            if(rootMatrix.invert(rootMatrix))matrix.setConcat(rootMatrix,matrix)
            if(!matrix.invert(inverse))return
            val color=scrim?.getInt(drawer)
            val alpha=if(color!=null)Color.alpha(color)/255f*offset else
                ((nativePaint?.get(drawer) as? Paint)?.alpha ?: 0)/255f
            var nativeAlpha=1f;var ancestor: View?=native
            while(ancestor!=null && ancestor!==root) { nativeAlpha*=ancestor.alpha;ancestor=ancestor.parent as? View }
            panel.getGlobalVisibleRect(visible)
            val rootLocation=IntArray(2);root.getLocationOnScreen(rootLocation)
            visible.offset(-rootLocation[0],-rootLocation[1])
            val rtl=drawer.layoutDirection==View.LAYOUT_DIRECTION_RTL
            clip.set(if(rtl)0f else visible.right.toFloat(),0f,if(rtl)visible.left.toFloat() else root.width.toFloat(),root.height.toFloat())
            inverse.mapRect(clip)
            clipInt.set(clip.left.toInt().coerceAtLeast(0),clip.top.toInt().coerceAtLeast(0),clip.right.toInt(),clip.bottom.toInt())
            layers.keys.forEach { view ->
                view.setAnimationMatrix(matrix);view.clipBounds=clipInt
                view.alpha=(nativeAlpha*(1f-alpha)).coerceIn(0f,1f)
            }
        }
    }
}
