package io.github.offlineglass.hook.adapters.bilibili

import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.home_glass.ComposeHomeBinding
import io.github.offlineglass.hook.adapters.home_glass.HomeSemanticSpec

/** Domestic native semantics; optics and bounded reflection are shared primitives. */
internal class BilibiliHomeComposeBinding(header: ViewGroup, strip: ViewGroup) : ComposeHomeBinding(
    header, strip, HomeSemanticSpec(
        channels = listOf("直播", "推荐", "影视"), avatar = listOf("avatar", "头像"),
        account = listOf("个人主页", "个人中心"), searchIcon = listOf("Search Icon", "搜索图标"),
        search = listOf("搜索"), message = listOf("消息", "message"), game = listOf("游戏中心"), requireMessage = true,
    ),
)
