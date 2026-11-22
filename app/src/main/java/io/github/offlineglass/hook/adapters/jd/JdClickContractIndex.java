package io.github.offlineglass.hook.adapters.jd;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Resolves the native navigation cooldown from behavior, never short symbol names. */
public final class JdClickContractIndex {
    public record Binding(String owner, String method, String timestamp) {}
    record Field(String owner, String name, String type) {}
    record Method(String owner, String name, String result, int arguments) {}
    record Probe(Method method, boolean interval, boolean clock, boolean message,
                 Set<Field> reads, Set<Field> writes) {}
    static Binding choose(List<Probe> probes) throws IOException {
        List<Binding> matches = new ArrayList<>();
        for (Probe p : probes) {
            if (!p.interval || !p.clock || !p.message || p.method.arguments != 0 || !p.method.result.equals("Z")) continue;
            Set<Field> stamps = new HashSet<>(p.reads); stamps.retainAll(p.writes);
            stamps.removeIf(f -> !f.type.equals("J") || !f.owner.equals(p.method.owner));
            if (stamps.size() == 1) matches.add(new Binding(javaName(p.method.owner), p.method.name, stamps.iterator().next().name));
        }
        if (matches.size() != 1) throw new IOException("Navigation cooldown contract candidates=" + matches.size());
        return matches.get(0);
    }
    static String javaName(String type) { return type.substring(1,type.length()-1).replace('/','.'); }
    public static Binding resolveCached(String path, String cachePath) throws Exception {
        File apk = new File(path), cache = new File(cachePath);
        String identity = "1:"+apk.getAbsolutePath()+":"+apk.length()+":"+apk.lastModified();
        if (cache.isFile()) try (InputStream in = new FileInputStream(cache)) {
            Properties p = new Properties(); p.load(in);
            if (identity.equals(p.getProperty("identity")) && p.getProperty("owner") != null &&
                p.getProperty("method") != null && p.getProperty("timestamp") != null)
                return new Binding(p.getProperty("owner"),p.getProperty("method"),p.getProperty("timestamp"));
        } catch (IOException ignored) {}
        Binding binding = resolve(path);
        Properties p = new Properties(); p.setProperty("identity",identity); p.setProperty("owner",binding.owner);
        p.setProperty("method",binding.method); p.setProperty("timestamp",binding.timestamp);
        try (OutputStream out = new FileOutputStream(cache)) { p.store(out,"APK-scoped navigation capability"); }
        catch (IOException ignored) {}
        return binding;
    }
    public static Binding resolve(String path) throws Exception {
        List<Probe> probes = new ArrayList<>();
        try (ZipFile apk = new ZipFile(path)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex") || entry.getSize()<112 || entry.getSize()>64L*1024*1024) continue;
                try (InputStream in = apk.getInputStream(entry)) { new Dex(in.readAllBytes()).scan(probes); }
            }
        }
        return choose(probes);
    }
    static final class Dex {
        final byte[] data; final ByteBuffer b; final int strings,types,protos,fields,methods;
        final Map<Integer,String> text = new HashMap<>();
        Dex(byte[] bytes) {
            data=bytes; b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(bytes.length<112 || bytes[0]!='d'||bytes[1]!='e'||bytes[2]!='x') throw new IllegalArgumentException("DEX magic");
            strings=u4(60);types=u4(68);protos=u4(76);fields=u4(84);methods=u4(92);
        }
        int u4(int p) { int n=b.getInt(p); if(n<0)throw new IllegalArgumentException("DEX offset");return n; }
        int u2(int p) { return b.getShort(p)&65535; }
        String str(int id) { return text.computeIfAbsent(id,k->{int p=u4(strings+k*4);while((data[p++]&128)!=0){}int start=p;while(data[p]!=0)p++;return new String(data,start,p-start,StandardCharsets.UTF_8);}); }
        String type(int id) { return str(u4(types+id*4)); }
        Field field(int id) { int p=fields+id*8;return new Field(type(u2(p)),str(u4(p+4)),type(u2(p+2))); }
        Method method(int id) { int p=methods+id*8,proto=protos+u2(p+2)*12,args=u4(proto+8);return new Method(type(u2(p)),str(u4(p+4)),type(u4(proto+4)),args==0?0:u4(args)); }
        final class Cursor { int p; Cursor(int at){p=at;} int leb(){int n=0,s=0,v;do{v=data[p++]&255;n|=(v&127)<<s;s+=7;if(s>35)throw new IllegalArgumentException("LEB");}while((v&128)!=0);return n;} }
        void scan(List<Probe> out) throws IOException {
            boolean relevant=false;
            for(int i=0;i<u4(80);i++){Field f=field(i);if(isInterval(f)){relevant=true;break;}}
            if(!relevant)return;
            for(int i=0;i<u4(96);i++) {
                int def=u4(100)+i*32;String owner=type(u4(def));
                if(!owner.startsWith("Lcom/jingdong/app/mall/navigationbar/refactor/util/"))continue;
                int at=u4(def+24);if(at==0)continue;Cursor c=new Cursor(at);
                int sf=c.leb(),inf=c.leb(),direct=c.leb(),virtual=c.leb();
                for(int n:new int[]{sf,inf})for(int j=0;j<n;j++){c.leb();c.leb();}
                for(int n:new int[]{direct,virtual}){int id=0;for(int j=0;j<n;j++){
                    id+=c.leb();int flags=c.leb(),code=c.leb();Method m=method(id);
                    if(code!=0 && (flags&8)!=0 && m.arguments==0 && m.result.equals("Z"))out.add(body(m,code));
                }}
            }
        }
        boolean isInterval(Field f) { return f.owner.equals("Lcom/jingdong/app/mall/navigationbar/refactor/util/NavConfigUtil;") && f.name.equals("sNavClickCD") && f.type.equals("I"); }
        Probe body(Method m,int code)throws IOException {
            boolean interval=false,clock=false,message=false;Set<Field> reads=new HashSet<>(),writes=new HashSet<>();
            int start=code+16,size=u4(code+12);if((long)start+size*2L>data.length)throw new IOException("DEX body bounds");
            for(int pos=0;pos<size;){int word=u2(start+pos*2),op=word&255,w=width(op);
                if(op==0){if(word==0x100)w=4+u2(start+(pos+1)*2)*2;else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*u4(start+(pos+2)*2)+1)/2);else if(word!=0)throw new IOException("DEX payload");}
                if(w<1||pos+w>size)throw new IOException("DEX instruction width");
                if(op==0x60)interval|=isInterval(field(u2(start+(pos+1)*2)));
                if(op==0x61)reads.add(field(u2(start+(pos+1)*2)));
                if(op==0x68)writes.add(field(u2(start+(pos+1)*2)));
                if(op==0x1a||op==0x1b)message|=str(op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2)).startsWith("click cooldown, interval=");
                if(op==0x71||op==0x77){Method call=method(u2(start+(pos+1)*2));clock|=call.owner.equals("Landroid/os/SystemClock;")&&call.name.equals("elapsedRealtime")&&call.result.equals("J")&&call.arguments==0;}
                pos+=w;
            }
            return new Probe(m,interval,clock,message,reads,writes);
        }
        static int width(int op) {
            if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
            if(op==3||op==6||op==9||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
            if(op==2||op==5||op==8||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;return 1;
        }
    }
    public static void main(String[] args) throws Exception { System.out.println(resolve(args[0])); }
}
