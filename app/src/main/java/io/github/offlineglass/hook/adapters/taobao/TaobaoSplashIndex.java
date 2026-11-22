package io.github.offlineglass.hook.adapters.taobao;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** One APK scan; resolves semantic splash diagnostics and their actual call edges. */
public final class TaobaoSplashIndex {
 public record Binding(String visual,String launcher,String showing,String close,String delay,String dataFlow,String dataList){}
 record Ref(String owner,String name,String result,List<String> args){}
 record Body(Ref ref,Set<String> strings,Set<Ref> calls){}
 public static Binding resolveCached(String path,String cachePath)throws Exception{
  File apk=new File(path),cache=new File(cachePath);String identity="2:"+apk.getAbsolutePath()+":"+apk.length()+":"+apk.lastModified();
  if(cache.isFile())try(InputStream in=new FileInputStream(cache)){Properties p=new Properties();p.load(in);if(identity.equals(p.getProperty("identity")))return new Binding(p.getProperty("visual"),p.getProperty("launcher"),p.getProperty("showing"),p.getProperty("close"),p.getProperty("delay"),p.getProperty("dataFlow"),p.getProperty("dataList"));}catch(Exception ignored){}
  Binding b=resolve(path);Properties p=new Properties();p.setProperty("identity",identity);p.setProperty("visual",b.visual);p.setProperty("launcher",b.launcher);p.setProperty("showing",b.showing);p.setProperty("close",b.close);p.setProperty("delay",b.delay);p.setProperty("dataFlow",b.dataFlow);p.setProperty("dataList",b.dataList);
  File parent=cache.getParentFile();if(parent.isDirectory()||parent.mkdirs())try(OutputStream out=new FileOutputStream(cache)){p.store(out,"APK-identity scoped semantic discovery");}catch(IOException ignored){}return b;
 }
 public static Binding resolve(String path)throws Exception{
  Set<String> visuals=new HashSet<>();List<Body> bodies=new ArrayList<>();
  try(ZipFile zip=new ZipFile(path)){var entries=zip.entries();while(entries.hasMoreElements()){
   var e=entries.nextElement();if(!e.getName().matches("classes[0-9]*\\.dex")||e.getSize()<112||e.getSize()>64*1024*1024)continue;
   try(InputStream in=zip.getInputStream(e)){byte[] bytes=new byte[(int)e.getSize()];new DataInputStream(in).readFully(bytes);new Dex(bytes).scan(visuals,bodies);}
  }}
  Set<Ref> dataLists=new LinkedHashSet<>();
  for(Body flow:bodies)if(flow.strings.contains("BootImageBizDataFlow")&&flow.strings.contains("getBootImage businessCode:"))
   for(Body getter:bodies)if(getter.ref.owner.equals(flow.ref.owner)&&getter.ref.args.equals(List.of("Ljava/lang/String;"))&&getter.ref.result.equals("Ljava/util/List;"))dataLists.add(getter.ref);
  if(dataLists.size()!=1)throw new IOException("Splash data selection candidates="+dataLists.size());
  Ref dataList=dataLists.iterator().next();
  List<Binding> found=new ArrayList<>();
  for(Body showing:bodies)if(showing.strings.contains("launcher onShowing")&&showing.ref.args.equals(List.of("Ljava/lang/String;"))&&showing.ref.result.equals("V"))
   for(Body close:bodies)if(close.ref.owner.equals(showing.ref.owner)&&showing.calls.contains(close.ref)&&close.strings.contains("triggerTerminate closeType:")&&close.ref.args.equals(List.of("I"))&&close.ref.result.equals("V"))
    for(Body delay:bodies)if(delay.ref.owner.equals(close.ref.owner)&&close.calls.contains(delay.ref)&&delay.ref.args.isEmpty()&&delay.ref.result.equals("J")&&delay.strings.contains("triggerTerminate showTime "))
     for(String visual:visuals)found.add(new Binding(javaName(visual),javaName(close.ref.owner),showing.ref.name,close.ref.name,delay.ref.name,javaName(dataList.owner),dataList.name));
  if(found.size()!=1)throw new IOException("Splash semantic contract candidates="+found.size());return found.get(0);
 }
 static String javaName(String s){return s.substring(1,s.length()-1).replace('/','.');}
 static final class Dex{
  final byte[] data;final ByteBuffer b;final int strings,types,protos,methods;final Map<Integer,String> cache=new HashMap<>();
  Dex(byte[] bytes){data=bytes;b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(bytes[0]!='d'||bytes[1]!='e'||bytes[2]!='x')throw new IllegalArgumentException("DEX magic");strings=u4(60);types=u4(68);protos=u4(76);methods=u4(92);}
  int u4(int p){int v=b.getInt(p);if(v<0)throw new IllegalArgumentException("DEX unsigned offset");return v;}int u2(int p){return b.getShort(p)&65535;}
  String str(int id){return cache.computeIfAbsent(id,k->{int p=u4(strings+k*4);while((data[p++]&128)!=0){}int start=p;while(data[p]!=0)p++;return new String(data,start,p-start,StandardCharsets.UTF_8);});}
  // Discovery literals are ASCII. Comparing their encoded string-table bytes
  // avoids interning/allocating every unrelated APK string on the main thread.
  boolean matchesAscii(int id,String literal){int p=u4(strings+id*4);while((data[p++]&128)!=0){}if((long)p+literal.length()>=data.length)return false;for(int i=0;i<literal.length();i++)if((data[p+i]&255)!=literal.charAt(i))return false;return data[p+literal.length()]==0;}
  String type(int id){return str(u4(types+id*4));}
  Ref ref(int id){int m=methods+id*8,p=protos+u2(m+2)*12,a=u4(p+8);List<String> args=new ArrayList<>();if(a!=0)for(int i=0;i<u4(a);i++)args.add(type(u2(a+4+i*2)));return new Ref(type(u2(m)),str(u4(m+4)),type(u4(p+4)),List.copyOf(args));}
  final class Cursor{int p;Cursor(int p){this.p=p;}int leb(){int value=0,s=0,q;do{q=data[p++]&255;value|=(q&127)<<s;s+=7;if(s>35)throw new IllegalArgumentException("LEB");}while((q&128)!=0);return value;}}
  void scan(Set<String> visual,List<Body> bodies)throws Exception{
   List<String> signature=List.of("Landroid/widget/FrameLayout;","Landroid/widget/ImageView;","Landroid/view/ViewGroup;","Landroid/view/View;","Landroid/view/View;","Landroid/view/View;","Lcom/taobao/bootimage/data/BootImageInfo;");
   // Inspect cheap table entries first. Previously every method reference in
   // every DEX allocated a full Ref/prototype, even for unrelated features.
   boolean hasModel=false;for(int i=0;i<u4(64);i++)if(matchesAscii(u4(types+i*4),signature.get(6))){hasModel=true;break;}
   if(hasModel)for(int i=0;i<u4(88);i++){int m=methods+i*8,p=protos+u2(m+2)*12,a=u4(p+8);if(a==0||u4(a)!=7||!matchesAscii(u4(m+4),"<init>"))continue;Ref r=ref(i);if(r.args.equals(signature))visual.add(r.owner);}
   Set<Integer> anchors=new HashSet<>();for(int i=0;i<u4(56);i++)if(matchesAscii(i,"launcher onShowing")||matchesAscii(i,"BootImageBizDataFlow"))anchors.add(i);if(anchors.isEmpty())return;
   for(int i=0;i<u4(96);i++){int def=u4(100)+i*32,start=u4(def+24);if(start==0)continue;Cursor c=new Cursor(start);int sf=c.leb(),f=c.leb(),d=c.leb(),v=c.leb();for(int n:new int[]{sf,f})for(int j=0;j<n;j++){c.leb();c.leb();}
    List<int[]> codeItems=new ArrayList<>();boolean anchored=false;
    for(int n:new int[]{d,v}){int id=0;for(int j=0;j<n;j++){id+=c.leb();c.leb();int code=c.leb();if(code!=0){codeItems.add(new int[]{id,code});if(!anchored&&hasAnchor(code,anchors))anchored=true;}}}
    // Only a class containing an actual const-string anchor needs expensive
    // invoke/prototype decoding. Names and renaming do not affect this scope.
    if(anchored)for(int[] item:codeItems){Body body=body(ref(item[0]),item[1]);if(body.strings.contains("LauncherBootImage")||body.strings.contains("BootImageBizDataFlow")||(body.ref.result.equals("Ljava/util/List;")&&body.ref.args.equals(List.of("Ljava/lang/String;"))))bodies.add(body);}
   }
  }
  int instructionWidth(int start,int pos,int count)throws IOException{
   int word=u2(start+pos*2),op=word&255,w=width(op);if(op==0){if(word==0x100)w=4+u2(start+(pos+1)*2)*2;else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*u4(start+(pos+2)*2)+1)/2);else if(word!=0)throw new IOException("DEX payload");}if(w<1||pos+w>count)throw new IOException("DEX width");return w;
  }
  boolean hasAnchor(int code,Set<Integer> anchors)throws Exception{
   int start=code+16,count=u4(code+12);if((long)start+count*2L>data.length)throw new IOException("DEX code bounds");
   for(int pos=0;pos<count;){int op=u2(start+pos*2)&255,w=instructionWidth(start,pos,count);if(op==0x1a&&anchors.contains(u2(start+(pos+1)*2))||op==0x1b&&anchors.contains(u4(start+(pos+1)*2)))return true;pos+=w;}return false;
  }
  Body body(Ref r,int code)throws Exception{Set<String> text=new HashSet<>();Set<Ref> calls=new HashSet<>();int start=code+16,count=u4(code+12);if((long)start+count*2L>data.length)throw new IOException("DEX code bounds");
   for(int pos=0;pos<count;){int op=u2(start+pos*2)&255,w=instructionWidth(start,pos,count);
    if(op==0x1a)text.add(str(u2(start+(pos+1)*2)));if(op==0x1b)text.add(str(u4(start+(pos+1)*2)));if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))calls.add(ref(u2(start+(pos+1)*2)));pos+=w;}
   return new Body(r,text,calls);
  }
  static int width(int op){if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
   if(op==3||op==6||op==9||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
   if(op==2||op==5||op==8||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;return 1;}
 }
 public static void main(String[] args)throws Exception{System.out.println(resolve(args[0]));}
}
