package io.github.offlineglass.rendering

/** Optional adapter-owned, non-interactive visual bridge; never owns native routing. */
internal interface FirstVisitPresentation {
    fun begin(index: Int)
    fun nativeDispatched(index: Int)
    fun cancel()
}
