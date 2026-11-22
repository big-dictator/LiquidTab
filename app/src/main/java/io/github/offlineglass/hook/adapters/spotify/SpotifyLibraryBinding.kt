package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

internal object SpotifyLibraryBinding {
    data class Result(val owner: View,val avatar: SpotifyHeaderBinding.Button,val title: SpotifyHeaderBinding.Button,
        val tools: List<SpotifyHeaderBinding.Button>,val filters: List<SpotifyHeaderBinding.Button>,val headerId: Int,val filtersId: Int,val feedId: Int,val feedBounds: Rect,val clearFilterIds: Set<Int> = emptySet())
    private data class Node(val id: Int,val parent: Int,val text: String,val desc: String,val bounds: Rect,
        val click: Int?,val clickBounds: Rect?,val checked: Boolean,val scroll: Boolean,val collection: Boolean)
    private val childId=runCatching { AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId",Int::class.javaPrimitiveType).apply { isAccessible=true } }.getOrNull()
    private val resourceIds=HashMap<String,Int>()
    private fun resourceId(view: View,type: String,name: String)=resourceIds.getOrPut("$type:$name") {
        view.resources.getIdentifier(name,type,view.context.packageName)
    }
    private fun string(view: View,name: String): String? {
        val id=resourceId(view,"string",name)
        return if(id!=0)runCatching { view.resources.getString(id) }.getOrNull() else null
    }
    private var labelLocale=""
    private var descriptionLabels=emptyMap<String,String>()
    private fun filterLabels(root: View): Map<String,String> {
        val locale=root.resources.configuration.locales.toLanguageTags()
        if(labelLocale==locale)return descriptionLabels
        // Stable native resource labels; render only actions present in the row.
        val roles=listOf("albums","artists","authors","books","by_spotify","by_you","courses","downloads","events","finished","folders","in_progress","mixed","not_started","past","playlists","podcasts","upcoming","venue")
        val pairs=roles.mapNotNull { role ->
            val key="your_library_content_filter_"+role
            val description=string(root,key+"_content_description") ?: return@mapNotNull null
            val label=string(root,key) ?: return@mapNotNull null
            description to label
        }
        descriptionLabels=pairs.groupBy { it.first }.mapNotNull { (desc,values) ->
            values.map { it.second }.distinct().singleOrNull()?.let { desc to it }
        }.toMap();labelLocale=locale
        return descriptionLabels
    }
    fun read(root: View, chromeOnly: Boolean=false): Result? {
        if(root is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay || root is io.github.offlineglass.hook.GlassHostLayout)return null
        fun native(name: String): View? {
            val id=resourceId(root,"id",name)
            return if(id==0)null else root.findViewById(id)
        }
        val homeFilters=native("filters_container")
        val homeBar=native("app_bar") as? ViewGroup
        if(homeFilters?.isShown==true && homeBar!=null &&
            homeBar.findViewById<View>(homeFilters.id)===homeFilters)return null
        val searchFace=native("face_header_container")
        val camera=native("camera_button")
        if(searchFace?.isShown==true && camera?.isShown==true && searchFace.parent===camera.parent)return null
        return readOwner(root,chromeOnly)
    }
    private fun readOwner(root: View,chromeOnly: Boolean): Result? {
        if(root is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay || root is io.github.offlineglass.hook.GlassHostLayout)return null
        val provider=root.accessibilityNodeProvider
        if(provider==null) {
            if(root is ViewGroup)for(i in 0 until root.childCount)readOwner(root.getChildAt(i),chromeOnly)?.let { return it }
            return null
        }
        val nodes=LinkedHashMap<Int,Node>()
        fun visit(id: Int,parent: Int,click: Int?,clickBounds: Rect?,checked: Boolean,depth: Int) {
            if(depth>24 || nodes.size>=256 || nodes.containsKey(id))return
            val raw=provider.createAccessibilityNodeInfo(id) ?: return
            try {
                val rect=Rect().also { raw.getBoundsInScreen(it) }
                val target=if(raw.isClickable)id else click
                val targetBounds=if(raw.isClickable)rect else clickBounds
                val selected=checked || raw.isChecked || raw.isSelected
                nodes[id]=Node(id,parent,raw.text?.toString().orEmpty(),raw.contentDescription?.toString().orEmpty(),rect,target,targetBounds,selected,raw.isScrollable,raw.collectionInfo!=null)
                for(i in 0 until raw.childCount) {
                    val key=childId?.invoke(raw,i) as? Long ?: continue
                    visit((key shr 32).toInt(),id,target,targetBounds,selected,depth+1)
                }
            } finally { @Suppress("DEPRECATION") raw.recycle() }
        }
        visit(View.NO_ID,View.NO_ID,null,null,false,0)
        val titleText=string(root,"library_all_header_title") ?: return null
        val profileDescription=string(root,"library_all_header_profile_button_content_description") ?: return null
        val title=nodes.values.singleOrNull { it.text==titleText && it.text.isNotBlank() } ?: return null
        val face=nodes.values.singleOrNull { it.desc==profileDescription && it.desc.isNotBlank() }
            ?: nodes.values.filter { it.click!=null && it.click!=title.click && it.text.isBlank() &&
                it.bounds.width()>0 && kotlin.math.abs(it.bounds.width()-it.bounds.height())<=2 &&
                it.bounds.right<=title.bounds.left && it.bounds.centerY() in title.bounds.top..title.bounds.bottom }
                .groupBy { it.click }.values.mapNotNull { group -> group.minByOrNull { it.bounds.width().toLong()*it.bounds.height() } }
                .singleOrNull() ?: return null
        fun button(node: Node,label: String=node.text.ifBlank { node.desc }): SpotifyHeaderBinding.Button? {
            val target=node.click ?: return null
            return SpotifyHeaderBinding.Button(label,node.checked,root,target,Rect(node.clickBounds ?: node.bounds),Rect(node.bounds),node.id)
        }
        val tools=nodes.values.filter { it.id==it.click &&
            it.bounds.centerY() in title.bounds.top..title.bounds.bottom && it.bounds.left>=title.bounds.right-2 }
            .sortedBy { it.bounds.left }.mapNotNull { button(it) }
        if(!chromeOnly && tools.size!=2)return null
        val groupDescription=string(root,"filter_row_content_description") ?: return null
        val group=nodes.values.singleOrNull { it.desc==groupDescription && it.desc.isNotBlank() }
        if(group==null && !chromeOnly)return null
        fun inGroup(node: Node): Boolean {
            var current: Node?=node;var depth=0
            while(current!=null && depth++<32) {
                if(current.id==group?.id)return true
                current=nodes[current.parent]
                if(current?.id==View.NO_ID)return false
            }
            return false
        }
        val clearDescription=string(root,"filter_row_library_clear_button_content_description")
        val clearIds=HashSet<Int>()
        val labelMap=filterLabels(root).toMutableMap().apply { if(clearDescription!=null)put(clearDescription,"×") }
        val virtualFilters=if(group==null)emptyList() else nodes.values.filter { it.click!=null && inGroup(it) }
            .groupBy { it.click }.values.mapNotNull { members ->
                val action=members.singleOrNull { it.id==it.click } ?: return@mapNotNull null
                val label=SpotifyLibraryFilterLabel.resolve(members.map { it.text },members.map { it.desc },labelMap)
                    ?: return@mapNotNull null
                if(clearDescription!=null && members.any { it.desc==clearDescription })clearIds+=action.id
                button(action,label)?.copy(selected=members.any { it.checked })
            }.sortedBy { it.bounds.left }
        val nativeFilters=ArrayList<SpotifyHeaderBinding.Button>()
        val tagId=resourceId(root,"id","library_filter_type_button")
        val clearId=resourceId(root,"id","library_filter_chip_clear_button")
        var visitedViews=0
        fun nativeChips(view: View) {
            if(visitedViews++>=256)return
            if(tagId!=0 && view.getTag(tagId)==true && view.isShown && view.isClickable) {
                val bounds=Rect();if(!view.getGlobalVisibleRect(bounds) || group?.bounds?.contains(bounds)!=true)return
                val labels=ArrayList<String>()
                fun text(v: View) {
                    if(v is android.widget.TextView && v.text.isNotBlank())labels+=v.text.toString()
                    if(v is ViewGroup)for(i in 0 until v.childCount)text(v.getChildAt(i))
                }
                text(view)
                val label=labels.distinct().singleOrNull() ?: view.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: return
                val info=view.createAccessibilityNodeInfo()
                val selected=try { info.isChecked || info.isSelected || view.isSelected || view.isActivated }
                    finally { @Suppress("DEPRECATION") info.recycle() }
                // Native chips use their actual View ID as the local action key;
                // no obfuscated controller or synthetic coordinate click.
                if(view.id==clearId)clearIds+=view.id
                nativeFilters+=SpotifyHeaderBinding.Button(label,selected,view,view.id,Rect(bounds),Rect(bounds),view.id,true)
                return
            }
            if(view is ViewGroup)for(i in 0 until view.childCount)nativeChips(view.getChildAt(i))
        }
        if(group!=null)nativeChips(root)
        val filters=(virtualFilters.filterNot { v -> nativeFilters.any { n -> n.label==v.label && n.bounds==v.bounds } }+nativeFilters)
            .sortedBy { it.bounds.left }
        // A temporarily empty filter row must not reject the verified header.
        // A short filtered LazyList can report isScrollable=false while it is
        // still the very same collection that needs scrolling-content insets.
        val feed=nodes.values.filter { (it.scroll || it.collection) && it.bounds.height()>title.bounds.height()*3 }.singleOrNull()
        fun under(id: Int,ancestor: Int): Boolean {
            var node=nodes[id];var depth=0
            while(node!=null && depth++<32) {
                if(node.id==ancestor)return true
                if(node.parent==node.id)return false
                node=nodes[node.parent]
            }
            return false
        }
        var header=nodes[title.parent];var depth=0
        while(header!=null && depth++<32 && !(under(face.id,header.id) && tools.all { under(it.virtualId,header.id) }))
            header=nodes[header.parent]
        val headerNode=header?.takeIf { it.id!=View.NO_ID && (feed==null || !under(feed.id,it.id)) } ?: return null
        val titleButton=button(title) ?: SpotifyHeaderBinding.Button(title.text,false,root,View.NO_ID,Rect(title.bounds),Rect(title.bounds),title.id)
        return Result(root,button(face) ?: return null,titleButton,tools,filters,headerNode.id,group?.id ?: headerNode.id,feed?.id ?: View.NO_ID,feed?.bounds?.let { Rect(it) } ?: Rect(),clearIds)
    }
}
