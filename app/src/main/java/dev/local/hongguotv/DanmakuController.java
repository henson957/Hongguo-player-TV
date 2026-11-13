package dev.local.hongguotv;

import android.os.*;
import com.hgtv.star.core.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Keeps only nearby 30-second windows. Disabled mode makes no requests. */
final class DanmakuController {
    private final TvApplication app;
    private final DanmakuOverlay overlay;
    private final TimedComments reader;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<Long,JSONArray> windows=new LinkedHashMap<>();
    private final Map<Long,RequestScope> pending=new HashMap<>();
    private final Set<Long> failed=new HashSet<>();
    private int generation;
    private String key="";
    private long previousPosition;
    DanmakuController(TvApplication app,DanmakuOverlay overlay){this.app=app;this.overlay=overlay;reader=new TimedComments(app.repository);}
    void clear(){generation++;for(RequestScope scope:pending.values())scope.cancel();pending.clear();windows.clear();failed.clear();key="";overlay.clear();}
    void close(){clear();worker.shutdownNow();}
    void tick(String sid,String episode,long position,long duration){
        String next=sid+"/"+episode+"/"+app.preferences.getString("danmaku_mode","top");
        if(duration<=0||next.endsWith("/hidden")){if(!key.isEmpty())clear();return;}
        if(!next.equals(key)||Math.abs(position-previousPosition)>2500){clear();key=next;}previousPosition=position;
        long window=position/30000*30000;fetch(sid,episode,window,duration);
        if(window>0&&position-window<12000)fetch(sid,episode,window-30000,duration);
        if(position-window>15000&&window+30000<duration)fetch(sid,episode,window+30000,duration);
        overlay.invalidate();
    }
    private void fetch(String sid,String episode,long start,long duration){
        if(windows.containsKey(start)||pending.containsKey(start)||failed.contains(start))return;
        final int token=generation;RequestScope scope=new RequestScope();pending.put(start,scope);
        worker.execute(()->{
            if(token!=generation)return;
            try{JSONObject data=scope.run(()->reader.read(sid,episode,start,duration));main.post(()->{if(token!=generation)return;pending.remove(start);windows.put(start,data.optJSONArray("items"));while(windows.size()>6)windows.remove(windows.keySet().iterator().next());JSONArray all=new JSONArray();for(JSONArray rows:windows.values())if(rows!=null)for(int i=0;i<rows.length();i++)all.put(rows.optJSONObject(i));overlay.items(all);});}
            catch(Exception ignored){main.post(()->{if(token==generation){pending.remove(start);failed.add(start);}});}
        });
    }
}
