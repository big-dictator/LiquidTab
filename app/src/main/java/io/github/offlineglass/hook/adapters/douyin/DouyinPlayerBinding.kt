package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Resolve the factory actually invoked by FeedVideoPlayerView, without loading
 * all obfuscated classes. One bounded DEX at a time, once during hook install. */
internal object DouyinPlayerBinding {
    data class Binding(val factory: Method, val entry: Method)

    fun resolve(apk: String, feed: Class<*>, loader: ClassLoader): Binding {
        val descriptor = "L${feed.name.replace('.', '/')};"
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.matches(Regex("classes[0-9]*\\.dex")) || entry.size !in 112L..16777216L) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val dex = Dex(bytes)
                val classIndex = dex.findClass(descriptor) ?: continue
                val fields = feed.declaredFields.map { it.type }.toSet()
                val bindings = ArrayList<Binding>()
                dex.methodsOf(classIndex).forEach { (methodIndex, code) ->
                    if (code == 0) return@forEach
                    val name = dex.methodName(methodIndex)
                    val caller = feed.declaredMethods.singleOrNull {
                        it.name == name && it.parameterCount == 0 && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                    } ?: return@forEach
                    dex.staticCalls(code).forEach calls@ { target ->
                        if (!dex.isFactorySignature(target)) return@calls
                        val owner = dex.owner(target)
                        val factory = runCatching {
                            val type = Class.forName(owner.substring(1, owner.length - 1).replace('/', '.'), false, loader)
                            val shared = type.interfaces.filter { it in fields }
                            if (shared.none { api -> api.methods.any { it.name == "getView" && it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType) } }) return@runCatching null
                            val method = type.getDeclaredMethod(dex.methodName(target), ViewGroup::class.java, Boolean::class.javaPrimitiveType)
                            if (!Modifier.isStatic(method.modifiers) || method.returnType != type) return@runCatching null
                            if (type.declaredFields.none { it.type in shared }) return@runCatching null
                            method
                        }.getOrNull() ?: return@calls
                        bindings.add(Binding(factory, caller))
                    }
                }
                return bindings.distinctBy { it.factory to it.entry }.singleOrNull()
                    ?: error("Ambiguous or missing feed renderer factory")
            }
        }
        error("Feed player DEX definition unavailable")
    }

    private class Dex(private val bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private fun u32(p: Int) = b.getInt(p).also { require(it >= 0) }
        private fun u16(p: Int) = b.getShort(p).toInt() and 65535
        private val strings = u32(60)
        private val types = u32(68)
        private val protos = u32(76)
        private val methods = u32(92)
        private fun string(index: Int): String {
            var p = u32(strings + index * 4)
            while ((bytes[p++].toInt() and 128) != 0) {}
            val start = p
            while (bytes[p] != 0.toByte()) p++
            return String(bytes, start, p - start, Charsets.UTF_8)
        }
        private fun type(index: Int) = string(u32(types + index * 4))
        fun findClass(name: String): Int? {
            val offset = u32(100)
            for (i in 0 until u32(96)) if (type(u32(offset + i * 32)) == name) return i
            return null
        }
        fun owner(index: Int) = type(u16(methods + index * 8))
        fun methodName(index: Int) = string(u32(methods + index * 8 + 4))
        fun isFactorySignature(index: Int): Boolean {
            if (index >= u32(88)) return false
            val proto = protos + u16(methods + index * 8 + 2) * 12
            if (type(u32(proto + 4)) != owner(index)) return false
            val params = u32(proto + 8)
            return params != 0 && u32(params) == 2 &&
                type(u16(params + 4)) == "Landroid/view/ViewGroup;" && type(u16(params + 6)) == "Z"
        }
        fun methodsOf(classIndex: Int): List<Pair<Int, Int>> {
            var cursor = u32(u32(100) + classIndex * 32 + 24)
            if (cursor == 0) return emptyList()
            fun leb(): Int {
                var value = 0
                var shift = 0
                do {
                    val byte = bytes[cursor++].toInt() and 255
                    value = value or ((byte and 127) shl shift)
                    shift += 7
                    require(shift <= 35)
                } while (byte and 128 != 0)
                return value
            }
            val staticFields = leb(); val instanceFields = leb()
            val directMethods = leb(); val virtualMethods = leb()
            repeat(staticFields + instanceFields) { leb(); leb() }
            val result = ArrayList<Pair<Int, Int>>()
            for (count in listOf(directMethods, virtualMethods)) {
                var index = 0
                repeat(count) { index += leb(); leb(); result.add(index to leb()) }
            }
            return result
        }
        fun staticCalls(code: Int): Set<Int> {
            val result = HashSet<Int>()
            val count = u32(code + 12)
            val start = code + 16
            // Candidate references are additionally checked against exact method
            // and renderer-interface signatures; no arbitrary class loading.
            for (i in 0 until count - 2) {
                val opcode = u16(start + i * 2) and 255
                if (opcode == 0x71 || opcode == 0x77) result.add(u16(start + (i + 1) * 2))
            }
            return result
        }
    }
}
