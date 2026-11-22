package io.github.offlineglass.hook.adapters.bilibili;

import java.util.*;

/** Labels are semantic identities; positions always come from the current native row. */
public final class BilibiliHomeChannels {
    private static final Set<String> PINNED=Set.of("直播","推荐","影视");
    public static List<String> visible(List<String> nativeOrder,String selected) {
        List<String> result=new ArrayList<>();
        for(String label:nativeOrder) if((PINNED.contains(label) || label.equals(selected)) && !result.contains(label)) result.add(label);
        return result;
    }
    public static Map<String,Float> positions(List<String> labels) {
        Map<String,Float> result=new LinkedHashMap<>();
        int count=labels.size()+1;
        result.put("",0.5f/count); // Existing 'more' action remains the first slot.
        for(int i=0;i<labels.size();i++)result.put(labels.get(i),(i+1.5f)/count);
        return result;
    }
}
