package io.github.offlineglass.hook.adapters.wechat

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

/** Fixed numeric schema only: no pixels, identifiers, hashes, text or exception messages. */
internal class WeChatPreviewDiagnostics(private val context: Context, private val execute: (Runnable) -> Unit) {
    enum class Stage {
        STARTING, ACCOUNT_MISSING, READY, SOURCE_MISSING, PAGER_MISSING, PAGER_API_MISSING,
        CAPTURE_BUSY, SAFETY_REJECTED, SNAPSHOT_UNAVAILABLE, SNAPSHOT_READY,
        NO_FILE, KEY_MISMATCH, SIZE_MISMATCH, EXPIRED, READ_FAILED, DECODE_FAILED,
        LOAD_OK, SAVE_FAILED, SAVE_OK, SHOW_OK, SHOW_REJECTED, CLOSED,
    }
    companion object {
        const val REPORT="wechat_preview_status_report"
        const val READ="debug_wechat_preview_status"
    }
    private val counters=AtomicIntegerArray(Stage.entries.size)
    @Volatile private var lastState: Stage?=null
    @Volatile private var closed=false
    private val scheduled=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private val flush=Runnable {
        scheduled.set(false)
        val data=Bundle().apply {
            putInt("pid",android.os.Process.myPid())
            Stage.entries.forEach { putInt("n_${it.ordinal}",counters.get(it.ordinal)) }
        }
        runCatching { execute(Runnable {
            runCatching { context.contentResolver.call(io.github.offlineglass.config.ConfigContract.URI,REPORT,null,data) }
        }) }
    }
    fun mark(stage: Stage) {
        if(closed || !io.github.offlineglass.BuildConfig.DEBUG)return
        counters.incrementAndGet(stage.ordinal)
        if(scheduled.compareAndSet(false,true))handler.postDelayed(flush,1500L)
    }
    fun state(stage: Stage) { if(lastState!=stage) {lastState=stage;mark(stage)} }
    fun close() { mark(Stage.CLOSED);closed=true;handler.removeCallbacks(flush); scheduled.set(false); flush.run() }
}
