package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Export the encoded static photo through the host's existing image pipeline. */
internal object XhsOriginalImageExport {
    private const val TAG = "LiquidTabXhsPhoto"
    private const val NOTE = "com.xingin.matrix.notedetail.NoteDetailActivity"
    private const val LIMIT = 128 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val preparingMenu = ThreadLocal<ArrayDeque<Boolean>>()
    private val clickingPhoto = ThreadLocal<ArrayDeque<Boolean>>()
    private val originalUrls = LinkedHashMap<Uri, Uri>()

    fun install(roles: Properties, loader: ClassLoader, context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val handles = ArrayList<XC_MethodHook.Unhook>()
        runCatching {
            val menu = method(roles, "photoSaveMenu", loader)
            val flag = method(roles, "photoSaveFlag", loader)
            val save = method(roles, "photoSave", loader)
            val activity = field(roles, "photoActivity", loader)
            val url = field(roles, "photoUrl", loader)
            val liveUrl = field(roles, "photoLiveUrl", loader)
            val pipeline = ImagePipeline(loader)
            val directSave = loader.loadClass("com.xingin.image.SaveImageProxyImpl").methods.single {
                it.name == "save" && it.returnType == Void.TYPE && it.parameterTypes.take(6) ==
                    listOf(Activity::class.java, String::class.java, String::class.java, String::class.java,
                        String::class.java, Boolean::class.javaPrimitiveType)
            }
            val clicks = roles.getProperty("photoClicks", "").lineSequence().filter(String::isNotEmpty)
                .map { method(it, loader) }.toList()
            val clickFlag = method(roles, "photoClickFlag", loader)
            val selectedUrl = method(roles, "photoSelectedUrl", loader)
            val originalUrl = method(roles, "photoOriginalUrl", loader)
            check(clicks.isNotEmpty() && clickFlag.returnType == Boolean::class.javaPrimitiveType)
            check(selectedUrl.declaringClass == originalUrl.declaringClass && originalUrl.returnType == String::class.java)
            check(flag.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)))
            check(activity.declaringClass == save.declaringClass && activity.type == Activity::class.java)
            check(url.declaringClass == save.parameterTypes[1] && liveUrl.declaringClass == url.declaringClass &&
                url.type == String::class.java && liveUrl.type == String::class.java)
            handles += XposedBridge.hookMethod(save, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = activity.get(param.thisObject) as? Activity ?: return
                    if (host.javaClass.name != NOTE || !enabled(context)) return
                    val action = param.args[1] ?: return
                    if (!(liveUrl.get(action) as? String).isNullOrEmpty()) return
                    export(param, pipeline, context, url.get(action))
                }
            })
            handles += XposedBridge.hookMethod(directSave, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (clickingPhoto.get()?.lastOrNull() != true ||
                        (param.args[0] as? Activity)?.javaClass?.name != NOTE) return
                    export(param, pipeline, context, param.args[3])
                }
            })
            handles += XposedBridge.hookMethod(clickFlag, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (clickingPhoto.get()?.lastOrNull() == true && !param.hasThrowable()) param.result = false
                }
            })
            for (click in clicks) handles += XposedBridge.hookMethod(click, scope(clickingPhoto, context))
            handles += XposedBridge.hookMethod(flag, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (preparingMenu.get()?.lastOrNull() == true) param.args[0] = false
                }
            })
            handles += XposedBridge.hookMethod(selectedUrl, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (preparingMenu.get()?.lastOrNull() != true && clickingPhoto.get()?.lastOrNull() != true) return
                    val source = imageUri(param.result) ?: return
                    val original = runCatching { imageUri(originalUrl.invoke(param.thisObject)) }.getOrNull() ?: return
                    if (original == source) return
                    synchronized(originalUrls) {
                        originalUrls[source] = original
                        if (originalUrls.size > 24) originalUrls.remove(originalUrls.keys.first())
                    }
                }
            })
            handles += XposedBridge.hookMethod(menu, scope(preparingMenu, context))
            if (roles.getProperty("photoDownloadMenu") != null) {
                val decorate = method(roles, "photoDownloadMenu", loader)
                val disabled = field(roles, "photoDownloadMenuDisabled", loader)
                check(disabled.type == Boolean::class.javaObjectType && disabled.declaringClass == decorate.returnType &&
                    decorate.parameterTypes[1] == decorate.returnType)
                handles += XposedBridge.hookMethod(decorate, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val item = param.result ?: return
                        if (item !== param.args[1] && param.args[2] == true &&
                            disabled.get(item) == true && enabled(context)) disabled.set(item, false)
                    }
                })
            }
            Log.i(TAG, "Native photo menu and encoded image export bound")
        }.onFailure {
            handles.forEach { handle -> handle.unhook() }
            Log.w(TAG, "Photo export contract unavailable", it)
        }
    }

    private fun scope(local: ThreadLocal<ArrayDeque<Boolean>>, context: Context) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val stack = local.get() ?: ArrayDeque<Boolean>().also(local::set)
            stack.addLast(enabled(context))
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            val stack = local.get() ?: return
            stack.removeLastOrNull()
            if (stack.isEmpty()) local.remove()
        }
    }

    private fun export(param: XC_MethodHook.MethodHookParam, pipeline: ImagePipeline, context: Context, value: Any?) {
        val source = imageUri(value) ?: return
        val original = synchronized(originalUrls) { originalUrls[source] }
        param.result = null
        if (original == null) pipeline.export(context, source) { report(context, it) }
        else pipeline.export(context, original) { error ->
            if (error == null) report(context, null)
            else pipeline.export(context, source) { report(context, it, usedAlternate = true) }
        }
    }

    private fun imageUri(value: Any?): Uri? = (value as? String)?.let(Uri::parse)
        ?.takeIf { it.scheme == "https" && !it.host.isNullOrEmpty() }

    private fun enabled(context: Context) =
        runCatching { HookConfigReader.read(context, context.packageName).enabled }.getOrDefault(false)

    private fun method(roles: Properties, role: String, loader: ClassLoader): Method =
        method(requireNotNull(roles.getProperty(role)) { role }, loader)

    private fun method(descriptor: String, loader: ClassLoader): Method {
        val (owner, name, result, args) = descriptor.split('|')
        val parameters = args.split(',').filter(String::isNotEmpty).map { XhsNativeBindings.type(it, loader) }
        return XhsNativeBindings.type(owner, loader).getDeclaredMethod(name, *parameters.toTypedArray()).apply {
            check(returnType == XhsNativeBindings.type(result, loader))
        }
    }

    private fun field(roles: Properties, role: String, loader: ClassLoader): Field {
        val (owner, name, type) = requireNotNull(roles.getProperty(role)) { role }.split('|')
        return XhsNativeBindings.type(owner, loader).getDeclaredField(name).apply {
            check(this.type == XhsNativeBindings.type(type, loader) && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
    }

    private class ImagePipeline(loader: ClassLoader) {
        private val getPipeline = loader.loadClass("com.facebook.drawee.backends.pipeline.Fresco")
            .getMethod("getImagePipeline")
        private val buffer = loader.loadClass("com.facebook.common.memory.PooledByteBuffer")
        private val fetch = getPipeline.returnType.methods.single { method ->
            val wrapper = (method.genericReturnType as? ParameterizedType)?.actualTypeArguments
                ?.singleOrNull() as? ParameterizedType
            method.parameterCount == 2 && method.parameterTypes[1] == Any::class.java &&
                method.parameterTypes[0].name.startsWith("com.facebook.imagepipeline.request.") &&
                wrapper?.actualTypeArguments?.singleOrNull() == buffer
        }
        private val request = fetch.parameterTypes[0].getMethod("fromUri", Uri::class.java).apply {
            check(Modifier.isStatic(modifiers) && returnType == fetch.parameterTypes[0])
        }
        private val source = fetch.returnType
        private val subscribe = source.methods.single { it.returnType == Void.TYPE && it.parameterCount == 2 &&
            it.parameterTypes[0].isInterface && it.parameterTypes[1] == Executor::class.java &&
            it.parameterTypes[0].methods.any { callback ->
                callback.name == "onNewResult" && callback.parameterTypes.contentEquals(arrayOf(source))
            } }
        private val subscriber = subscribe.parameterTypes[0]
        private val result = source.getMethod("getResult")
        private val close = source.getMethod("close")
        private val getBuffer = ((fetch.genericReturnType as ParameterizedType).actualTypeArguments.single()
            as ParameterizedType).let { it.rawType as Class<*> }.let { wrapper ->
            check(java.io.Closeable::class.java.isAssignableFrom(wrapper))
            wrapper.methods.single { it.parameterCount == 0 && it.genericReturnType == wrapper.typeParameters.single() }
        }
        private val size = buffer.getMethod("size")
        private val read = buffer.methods.single { it.returnType == Int::class.javaPrimitiveType &&
            it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)) }
        private val statusFields = HashMap<Class<*>, Field>()

        fun export(context: Context, uri: Uri, finished: (Throwable?) -> Unit) {
            worker.execute {
                runCatching {
                    val data = checkNotNull(fetch.invoke(getPipeline.invoke(null), request.invoke(null, uri), null))
                    subscribe(context, data, finished)
                }.onFailure { finished(it) }
            }
        }

        private fun subscribe(context: Context, data: Any, finished: (Throwable?) -> Unit) {
            var done = false
            val timeout = worker.schedule({
                if (!done) {
                    done = true
                    runCatching { close.invoke(data) }
                    finished(IllegalStateException("Native image request timed out"))
                }
            }, 45, TimeUnit.SECONDS)
            fun finish(error: Throwable?) {
                done = true
                timeout.cancel(false)
                runCatching { close.invoke(data) }
                finished(error)
            }
            try {
                val state = statusFields.getOrPut(data.javaClass) {
                    generateSequence(data.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .single { field -> !Modifier.isStatic(field.modifiers) &&
                            field.type.enumConstants?.map { (it as Enum<*>).name } ==
                            listOf("IN_PROGRESS", "SUCCESS", "FAILURE") }
                        .apply { isAccessible = true }
                }
                val callback = Proxy.newProxyInstance(subscriber.classLoader, arrayOf(subscriber)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "LiquidTab photo export subscriber"
                        else -> {
                            if (!done && args?.firstOrNull() === data) when (method.name) {
                                "onCancellation", "onFailure" -> finish(IllegalStateException("Native image request failed"))
                                "onNewResult" -> {
                                    val pending = runCatching {
                                        synchronized(data) {
                                            if ((state.get(data) as Enum<*>).name == "SUCCESS")
                                                checkNotNull(result.invoke(data)) as java.io.Closeable
                                            else null
                                        }
                                    }
                                    val reference = pending.getOrNull()
                                    if (reference != null) finish(runCatching {
                                        reference.use { save(context, checkNotNull(getBuffer.invoke(reference))) }
                                    }.exceptionOrNull())
                                    else pending.exceptionOrNull()?.let(::finish)
                                }
                            }
                            null
                        }
                    }
                }
                subscribe.invoke(data, callback, worker)
            } catch (error: Exception) {
                finish(error)
            }
        }

        private fun save(context: Context, bytes: Any) {
            val length = size.invoke(bytes) as Int
            check(length in 1..LIMIT)
            fun input() = object : InputStream() {
                private var offset = 0
                private val one = ByteArray(1)
                override fun read(): Int = if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
                override fun read(target: ByteArray, start: Int, count: Int): Int {
                    if (count == 0) return 0
                    if (offset == length) return -1
                    val capacity = minOf(count, length - offset)
                    val copied = read.invoke(bytes, offset, target, start, capacity) as Int
                    check(copied in 1..capacity)
                    offset += copied
                    return copied
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            input().use { BitmapFactory.decodeStream(it, null, options) }
            val mime = checkNotNull(options.outMimeType)
            check(mime.startsWith("image/") && options.outWidth > 0 && options.outHeight > 0)
            val extension = checkNotNull(MimeTypeMap.getSingleton().getExtensionFromMimeType(mime))
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "XHS_${UUID.randomUUID()}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/小红书")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val file = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(file)).use { output ->
                    check(input().use { it.copyTo(output, 64 * 1024) } == length.toLong())
                }
                val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(file, complete, null, null) == 1)
            } catch (error: Exception) {
                runCatching { resolver.delete(file, null, null) }
                throw error
            }
        }
    }

    private fun report(context: Context, error: Throwable?, usedAlternate: Boolean = false) {
        if (error != null) Log.w(TAG, "Original image export failed: ${error.javaClass.simpleName}")
        main.post {
            val message = when {
                error != null -> "原图保存失败"
                usedAlternate -> "原图不可用，已保存无水印高清图"
                else -> "已保存无水印图片"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
