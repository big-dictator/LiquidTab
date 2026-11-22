package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Native post-content header rules only; conversation-row separators stay native. */
internal object WeChatHeaderDivider {
    private class Draw(val view: View, val canvas: Canvas, val previous: Draw?, var chrome: Boolean = false)
    private val active = ThreadLocal<Draw?>()
    private val canvasOwners = HashSet<Class<*>>()
    fun install(loader: ClassLoader) {
        val list = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", loader)
        XposedBridge.hookAllMethods(list, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!WeChatChatToolbarGradient.nativeMainHeaderVisible(view.rootView)) return
                val canvas = param.args[0] as? Canvas ?: return
                val owner = canvas.javaClass.getMethod("drawLine", Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Paint::class.java).declaringClass
                if (canvasOwners.add(owner)) {
                    XposedBridge.hookMethod(owner.getMethod("drawLine", Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Paint::class.java), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val draw = active.get() ?: return
                            if (draw.chrome && param.thisObject === draw.canvas) param.result = null
                        }
                    })
                }
                active.set(Draw(view, canvas, active.get()))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val draw = active.get() ?: return
                if (draw.view === param.thisObject) active.set(draw.previous)
            }
        })
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchDraw", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val draw = active.get() ?: return
                // Native ConversationListView adds its header line AFTER super.
                if (draw.view === param.thisObject) draw.chrome = true
            }
        })
        XposedBridge.framework().deoptimize(list.getDeclaredMethod("dispatchDraw", Canvas::class.java))

        val overlay = XposedHelpers.findClass("androidx.appcompat.widget.ActionBarOverlayLayout", loader)
        // Decompiled draw() has one theme windowContentOverlay Drawable, painted
        // below the ActionBarContainer after super.draw. Resolve by unique role/type.
        val field = overlay.declaredFields.singleOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && Drawable::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true } ?: error("Ambiguous AppCompat header overlay contract")
        XposedBridge.hookAllMethods(overlay, "draw", object : XC_MethodHook() {
            private val saved = ThreadLocal.withInitial { ArrayDeque<Pair<ViewGroup, Drawable?>>() }
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = param.thisObject as? ViewGroup ?: return
                if (!WeChatChatToolbarGradient.managesHeader(root)) return
                saved.get().addLast(root to (field.get(root) as? Drawable))
                field.set(root, null)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = saved.get()
                val entry = stack.lastOrNull()?.takeIf { it.first === param.thisObject } ?: return
                stack.removeLast()
                field.set(param.thisObject, entry.second)
                if (stack.isEmpty()) saved.remove()
            }
        })
    }
}
