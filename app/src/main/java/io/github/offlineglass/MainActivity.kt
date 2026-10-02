package io.github.offlineglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.offlineglass.config.ColorMode
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.LocalSettings
import io.github.offlineglass.config.ManagerSettings
import io.github.offlineglass.config.UiMode
import io.github.offlineglass.ui.ManagerRoot
import io.github.offlineglass.ui.OfflineGlassTheme
import io.github.offlineglass.ui.UiActions
import io.github.offlineglass.hook.AppMasterSwitchReceiver
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var settingsState: MutableState<ManagerSettings>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LocalSettings.applyFormalDefaultsOnce(this)
        settingsState = mutableStateOf(LocalSettings.snapshot(this))

        setContent {
            val settings = settingsState.value
            OfflineGlassTheme(settings) {
                ManagerRoot(settings, actions())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsState.isInitialized) refresh()
    }

    private fun actions() = UiActions(
        setUiMode = { LocalSettings.setUiMode(this, it); refresh() },
        setColorMode = { LocalSettings.setColorMode(this, it); refresh() },
        setDynamicColor = { LocalSettings.setDynamicColor(this, it); refresh() },
        setLiquidGlassEnabled = { LocalSettings.setLiquidGlassEnabled(this, it); refresh() },

        setSolidBarEnabled = { LocalSettings.setSolidBarEnabled(this, it); refresh() },
        setOutlineEnabled = { LocalSettings.setOutlineEnabled(this, it); refresh() },
        setAppEnabled = { packageName, enabled ->
            LocalSettings.setAppEnabled(this, packageName, enabled)
            refresh()
            stopTargetAfterSwitch(packageName)
        },
        setMiMarketTabEnabled = { key, enabled ->
            LocalSettings.setMiMarketTabEnabled(this, key, enabled)
            refresh()
            stopTargetAfterSwitch("com.xiaomi.market")
        },
        setGlassConfig = { config -> LocalSettings.setGlassConfig(this, config); refresh() },
        // Replaced by a screen-local preview callback in each manager UI so
        // slider motion never rebuilds the full settings tree.
        previewGlassConfig = {},
        setAppConfig = { packageName, config ->
            LocalSettings.setAppConfig(this, packageName, config)
            refresh()
        },
        clearAppConfig = { packageName -> LocalSettings.clearAppConfig(this, packageName); refresh() },
        applyChanges = {
            LocalSettings.applyChanges(this)
            refresh()
            // Recreate only the manager UI. Target applications already poll
            // the shared revision and receive the new parameters live.
            recreate()
        },
    )

    private fun refresh() {
        settingsState.value = LocalSettings.snapshot(this)
    }

    /**
     * Existing target processes may already have irreversible app-specific
     * layout hooks installed. Restarting at the master-switch boundary is the
     * only deterministic way to return to a pristine native hierarchy (and is
     * equally important when enabling again). The package name is accepted
     * only when it comes from our fixed catalog.
     */
    private fun stopTargetAfterSwitch(packageName: String) {
        if (io.github.offlineglass.targets.AppCatalog.forPackage(packageName) == null) return
        sendBroadcast(
            android.content.Intent(AppMasterSwitchReceiver.ACTION_DISABLE_APP)
                .setPackage(packageName)
                .putExtra(AppMasterSwitchReceiver.EXTRA_PACKAGE, packageName)
                .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
        )
        targetRestartExecutor.execute {
            // Compatibility fallback for a target process started before the
            // self-stop receiver was introduced or registered.
            Thread.sleep(350L)
            val stoppedByRoot = runCatching {
                ProcessBuilder(
                    "su",
                    "-c",
                    "cmd activity force-stop --user current $packageName",
                ).start().waitFor() == 0
            }.getOrDefault(false)
            if (!stoppedByRoot) {
                runCatching {
                    getSystemService(android.app.ActivityManager::class.java)
                        ?.killBackgroundProcesses(packageName)
                }
            }
        }
    }

    companion object {
        private val targetRestartExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-AppSwitch").apply { isDaemon = true }
        }
    }
}
