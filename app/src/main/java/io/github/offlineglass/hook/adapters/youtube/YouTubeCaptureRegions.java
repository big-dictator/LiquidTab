package io.github.offlineglass.hook.adapters.youtube;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Decoder-local pixel bounds. Never splits a consumer's blur/refraction apron. */
final class YouTubeCaptureRegions {
    static final class Region {
        final int left, top, right, bottom;
        Region(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        long area() { return (long) (right - left) * (bottom - top); }
        Region union(Region other) {
            return new Region(Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }
    }

    static List<Region> plan(List<Region> input) {
        if (input.isEmpty()) return List.of();
        List<Region> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(region -> region.top));
        int count = sorted.size();
        Region[] suffix = new Region[count];
        suffix[count - 1] = sorted.get(count - 1);
        for (int i = count - 2; i >= 0; i--) suffix[i] = sorted.get(i).union(suffix[i + 1]);
        Region whole = suffix[0], prefix = sorted.get(0), bestTop = null, bestBottom = null;
        long bestArea = whole.area();
        for (int i = 1; i < count; i++) {
            Region bottom = suffix[i];
            long area = prefix.area() + bottom.area();
            if (prefix.bottom < bottom.top && area < bestArea) {
                bestArea = area; bestTop = prefix; bestBottom = bottom;
            }
            prefix = prefix.union(sorted.get(i));
        }
        // A second compositor transaction is worthwhile only for a large gap.
        // Nearby controls and expanded menus retain the single-capture path.
        if (bestTop != null && bestArea * 100 <= whole.area() * 65) {
            return List.of(bestTop, bestBottom);
        }
        return List.of(whole);
    }
}
