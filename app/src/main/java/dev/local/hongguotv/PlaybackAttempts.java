package dev.local.hongguotv;

import com.hgtv.star.core.*;
import java.util.*;

/** One playback session: failures belong to an episode and requested ceiling, never to every video. */
final class PlaybackAttempts {
    private static final class Attempt {int ceiling,count;final Set<String> rejected=new HashSet<>();Attempt(int ceiling){this.ceiling=ceiling;}}
    private final Map<String,Attempt> attempts=new LinkedHashMap<String,Attempt>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Attempt> entry){return size()>32;}
    };
    private Attempt entry(String key,int ceiling){Attempt value=attempts.get(key);if(value==null){value=new Attempt(ceiling);attempts.put(key,value);}return value;}
    synchronized int ceiling(String key,int configured){return Math.min(configured,entry(key,configured).ceiling);}
    synchronized Set<String> rejected(String key,int configured){return new HashSet<>(entry(key,configured).rejected);}
    synchronized boolean lower(String key,int configured,StreamInfo failed){
        Attempt value=entry(key,configured);value.rejected.add(PlaybackStreams.INSTANCE.fingerprint(failed));
        int actual=ContentRepository.Companion.qualityNumber(failed.getQuality());
        if(actual>0)value.ceiling=Math.min(value.ceiling,actual-1);
        else {int next=0;for(int q:new int[]{1080,720,576,540,480,360})if(q<value.ceiling){next=q;break;}value.ceiling=next;}
        return ++value.count<12&&value.ceiling>0;
    }
    synchronized void clear(){attempts.clear();}
}
