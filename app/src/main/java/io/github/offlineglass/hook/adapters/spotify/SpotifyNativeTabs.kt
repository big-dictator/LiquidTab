package io.github.offlineglass.hook.adapters.spotify

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.hook.GlassHostLayout

/** Native Create is a preference; Premium visibility is account-driven. */
internal object SpotifyNativeTabs {
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private val readingPreference = ThreadLocal.withInitial { false }
    private var preferences: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile var createEnabled = true
        private set
    @Volatile var premiumAvailable = true
        private set
    private var pendingReportMask = 0
    private var pendingReportHidden = 0
    private var reportRevision = 0L
    private var createInitialized = false
    private var installed = false
    private val hosts = java.util.WeakHashMap<GlassHostLayout, Boolean>()
    @Volatile private var renderedIndices: List<Int>? = null
    val layoutKnown: Boolean get() = renderedIndices != null
    val createVisible: Boolean get() = createEnabled && (renderedIndices?.contains(4) ?: true)
    fun indices(): List<Int> = renderedIndices ?: buildList {
        add(0); add(1); add(2)
        if (premiumAvailable) add(3)
        if (createEnabled) add(4)
    }
    fun attach(host: android.view.View) {
        this.context = host.context.applicationContext ?: host.context
        if (host is GlassHostLayout) hosts[host] = true
        initializeCreate()
        flushReport()
    }
    private fun initializeCreate() {
        if (context == null) return
        if (preferences == null || createInitialized) return
        createInitialized = true
        report(16, if (createVisible) 0 else 16)
    }
    private fun observePreference(prefs: SharedPreferences, name: String) {
        val update = Runnable {
            runCatching {
                val newlyBound = preferences !== prefs
                if (newlyBound) {
                    preferences?.let { old -> listener?.let { old.unregisterOnSharedPreferenceChangeListener(it) } }
                    preferences = prefs
                    createInitialized = false
                    listener = SharedPreferences.OnSharedPreferenceChangeListener { p, changed ->
                        if (changed == name || changed == null) observePreference(p, name)
                    }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
                }
                // Re-read on the UI thread, rather than replaying an older
                // value queued by a background reader before the user's edit.
                readingPreference.set(true)
                val enabled = try { prefs.getBoolean(name, true) } finally { readingPreference.set(false) }
                if (newlyBound || enabled != createEnabled) {
                    createEnabled = enabled
                    android.util.Log.i("SpotifyGlass", "native create=$enabled layout=$renderedIndices")
                    report(16, if (createVisible) 0 else 16)
                }
                initializeCreate()
            }.onFailure { XposedBridge.log("[SpotifyGlass] native create observation failed: $it") }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
    }
    private fun report(mask: Int, hidden: Int) {
        reportRevision++
        pendingReportHidden = (pendingReportHidden and mask.inv()) or (hidden and mask)
        pendingReportMask = pendingReportMask or mask
        SpotifyAdapter.reconcileNativeTabs(mask, hidden, premiumAvailable)
        // Native state is not a new committed module configuration. Refresh
        // derived layout locally; unversioned config broadcasts are rejected.
        hosts.keys.toList().forEach { if (it.isAttachedToWindow) it.refreshAdapterDerivedConfig() }
        flushReport()
    }
    private fun flushReport() {
        val ctx = context ?: return
        if (pendingReportMask == 0) return
        val mask = pendingReportMask; val hidden = pendingReportHidden
        pendingReportMask = 0
        val premium = premiumAvailable
        val revision = reportRevision
        worker.execute {
            val ok = runCatching {
                ctx.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_SPOTIFY_TABS,
                    "com.spotify.music", Bundle().apply {
                        putInt("changed_mask", mask); putInt(ConfigContract.KEY_HIDDEN_MASK, hidden)
                        putBoolean(ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, premium)
                    })?.getBoolean("accepted", false) == true
            }.getOrDefault(false)
            if (!ok) main.postDelayed({
                if (revision != reportRevision) return@postDelayed
                pendingReportHidden = (hidden and pendingReportMask.inv()) or pendingReportHidden
                pendingReportMask = pendingReportMask or mask
                flushReport()
            }, 3000L)
        }
    }
    fun install(loader: ClassLoader, sourceApk: String, dataDir: String) {
        if (installed) return
        installed = true
        runCatching {
            // Stable Android API + stable native preference key. This does not
            // depend on Spotify's renamed r9m/kz61/dz61 implementation classes.
            val type = Class.forName("android.app.SharedPreferencesImpl")
            XposedBridge.hookAllMethods(type, "getBoolean", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (readingPreference.get() == true || param.result !is Boolean) return
                    val name = param.args.firstOrNull() as? String ?: return
                    if (name != "create_button_enabled") return
                    val prefs = param.thisObject as? SharedPreferences ?: return
                    observePreference(prefs, name)
                }
            })
        }.onFailure { XposedBridge.log("[SpotifyGlass] native create hook unavailable: $it") }
        runCatching {
            val symbols = SpotifyNavigationIndex.resolve(sourceApk, dataDir)
            val type = loader.loadClass(symbols.model)
            val entry = loader.loadClass(symbols.entry)
            val identity = entry.getDeclaredField(symbols.identity).apply { isAccessible = true }
            val enumType = loader.loadClass(symbols.enumeration)
            val roles = mapOf("HOME" to 0, "SEARCH" to 1, "YOUR_LIBRARY" to 2, "PREMIUM" to 3, "CREATE" to 4)
            check(enumType.isEnum && enumType.enumConstants.orEmpty().map { (it as Enum<*>).name }.containsAll(roles.keys))
            check(identity.type == enumType && Set::class.java.isAssignableFrom(type))
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.thisObject as? Set<*> ?: return
                    val entries = result.map { item ->
                        if (!entry.isInstance(item)) return
                        roles[(identity.get(item) as? Enum<*>)?.name] ?: return
                    }
                    if (!entries.containsAll(listOf(0, 1, 2)) || entries.distinct().size != entries.size) {
                        main.post { renderedIndices = null; report(24, 16) }
                        return
                    }
                    main.post {
                        if (renderedIndices == entries) return@post
                        renderedIndices = entries.toList()
                        android.util.Log.i("SpotifyGlass", "native navigation=$entries")
                        premiumAvailable = 3 in entries
                        // A queued layout result must never overwrite the native
                        // preference, especially after the user has disabled Create.
                        report(24, (if (premiumAvailable) 0 else 8) or (if (createVisible) 0 else 16))
                    }
                }
            })
            android.util.Log.i("SpotifyGlass", "semantic navigation model=${symbols.model}")
        }.onFailure { XposedBridge.log("[SpotifyGlass] final navigation list hook unavailable: $it") }
    }
}
