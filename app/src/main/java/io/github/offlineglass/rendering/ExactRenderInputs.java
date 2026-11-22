package io.github.offlineglass.rendering;

import java.util.Arrays;

/** Per-owner, single-entry input key. No pixel cache, rounding, hashing or per-frame allocation.
 * Stage every slot before matches(), then commit only after native work succeeds.
 * The identity is the actual source/shader, not an app name or a geometry hash.
 */
public final class ExactRenderInputs {
    private final int[] staged;
    private final int[] applied;
    private Object appliedIdentity;
    private boolean valid;

    public ExactRenderInputs(int count) {
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        staged = new int[count];
        applied = new int[count];
    }

    public void set(int index, float value) {
        staged[index] = Float.floatToRawIntBits(value);
    }

    public boolean matches(Object identity) {
        return valid && appliedIdentity == identity && Arrays.equals(staged, applied);
    }

    public void commit(Object identity) {
        System.arraycopy(staged, 0, applied, 0, staged.length);
        appliedIdentity = identity;
        valid = true;
    }

    public void clear() {
        valid = false;
        appliedIdentity = null;
    }
}
