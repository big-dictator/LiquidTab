package io.github.offlineglass.hook.adapters.zhihu

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Resolve implementors from DEX interface edges, never from implementation names. */
internal object ZhihuApkContract {
    fun completion(paths: List<String>, owner: Class<*>): String? {
        val target = "L" + owner.name.replace('.', '/') + ";"
        val matches = mutableListOf<String>()
        for (path in paths) ZipFile(path).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.take(64)) {
                require(entry.size in 112..100_000_000)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun u2(p: Int) = b.getShort(p).toInt() and 65535
                fun leb(cursor: IntArray): Int {
                    var value = 0
                    for (shift in 0..28 step 7) {
                        val next = bytes[cursor[0]++].toInt() and 255
                        value = value or ((next and 127) shl shift)
                        if (next < 128) return value
                    }
                    error("Malformed DEX integer")
                }
                fun string(index: Int): String {
                    val cursor = intArrayOf(b.getInt(b.getInt(60) + index * 4))
                    leb(cursor)
                    val start = cursor[0]
                    var end = start
                    while (bytes[end].toInt() != 0) end++
                    return String(bytes, start, end - start, Charsets.UTF_8)
                }
                val definitions = b.getInt(100)
                val count = b.getInt(96)
                require(count in 0..200_000)
                for (i in 0 until count) {
                    val at = definitions + i * 32
                    if (string(b.getInt(b.getInt(68) + b.getInt(at) * 4)) != target) continue
                    val data = b.getInt(at + 24)
                    if (data == 0) continue
                    val cursor = intArrayOf(data)
                    val fields = leb(cursor) + leb(cursor)
                    val direct = leb(cursor)
                    val virtual = leb(cursor)
                    require(fields in 0..4096 && direct in 0..4096 && virtual in 0..4096)
                    repeat(fields) { leb(cursor); leb(cursor) }
                    for (size in listOf(direct, virtual)) {
                        var index = 0
                        repeat(size) {
                            index += leb(cursor)
                            leb(cursor)
                            val code = leb(cursor)
                            val method = b.getInt(92) + index * 8
                            val proto = b.getInt(76) + u2(method + 2) * 12
                            if (code != 0 && b.getInt(proto + 8) == 0 &&
                                string(b.getInt(b.getInt(68) + b.getInt(proto + 4) * 4)) == "V") {
                                val units = b.getInt(code + 12)
                                require(units in 0..65536)
                                var p = 0
                                while (p < units) {
                                    val ip = code + 16 + p * 2
                                    val word = u2(ip)
                                    val op = word and 255
                                    val width = when {
                                        word == 0x100 -> 4 + u2(ip + 2) * 2
                                        word == 0x200 -> 2 + u2(ip + 2) * 4
                                        word == 0x300 -> 4 + (u2(ip + 2) * b.getInt(ip + 4) + 1) / 2
                                        op == 0x18 -> 5
                                        op in 0xfa..0xfb -> 4
                                        op in setOf(3,6,9,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c,0xfc,0xfd) || op in 0x6e..0x72 || op in 0x74..0x78 -> 3
                                        op in setOf(2,5,8,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29,0xfe,0xff) || op in 0x2d..0x3d || op in 0x44..0x6d || op in 0x90..0xaf || op in 0xd0..0xe2 -> 2
                                        else -> 1
                                    }
                                    require(width in 1..(units - p))
                                    if (op in 0x1a..0x1b && string(if (op == 0x1a) u2(ip + 2) else b.getInt(ip + 2)).startsWith("onPop, ")) {
                                        matches += string(b.getInt(method + 4))
                                        break
                                    }
                                    p += width
                                }
                            }
                        }
                    }
                }
            }
        }
        return matches.distinct().singleOrNull()
    }

    fun implementors(paths: List<String>, contract: String): List<String> {
        val result = linkedSetOf<String>()
        for (path in paths) ZipFile(path).use { zip ->
            val dexes = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.toList()
            require(dexes.size <= 64)
            for (entry in dexes) {
                require(entry.size in 112..100_000_000)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
                val strings = b.getInt(60)
                val types = b.getInt(68)
                fun descriptor(index: Int): String {
                    val stringIndex = b.getInt(types + index * 4)
                    var offset = b.getInt(strings + stringIndex * 4)
                    while (bytes[offset++].toInt() and 128 != 0) { }
                    val start = offset
                    while (bytes[offset].toInt() != 0) offset++
                    return String(bytes, start, offset - start, Charsets.UTF_8)
                }
                val count = b.getInt(96)
                val definitions = b.getInt(100)
                require(count in 0..200_000)
                for (i in 0 until count) {
                    val at = definitions + i * 32
                    val interfaces = b.getInt(at + 12)
                    if (interfaces == 0) continue
                    val n = b.getInt(interfaces)
                    require(n in 0..1024)
                    if ((0 until n).any { descriptor(b.getShort(interfaces + 4 + it * 2).toInt() and 65535) == contract }) {
                        result += descriptor(b.getInt(at)).removePrefix("L").removeSuffix(";").replace('/', '.')
                    }
                }
            }
        }
        return result.toList()
    }
}
