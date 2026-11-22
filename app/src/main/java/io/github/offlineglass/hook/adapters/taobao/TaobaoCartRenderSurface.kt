package io.github.offlineglass.hook.adapters.taobao

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Bind only the engine below the already-owned cart root. The native conversion
 * owns renderer attachment, first-frame handoff and surface cleanup. */
internal object TaobaoCartRenderSurface {
    private data class FrameTarget(val root: WeakReference<View>, val callback: () -> Unit)
    private val targets = java.util.WeakHashMap<Any, FrameTarget>()
    private val hooked = java.util.WeakHashMap<Class<*>, Boolean>()

    private fun observe(texture: TextureView, root: View, onFrame: () -> Unit) {
        val listener = texture.surfaceTextureListener ?: return
        val type = listener.javaClass
        if (hooked[type] != true) {
            val method = type.getMethod("onSurfaceTextureUpdated", android.graphics.SurfaceTexture::class.java)
            require(method.returnType == Void.TYPE)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = targets[param.thisObject] ?: return
                    if (target.root.get()?.isAttachedToWindow == true) target.callback()
                }
            })
            hooked[type] = true
        }
        targets[listener] = FrameTarget(WeakReference(root), onFrame)
    }

    fun attach(root: View, onFrame: () -> Unit) {
        val reference = WeakReference(root)
        root.post {
            val current = reference.get()?.takeIf { it.isAttachedToWindow } ?: return@post
            runCatching {
                val candidates = ArrayList<ViewGroup>()
                val queue = ArrayDeque<Pair<View, Int>>()
                queue.add(current to 0)
                var visited = 0
                while (queue.isNotEmpty() && visited++ < 64) {
                    val (view, depth) = queue.removeFirst()
                    if (view.javaClass.name == "io.unicorn.embedding.android.UnicornView" && view is ViewGroup)
                        candidates.add(view)
                    else if (view is ViewGroup && depth < 4)
                        for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
                }
                require(queue.isEmpty() && candidates.size == 1) { "Cart engine ownership is ambiguous or incomplete" }
                val engine = candidates.single()
                val convert = engine.javaClass.getMethod("convertToTextureView")
                val restore = engine.javaClass.getMethod("convertToSurfaceView")
                val attached = engine.javaClass.getMethod("isAttachedToFlutterEngine")
                require(convert.returnType == Void.TYPE && restore.returnType == Void.TYPE &&
                    attached.returnType == Boolean::class.javaPrimitiveType)
                if (attached.invoke(engine) != true) return@runCatching
                val children = (0 until engine.childCount).map { engine.getChildAt(it) }
                val existing = children.filterIsInstance<TextureView>()
                require(existing.size <= 1) { "Cart texture ownership is ambiguous" }
                if (existing.isEmpty()) {
                    require(children.count { it is SurfaceView } == 1) { "Cart engine has no unique native surface" }
                    convert.invoke(engine)
                }
                val texture = (0 until engine.childCount).map { engine.getChildAt(it) }
                    .filterIsInstance<TextureView>().single()
                observe(texture, current, onFrame)
            }.onFailure {
                android.util.Log.e("LiquidTabCart", "Cart texture contract failed", it)
            }
        }
    }
}
