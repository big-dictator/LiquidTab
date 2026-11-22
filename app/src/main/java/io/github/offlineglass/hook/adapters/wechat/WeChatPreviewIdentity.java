package io.github.offlineglass.hook.adapters.wechat;

/** Local-image identity: screen origin and temporary material colors are not image content. */
public final class WeChatPreviewIdentity {
    public static String of(String accountDigest, String apk, int width, int height,
                            int leftPadding, int topPadding, int rightPadding, int bottomPadding,
                            int uiMode, int density, int fontBits, String locales, boolean english) {
        return "wx-preview-identity-v2|"+accountDigest+"|"+apk+"|"+width+"|"+height+"|"+
            leftPadding+"|"+topPadding+"|"+rightPadding+"|"+bottomPadding+"|"+uiMode+"|"+
            density+"|"+fontBits+"|"+locales+"|"+english;
    }
    public static boolean captureBusy(boolean visualPending, boolean animating, long now,
                                      long scrollUntil, long pagerStillSince) {
        return visualPending || animating || now < scrollUntil || now-pagerStillSince < 250L;
    }
}
