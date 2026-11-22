package io.github.offlineglass.hook.adapters.meituan_main;

/** Page provenance for optical input, independent of slider animation or node identity. */
public final class MeituanMainBackdropState {
    private int page = -1;
    private boolean pending = true;
    public boolean select(int committedPage) {
        if (committedPage < 0 || committedPage == page) return false;
        page = committedPage;
        pending = true;
        return true;
    }
    public boolean needsSample(boolean nativeContentDirty) { return pending || nativeContentDirty; }
    public void recorded() { pending = false; }
    public void reset() { page = -1; pending = true; }
}
