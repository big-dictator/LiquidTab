package io.github.offlineglass.hook.adapters.quora

import android.view.View
import android.view.ViewGroup

/** Small bounded checks shared by the verified Quora bindings. No persistent tree cache. */
internal object QuoraViews {
    fun child(parent: ViewGroup, resource: String): View? {
        var found: View?=null
        for(i in 0 until parent.childCount) {
            val view=parent.getChildAt(i)
            if(QuoraAdapter.name(view)!=resource)continue
            if(found!=null)return null
            found=view
        }
        return found
    }
    fun selected(row: ViewGroup?): Int {
        row ?: return -1
        var found=-1
        for(i in 0 until row.childCount)if(row.getChildAt(i).isSelected) {
            if(found>=0)return -1
            found=i
        }
        return found
    }
    fun descendant(view: View, parent: ViewGroup): Boolean {
        var node: View?=view
        repeat(24) {
            if(node===parent)return true
            node=node?.parent as? View ?: return false
        }
        return false
    }
}
