package io.github.offlineglass.hook.adapters.tiktok;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded DEX call-chain discovery; internal names are outputs, never lookup keys. */
public final class TikTokTextureContract {
    public record Binding(String factory, String configMethod, String config, String surfaceField) {}
    private static final String PLAYER="Lcom/ss/android/ugc/aweme/feed/adapter/playerview/FeedVideoPlayerView;";
    private record Ref(String owner,String name,String result,List<String> args) {}
    private record Field(String owner,String name,String type) {}
    private record Body(Ref ref,int code) {}

    public static Binding inspect(String[] apks) throws IOException {
        for(String apk:apks) try(ZipFile zip=new ZipFile(apk)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();
                if(!entry.getName().matches("classes[0-9]*\\.dex"))continue;
                if(entry.getSize()<112 || entry.getSize()>64L*1024*1024)throw new IOException("DEX size limit");
                try(InputStream in=zip.getInputStream(entry)) {
                    Dex dex=new Dex(in.readAllBytes());
                    List<Body> player=dex.methods(PLAYER);
                    if(player.isEmpty())continue;
                    Set<String> factories=new LinkedHashSet<>();
                    for(Body body:player) if(body.ref.name.equals("<init>"))
                        for(Ref call:dex.calls(body)) if(call.owner.equals(call.result)&&
                            call.args.size()==1&&!call.args.get(0).startsWith("Landroid/")) factories.add(call.owner);
                    List<Binding> found=new ArrayList<>();
                    for(String owner:factories) {
                        List<Body> methods=dex.methods(owner);
                        boolean alternate=methods.stream().anyMatch(m->m.ref.result.equals(owner)&&m.ref.args.equals(
                            List.of("Landroid/view/ViewGroup;","Z","Z")));
                        if(!alternate)continue;
                        for(Body method:methods) {
                            Ref ref=method.ref;
                            if(!ref.result.equals(owner)||ref.args.size()!=1)continue;
                            String config=ref.args.get(0);
                            Set<Field> fields=dex.selectionFields(method,config);
                            if(fields.size()==1)found.add(new Binding(owner,ref.name,config,fields.iterator().next().name));
                        }
                    }
                    return unique(found);
                }
            }
        }
        throw new IOException("Native player constructor not found");
    }
    static Binding unique(List<Binding> candidates)throws IOException {
        if(candidates.size()!=1)throw new IOException("Native texture contract ambiguous/missing: "+candidates.size());
        return candidates.get(0);
    }

    private static final class Dex {
        final byte[] b;
        final Map<Integer,String> strings=new HashMap<>();
        Dex(byte[] bytes)throws IOException {b=bytes;if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x'||u4(32)!=b.length)throw new IOException("DEX header");}
        int u2(int p){if(p<0||p+2>b.length)throw new IllegalArgumentException("DEX bounds");return(b[p]&255)|((b[p+1]&255)<<8);}
        int u4(int p){return u2(p)|(u2(p+2)<<16);}
        int leb(int[] p){int value=0;for(int i=0;i<5;i++){int v=b[p[0]++]&255;value|=(v&127)<<(7*i);if((v&128)==0)return value;}throw new IllegalArgumentException("ULEB");}
        String str(int id){return strings.computeIfAbsent(id,k->{int[] p={u4(u4(60)+k*4)};leb(p);int end=p[0];while(b[end]!=0)end++;return new String(b,p[0],end-p[0],StandardCharsets.UTF_8);});}
        String type(int id){return str(u4(u4(68)+id*4));}
        Ref ref(int id){int p=u4(92)+id*8,proto=u4(76)+u2(p+2)*12,params=u4(proto+8);List<String> args=new ArrayList<>();if(params!=0)for(int i=0;i<u4(params);i++)args.add(type(u2(params+4+i*2)));return new Ref(type(u2(p)),str(u4(p+4)),type(u4(proto+4)),args);}
        Field field(int id){int p=u4(84)+id*8;return new Field(type(u2(p)),str(u4(p+4)),type(u2(p+2)));}
        List<Body> methods(String owner){
            for(int i=0;i<u4(96);i++){
                int def=u4(100)+i*32;if(!type(u4(def)).equals(owner))continue;
                int data=u4(def+24);if(data==0)return List.of();
                int[] p={data};int s=leb(p),f=leb(p),d=leb(p),v=leb(p);
                for(int n=0;n<s+f;n++){leb(p);leb(p);}
                List<Body> result=new ArrayList<>();
                for(int count:new int[]{d,v}){int id=0;for(int n=0;n<count;n++){id+=leb(p);leb(p);int code=leb(p);result.add(new Body(ref(id),code));}}
                return result;
            }
            return List.of();
        }
        interface Visitor {void visit(int op,int address);}
        void instructions(Body body,Visitor visitor)throws IOException {
            if(body.code==0)return;
            int count=u4(body.code+12),start=body.code+16;
            if(count<0||(long)start+count*2L>b.length)throw new IOException("DEX code bounds");
            for(int pos=0;pos<count;){int address=start+pos*2,word=u2(address),op=word&255,w=width(op);
                if(op==0&&word!=0){if(word==0x100)w=4+u2(address+2)*2;else if(word==0x200)w=2+u2(address+2)*4;else if(word==0x300)w=4+(int)(((long)u2(address+2)*Integer.toUnsignedLong(u4(address+4))+1)/2);else throw new IOException("DEX payload");}
                if(w<1||pos+w>count)throw new IOException("DEX instruction");
                visitor.visit(op,address);pos+=w;
            }
        }
        List<Ref> calls(Body body)throws IOException {List<Ref> result=new ArrayList<>();instructions(body,(op,p)->{if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))result.add(ref(u2(p+2)));});return result;}
        Set<Field> selectionFields(Body body,String config)throws IOException {
            Set<Field> result=new LinkedHashSet<>();boolean[] selection={false};
            instructions(body,(op,p)->{
                if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    Ref call=ref(u2(p+2));
                    if(call.name.equals("isUseSurfaceView")&&call.args.isEmpty()&&call.result.equals("Z"))selection[0]=true;
                    if(call.name.equals("isUseSurfaceControl"))selection[0]=false;
                }
                if(selection[0]&&op==0x55){Field field=field(u2(p+2));if(field.owner.equals(config)&&field.type.equals("Z"))result.add(field);}
            });
            return result;
        }
        static int width(int op){
            if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
            if(op==0x03||op==0x06||op==0x09||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
            if(op==0x02||op==0x05||op==0x08||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;return 1;
        }
    }
}
