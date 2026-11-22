package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.hook.adapters.bilibili_ambient.BilibiliVideoAmbientEngine

internal object BilibiliVideoAmbient {
    private val engine = BilibiliVideoAmbientEngine("tv.danmaku.bili",
        { it.bilibiliVideoAmbient }, "bilibili-video-ambient.txt")
    fun install(loader: ClassLoader) = engine.install(loader)
}
