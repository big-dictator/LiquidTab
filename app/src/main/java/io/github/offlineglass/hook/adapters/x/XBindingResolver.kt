package io.github.offlineglass.hook.adapters.x

import android.content.Context
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/** APK identity invalidates the entire single-slot discovery cache after every update.
 * Reflection revalidates cached descriptors before any interception is installed.
 */
internal object XBindingResolver {
    private const val SCHEMA = "semantic-contract-v12"
    private val roles = listOf("landing", "bottom", "postFab", "chatFab", "toggle", "drawerContainer", "drawerRenderer", "drawerOpen", "detailConstructor", "detailDestroy",
        "gestureDrawerContainer", "gestureDrawerRenderer", "gestureDrawerOpen", "replyComposer", "replySurface", "backgroundFactory", "coordinateBounds", "coordinateRoot", "coordinateSize", "drawContent", "backgroundNodeConstructor", "backgroundInvalidation", "hazeDraw", "hazeAreas", "hazeAreaRecording", "hazeAreaLayer", "replyBorderFactory", "replyBorderScope", "replyMaskOwner", "replyMaskScope", "replyMaskFactory")
    data class Bindings(val methods: Map<String, Method>, val detailClass: Class<*>?, val report: String,
                        val backgroundNode: Class<*>? = null, val fields: Map<String,java.lang.reflect.Field> = emptyMap())

    fun resolve(context: Context, loader: ClassLoader): Bindings {
        val info = context.applicationInfo
        val apks = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File).sortedBy { it.path }
        val fingerprint = fingerprint(apks)
        val file = File(context.cacheDir, "liquidtab-x-bindings.properties")
        val cached = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull()
        if (cached?.getProperty("schema") == SCHEMA && cached.getProperty("apk") == fingerprint) {
            runCatching { materialize(cached, loader, "cache", true) }.getOrNull()?.let { return it }
        }
        val start = android.os.SystemClock.uptimeMillis()
        val index = XApkIndex.scan(apks)
        val properties = Properties().apply {
            setProperty("schema", SCHEMA)
            setProperty("apk", fingerprint)
            for (role in roles) index.unique(role)?.let { setProperty(role, it.key()) }
            for (role in roles) setProperty("$role.candidates",(index.matches[role]?.size ?: 0).toString())
        }
        val counts = roles.joinToString { "$it=${index.matches[it]?.size ?: 0}" }
        val result = materialize(properties, loader, "scan ${android.os.SystemClock.uptimeMillis() - start}ms $counts")
        // Each independently validated capability can be cached. Missing
        // navigation must not discard working reply/material/page contracts.
        run {
            runCatching {
                val pending = File(file.parentFile, "${file.name}.pending")
                pending.outputStream().use { properties.store(it, SCHEMA) }
                if (!pending.renameTo(file)) pending.delete()
            }
        }
        return result
    }

    private fun materialize(properties: Properties, loader: ClassLoader, origin: String, strict: Boolean = false): Bindings {
        val methods = mutableMapOf<String, Method>()
        val fields = mutableMapOf<String,java.lang.reflect.Field>()
        val failures = mutableListOf<String>()
        var detail: Class<*>? = null
        var background: Class<*>? = null
        for (role in roles) {
            val key = properties.getProperty(role) ?: continue
            val resolved = runCatching {
                val arrow = key.indexOf("->")
                val open = key.indexOf('(', arrow)
                val close = key.indexOf(')', open)
                require(arrow > 0 && open > arrow && close > open)
                val owner = type(key.substring(0, arrow), loader)
                val params = descriptors(key.substring(open + 1, close)).map { type(it, loader) }.toTypedArray()
                if(role=="hazeAreas" || role=="hazeAreaRecording") {
                    fields[role]=owner.getDeclaredField(key.substring(arrow+2,open)).apply {
                        require(type==type(key.substring(close+1),loader) && !Modifier.isStatic(modifiers))
                        isAccessible=true
                    }
                    null
                } else if (role == "backgroundNodeConstructor") {
                    background = owner // R8 may inline its constructor into the element factory.
                    null
                } else if (role == "detailConstructor") {
                    owner.getDeclaredConstructor(*params)
                    detail = owner
                    null
                } else {
                    owner.getDeclaredMethod(key.substring(arrow + 2, open), *params).also {
                        require(it.returnType == type(key.substring(close + 1), loader))
                        require(Modifier.isStatic(it.modifiers) == (role !in setOf("drawerOpen", "gestureDrawerOpen", "detailDestroy", "replyComposer", "hazeDraw", "hazeAreaLayer", "drawContent", "coordinateSize")))
                        it.isAccessible = true
                    }
                }
            }
            resolved.getOrNull()?.let { methods[role] = it }
            if (resolved.isFailure) failures += role
        }
        if (strict) require(failures.isEmpty()) { "Cached binding failed reflection validation" }
        methods["landing"]?.let { landing ->
            val enums=landing.parameterTypes.filter { type -> type.isEnum && XApkIndex.TAB_NAMES.all { name ->
                runCatching { type.getField(name).type==type }.getOrDefault(false)
            } }
            if(enums.size!=1 || landing.parameterTypes.none { it.name=="kotlin.jvm.functions.Function1" }) {
                methods.remove("landing");failures+="landing:enum/callback contract"
            }
        }
        if (properties.getProperty("replyMaskOwner.candidates", "0") != "0" && methods["replyMaskOwner"] == null) {
            methods.remove("replyMaskScope")
            methods.remove("replyMaskFactory")
            failures += "replyMask:owner unavailable"
        }
        val absent=roles.filter { properties.getProperty(it)==null }.joinToString { "$it(candidates=${properties.getProperty("$it.candidates","0")})" }
        return Bindings(methods, detail, "$SCHEMA; $origin; active=${methods.keys}; fields=${fields.keys}; invalid=$failures; unavailable=[$absent]", background, fields)
    }

    private fun fingerprint(apks: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (apk in apks) {
            digest.update("$SCHEMA|${apk.path}|${apk.length()}|${apk.lastModified()}".toByteArray())
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                    .sortedBy { it.name }.forEach { digest.update("|${it.name}:${it.crc}:${it.size}".toByteArray()) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun descriptors(value: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < value.length) {
            val start = cursor
            while (value[cursor] == '[') cursor++
            cursor = if (value[cursor] == 'L') value.indexOf(';', cursor).also { require(it >= 0) } + 1 else cursor + 1
            result += value.substring(start, cursor)
        }
        return result
    }
    private fun type(value: String, loader: ClassLoader): Class<*> = when (value) {
        "V" -> Void.TYPE; "Z" -> Boolean::class.javaPrimitiveType!!; "B" -> Byte::class.javaPrimitiveType!!
        "S" -> Short::class.javaPrimitiveType!!; "I" -> Int::class.javaPrimitiveType!!; "J" -> Long::class.javaPrimitiveType!!
        "C" -> Char::class.javaPrimitiveType!!; "F" -> Float::class.javaPrimitiveType!!; "D" -> Double::class.javaPrimitiveType!!
        else -> Class.forName(if (value.startsWith('L')) value.substring(1, value.length - 1).replace('/', '.')
            else value.replace('/', '.'), false, loader)
    }
}

