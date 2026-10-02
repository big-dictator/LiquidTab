package io.github.offlineglass.hook.adapters.netease

const val NETEASE_PACKAGE = "com.netease.cloudmusic"

/** NetEase brand red used for the selected tab tint. */

const val NETEASE_ACCENT_COLOR = 0xFFC20C0C.toInt()

/** Text-only tab label size for NetEase (native bar has no icons). */

const val NETEASE_LABEL_TEXT_SP = 16.8f

const val NETEASE_LABEL_SCALE = 1.1f

/** NetEase home: hide bloom stroke + ambient shadow after this idle period. */

const val NETEASE_HOME_OUTLINE_IDLE_MS = 1500L

/** NetEase home: fade duration for the stroke/shadow hide animation. */

const val NETEASE_HOME_OUTLINE_FADE_MS = 400L

/** Only the 心动 sub-page of the NetEase home exercises the home-fade

 *  + hidden mini-player behavior; every other home sub-page shows the

 *  full liquid bar and the normal mini player. */

const val NETEASE_HOME_HEARTBEAT_TAB_TEXT = "心动"

const val NETEASE_HEARTBEAT_PROBE_INTERVAL_MS = 200L

const val NETEASE_NAVIGATION_TAB_LAYOUT_CLASS =

    "com.netease.cloudmusic.theme.ui.NavigationTabLayout"

val NETEASE_LABELS = arrayOf("首页", "搜索", "笔记", "我的")

const val NETEASE_MINE_INDEX = 3

val NETEASE_NORMAL_ICON_NAMES = arrayOf(

    "t_actionbar_discover_normal",

    "dolphin_outline_search",

    "t_actionbar_music_normal",

    "dolphin_outline_mine",

)

val NETEASE_SELECTED_ICON_NAMES = arrayOf(

    "t_actionbar_discover_selected",

    "dolphin_outline_search",

    "t_actionbar_music_selected",

    "dolphin_outline_mine",

)

val NETEASE_MINI_PLAYER_VIEW_NAMES = arrayOf(

    "miniPlayBarReallyRoot",

    "miniPlayBarLayout",

    "minPlayerBarContainer",

    "minPlayerBar",

)

val NETEASE_MINI_PLAYER_NATIVE_BG_IDS = arrayOf(

    "minibarContentContainerNew",

    "miniPlayBarLayout",

    "minPlayerBarContainer",

    "minPlayerBar",

)

val NETEASE_MINI_PLAYER_DECORATION_IDS = arrayOf(

    "minibarContentBg",

    "minibar_hover",

    "minibarSpace",

    "v4ShadowView",

    "v4ShadowViewMask",

    "shadowView",

)

val NETEASE_BOTTOM_DECORATION_IDS = arrayOf(

    "bottomNavDividerLine",

    "navigationBarBackground",

    "navigationBarBackgroundPadLand",

    "navCenterBackground",

)

/** Mini player content keys -> native resource id names, host-local layout. */

val NETEASE_MINI_PLAYER_CHILD_IDS = mapOf(

    "album" to "iv_smallAlbumCover",

    "disc" to "smallAlbumDisc0",

    "diskArea" to "miniDiskContainer",

    "title" to "tv_music",

    "play" to "minPlayBtn",

    // Forward directly to the clickable ImageView. The surrounding

    // container is deliberately non-clickable in current NetEase and

    // silently discarded performClick(), making the right key inert.

    "list" to "minPlaylistBtn",

    "songArea" to "miniPlayBarLayout",

)

val NETEASE_VIP_BANNER_IDS = arrayOf(

    "bgContainer",

    "freeIV",

    "titleTV",

    "countdownView",

    "actionTV",

)

const val NETEASE_MINI_PLAYER_GAP_DP = 8f

const val NETEASE_MINI_PLAYER_PROBE_INTERVAL_MS = 160L

// Native mini-player chrome is rebuilt asynchronously, but it does not

// need a full hierarchy scan on every display frame. A short periodic

// repair catches rebinds without taxing complex search-result lists.

const val NETEASE_MINI_PLAYER_MAINTENANCE_INTERVAL_MS = 480L

const val NETEASE_DRAWER_PROBE_INTERVAL_MS = 480L

const val NETEASE_DRAWER_STATE_INTERVAL_MS = 32L

const val NETEASE_DRAWER_OPENING_GRACE_MS = 900L

/** Player regions that forward touches to the hidden native controls. */

val NETEASE_PLAYER_CLICKABLE_KEYS = setOf(

    "album", "disc", "diskArea", "play", "list", "songArea",

)

/** Marquee scroll speed for overlong titles (px per millisecond). */

const val NETEASE_MARQUEE_SPEED_PX_PER_MS = 0.06f

/** Disc shrunk to 82% of the player capsule's corner radius. */

const val NETEASE_DISC_RADIUS_SCALE = 0.82f

/** Minimum right margin between the marquee title and the play key. */

const val NETEASE_TITLE_RIGHT_MARGIN_DP = 8f

/** Mini player bar height (approx 143px @ 2.625 density); runtime geometry wins. */

const val NETEASE_PLAYER_BAR_DP = 54f
