package dev.local.hongguotv;

import java.util.*;

/** Fixed-velocity lanes: later messages cannot catch and overlap earlier messages. */
public final class DanmakuTimeline {
    public static final class Item {
        public final String id,text;public final long time;public final float width;public int lane;
        public Item(String id,String text,long time,float width){this.id=id;this.text=text;this.time=time;this.width=width;}
    }
    public static List<Item> schedule(List<Item> incoming,int lanes,float pixelsPerMillisecond){
        if(lanes<1||!(pixelsPerMillisecond>0)||Float.isInfinite(pixelsPerMillisecond))return Collections.emptyList();
        ArrayList<Item> sorted=new ArrayList<>(incoming);sorted.sort(Comparator.comparingLong(i->i.time));
        double[] available=new double[lanes];Arrays.fill(available,Double.NEGATIVE_INFINITY);Set<String> ids=new HashSet<>();ArrayList<Item> result=new ArrayList<>();
        for(Item item:sorted){if(item.time<0||!(item.width>=0)||Float.isInfinite(item.width)||!ids.add(item.id))continue;int lane=-1;for(int i=0;i<lanes;i++)if(available[i]<=item.time&&(lane<0||available[i]<available[lane]))lane=i;if(lane<0)continue;item.lane=lane;available[lane]=item.time+(item.width+36)/pixelsPerMillisecond;result.add(item);if(result.size()>=540)break;}
        return result;
    }
}
