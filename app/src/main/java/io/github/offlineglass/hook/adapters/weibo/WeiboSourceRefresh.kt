package io.github.offlineglass.hook.adapters.weibo

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
internal fun GlassHostLayout.refreshWeiboNavigationSource(): Boolean {

        
        val current = navigationSource

        if (current != null && current.isAttachedToWindow && current.width > 0) return false

        // Search for main_radio in the activity's content view

        val activity = context.findActivity() ?: return false

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false

        val stack = ArrayDeque<View>()

        stack.add(content)

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            val name = viewResourceEntryName(view).orEmpty()

            if ((name == "main_radio" || name == "main_theme_radio") && view is ViewGroup) {

                XposedBridge.log("[OfflineGlass] WEIBO: refreshed navigationSource (was stale=${current == null})")

                navigationSource = view

                appNavigationState?.suppressNativeChrome(view)

                scheduleNavigationSnapshotRefresh(longArrayOf(0L, 48L, 120L))

                postInvalidateOnAnimation()

                return true

            }

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) {

                    stack.add(view.getChildAt(i))

                }

            }

        }

        return false

    }
