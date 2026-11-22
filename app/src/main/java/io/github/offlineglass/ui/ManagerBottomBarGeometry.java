package io.github.offlineglass.ui;

/** Manager binding of the shared bar's 4dp inset and parallel-expansion geometry.
 * No Compose constraints, app identity, or native state lives in this model. */
public final class ManagerBottomBarGeometry {
    public static float stretchX(float velocity) {
        return io.github.offlineglass.rendering.BottomBarDynamics.stretchX(velocity);
    }
    public static float stretchY(float velocity) {
        return io.github.offlineglass.rendering.BottomBarDynamics.stretchY(velocity);
    }
    public static float refractionHeight(float height, float density) {
        return Math.max(.5f, Math.min(24f * density, height * .5f - density));
    }
    public final float slotWidth, lensWidth, lensHeight, left, top, contentScale;
    public ManagerBottomBarGeometry(float width, float height, float density,
            int tabs, float position, float verticalScale, float fontScale) {
        float inset = 4f * density;
        slotWidth = Math.max(1f, width - 2f * inset) / Math.max(1, tabs);
        float innerHeight = Math.max(1f, height - 2f * inset);
        float outset = io.github.offlineglass.rendering.BottomBarDynamics.indicatorOutset(innerHeight, verticalScale);
        lensWidth = slotWidth + 2f * outset;
        lensHeight = innerHeight + 2f * outset;
        left = inset + Math.max(0f, Math.min(tabs - 1f, position)) * slotWidth - outset;
        top = (height - lensHeight) * .5f;
        // Icon 28dp + 1dp gap + 12sp line at the 56dp reference. Fit large-font
        // configurations as a group instead of letting text escape the capsule.
        contentScale = Math.max(0f, Math.min(height / (56f * density),
                innerHeight / (density * (28f + 1f + 12f * Math.max(1f, fontScale)))));
    }
}
