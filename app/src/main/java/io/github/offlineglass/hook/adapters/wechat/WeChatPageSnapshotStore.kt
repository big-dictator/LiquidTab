package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Four replaceable private-cache slots. No images/assets are shipped with the module. */
internal class WeChatPageSnapshotStore(private val directory: File,
    private val status: (WeChatPreviewDiagnostics.Stage)->Unit = {}) {
    private class Rejected(val stage: WeChatPreviewDiagnostics.Stage) : Exception()
    private fun requireCache(condition: Boolean, stage: WeChatPreviewDiagnostics.Stage) {
        if(!condition)throw Rejected(stage)
    }
    companion object {
        private const val MAGIC = 0x57585032
        const val MAX_FRAME_BYTES = 16L * 1024 * 1024
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
    private val encryptionKey: SecretKey by lazy {
        val vault=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (vault.getKey("liquidtab_wechat_preview_v1",null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("liquidtab_wechat_preview_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun aad(key: String, time: Long, width: Int, height: Int) = "$key|$time|$width|$height".toByteArray(Charsets.UTF_8)
    private fun file(index: Int) = File(directory, "$index.cache")
    fun newest(): List<Int> = (0..3).filter { file(it).isFile }.sortedByDescending { file(it).lastModified() }
    fun load(index: Int, key: String, width: Int, height: Int): Bitmap? = runCatching {
        if (index !in 0..3 || key.length != 64 || width <= 0 || height <= 0 ||
            width.toLong() * height * 4 > MAX_FRAME_BYTES) {
            status(WeChatPreviewDiagnostics.Stage.SIZE_MISMATCH);return@runCatching null
        }
        val entry = file(index)
        if (!entry.isFile) {status(WeChatPreviewDiagnostics.Stage.NO_FILE);return@runCatching null}
        requireCache(entry.length()<=MAX_FRAME_BYTES,WeChatPreviewDiagnostics.Stage.SIZE_MISMATCH)
        fun decrypted(input: DataInputStream): CipherInputStream {
            requireCache(input.readInt()==MAGIC,WeChatPreviewDiagnostics.Stage.READ_FAILED)
            requireCache(input.readUTF()==key,WeChatPreviewDiagnostics.Stage.KEY_MISMATCH)
            val time=input.readLong()
            requireCache(System.currentTimeMillis()-time in 0..MAX_AGE_MS,WeChatPreviewDiagnostics.Stage.EXPIRED)
            requireCache(input.readInt()==width && input.readInt()==height,WeChatPreviewDiagnostics.Stage.SIZE_MISMATCH)
            check(input.readInt()==12)
            val iv=ByteArray(12);input.readFully(iv)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,GCMParameterSpec(128,iv))
            cipher.updateAAD(aad(key,time,width,height))
            return CipherInputStream(input,cipher)
        }
        fun authenticate(input: CipherInputStream) {
            val tail=ByteArray(8192)
            while(input.read(tail)!=-1) { /* Verify the final GCM tag, even after a bounds-only decode. */ }
        }
        DataInputStream(entry.inputStream().buffered()).use { input ->
            decrypted(input).use { png ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(png, null, bounds);authenticate(png)
                requireCache(bounds.outWidth==width && bounds.outHeight==height,WeChatPreviewDiagnostics.Stage.DECODE_FAILED)
            }
        }
        DataInputStream(entry.inputStream().buffered()).use { input ->
            decrypted(input).use { png ->
                val image=BitmapFactory.decodeStream(png, null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE })
                authenticate(png)
                image?.also {
                    requireCache(it.width==width && it.height==height,WeChatPreviewDiagnostics.Stage.SIZE_MISMATCH)
                    status(WeChatPreviewDiagnostics.Stage.LOAD_OK)
                } ?: run {status(WeChatPreviewDiagnostics.Stage.DECODE_FAILED);null}
            }
        }
    }.onFailure {
        status((it as? Rejected)?.stage ?: WeChatPreviewDiagnostics.Stage.READ_FAILED)
    }.getOrNull()

    fun save(index: Int, key: String, bitmap: Bitmap) {
        if (index !in 0..3 || key.length != 64 || bitmap.width.toLong() * bitmap.height * 4 > MAX_FRAME_BYTES) return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val pending = File(directory, "$index.pending")
        var software: Bitmap? = null
        try {
            software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
            val time=System.currentTimeMillis()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,encryptionKey)
            cipher.updateAAD(aad(key,time,bitmap.width,bitmap.height))
            DataOutputStream(pending.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC); out.writeUTF(key); out.writeLong(time)
                out.writeInt(bitmap.width); out.writeInt(bitmap.height)
                check(cipher.iv.size==12);out.writeInt(cipher.iv.size);out.write(cipher.iv)
                // Cipher.close() must write its GCM tag without prematurely
                // closing the caller-owned header/file stream on Android.
                CipherOutputStream(LeaveOpenOutputStream(out),cipher).use { encrypted ->
                    check(software.compress(Bitmap.CompressFormat.PNG, 100, encrypted))
                }
            }
            check(pending.length() <= MAX_FRAME_BYTES)
            Files.move(pending.toPath(), file(index).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            status(WeChatPreviewDiagnostics.Stage.SAVE_OK)
        } catch (failure: Throwable) {
            status(WeChatPreviewDiagnostics.Stage.SAVE_FAILED)
            throw failure
        } finally { software?.recycle(); pending.delete() }
    }
    fun clear() {
        // Exact module-owned slots only; no recursive deletion or native data access.
        for (index in 0..3) { file(index).delete(); File(directory, "$index.pending").delete() }
    }
}
