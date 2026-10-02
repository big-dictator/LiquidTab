package io.github.offlineglass.hook.adapters.netease

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout

/** All mutable mini-player, drawer and heartbeat state is host-local and NetEase-owned. */
internal class NeteasePlayerState {
    private var host: GlassHostLayout? = null
    fun bind(host: GlassHostLayout) { this.host = host }
    var miniPlayerTarget: View? = null
    var miniPlayerGlassDrawable: NeteaseMiniPlayerGlassDrawable? = null
    var miniPlayerBlurTarget: View? = null
    var lastMiniPlayerProbe = 0L
    var navigationTabView: View? = null
    var miniPlayerScene: View? = null
    var lastMiniPlayerMaintenance = 0L
    var clipGeometrySignature = ""
    var playerBarRect: RectF? = null
    @Volatile var homeLastInteractionMs = 0L
    var lastChromeSuppress = 0L
    var lastVipBannerProbe = 0L
    var vipBannerRoot: View? = null
    var chromeSource: ViewGroup? = null
    var nativeBar: View? = null
    var nativeBarContainer: View? = null
    var nativeBarTitle: View? = null
    var playerSceneProbeTarget: View? = null
    val visiblePlayerChildren = ArrayList<View>()
    var visibleChildrenTarget: View? = null
    var hasNavigationBar = true
    var lastHomeSelected = -1
    val homeIdleHideRunnable = Runnable {
        host?.takeIf { it.selectedIndex == 0 }?.postInvalidateOnAnimation()
    }
    var playerContentRects: MutableMap<String, RectF> = HashMap()
    val playerChildViews = HashMap<String, View>()
    var albumBitmap: Bitmap? = null
    var albumDrawableState: Drawable.ConstantState? = null
    var albumRotation = 0f
    var songTitle = ""
    var playDrawable: Drawable? = null
    var listDrawable: Drawable? = null
    var lastMiniPlayerContentProbe = 0L
    var marqueeStartNanos = 0L
    var marqueeTitle = ""
    var lastDiscRotation = 0f
    var lastDiscRotationTime = 0L
    var discSpeed = 0f
    var playerTouchTarget: View? = null
    var playerTouchRect: RectF? = null
    var playerHitRect: RectF? = null
    val playerTouchGuards = java.util.WeakHashMap<View, Boolean>()
    var playerTouchShield: View? = null
    var playerOutlineSignature = ""
    var contentExtractTarget: View? = null
    var lastContentExtractMs = 0L
    var contentExtractPending = true
    var heartbeatActive = false
    var lastHeartbeatProbeMs = 0L
    var lastHeartbeatLogMs = 0L
    var wasHeartbeatActive = false
    var playerHiddenByHeartbeat = false
    var playerRestoreTranslationY = 0f
    var miniPlayerNativeRevive = false
    var adjustCount = 0L
    var adjustNs = 0L
    var extractNs = 0L
    var lastAdjustLogMs = 0L
    var sideDrawer: ViewGroup? = null
    var sideDrawerIsPanel = false
    var sideDrawerWasOpen = false
    var sideDrawerOpenCached = false
    var sideDrawerOriginalElevation: Float? = null
    var sideDrawerOriginalTranslationZ: Float? = null
    var lastSideDrawerProbe = 0L
    var lastDrawerStateCheck = 0L
    var drawerOpeningUntil = 0L
    var drawerInteractionArmed = false
    var drawerButtonView: View? = null
    val drawerButtonHooks = java.util.WeakHashMap<View, Boolean>()
    val playerPath = Path()
    val playerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    val playerBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val playerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = false }
    val playerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val bottomBarRect = RectF()
    val albumCirclePath = Path()
}
