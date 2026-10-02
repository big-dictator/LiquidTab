package io.github.offlineglass.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(packageName: String, displayName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(AppIconCache.get(packageName)) }
    LaunchedEffect(packageName) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                AppIconCache.load(context.applicationContext, packageName)
            }
        }
    }
    val staticImage = remember(bitmap) { bitmap?.asImageBitmap() }
    if (staticImage != null) {
        Image(
            bitmap = staticImage,
            contentDescription = displayName,
            modifier = modifier.size(48.dp).clip(RoundedCornerShape(13.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .size(48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(displayName.take(1), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

fun isPackageInstalled(context: android.content.Context, packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

private object AppIconCache {
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(packageName: String): Bitmap? = synchronized(cache) { cache.get(packageName) }

    suspend fun load(context: android.content.Context, packageName: String): Bitmap? =
        withContext(iconDispatcher) {
        get(packageName)?.let { return@withContext it }
        runCatching {
            val cacheDir = File(context.cacheDir, "static-app-icons").apply { mkdirs() }
            val file = File(cacheDir, "$packageName.png")
            if (file.isFile) {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { return@runCatching it }
            }
            val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(144)
            runCatching {
                val temporary = File(cacheDir, "$packageName.tmp")
                FileOutputStream(temporary).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.fd.sync()
                }
                if (!temporary.renameTo(file)) temporary.delete()
            }
            bitmap
        }.getOrNull()?.also { bitmap ->
            synchronized(cache) { cache.put(packageName, bitmap) }
        }
    }
}

private fun Drawable.toBitmap(size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap
}
