package io.github.offlineglass.targets

/** Stable display order; each app owns its own TargetSpec.kt. */
internal object TargetSpecRegistry {
    val all: List<TargetSpec> = listOf(
        io.github.offlineglass.hook.adapters.bilibili.targetSpec,
        io.github.offlineglass.hook.adapters.bilibili_in.targetSpec,
        io.github.offlineglass.hook.adapters.meituan.targetSpec,
        io.github.offlineglass.hook.adapters.meituan_main.targetSpec,
        io.github.offlineglass.hook.adapters.mihome.targetSpec,
        io.github.offlineglass.hook.adapters.mi_health.targetSpec,
        io.github.offlineglass.hook.adapters.mi_market.targetSpec,
        io.github.offlineglass.hook.adapters.mi_community.targetSpec,
        io.github.offlineglass.hook.adapters.netease.targetSpec,
        io.github.offlineglass.hook.adapters.qqmusic.targetSpec,
        io.github.offlineglass.hook.adapters.xhs.targetSpec,
        io.github.offlineglass.hook.adapters.pdd.targetSpec,
        io.github.offlineglass.hook.adapters.taobao.targetSpec,
        io.github.offlineglass.hook.adapters.amap.targetSpec,
        io.github.offlineglass.hook.adapters.douyin.targetSpec,
        io.github.offlineglass.hook.adapters.cainiao.targetSpec,
        io.github.offlineglass.hook.adapters.xianyu.targetSpec,
        io.github.offlineglass.hook.adapters.hellobike.targetSpec,
        io.github.offlineglass.hook.adapters.jd.targetSpec,
        io.github.offlineglass.hook.adapters.tieba.targetSpec,
        io.github.offlineglass.hook.adapters.youtube.targetSpec,
        io.github.offlineglass.hook.adapters.weibo.targetSpec,
        io.github.offlineglass.hook.adapters.mi_file_manager.targetSpec,
        io.github.offlineglass.hook.adapters.mi_phone.targetSpec,
        io.github.offlineglass.hook.adapters.mi_messages.targetSpec,
        io.github.offlineglass.hook.adapters.mi_calendar.targetSpec,
        io.github.offlineglass.hook.adapters.mi_gallery.targetSpec,
        io.github.offlineglass.hook.adapters.mi_theme.targetSpec,
        io.github.offlineglass.hook.adapters.mi_notes.targetSpec,
        io.github.offlineglass.hook.adapters.wechat.targetSpec,
        io.github.offlineglass.hook.adapters.system_picker.targetSpec,
        io.github.offlineglass.hook.adapters.xjtu.targetSpec,
        io.github.offlineglass.hook.adapters.zhihu.targetSpec,
    )
    val byKey: Map<String, TargetSpec> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        all.associateBy(TargetSpec::key)
    }
}
