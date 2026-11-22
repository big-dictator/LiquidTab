package io.github.offlineglass.hook.topbar;

import java.util.ArrayList;
import java.util.List;

/** Project native channel indices, without inventing an identity or changing native order. */
public final class HomeChannelProjection {
    private HomeChannelProjection() {}

    public static List<Integer> visible(int count, List<Integer> permanent, Integer selected) {
        List<Integer> result = new ArrayList<>(permanent.size() + 1);
        for (int nativeIndex = 0; nativeIndex < count; nativeIndex++) {
            if (permanent.contains(nativeIndex) || (selected != null && selected == nativeIndex))
                result.add(nativeIndex);
        }
        return result;
    }
}
