package io.github.offlineglass.hook.adapters.youtube;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/** Resolves the native long-hold haptics role from its diagnostic anchor and
 * actual constructor references. No version table or obfuscated name gate. */
final class YouTubeHapticsBinding {
    record Binding(String controller, String callback) {}

    static List<Binding> resolve(String apk) throws Exception {
        Set<Binding> found = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex") ||
                        entry.getSize() < 112 || entry.getSize() > 33554432) continue;
                byte[] bytes;
                try (var stream = zip.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                found.addAll(new Dex(bytes).bindings());
                if (!found.isEmpty()) break;
            }
        }
        return new ArrayList<>(found);
    }

    private static final class Dex {
        private static final String ANCHOR = "com/google/android/apps/youtube/app/player/overlay/EasySeekHapticsController";
        private final byte[] data;
        private final ByteBuffer buffer;
        private final int strings, types, methods, protos;
        Dex(byte[] data) {
            this.data = data;
            buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            strings = u32(60); types = u32(68); methods = u32(92); protos = u32(76);
        }
        int u32(int offset) { int value = buffer.getInt(offset); if (value < 0) throw new IllegalArgumentException("DEX offset"); return value; }
        int u16(int offset) { return buffer.getShort(offset) & 65535; }
        String string(int index) {
            int p = u32(strings + index * 4);
            while ((data[p++] & 128) != 0) {}
            int start = p;
            while (data[p] != 0) p++;
            return new String(data, start, p - start, StandardCharsets.UTF_8);
        }
        String type(int index) { return string(u32(types + index * 4)); }
        String name(int method) { return string(u32(methods + method * 8 + 4)); }
        int proto(int method) { return protos + u16(methods + method * 8 + 2) * 12; }
        boolean noArgsVoid(int method) {
            int proto = proto(method);
            return type(u32(proto + 4)).equals("V") && u32(proto + 8) == 0;
        }
        boolean constructor(int method) {
            if (!name(method).equals("<init>")) return false;
            int p = u32(proto(method) + 8);
            return p != 0 && u32(p) == 2 && type(u16(p + 4)).equals("Landroid/content/Context;") &&
                    type(u16(p + 6)).equals("Landroid/os/Handler;");
        }
        List<int[]> codeMethods(int definition) {
            int start = u32(definition + 24);
            if (start == 0) return List.of();
            Cursor cursor = new Cursor(start);
            int fields = cursor.leb() + cursor.leb();
            int direct = cursor.leb(), virtual = cursor.leb();
            for (int i = 0; i < fields; i++) { cursor.leb(); cursor.leb(); }
            List<int[]> result = new ArrayList<>();
            for (int count : new int[]{direct, virtual}) {
                int index = 0;
                for (int i = 0; i < count; i++) {
                    index += cursor.leb(); cursor.leb();
                    int code = cursor.leb();
                    if (code != 0) result.add(new int[]{index, code});
                }
            }
            return result;
        }
        final class Cursor {
            int p;
            Cursor(int p) { this.p = p; }
            int leb() {
                int value = 0, shift = 0, next;
                do {
                    next = data[p++] & 255;
                    value |= (next & 127) << shift;
                    shift += 7;
                    if (shift > 35) throw new IllegalArgumentException("DEX leb");
                } while ((next & 128) != 0);
                return value;
            }
        }
        List<Binding> bindings() {
            int anchor = -1, low = 0, high = u32(56) - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int order = string(mid).compareTo(ANCHOR);
                if (order == 0) { anchor = mid; break; }
                if (order < 0) low = mid + 1; else high = mid - 1;
            }
            if (anchor < 0) return List.of();
            List<Binding> result = new ArrayList<>();
            Set<String> controllers = new LinkedHashSet<>();
            int definitions = u32(100), count = u32(96);
            for (int i = 0; i < u32(88); i++) {
                if (constructor(i)) controllers.add(type(u16(methods + i * 8)));
            }
            for (int i = 0; i < count; i++) {
                int definition = definitions + i * 32;
                for (int[] method : codeMethods(definition)) {
                    if (!name(method[0]).equals("run") || !noArgsVoid(method[0])) continue;
                    int start = method[1] + 16, words = u32(method[1] + 12);
                    boolean marked = false;
                    Set<String> casts = new LinkedHashSet<>();
                    // Candidate instructions are validated against native class,
                    // constructor, callback and instance capabilities before use.
                    for (int j = 0; j + 1 < words; j++) {
                        int op = u16(start + j * 2) & 255;
                        int index = u16(start + (j + 1) * 2);
                        if (op == 0x1a && index == anchor) marked = true;
                        if (op == 0x1b && j + 2 < words && buffer.getInt(start + (j + 1) * 2) == anchor) marked = true;
                        if (op == 0x1f && index < u32(64)) {
                            String cast = type(index);
                            if (controllers.contains(cast)) casts.add(cast);
                        }
                    }
                    if (marked) for (String cast : casts) {
                        result.add(new Binding(javaName(cast), javaName(type(u32(definition)))));
                    }
                }
            }
            return result;
        }
        String javaName(String descriptor) { return descriptor.substring(1, descriptor.length() - 1).replace('/', '.'); }
    }
}
