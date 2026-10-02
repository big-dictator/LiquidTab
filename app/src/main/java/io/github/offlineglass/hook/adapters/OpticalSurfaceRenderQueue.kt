package io.github.offlineglass.hook.adapters

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces generic optical Surface submissions without blocking the UI thread. */
internal class OpticalSurfaceRenderQueue {
    private val queued = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)

    fun request(worker: Handler?, active: () -> Boolean, render: () -> Unit) {
        dirty.set(true)
        if (!queued.compareAndSet(false, true)) return
        if (worker == null || !worker.post {
                dirty.set(false)
                try {
                    render()
                } finally {
                    queued.set(false)
                    if (dirty.get() && active()) request(worker, active, render)
                }
            }) queued.set(false)
    }
}
