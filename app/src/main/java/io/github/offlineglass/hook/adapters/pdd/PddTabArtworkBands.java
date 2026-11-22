package io.github.offlineglass.hook.adapters.pdd;

/** Identifies a single compact caption below independently visible artwork. */
public final class PddTabArtworkBands {
    private PddTabArtworkBands() {}

    public static int iconEnd(int[] pixels, int width, int height, float density) {
        if (width <= 0 || height <= 0 || (long) width * height > pixels.length || !(density > 0)) return -1;
        final int minGap = Math.max(2, Math.round(density));
        int first = -1, last = -1, iconEnd = -1, captionStart = -1;
        for (int y = 0; y < height; y++) {
            boolean occupied = false;
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) > 24) { occupied = true; break; }
            }
            if (!occupied) continue;
            if (first < 0) first = y;
            else if (y - last - 1 >= minGap) {
                if (iconEnd >= 0) return -1;
                iconEnd = last + 1;
                captionStart = y;
            }
            last = y;
        }
        if (iconEnd < 0) return -1;
        final int captionHeight = last + 1 - captionStart;
        final int iconHeight = iconEnd - first;
        return captionHeight >= 4 * density && captionHeight <= 16 * density
                && iconHeight > captionHeight && iconHeight >= 12 * density
                && captionStart - iconEnd <= 12 * density ? iconEnd : -1;
    }
}
