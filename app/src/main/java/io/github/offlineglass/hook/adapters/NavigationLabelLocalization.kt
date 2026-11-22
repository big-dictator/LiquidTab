package io.github.offlineglass.hook.adapters

import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Module-owned translations for bottom labels which the adapter redraws itself.
 * This changes pixels only: native views, accessibility text and click owners
 * stay in the target application's own language and retain their semantics.
 */
internal object NavigationLabelLocalization {
    private val labels = mapOf(
        "bilibili" to mapOf("首页" to "Home", "动态" to "Feed", "会员购" to "Mall", "我的" to "Profile"),
        "bilibili_in" to mapOf("首页" to "Home", "关注" to "Following", "消息" to "Inbox", "我的" to "Profile"),
        "mihome" to mapOf("米家" to "Home", "智能" to "Smart", "产品" to "Products", "商城" to "Shop", "我的" to "Profile"),
        "mi_health" to mapOf("健康" to "Health", "运动" to "Workout", "设备" to "Devices", "我的" to "Profile"),
        "netease" to mapOf("首页" to "Home", "搜索" to "Search", "笔记" to "Notes", "我的" to "Account"),
        "qqmusic" to mapOf("首页" to "Home", "视频" to "Videos", "刷歌" to "Discover", "星光" to "Starlight", "我的" to "My Music"),
        "spotify" to mapOf("主页" to "Home", "首页" to "Home", "搜索" to "Search", "音乐库" to "Your Library", "创建" to "Create", "Premium" to "Premium"),
        "xhs" to mapOf("首页" to "Home", "市集" to "Shop", "发布" to "Post", "消息" to "Inbox", "我" to "Profile", "我的" to "Profile"),
        "taobao" to mapOf("首页" to "Home", "视频" to "Video", "消息" to "Messages", "购物车" to "Cart", "我的淘宝" to "Account"),
        "amap" to mapOf("首页" to "Home", "附近" to "Explore", "消息" to "Messages", "打车" to "Ride", "我的" to "Profile"),
        "tiktok" to mapOf(
            // Bottom navigation variants across TikTok locales/builds.
            "首页" to "Home", "主页" to "Home", "推荐" to "For You",
            "朋友" to "Friends", "好友" to "Friends", "发布" to "Create", "创建" to "Create",
            "消息" to "Inbox", "收件箱" to "Inbox", "通知" to "Inbox",
            "个人主页" to "Profile", "个人资料" to "Profile", "我" to "Profile", "我的" to "Profile",
            // Reconstructed top channel row uses the same live language switch.
            "关注" to "Following", "推薦" to "For You", "關注" to "Following",
            "直播" to "LIVE", "附近" to "Nearby",
            "商城" to "Shop", "购物" to "Shop", "遊戲" to "Gaming", "游戏" to "Gaming",
            "音乐" to "Music", "音樂" to "Music", "知识" to "STEM", "知識" to "STEM",
        ),
        "cainiao" to mapOf("首页" to "Home", "发现" to "Discover", "购物券" to "Coupons", "消息" to "Messages", "我的" to "Profile"),
        "jd" to mapOf("首页" to "Home", "逛" to "Discover", "消息" to "Messages", "购物车" to "Cart", "我的" to "Account"),
        "youtube" to mapOf(
            "首页" to "Home", "Shorts" to "Shorts", "发布" to "Create",
            "订阅" to "Subscriptions", "订阅内容" to "Subscriptions",
            "通知" to "Notifications", "我" to "You", "你" to "You",
            // YouTube's reconstructed home filter row.
            "全部" to "All", "播客" to "Podcasts", "直播" to "Live",
        ),
        "weibo" to mapOf("首页" to "Home", "视频" to "Video", "发现" to "Explore", "消息" to "Messages", "我" to "Me", "我的" to "Me"),
        "wechat" to mapOf(
            "微信" to "Chats", "聊天" to "Chats", "通讯录" to "Contacts", "联系人" to "Contacts",
            "发现" to "Discover", "探索" to "Discover", "我" to "Me", "个人" to "Me",
        ),
        "huawei_health" to mapOf("首页" to "Health", "运动" to "Exercise", "健康" to "Health", "我的" to "Me"),
        "instagram" to mapOf(
            "首页" to "Home", "Reels" to "Reels", "消息" to "Messages", "私信" to "Messages",
            "搜索" to "Search", "搜索和探索" to "Search", "发布" to "Create", "创建" to "Create",
            "个人主页" to "Profile", "个人资料" to "Profile", "我的" to "Profile", "主页" to "Profile",
        ),
        "x" to mapOf(
            "主页" to "Home", "首页" to "Home", "搜索" to "Search", "探索" to "Explore",
            "通知" to "Notifications", "消息" to "Messages", "私信" to "Messages",
            "列表" to "Lists", "书签" to "Bookmarks", "我" to "Profile", "个人资料" to "Profile",
        ),
        "chatgpt" to mapOf("新聊天" to "New chat", "历史记录" to "History", "项目" to "Projects", "设置" to "Settings"),
    )

    fun toEnglish(targetKey: String, label: String): String =
        labels[targetKey]?.get(label.trim()) ?: label

    data class SnapshotLabel(val view: TextView, val text: String)

    /** Finds only labels with an explicit per-target translation. */
    fun snapshotLabels(targetKey: String, source: ViewGroup, candidates: List<TextView> = emptyList()): List<SnapshotLabel> {
        val views = buildList {
            addAll(candidates)
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += source to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < 256) {
                val (view, depth) = stack.removeLast()
                if (view is TextView) add(view)
                if (view is ViewGroup && depth < 8)
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return views.distinct().mapNotNull { view ->
            if (view.width <= 0 || view.height <= 0) return@mapNotNull null
            val native = view.text?.toString()?.trim().orEmpty()
            val english = toEnglish(targetKey, native)
            if (native.isEmpty() || native == english) null else SnapshotLabel(view, english)
        }
    }

    /** Paints translated pixels over a source snapshot without mutating the app's TextViews. */
    fun drawSnapshotLabels(canvas: android.graphics.Canvas, source: ViewGroup, labels: List<SnapshotLabel>) {
        if (labels.isEmpty()) return
        val sourcePosition = IntArray(2).also(source::getLocationInWindow)
        labels.forEach { (view, text) ->
            val position = IntArray(2).also(view::getLocationInWindow)
            val left = position[0] - sourcePosition[0]
            val top = position[1] - sourcePosition[1]
            val available = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1).toFloat()
            val originalPaint = android.text.TextPaint(view.paint)
            val native = view.text?.toString().orEmpty()
            val nativeBounds = android.graphics.Rect()
            originalPaint.getTextBounds(native, 0, native.length, nativeBounds)
            val gravity = view.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
            val textCenter = when (gravity) {
                android.view.Gravity.RIGHT, android.view.Gravity.END -> left + view.width - view.paddingRight - originalPaint.measureText(native) / 2f
                android.view.Gravity.LEFT, android.view.Gravity.START -> left + view.paddingLeft + originalPaint.measureText(native) / 2f
                else -> left + view.paddingLeft + available / 2f
            }
            val baseline = top.toFloat() + view.baseline
            val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
            canvas.drawRect(textCenter - originalPaint.measureText(native) / 2f - 1f,
                baseline + nativeBounds.top - 1f,
                textCenter + originalPaint.measureText(native) / 2f + 1f,
                baseline + nativeBounds.bottom + 1f,
                erase)
            val paint = android.text.TextPaint(view.paint).apply {
                color = view.currentTextColor
                textAlign = Paint.Align.CENTER
                val measured = measureText(text)
                if (measured > available) textScaleX *= available / measured
            }
            var alpha = 1f
            var node: View? = view
            while (node != null) {
                alpha *= node.alpha
                if (node === source) break
                node = node.parent as? View
            }
            if (alpha <= 0f) return@forEach
            paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255)
            canvas.drawText(text, textCenter, baseline, paint)
        }
    }
}
