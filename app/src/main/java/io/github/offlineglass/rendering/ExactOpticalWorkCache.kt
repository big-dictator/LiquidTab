package io.github.offlineglass.rendering

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import io.github.offlineglass.hook.setBottomBarSquircle

/** Bounded exact work reuse. No sampled pixels, app bindings or quantized geometry. */
internal class ExactOpticalGeometryCache(private val capacity: Int = 64) {
    private data class Key(val width: Float, val height: Float,
        val radius: Float, val smoothing: Float)
    private val paths = android.util.LruCache<Key, Path>(capacity)
    var builds = 0; private set
    var hits = 0; private set

    @Synchronized fun copyInto(destination: Path, rect: RectF, radius: Float, smoothing: Float) {
        // Translation is not a change of contour. Keep every size/curve value
        // unquantized; let native Path.offset move the private copied geometry.
        val key = Key(rect.width(), rect.height(), radius, smoothing)
        val existing = paths.get(key)
        if (existing != null) hits++
        val path = existing ?: Path().also {
            it.setBottomBarSquircle(RectF(0f, 0f, key.width, key.height), radius, smoothing)
            paths.put(key, it)
            builds++
        }
        // Path.reset() in the canonical builder preserves the caller's fill type.
        val fill = destination.fillType
        destination.set(path)
        destination.offset(rect.left, rect.top)
        destination.fillType = fill
    }
    @Synchronized fun clear() { paths.evictAll() }
}

/** Own this alongside a shader; every write to a cached parameter uses this gate. */
internal class ExactOpticalUniformCache(private val shader: RuntimeShader) {
    private val floats = HashMap<String, FloatArray>()
    private val colors = HashMap<String, Int>()
    var updates = 0; private set
    fun floats(name: String, vararg values: Float) {
        array(name, values)
    }
    /** Compare a caller-owned mutable array without allocating on a cache hit. */
    fun array(name: String, values: FloatArray) {
        if (floats[name]?.contentEquals(values) == true) return
        shader.setFloatUniform(name, values)
        floats[name] = values.copyOf()
        updates++
    }
    fun color(name: String, value: Int = Color.WHITE) {
        if (colors[name] == value) return
        shader.setColorUniform(name, value)
        colors[name] = value
        updates++
    }
}
