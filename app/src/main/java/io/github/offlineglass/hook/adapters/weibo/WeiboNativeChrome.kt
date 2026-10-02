package io.github.offlineglass.hook.adapters.weibo

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.offlineglass.hook.GlassHostLayout

internal fun GlassHostLayout.suppressNativeWeiboBottomBar(source: ViewGroup?) {

        if (source == null) return



        val shell = source.parent as? ViewGroup

        val tabHost = shell?.parent as? ViewGroup



        tabHost?.let { th ->

            val currentTab = runCatching {

                th.javaClass.getMethod("getCurrentTab").invoke(th) as? Int

            }.getOrNull() ?: 0



            var needsLayout = false

            for (i in 0 until th.childCount) {

                val child = th.getChildAt(i) ?: continue

                val idName = viewResourceEntryName(child).orEmpty()

                if (child.paddingBottom > 0) {

                    child.setPadding(

                        child.paddingLeft, child.paddingTop,

                        child.paddingRight, 0

                    )

                    needsLayout = true

                }

                val isShadow = idName.contains("shadow") ||

                    (child is ImageView && child.layoutParams.height <= 5)

                if (isShadow) {

                    if (child.visibility != View.GONE) child.visibility = View.GONE

                    child.background = null

                    if (child is ImageView) child.setImageDrawable(null)

                }

            }

            if (currentTab == 3) {

                val glassHeight = height.coerceAtLeast(measuredHeight).coerceAtLeast(1)

                val glassBottomGap = (rootView.height * 68f / 2656f).toInt().coerceAtLeast(1)

                val requiredPadding = (glassHeight + glassBottomGap)

                    .coerceAtLeast(source.height.coerceAtLeast(1))

                val tabcontent = (0 until th.childCount).map { th.getChildAt(it) }

                    .firstOrNull { viewResourceEntryName(it).orEmpty() == "tabcontent" }

                if (tabcontent is ViewGroup) {

                    val list = findScrollableList(tabcontent) as? ViewGroup

                    if (list != null && list.paddingBottom != requiredPadding) {

                        list.setPadding(

                            list.paddingLeft, list.paddingTop,

                            list.paddingRight, requiredPadding

                        )

                        list.clipToPadding = false

                        list.clipChildren = false

                        needsLayout = true

                    }

                }

            }

            if (needsLayout) th.requestLayout()

        }



        if (shell != null) {

            shell.background = null

            shell.foreground = null

            for (i in 0 until shell.childCount) {

                val sib = shell.getChildAt(i) ?: continue

                if (sib === source) continue

                sib.background = null

                sib.foreground = null

                if (sib is ImageView) sib.setImageDrawable(null)

            }

        }



        source.background = null

        source.foreground = null

        for (i in 0 until source.childCount) {

            val child = source.getChildAt(i) ?: continue

            child.background = null

            child.foreground = null

            child.backgroundTintList = null

            if (child is ViewGroup) {

                for (j in 0 until child.childCount) {

                    val grandchild = child.getChildAt(j) ?: continue

                    grandchild.background = null

                    grandchild.foreground = null

                    grandchild.backgroundTintList = null

                }

            }

        }

    }
