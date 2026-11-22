package io.github.offlineglass.hook.adapters.spotify

import android.view.View
import java.lang.reflect.Modifier

/** Native public owner contract, shared by avatar decoding and toolbar masking. */
internal object SpotifyComposeNodes {
    var failure="not probed";private set
    fun snapshot(owner: View): SpotifyComposeIndex.Snapshot? = runCatching {
        val root=owner.javaClass.getMethod("getRoot").invoke(owner) ?: return null
        val semantics=owner.javaClass.getMethod("getSemanticsOwner").invoke(owner) ?: return null
        // The owner passes this very table into SemanticsOwner and registers
        // LayoutNode.semanticId -> LayoutNode in it (verified in the current APK).
        val map=owner.javaClass.getMethod("getLayoutNodes").invoke(owner) ?: return null
        val linked=semantics.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }
            .any { it.get(semantics)===map }
        if(!linked) { failure="owner table not shared with SemanticsOwner";return null }
        SpotifyComposeIndex.read(root,map).also {
            failure=if(it==null)"ID table/root/node semantic-ID contract ambiguous or not ready" else "verified"
        }
    }.onFailure { failure="owner contract: ${it.javaClass.simpleName}: ${it.message}" }.getOrNull()
}
