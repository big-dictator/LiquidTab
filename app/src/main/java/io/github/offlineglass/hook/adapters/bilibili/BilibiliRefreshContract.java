package io.github.offlineglass.hook.adapters.bilibili;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Roles come from the native public setter/getter bodies in this APK. */
public final class BilibiliRefreshContract {
    private static final String OWNER="Ltv/danmaku/bili/widget/swiperefresh/SwipeRefreshLayout;";
    private static final String FRAME="Lcom/bilibili/video/story/view/StoryVideoFrameLayout;";
    public static Properties resolve(String apk,String cachePath) throws IOException {
        File source=new File(apk),cache=new File(cachePath);
        String identity="2:"+source.getAbsolutePath()+":"+source.length()+":"+source.lastModified();
        Properties p=new Properties();
        if(cache.isFile())try(InputStream in=new FileInputStream(cache)) {
            p.load(in);if(identity.equals(p.getProperty("identity")) && p.containsKey("refreshScanned") &&
                p.containsKey("frameScanned"))return p;
        }catch(Exception ignored){}
        p.clear();
        try(ZipFile zip=new ZipFile(source)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();
                if(!e.getName().matches("classes[0-9]*\\.dex") || e.getSize()<112 || e.getSize()>64*1024*1024)continue;
                byte[] bytes;try(InputStream in=zip.getInputStream(e)){bytes=in.readAllBytes();}
                var dex=new BilibiliCommentIndex.Dex(bytes);
                var frame=dex.contractBodies(FRAME);
                if(!frame.isEmpty()) {
                    Set<String> reserved=null,time=null,measured=null;
                    for(var entry:frame.entrySet()) {
                        var r=entry.getKey();
                        if(r.name().equals("setContainerBottom") && r.result().equals("V") && r.args().equals(List.of("I")))
                            reserved=dex.accessedFields(entry.getValue(),0x67); // sput int
                        if(r.name().equals("onConfigurationChanged") && r.result().equals("V") &&
                            r.args().equals(List.of("Landroid/content/res/Configuration;")))time=dex.accessedFields(entry.getValue(),0x5a); // iput-wide
                        if(r.name().equals("onMeasure") && r.result().equals("V") && r.args().equals(List.of("I","I")))
                            measured=dex.accessedFields(entry.getValue(),0x53); // iget-wide
                    }
                    if(reserved!=null && reserved.size()==1 && time!=null && measured!=null) {
                        time.retainAll(measured);
                        if(time.size()==1) {
                            p.setProperty("frameReserved",reserved.iterator().next());
                            p.setProperty("frameTime",time.iterator().next());
                        }
                    }
                }
                var bodies=dex.contractBodies(OWNER);if(bodies.isEmpty())continue;
                Set<String> scale=null,custom=null,refreshing=null;
                for(var entry:bodies.entrySet()) {
                    var r=entry.getKey();
                    if(r.name().equals("setProgressViewEndTarget") && r.result().equals("V") && r.args().equals(List.of("Z","I")))
                        scale=dex.booleanFields(entry.getValue(),true);
                    if(r.name().equals("setProgressViewOffset") && r.result().equals("V") && r.args().equals(List.of("I","I")))
                        custom=dex.booleanFields(entry.getValue(),true);
                    if(r.name().equals("isRefreshing") && r.result().equals("Z") && r.args().isEmpty())
                        refreshing=dex.booleanFields(entry.getValue(),false);
                }
                if(scale==null || custom==null || refreshing==null || scale.size()!=1 || refreshing.size()!=1)
                    continue;
                custom.removeAll(scale);custom.removeAll(refreshing);
                if(custom.size()!=1)continue;
                p.setProperty("scale",scale.iterator().next());p.setProperty("custom",custom.iterator().next());
            }
        }
        // Frame capability is independent: missing geometry roles must not disable refresh.
        p.setProperty("identity",identity);
        p.setProperty("frameScanned","true");
        p.setProperty("refreshScanned","true");
        try(OutputStream out=new FileOutputStream(cache)){p.store(out,"Native layout roles; APK-scoped");}catch(IOException ignored){}
        return p;
    }
}
