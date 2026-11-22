package io.github.offlineglass.hook.adapters.wechat;

/** Native list onScroll writes a positive translation only during its top pull. */
public final class WeChatTopPullMaterialState {
    private boolean hidden;
    public boolean update(boolean launcher, float nativeTranslationY) {
        boolean next = launcher && nativeTranslationY != 0f;
        boolean changed = hidden != next;
        hidden = next;
        return changed;
    }
    public boolean isHidden() { return hidden; }
}
