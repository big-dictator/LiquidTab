package io.github.offlineglass.hook.adapters.bilibili_ambient;

/** Two bounded sliding-window box passes, on the tiny sample only. */
public final class BilibiliAmbientBlur {
    private BilibiliAmbientBlur() {}
    public static void blur(int[] pixels, int[] scratch, int width, int height, int radius) {
        if (width < 1 || height < 1 || pixels.length < width * height || scratch.length < width * height || radius < 1) throw new IllegalArgumentException();
        for (int repeat = 0; repeat < 2; repeat++) {
            pass(pixels, scratch, width, height, radius, false);
            pass(scratch, pixels, width, height, radius, true);
        }
    }
    private static void pass(int[] input, int[] output, int width, int height, int r, boolean vertical) {
        int length = vertical ? height : width, lines = vertical ? width : height, count = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int red = 0, green = 0, blue = 0;
            for (int k = -r; k <= r; k++) {
                int p = input[index(line, clamp(k, length), width, vertical)];
                red += (p >>> 16) & 255; green += (p >>> 8) & 255; blue += p & 255;
            }
            for (int x = 0; x < length; x++) {
                output[index(line, x, width, vertical)] = 0xff000000 | (red / count << 16) | (green / count << 8) | blue / count;
                int left = input[index(line, clamp(x - r, length), width, vertical)];
                int right = input[index(line, clamp(x + r + 1, length), width, vertical)];
                red += ((right >>> 16) & 255) - ((left >>> 16) & 255);
                green += ((right >>> 8) & 255) - ((left >>> 8) & 255);
                blue += (right & 255) - (left & 255);
            }
        }
    }
    private static int clamp(int x, int size) { return Math.max(0, Math.min(size - 1, x)); }
    private static int index(int line, int x, int width, boolean vertical) { return vertical ? x * width + line : line * width + x; }
}
