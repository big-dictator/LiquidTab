package io.github.offlineglass.hook.adapters.douyin;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
/** Finds the shared admission gate called by both native cold/hot splash entry points. */
public final class DouyinSplashIndex {
    static final String ENTRY="Lcom/bytedance/ies/ugc/aweme/commercialize/splash/service/SplashAdEntryServiceImpl;";
    record Ref(String owner,String name,String result,List<String> args) {}
    record Body(Ref ref,Set<String> strings,Set<Ref> calls) {}
    public record Binding(String owner,String method) {}
    public static Binding resolve(String path) throws Exception {
        List<Body> bodies=new ArrayList<>();
        try(ZipFile zip=new ZipFile(path)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var e=entries.nextElement();
                if(!e.getName().matches("classes[0-9]*\\.dex") || e.getSize()<112 || e.getSize()>64*1024*1024)continue;
                try(InputStream in=zip.getInputStream(e)){new Dex(in.readAllBytes()).scan(bodies);}
            }
        }
        return select(bodies);
    }
    static Binding select(List<Body> bodies) throws IOException {
        var cold=bodies.stream().filter(b->b.ref.result.equals("V") && b.ref.args.equals(List.of("Landroid/app/Activity;","I","Z","Z")) && b.strings.contains("entry")).toList();
        var hot=bodies.stream().filter(b->b.ref.result.equals("Z") && b.ref.args.equals(List.of("Landroidx/fragment/app/FragmentActivity;","Z"))).toList();
        if(cold.size()!=1 || hot.size()!=1)throw new IOException("Splash entry contract ambiguous/missing");
        var gates=cold.get(0).calls.stream().filter(r->hot.get(0).calls.contains(r) && r.result.equals("Z") && r.args.equals(List.of("I","Z","Z"))).toList();
        if(gates.size()!=1)throw new IOException("Splash admission call edge ambiguous/missing");
        Ref gate=gates.get(0);
        return new Binding(gate.owner.substring(1,gate.owner.length()-1).replace('/','.'),gate.name);
    }
    public static void main(String[] args) throws Exception {System.out.println(resolve(args[0]));}
    static final class Dex {
        final byte[] data; final ByteBuffer b; final int strings, types, protos, methods;
        final Map<Integer, String> cache = new HashMap<>();
        Dex(byte[] bytes) {
            data = bytes; b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x') throw new IllegalArgumentException("DEX header");
            strings = u4(60); types = u4(68); protos = u4(76); methods = u4(92);
        }
        int u4(int p) { int v = b.getInt(p); if (v < 0) throw new IllegalArgumentException("DEX unsigned offset"); return v; }
        int u2(int p) { return b.getShort(p) & 65535; }
        String str(int id) { return cache.computeIfAbsent(id, k -> { int p = u4(strings + k * 4); while ((data[p++] & 128) != 0) {} int start = p; while (data[p] != 0) p++; return new String(data, start, p - start, StandardCharsets.UTF_8); }); }
        String type(int id) { return str(u4(types + id * 4)); }
        Ref ref(int id) {
            int m = methods + id * 8, p = protos + u2(m + 2) * 12, a = u4(p + 8);
            List<String> args = new ArrayList<>();
            if (a != 0) for (int i = 0; i < u4(a); i++) args.add(type(u2(a + 4 + i * 2)));
            return new Ref(type(u2(m)), str(u4(m + 4)), type(u4(p + 4)), List.copyOf(args));
        }
        final class Cursor { int p; Cursor(int p) { this.p = p; } int leb() { int v = 0, s = 0, q; do { q = data[p++] & 255; v |= (q & 127) << s; s += 7; if (s > 35) throw new IllegalArgumentException("LEB"); } while ((q & 128) != 0); return v; } }
        void scan(List<Body> bodies) throws IOException {
            boolean relevant = false;
            for (int i = 0; i < u4(64); i++) if (type(i).equals(ENTRY)) { relevant = true; break; }
            if (!relevant) return;
            for (int i = 0; i < u4(96); i++) {
                int def = u4(100) + i * 32, start = u4(def + 24); if (start == 0) continue;
                // Stable package limits discovery; no simple class name chooses a hit.
                if (!type(u4(def)).equals(ENTRY)) continue;
                Cursor c = new Cursor(start); int sf = c.leb(), f = c.leb(), d = c.leb(), v = c.leb();
                for (int n : new int[]{sf, f}) for (int j = 0; j < n; j++) { c.leb(); c.leb(); }
                for (int n : new int[]{d, v}) { int id = 0; for (int j = 0; j < n; j++) {
                    id += c.leb(); c.leb(); int code = c.leb(); if (code == 0) continue;
                    Body body = body(ref(id), code);
                    bodies.add(body);
                } }
            }
        }
        Body body(Ref r, int code) throws IOException {
            Set<String> text = new HashSet<>(); Set<Ref> calls = new HashSet<>();
            int start = code + 16, count = u4(code + 12);
            if ((long) start + count * 2L > data.length) throw new IOException("DEX code bounds");
            for (int pos = 0; pos < count;) {
                int word = u2(start + pos * 2), op = word & 255, w = width(op);
                if (op == 0) {
                    if (word == 0x100) w = 4 + u2(start + (pos + 1) * 2) * 2;
                    else if (word == 0x200) w = 2 + u2(start + (pos + 1) * 2) * 4;
                    else if (word == 0x300) w = 4 + (int) (((long) u2(start + (pos + 1) * 2) * u4(start + (pos + 2) * 2) + 1) / 2);
                    else if (word != 0) throw new IOException("DEX payload");
                }
                if (w < 1 || pos + w > count) throw new IOException("DEX instruction bounds");
                if (op == 0x1a) text.add(str(u2(start + (pos + 1) * 2)));
                if (op == 0x1b) text.add(str(u4(start + (pos + 1) * 2)));
                if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) calls.add(ref(u2(start + (pos + 1) * 2)));
                pos += w;
            }
            return new Body(r, text, calls);
        }
        static int width(int op) {
            if (op == 0x18) return 5; if (op == 0xfa || op == 0xfb) return 4;
            if (op == 3 || op == 6 || op == 9 || op == 0x14 || op == 0x17 || op == 0x1b || op == 0x24 || op == 0x25 || op == 0x26 || op == 0x2a || op == 0x2b || op == 0x2c || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xfc || op == 0xfd) return 3;
            if (op == 2 || op == 5 || op == 8 || op == 0x13 || op == 0x15 || op == 0x16 || op == 0x19 || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20 || op == 0x22 || op == 0x23 || op == 0x29 || (op >= 0x2d && op <= 0x3d) || (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf) || (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) return 2;
            return 1;
        }
    }

}