package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

/** Search header ownership and virtual actions, shared by early masking and rendering. */
internal object SpotifySearchBinding {
    private val childId=runCatching { AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId",Int::class.javaPrimitiveType).apply { isAccessible=true } }.getOrNull()
    data class Result(val avatar: SpotifyHeaderBinding.Button,val title: SpotifyHeaderBinding.Button,
        val search: SpotifyHeaderBinding.Button)
    data class Layout(val root: ViewGroup,val bar: ViewGroup,val face: ViewGroup,val field: ViewGroup,
        val camera: View,val data: Result,val composeField: Boolean)
    private fun string(view: View,name: String): String? {
        val id=view.resources.getIdentifier(name,"string",view.context.packageName)
        return if(id==0)null else view.resources.getString(id)
    }
    private fun find(view: View,name: String): View? {
        val id=view.resources.getIdentifier(name,"id",view.context.packageName)
        return if(id==0)null else view.findViewById(id)
    }
    private fun nodes(root: View): List<SpotifyHeaderBinding.Button> {
        val found=ArrayList<SpotifyHeaderBinding.Button>();var owners=0
        fun owner(view: View) {
            if(owners>=32)return
            val provider=view.accessibilityNodeProvider
            if(provider==null) { if(view is ViewGroup)for(i in 0 until view.childCount)owner(view.getChildAt(i));return }
            owners++
            val visited=HashSet<Int>()
            fun visit(id: Int,click: Int?,clickBounds: Rect?,depth: Int) {
                if(depth>24 || visited.size>=256 || !visited.add(id))return
                val node=provider.createAccessibilityNodeInfo(id) ?: return
                try {
                    val bounds=Rect().also { node.getBoundsInScreen(it) }
                    val action=if(node.isClickable)id else click
                    val actionBounds=if(node.isClickable)bounds else clickBounds
                    val label=node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString().orEmpty()
                    if(!bounds.isEmpty && action!=null && actionBounds!=null)
                        found+=SpotifyHeaderBinding.Button(label,false,view,action,Rect(actionBounds),Rect(bounds),id)
                    for(i in 0 until node.childCount) {
                        val raw=childId?.invoke(node,i) as? Long ?: continue
                        visit((raw shr 32).toInt(),action,actionBounds,depth+1)
                    }
                } finally { @Suppress("DEPRECATION") node.recycle() }
            }
            visit(View.NO_ID,null,null,0)
        }
        owner(root);return found
    }
    fun read(face: ViewGroup,search: ViewGroup): Result? {
        val header=nodes(face)
        val titleText=string(face,"search_tab_title") ?: return null
        val title=header.filter { it.label==titleText }.distinctBy { it.source to it.virtualId }.singleOrNull() ?: return null
        val actions=header.filterNot { it.source===title.source && it.virtualId==title.virtualId }
            .groupBy { it.source to it.virtualId }
        // The verified face header owns exactly a title action and a separate
        // square avatar action. Its image leaf may intentionally have no label.
        val avatar=actions.values.mapNotNull { group ->
            group.filter { it.bounds.width()>0 && kotlin.math.abs(it.bounds.width()-it.bounds.height())<=2 &&
                it.bounds.right<=title.bounds.left && it.label!=titleText }
                // Anchor decoding at the verified clickable avatar root. A
                // smaller anonymous child can be initials/decoration, not the photo.
                .singleOrNull { it.artworkId==it.virtualId }
        }.singleOrNull() ?: return null
        val hint=string(search,"search_screen_input_hint") ?: return null
        val field=nodes(search).filter { it.label==hint }.distinctBy { it.source to it.virtualId }.singleOrNull() ?: return null
        // face_header_container is the native profile/title-only composition;
        // camera is a sibling. Accessibility's click node can be a leaf overlay,
        // so image decoding must use this verified owner's tree, not that leaf.
        if(avatar.source!==title.source)return null
        var parent: View?=avatar.source
        var inside=false
        repeat(16) { if(parent===face)inside=true;parent=parent?.parent as? View }
        if(!inside)return null
        return Result(avatar.copy(profileHeaderOwner=true),title,field)
    }
    fun layout(content: View): Layout? {
        val face=find(content,"face_header_container") as? ViewGroup ?: return null
        val parent=face.parent as? ViewGroup ?: return null
        val camera=find(parent,"camera_button") ?: return null
        if(!face.isShown || !camera.isShown)return null
        val legacy=find(content,"browse_content_state") as? ViewGroup
        if(legacy!=null) {
            val bar=find(legacy,"browse_app_bar") as? ViewGroup ?: return null
            val field=find(legacy,"browse_search_bar_container") as? ViewGroup ?: return null
            return Layout(legacy,bar,face,field,camera,read(face,field) ?: return null,false)
        }
        var ancestor=parent.parent as? View
        var count=0
        while(ancestor!=null && count++<16) {
            if(ancestor is ViewGroup && ancestor.accessibilityNodeProvider!=null) {
                val data=read(face,ancestor) ?: return null
                if(data.search.source!==ancestor)return null
                return Layout(ancestor,parent,face,ancestor,camera,data,true)
            }
            ancestor=ancestor.parent as? View
        }
        return null
    }
}
