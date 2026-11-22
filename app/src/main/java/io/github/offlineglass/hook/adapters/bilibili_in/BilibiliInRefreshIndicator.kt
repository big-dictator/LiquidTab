package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Offsets only the native indicator; its pull threshold/gesture detector is untouched. */
internal class BilibiliInRefreshIndicator(private val source: View) {
    private val start = source.javaClass.methods.firstOrNull { it.name == "getProgressViewStartOffset" && it.parameterCount == 0 }
    private val end = source.javaClass.methods.firstOrNull { it.name == "getProgressViewEndOffset" && it.parameterCount == 0 }
    private val diameter = source.javaClass.methods.firstOrNull { it.name == "getProgressCircleDiameter" && it.parameterCount == 0 }
    private val originalStart = (start?.invoke(source) as? Int) ?: 0
    private val originalEnd = (end?.invoke(source) as? Int) ?: 0
    private val flag = listOf("mUsingCustomStart", "mCustomStart", "E").firstNotNullOfOrNull { name ->
        runCatching { source.javaClass.getDeclaredField(name).takeIf { it.type == Boolean::class.javaPrimitiveType }?.apply { isAccessible = true } }.getOrNull()
    }
    private val originalFlag = flag?.getBoolean(source)
    private val setter: Method? = source.javaClass.methods.firstOrNull { it.name == "setProgressViewOffset" && it.parameterCount == 2 }
        ?: source.javaClass.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }.singleOrNull()?.apply { isAccessible = true }
    private var applied = Int.MIN_VALUE
    private var translated: View? = null
    private var originalTranslation = 0f
    private val position = IntArray(2)

    fun update(safeWindowTop: Int, density: Float) {
        source.getLocationInWindow(position)
        val wanted = (safeWindowTop-position[1]).coerceAtLeast(0)
        if (applied == wanted) return
        if (setter != null && start != null && end != null && originalFlag != null) {
            setter.invoke(source, wanted, wanted+(24*density).toInt())
        } else {
            // The stable Android geometry path remains available when an optimizer removes the offset setter.
            val size = diameter?.invoke(source) as? Int ?: return
            val circle = (source as? ViewGroup)?.let { parent ->
                (0 until parent.childCount).map { parent.getChildAt(it) }.singleOrNull { it.width == size && it.height == size }
            } ?: return
            if (translated !== circle) { translated = circle; originalTranslation = circle.translationY }
            val settledTop = originalEnd + if (originalStart < 0) originalStart else 0
            val offset = originalTranslation+(wanted-settledTop).coerceAtLeast(0)
            if (circle.translationY != offset) circle.translationY = offset
        }
        applied = wanted
    }

    fun restore() {
        if (applied == Int.MIN_VALUE) return
        if (translated != null) translated?.translationY = originalTranslation
        else if (setter != null && originalFlag != null) {
            setter.invoke(source, originalStart, originalEnd)
            flag?.setBoolean(source, originalFlag)
        }
    }
}
