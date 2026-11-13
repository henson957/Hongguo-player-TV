package dev.local.hongguotv;

import org.json.*;
import java.util.*;

/** Durable last-write-wins records. Acknowledging an older write never discards a newer checkpoint. */
public final class SyncOutbox {
    private final LinkedHashMap<String,JSONObject> rows=new LinkedHashMap<>();
    private long serial;
    public SyncOutbox(String json){try{JSONArray data=new JSONArray(json);for(int i=0;i<data.length()&&rows.size()<240;i++){JSONObject row=data.getJSONObject(i);String owner=row.optString("owner"),sid=row.optString("sid"),type=row.optString("type");if(owner.matches("[0-9a-f]{64}")&&sid.matches("[0-9]{1,30}")&&(type.equals("favorite")||type.equals("progress"))){rows.put(key(owner,type,sid),row);serial=Math.max(serial,row.optLong("revision"));}}}catch(Exception ignored){}}
    private static String key(String owner,String type,String sid){return owner+":"+type+":"+sid;}
    private static JSONObject copy(JSONObject row){try{return new JSONObject(row.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    public synchronized void put(String owner,String type,String sid,JSONObject value){
        String key=key(owner,type,sid);if(!rows.containsKey(key)&&rows.size()>=240)throw new IllegalStateException("待同步记录已满，请先同步或清除待同步任务");
        try{JSONObject row=copy(value);row.put("owner",owner).put("type",type).put("sid",sid).put("revision",++serial);rows.put(key,row);}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized JSONObject first(String owner){for(JSONObject row:rows.values())if(owner.equals(row.optString("owner")))return copy(row);return null;}
    public synchronized void acknowledge(JSONObject row){String key=key(row.optString("owner"),row.optString("type"),row.optString("sid"));JSONObject current=rows.get(key);if(current!=null&&current.optLong("revision")==row.optLong("revision"))rows.remove(key);}
    public synchronized int count(String owner){int n=0;for(JSONObject row:rows.values())if(owner.equals(row.optString("owner")))n++;return n;}
    public synchronized void clear(){rows.clear();}
    public synchronized String serialize(){JSONArray data=new JSONArray();for(JSONObject row:rows.values())data.put(row);return data.toString();}
}
