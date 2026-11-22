package io.github.offlineglass.hook.adapters.bilibili_in

import io.github.offlineglass.hook.adapters.bilibili_ambient.BilibiliVideoAmbientEngine

internal object BilibiliInVideoAmbient {
    private val engine = BilibiliVideoAmbientEngine(BilibiliInAdapter.PACKAGE_NAME,
        { it.bilibiliInVideoAmbient }, "bilibili-in-video-ambient.txt")
    fun install(loader: ClassLoader) = engine.install(loader)
}
