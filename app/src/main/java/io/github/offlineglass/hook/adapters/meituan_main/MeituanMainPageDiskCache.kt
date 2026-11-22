package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Exactly five replaceable files in Meituan's private cache. No exit-time archives. */
internal class MeituanMainPageDiskCache(private val directory: File) {
    data class Entry(val index: Int, val bitmap: Bitmap, val x: Int, val y: Int)
    @Volatile var status = "diskFiles=0 diskBytes=0"
        private set

    fun load(key: String): List<Entry> {
        if (!directory.isDirectory) return emptyList()
        directory.listFiles()?.filter { it.name.endsWith(".pending") }?.forEach { it.delete() }
        trim()
        var decodedBytes = 0L
        return (0..4).mapNotNull { index ->
            val file = File(directory, "$index.cache")
            runCatching {
                if (!file.isFile || file.length() > MAX_BYTES) return@runCatching null
                DataInputStream(file.inputStream()).use { input ->
                    check(input.readInt() == MAGIC)
                    if (input.readUTF() != key) return@use null
                    val x = input.readInt(); val y = input.readInt()
                    val width = input.readInt(); val height = input.readInt()
                    check(width > 0 && height > 0 && decodedBytes + width.toLong() * height * 4L <= MAX_BYTES)
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
                    val bitmap = BitmapFactory.decodeStream(input, null, options) ?: error("Invalid page cache")
                    check(bitmap.width == width && bitmap.height == height)
                    decodedBytes += width.toLong() * height * 4L
                    Entry(index, bitmap, x, y)
                }
            }.getOrElse { file.delete(); null }
        }
    }

    fun save(key: String, entry: Entry) {
        if (entry.index !in 0..4 || !directory.isDirectory && !directory.mkdirs()) return
        val file = File(directory, "${entry.index}.cache")
        val pending = File(directory, "${entry.index}.pending")
        var copy: Bitmap? = null
        try {
            val bitmap = entry.bitmap
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
            DataOutputStream(pending.outputStream()).use { output ->
                output.writeInt(MAGIC); output.writeUTF(key)
                output.writeInt(entry.x); output.writeInt(entry.y)
                output.writeInt(bitmap.width); output.writeInt(bitmap.height)
                check(copy.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            check(pending.length() <= MAX_BYTES)
            java.nio.file.Files.move(pending.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            trim()
        } catch (_: Exception) {
            // Preserve the previous complete file if encoding was interrupted.
        } finally {
            copy?.recycle(); pending.delete()
        }
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.name.matches(Regex("[0-4]\\.cache")) }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) if (bytes > MAX_BYTES) {
            val size = file.length(); if (file.delete()) { bytes -= size; count-- }
        }
        status = "diskFiles=$count diskBytes=$bytes"
    }

    private companion object {
        const val MAGIC = 0x4d545031
        const val MAX_BYTES = 64L * 1024L * 1024L
    }
}
