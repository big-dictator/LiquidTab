package io.github.offlineglass.hook.adapters.spotify;

/** Missing lazy-row children are not a native deselection signal. */
public final class SpotifyFilterWidth {
    public static int resolve(boolean expandable,boolean selected,Boolean intent,int collapsed,
            Integer materialized,Integer retained) {
        boolean expanded=expandable && (intent!=null ? intent : selected);
        if(!expanded)return collapsed;
        if(materialized!=null)return materialized;
        return retained!=null ? retained : collapsed;
    }
    private SpotifyFilterWidth() { }
}
