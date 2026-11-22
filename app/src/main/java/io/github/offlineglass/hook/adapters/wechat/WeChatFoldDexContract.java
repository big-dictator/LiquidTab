package io.github.offlineglass.hook.adapters.wechat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Resolve the live helper's native toggle and the preference factory it uses. */
final class WeChatFoldDexContract {
    static final String STORE = "message_fold_config2";
    static final String STATE = "fold_button_status_int_sync";
    record Binding(String toggle, String storeClass, String storeFactory) {}

    static Binding resolve(String apk, String helper) throws IOException {
        String descriptor = "L" + helper.replace('.', '/') + ";";
        List<Binding> matches = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex") || entry.getSize() < 112 ||
                        entry.getSize() > 64L * 1024 * 1024) continue;
                try (var input = zip.getInputStream(entry)) {
                    var dex = new Dex(input.readAllBytes());
                    if (dex.scan(descriptor, matches)) break;
                }
            }
        }
        if (matches.size() != 1) throw new IOException("Native fold contract candidates=" + matches.size());
        return matches.get(0);
    }

    private static final class Dex {
        final byte[] bytes;
        final ByteBuffer data;
        final int strings, types, protos, methods;

        Dex(byte[] bytes) {
            this.bytes = bytes;
            data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            strings = word(60);
            types = word(68);
            protos = word(76);
            methods = word(92);
        }

        int word(int at) { return data.getInt(at); }
        int unit(int at) { return Short.toUnsignedInt(data.getShort(at)); }
        String type(int id) { return string(word(types + id * 4)); }
        String string(int id) {
            int start = word(strings + id * 4);
            while ((bytes[start++] & 128) != 0) {}
            int end = start;
            while (bytes[end] != 0) end++;
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        }

        int proto(int method) { return protos + unit(method + 2) * 12; }
        String result(int method) { return type(word(proto(method) + 4)); }
        String name(int method) { return string(word(method + 4)); }

        boolean scan(String helper, List<Binding> matches) throws IOException {
            int definitions = word(100);
            for (int index = 0; index < word(96); index++) {
                int definition = definitions + index * 32;
                if (!helper.equals(type(word(definition)))) continue;
                int body = word(definition + 24);
                if (body == 0) return true;
                Cursor c = new Cursor(body);
                int fields = c.leb() + c.leb();
                int direct = c.leb(), virtual = c.leb();
                for (int i = 0; i < fields; i++) { c.leb(); c.leb(); }
                for (int count : new int[]{direct, virtual}) {
                    int id = 0;
                    for (int i = 0; i < count; i++) {
                        id += c.leb();
                        int flags = c.leb(), code = c.leb();
                        int method = methods + id * 8;
                        if (code != 0 && (flags & 8) == 0 && word(proto(method) + 8) == 0 &&
                                result(method).equals("V")) {
                            Binding binding = inspect(method, code);
                            if (binding != null) matches.add(binding);
                        }
                    }
                }
                return true;
            }
            return false;
        }

        Binding inspect(int method, int code) throws IOException {
            boolean toggle = false, store = false, state = false;
            var factories = new java.util.LinkedHashSet<Binding>();
            int start = code + 16, size = word(code + 12);
            for (int i = 0; i < size;) {
                int instruction = unit(start + i * 2), op = instruction & 255;
                int width = instructionWidth(op);
                int argument = start + (i + 1) * 2;
                if (instruction == 0x100) width = 4 + unit(argument) * 2;
                if (instruction == 0x200) width = 2 + unit(argument) * 4;
                if (instruction == 0x300) width = 4 + (int) (((long) unit(argument) * word(argument + 2) + 1) / 2);
                if (i + width > size) throw new IOException("Native fold instruction bounds");
                if (op == 0x1a || op == 0x1b) {
                    String value = string(op == 0x1a ? unit(argument) : word(argument));
                    toggle |= value.startsWith("performFoldItemClick");
                    store |= STORE.equals(value);
                    state |= STATE.equals(value);
                }
                if (op == 0x71 || op == 0x77) {
                    int call = methods + unit(argument) * 8;
                    int args = word(proto(call) + 8);
                    String owner = type(unit(call));
                    if (args != 0 && word(args) == 1 &&
                            type(unit(args + 4)).equals("Ljava/lang/String;") && owner.equals(result(call))) {
                        factories.add(new Binding(name(method),
                                owner.substring(1, owner.length() - 1).replace('/', '.'), name(call)));
                    }
                }
                i += width;
            }
            return toggle && store && state && factories.size() == 1 ? factories.iterator().next() : null;
        }

        private final class Cursor {
            int position;
            Cursor(int position) { this.position = position; }
            int leb() throws IOException {
                int value = 0;
                for (int shift = 0; shift < 35; shift += 7) {
                    int part = Byte.toUnsignedInt(bytes[position++]);
                    value |= (part & 127) << shift;
                    if ((part & 128) == 0) return value;
                }
                throw new IOException("Native fold DEX ULEB");
            }
        }

        private static int instructionWidth(int op) {
            if (op == 0x18) return 5;
            if (op == 0xfa || op == 0xfb) return 4;
            if (op == 3 || op == 6 || op == 9 || op == 0x14 || op == 0x17 || op == 0x1b ||
                    (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c) ||
                    (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) ||
                    op == 0xfc || op == 0xfd) return 3;
            if (op == 2 || op == 5 || op == 8 || op == 0x13 || op == 0x15 || op == 0x16 ||
                    (op >= 0x19 && op <= 0x1a) || op == 0x1c || op == 0x1f || op == 0x20 ||
                    op == 0x22 || op == 0x23 || op == 0x29 || (op >= 0x2d && op <= 0x3d) ||
                    (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf) ||
                    (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) return 2;
            return 1;
        }
    }
}
