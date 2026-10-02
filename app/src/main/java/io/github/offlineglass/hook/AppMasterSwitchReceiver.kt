package io.github.offlineglass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process

/** Lets an already-hooked target process terminate itself after either master-switch transition. */
object AppMasterSwitchReceiver {
    const val ACTION_DISABLE_APP = "io.github.offlineglass.action.DISABLE_TARGET_APP"
    const val EXTRA_PACKAGE = "package"
    const val SENDER_PERMISSION = "io.github.offlineglass.permission.CONFIG_SYNC"

    @Volatile private var registered = false

    @Synchronized
    fun ensureRegistered(context: Context, packageName: String) {
        if (registered || context.packageName != packageName) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_DISABLE_APP) return
                if (intent.getStringExtra(EXTRA_PACKAGE) != receiverContext.packageName) return
                // The sender is protected by the module's signature permission
                // and the package extra is checked above. Both enabling and
                // disabling require a clean process so irreversible layout
                // hooks cannot survive either side of the transition.
                Process.killProcess(Process.myPid())
            }
        }
        val filter = IntentFilter(ACTION_DISABLE_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                SENDER_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter, SENDER_PERMISSION, null)
        }
        registered = true
    }
}
