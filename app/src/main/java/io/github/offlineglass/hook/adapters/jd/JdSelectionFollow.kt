package io.github.offlineglass.hook.adapters.jd

/** Keeps a user's latest tab tap authoritative while JD animates its native selection. */
internal class JdSelectionFollow {
    var target = -1
        private set
    private var followUntil = 0L
    private var deadline = 0L
    private var targetMatchedAt = 0L

    fun start(index: Int, slotCount: Int, now: Long) {
        target = index.coerceIn(0, slotCount - 1)
        followUntil = now + FOLLOW_MS
        deadline = now + SELECTION_TIMEOUT_MS
        targetMatchedAt = 0L
    }

    fun reset() {
        target = -1
        followUntil = 0L
        deadline = 0L
        targetMatchedAt = 0L
    }

    fun isFollowing(now: Long): Boolean = now < followUntil || isAwaitingSelection(now)
    fun isAwaitingSelection(now: Long): Boolean = target >= 0 && now < deadline

    /** False means JD still reports a stale tab during the pending transition. */
    fun acceptNativeSelection(resolved: Int, now: Long): Boolean {
        if (target < 0) return true
        if (resolved != target && now < deadline) return false
        if (resolved == target) {
            if (targetMatchedAt == 0L) targetMatchedAt = now
            if (now >= followUntil && now - targetMatchedAt >= SELECTION_SETTLE_MS) target = -1
        } else if (now >= deadline) {
            target = -1
        }
        return true
    }

    private companion object {
        const val FOLLOW_MS = 1_200L
        const val SELECTION_TIMEOUT_MS = 2_200L
        const val SELECTION_SETTLE_MS = 220L
    }
}
