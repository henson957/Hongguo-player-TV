package dev.local.hongguotv;

import org.json.*;
import java.util.*;

/** Atomic, account-scoped three-way merge. Network work never owns or replaces the live local snapshot. */
public final class LibraryState {
    public interface Persistence {void save(String value)throws Exception;}
    public static final class Ticket {
        public final String session,account;public final long epoch;
        Ticket(String session,String account,long epoch){this.session=session;this.account=account;this.epoch=epoch;}
    }
    public static final class MergeResult {
        public final boolean changed;public final int conflicts;public final boolean complete;
        MergeResult(boolean changed,int conflicts,boolean complete){this.changed=changed;this.conflicts=conflicts;this.complete=complete;}
    }
    private JSONObject root;
    private final Persistence persistence;
    private String session="",active="guest";
    private long epoch;
    private boolean claimGuest;
    private static JSONObject copy(JSONObject value){try{return new JSONObject(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private static void put(JSONObject o,String key,Object value){try{o.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
    private static JSONObject map(JSONObject o,String key){JSONObject m=o.optJSONObject(key);if(m==null){m=new JSONObject();put(o,key,m);}return m;}
    private static Set<String> keys(JSONObject... values){Set<String> keys=new TreeSet<>();for(JSONObject value:values)if(value!=null){Iterator<String> it=value.keys();while(it.hasNext())keys.add(it.next());}return keys;}
    private static boolean id(String value){return value!=null&&value.matches("[0-9]{1,30}");}
    private static JSONObject bucket(){JSONObject b=new JSONObject();for(String key:new String[]{"favorites","history","favoriteDeletes","historyDeletes","dirtyFavorites","dirtyHistory","baseFavorites","baseHistory"})put(b,key,new JSONObject());return b;}
    public LibraryState(String saved,JSONArray legacyFavorites,JSONArray legacyHistory,Persistence persistence)throws Exception{
        this.persistence=persistence;
        if(saved!=null&&!saved.isEmpty()){
            root=new JSONObject(saved);if(root.optInt("schema")!=2)throw new IllegalStateException("本机资料版本不受支持");
        }else{
            root=new JSONObject();put(root,"schema",2);put(root,"guest",bucket());put(root,"accounts",new JSONObject());put(root,"sessions",new JSONObject());put(root,"pending",new JSONObject());put(root,"serial",0);
            JSONObject guest=map(root,"guest");
            for(int i=0;i<legacyFavorites.length();i++){JSONObject item=legacyFavorites.optJSONObject(i);if(item!=null&&id(item.optString("id")))put(map(guest,"favorites"),item.optString("id"),record(item));}
            for(int i=0;i<legacyHistory.length();i++){JSONObject item=legacyHistory.optJSONObject(i);if(item!=null&&id(item.optString("id")))put(map(guest,"history"),item.optString("id"),progressRecord(item));}
            persistence.save(root.toString());
        }
    }
    private JSONObject current(){return active.equals("guest")?map(root,"guest"):active.startsWith("pending:")?map(map(root,"pending"),active.substring(8)):map(map(root,"accounts"),active);}
    private JSONObject account(String key){return map(map(root,"accounts"),key);}
    private void commit(JSONObject before)throws Exception{try{persistence.save(root.toString());}catch(Exception error){root=before;throw error;}}
    private long revision(){long value=root.optLong("serial")+1;put(root,"serial",value);return value;}
    private long now(){long value=Math.max(System.currentTimeMillis(),root.optLong("clock")+1);put(root,"clock",value);return value;}
    public static long timestamp(long value){if(value<=0)return 0;return value<100_000_000_000L?value*1000:value;}
    private static JSONObject record(JSONObject input){
        JSONObject row=new JSONObject();String[] names={"id","title","cover","description","badge","tags"};int[] limits={30,500,4096,4000,200,1000};
        for(int i=0;i<names.length;i++){String value=input.optString(names[i],"");put(row,names[i],value.substring(0,Math.min(limits[i],value.length())));}
        if(row.optString("title").isEmpty())put(row,"title","剧名待加载 · "+row.optString("id"));
        put(row,"favoriteAt",timestamp(input.optLong("favoriteAt")));return row;
    }
    private static JSONObject progressRecord(JSONObject input){JSONObject row=record(input);put(row,"index",Math.max(0,input.optInt("index")));put(row,"position",Math.max(0,input.optLong("position")));put(row,"duration",Math.max(0,input.optLong("duration")));put(row,"watchedAt",timestamp(input.optLong("watchedAt")));put(row,"episode",input.optString("episode"));put(row,"episodeCount",Math.max(0,input.optInt("episodeCount")));return row;}
    public synchronized Ticket ticket(){return new Ticket(session,active,epoch);}
    public synchronized boolean valid(Ticket t){return t!=null&&t.epoch==epoch&&t.session.equals(session)&&t.account.equals(active);}
    public synchronized void openSession(String value)throws Exception{
        JSONObject before=copy(root);String previousSession=session,previousActive=active;long previousEpoch=epoch;
        session=value==null?"":value;epoch++;claimGuest=!session.isEmpty();
        if(session.isEmpty())active="guest";
        else {String known=map(root,"sessions").optString(session);active=known.isEmpty()?"pending:"+session:known;if(active.startsWith("pending:")&&!map(root,"pending").has(session))put(map(root,"pending"),session,bucket());}
        try{commit(before);}catch(Exception error){session=previousSession;active=previousActive;epoch=previousEpoch;throw error;}
    }
    public synchronized void invalidateWork(){epoch++;}
    public synchronized Ticket bind(Ticket expected,String accountKey)throws Exception{
        if(!valid(expected))return null;if(!accountKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("账号身份无效");
        JSONObject before=copy(root);String previous=active;boolean previousClaim=claimGuest;
        JSONObject accounts=map(root,"accounts");if(!accounts.has(accountKey))put(accounts,accountKey,bucket());JSONObject destination=account(accountKey);
        if(active.startsWith("pending:")){importBucket(destination,current(),true);map(root,"pending").remove(session);}
        if(claimGuest){importBucket(destination,map(root,"guest"),false);put(root,"guest",bucket());claimGuest=false;}
        put(map(root,"sessions"),session,accountKey);active=accountKey;
        try{commit(before);}catch(Exception error){active=previous;claimGuest=previousClaim;throw error;}return ticket();
    }
    private void importBucket(JSONObject destination,JSONObject source,boolean accountEdits){
        JSONObject favorites=map(source,"favorites"),history=map(source,"history");
        for(String sid:keys(favorites))if(!map(destination,"favorites").has(sid)){JSONObject item=copy(favorites.optJSONObject(sid));put(map(destination,"favorites"),sid,item);dirtyFavorite(destination,sid,true,item.optLong("favoriteAt"));}
        for(String sid:keys(history)){JSONObject item=history.optJSONObject(sid),old=map(destination,"history").optJSONObject(sid);if(old==null||timestamp(item.optLong("watchedAt"))>timestamp(old.optLong("watchedAt"))){put(map(destination,"history"),sid,copy(item));dirtyHistory(destination,sid,item,false,item.optLong("watchedAt"));}}
        if(accountEdits){
            for(String sid:keys(map(source,"dirtyFavorites"))){JSONObject operation=map(source,"dirtyFavorites").optJSONObject(sid);if(!operation.optBoolean("value")){map(destination,"favorites").remove(sid);put(map(destination,"favoriteDeletes"),sid,tombstone(operation.optLong("at"),null,"local"));dirtyFavorite(destination,sid,false,operation.optLong("at"));}}
            for(String sid:keys(map(source,"dirtyHistory"))){JSONObject operation=map(source,"dirtyHistory").optJSONObject(sid);if(operation.optBoolean("deleted")){map(destination,"history").remove(sid);put(map(destination,"historyDeletes"),sid,tombstone(operation.optLong("at"),operation.optJSONObject("entry"),"local"));dirtyHistory(destination,sid,operation.optJSONObject("entry"),true,operation.optLong("at"));}}
            put(destination,"clearHistoryAt",Math.max(destination.optLong("clearHistoryAt"),source.optLong("clearHistoryAt")));
        }
    }
    public synchronized void importOldQueue(String value)throws Exception{
        if(root.optBoolean("oldQueueImported"))return;JSONObject before=copy(root);
        JSONArray entries=new JSONArray(value);if(!session.isEmpty())for(int i=0;i<entries.length();i++){
            JSONObject op=entries.optJSONObject(i);if(op==null||!session.equals(op.optString("owner"))||!id(op.optString("sid")))continue;String sid=op.optString("sid");JSONObject b=current();
            if(op.optString("type").equals("favorite")){boolean yes=op.optBoolean("value");if(yes&&!map(b,"favorites").has(sid)){JSONObject old=map(map(root,"guest"),"favorites").optJSONObject(sid);if(old!=null)put(map(b,"favorites"),sid,copy(old));}if(!yes){map(b,"favorites").remove(sid);put(map(b,"favoriteDeletes"),sid,tombstone(now(),null,"local"));}dirtyFavorite(b,sid,yes,now());}
            else if(op.optString("type").equals("progress")){JSONObject entry=map(map(root,"guest"),"history").optJSONObject(sid);entry=entry==null?new JSONObject():copy(entry);put(entry,"id",sid);put(entry,"index",op.optInt("index"));put(entry,"position",op.optLong("position"));put(entry,"duration",op.optLong("duration"));put(entry,"episode",op.optString("episode"));put(entry,"episodeCount",op.optInt("count"));put(map(b,"history"),sid,progressRecord(entry));dirtyHistory(b,sid,entry,false,entry.optLong("watchedAt"));}
        }
        put(root,"oldQueueImported",true);commit(before);
    }
    public synchronized JSONArray favorites(){return sorted(map(current(),"favorites"),"favoriteAt",2000);}
    public synchronized JSONArray history(){return sorted(map(current(),"history"),"watchedAt",2000);}
    private static JSONArray sorted(JSONObject values,String field,int limit){ArrayList<JSONObject> rows=new ArrayList<>();for(String key:keys(values)){JSONObject row=values.optJSONObject(key);if(row!=null)rows.add(copy(row));}rows.sort((a,b)->{int order=Long.compare(b.optLong(field),a.optLong(field));return order!=0?order:a.optString("id").compareTo(b.optString("id"));});return new JSONArray(rows.subList(0,Math.min(limit,rows.size())));}
    public synchronized boolean favorite(JSONObject series)throws Exception{
        String sid=series.optString("id");if(!id(sid))throw new IllegalArgumentException("剧集编号无效");JSONObject before=copy(root),b=current();boolean value=!map(b,"favorites").has(sid);long at=now();
        if(value){JSONObject row=record(series);put(row,"favoriteAt",at);put(map(b,"favorites"),sid,row);map(b,"favoriteDeletes").remove(sid);}
        else{JSONObject old=map(b,"favorites").optJSONObject(sid);map(b,"favorites").remove(sid);put(map(b,"favoriteDeletes"),sid,tombstone(at,old,"local"));}
        dirtyFavorite(b,sid,value,at);commit(before);return value;
    }
    public synchronized boolean progress(JSONObject series,int index,long position,long duration,long capturedAt)throws Exception{
        String sid=series.optString("id");if(!id(sid)||index<0)return false;JSONObject b=current(),old=map(b,"history").optJSONObject(sid);
        if(old!=null&&old.optInt("index")==index&&old.optLong("position")==Math.max(0,position))return false;
        if(old!=null&&old.optLong("watchedAt")>capturedAt)return false;
        JSONObject before=copy(root),entry=record(series);put(entry,"index",index);put(entry,"position",Math.max(0,position));put(entry,"duration",Math.max(0,duration));put(entry,"watchedAt",capturedAt);
        JSONArray episodes=series.optJSONArray("episodes");if(episodes!=null&&index<episodes.length()){put(entry,"episode",episodes.optString(index));put(entry,"episodeCount",episodes.length());}
        put(map(b,"history"),sid,entry);map(b,"historyDeletes").remove(sid);dirtyHistory(b,sid,entry,false,entry.optLong("watchedAt"));commit(before);return true;
    }
    public synchronized void clearHistory()throws Exception{
        JSONObject before=copy(root),b=current();long at=now();for(String sid:keys(map(b,"history"))){JSONObject old=map(b,"history").optJSONObject(sid);put(map(b,"historyDeletes"),sid,tombstone(at,old,"local"));dirtyHistory(b,sid,old,true,at);}put(b,"history",new JSONObject());put(b,"clearHistoryAt",at);commit(before);
    }
    private void dirtyFavorite(JSONObject b,String sid,boolean value,long at){JSONObject op=new JSONObject();put(op,"value",value);put(op,"at",at);put(op,"revision",revision());put(map(b,"dirtyFavorites"),sid,op);}
    private void dirtyHistory(JSONObject b,String sid,JSONObject value,boolean deleted,long at){JSONObject op=new JSONObject();put(op,"deleted",deleted);put(op,"at",at);put(op,"revision",revision());JSONObject entry=value==null?new JSONObject():copy(value);put(entry,"id",sid);put(op,"entry",entry);put(map(b,"dirtyHistory"),sid,op);}
    private static JSONObject tombstone(long at,JSONObject prior,String source){JSONObject value=new JSONObject();put(value,"at",at);put(value,"source",source);if(prior!=null)put(value,"before",copy(prior));return value;}
    private static boolean sameHistory(JSONObject a,JSONObject b){return a!=null&&b!=null&&a.optInt("index")==b.optInt("index")&&Math.abs(a.optLong("position")-b.optLong("position"))<=1000;}
    private static JSONObject metadata(JSONObject remote,JSONObject local){if(remote==null||local==null)return remote;JSONObject row=copy(remote);for(String field:new String[]{"title","cover","description","badge","tags"})if(row.optString(field).isEmpty())put(row,field,local.optString(field));return row;}
    private static JSONObject rows(JSONObject part){JSONObject rows=new JSONObject();JSONArray values=part.optJSONArray("items");if(values!=null)for(int i=0;i<values.length();i++){JSONObject row=values.optJSONObject(i);if(row!=null&&id(row.optString("id")))put(rows,row.optString("id"),row);}return rows;}
    private static JSONObject baseFavorite(boolean present,long at){JSONObject base=new JSONObject();put(base,"present",present);put(base,"favoriteAt",at);return base;}
    private static boolean reappeared(JSONObject deleted,JSONObject remote,String timeField){
        JSONObject prior=deleted.optJSONObject("before");long stamp=timestamp(remote.optLong(timeField)),previous=prior==null?0:timestamp(prior.optLong(timeField));
        return stamp>previous&&(deleted.optString("source").equals("remote")||stamp>deleted.optLong("at"));
    }
    public synchronized MergeResult merge(Ticket ticket,JSONObject snapshot,JSONObject echo)throws Exception{
        if(!valid(ticket)||active.startsWith("pending:")||active.equals("guest"))return new MergeResult(false,0,false);
        JSONObject before=copy(root),b=current();String oldFav=map(b,"favorites").toString(),oldHistory=map(b,"history").toString();int conflicts=0;
        JSONObject fav=snapshot.optJSONObject("favorites"),hist=snapshot.optJSONObject("history");
        if(fav!=null&&fav.optBoolean("available"))conflicts+=mergeFavorites(b,rows(fav),fav.optBoolean("complete"));
        if(hist!=null&&hist.optBoolean("available"))conflicts+=mergeHistory(b,rows(hist),hist.optBoolean("complete"),hist.optBoolean("windowed"),echo);
        put(b,"lastPullAt",System.currentTimeMillis());put(b,"conflicts",b.optLong("conflicts")+conflicts);
        boolean complete=fav!=null&&fav.optBoolean("available")&&hist!=null&&hist.optBoolean("available");
        put(b,"partial",!complete||!fav.optBoolean("complete")||!hist.optBoolean("complete"));
        boolean changed=!oldFav.equals(map(b,"favorites").toString())||!oldHistory.equals(map(b,"history").toString());commit(before);return new MergeResult(changed,conflicts,complete);
    }
    private int mergeFavorites(JSONObject b,JSONObject remote,boolean complete){
        JSONObject local=map(b,"favorites"),base=map(b,"baseFavorites"),dirty=map(b,"dirtyFavorites"),deleted=map(b,"favoriteDeletes");int conflicts=0;
        for(String sid:keys(local,base,dirty,remote)){
            JSONObject r=metadata(remote.optJSONObject(sid),local.optJSONObject(sid)),previous=base.optJSONObject(sid),op=dirty.optJSONObject(sid);boolean present=r!=null;
            if(!present&&!complete)continue;
            JSONObject sent=map(b,"sent").optJSONObject("favorite:"+sid);
            if(op!=null&&sent!=null&&op.optLong("revision")!=sent.optLong("revision")&&sent.optBoolean("value")==present){put(base,sid,baseFavorite(present,r==null?0:timestamp(r.optLong("favoriteAt"))));continue;}
            if(op!=null){
                boolean desired=op.optBoolean("value");boolean remoteDeleted=previous!=null&&previous.optBoolean("present")&&!present;
                if(!desired&&present&&timestamp(r.optLong("favoriteAt"))>op.optLong("at")+2000&&(previous==null||timestamp(r.optLong("favoriteAt"))>timestamp(previous.optLong("favoriteAt")))){conflicts++;put(local,sid,record(r));dirty.remove(sid);deleted.remove(sid);}
                else if(remoteDeleted&&desired){conflicts++;local.remove(sid);dirty.remove(sid);put(deleted,sid,tombstone(now(),previous,"remote"));}
                else if(desired==present){dirty.remove(sid);if(present){JSONObject merged=record(r);JSONObject old=local.optJSONObject(sid);if(old!=null)put(merged,"favoriteAt",Math.max(merged.optLong("favoriteAt"),old.optLong("favoriteAt")));put(local,sid,merged);deleted.remove(sid);}else local.remove(sid);}
                // An explicit local removal wins over a concurrent add. A local-only add is sent later.
            }else if(present){
                JSONObject mark=deleted.optJSONObject(sid);if(mark!=null&&!reappeared(mark,r,"favoriteAt"))continue;
                put(local,sid,record(r));deleted.remove(sid);
            }else if(previous!=null&&previous.optBoolean("present")){
                JSONObject prior=local.optJSONObject(sid);local.remove(sid);put(deleted,sid,tombstone(now(),prior==null?previous:prior,"remote"));
            }else if(local.has(sid)&&previous==null)dirtyFavorite(b,sid,true,local.optJSONObject(sid).optLong("favoriteAt"));
            put(base,sid,baseFavorite(present,r==null?0:timestamp(r.optLong("favoriteAt"))));
        }
        return conflicts;
    }
    private int mergeHistory(JSONObject b,JSONObject remote,boolean complete,boolean windowed,JSONObject echo){
        JSONObject local=map(b,"history"),base=map(b,"baseHistory"),dirty=map(b,"dirtyHistory"),deleted=map(b,"historyDeletes");int conflicts=0;long oldest=Long.MAX_VALUE;
        for(String sid:keys(remote)){long at=timestamp(remote.optJSONObject(sid).optLong("watchedAt"));if(at>0)oldest=Math.min(oldest,at);}
        for(String sid:keys(local,base,dirty,remote)){
            JSONObject l=local.optJSONObject(sid),r=metadata(remote.optJSONObject(sid),l),previous=base.optJSONObject(sid),op=dirty.optJSONObject(sid);
            boolean known=r!=null||complete;if(!known){if(windowed&&op!=null&&!op.optBoolean("deleted")&&op.optLong("at")<oldest&&oldest!=Long.MAX_VALUE)dirty.remove(sid);continue;}
            boolean priorPresent=previous!=null&&!previous.optBoolean("deleted");
            JSONObject sent=map(b,"sent").optJSONObject("progress:"+sid);
            boolean ownOldEcho=op!=null&&sent!=null&&op.optLong("revision")!=sent.optLong("revision")&&(sent.optBoolean("deleted")?r==null:sameHistory(r,sent.optJSONObject("entry")));
            if(ownOldEcho){put(base,sid,r==null?absent():progressRecord(r));continue;}
            if(r!=null&&b.optLong("clearHistoryAt")>0&&timestamp(r.optLong("watchedAt"))>0&&timestamp(r.optLong("watchedAt"))<b.optLong("clearHistoryAt")&&!local.has(sid)&&op==null&&!deleted.has(sid)){
                put(deleted,sid,tombstone(b.optLong("clearHistoryAt"),r,"local"));dirtyHistory(b,sid,r,true,b.optLong("clearHistoryAt"));op=dirty.optJSONObject(sid);
            }
            if(op!=null){
                if(op.optBoolean("deleted")){if(r==null){dirty.remove(sid);local.remove(sid);}else if(timestamp(r.optLong("watchedAt"))>op.optLong("at")+2000&&(previous==null||timestamp(r.optLong("watchedAt"))>timestamp(previous.optLong("watchedAt")))){conflicts++;put(local,sid,progressRecord(r));dirty.remove(sid);deleted.remove(sid);}}
                else if(r==null&&priorPresent){conflicts++;local.remove(sid);dirty.remove(sid);put(deleted,sid,tombstone(now(),previous,"remote"));}
                else if(r!=null&&sameHistory(l,r)){dirty.remove(sid);JSONObject row=progressRecord(r);if(l!=null)put(row,"watchedAt",Math.max(timestamp(r.optLong("watchedAt")),l.optLong("watchedAt")));put(local,sid,row);deleted.remove(sid);}
                else if(r!=null){
                    boolean remoteChanged=previous==null||previous.optBoolean("deleted")||!sameHistory(previous,r)||timestamp(r.optLong("watchedAt"))>timestamp(previous.optLong("watchedAt"));
                    long localAt=op.optLong("at"),remoteAt=timestamp(r.optLong("watchedAt"));
                    if(remoteChanged){conflicts++;if(remoteAt>0&&remoteAt>localAt+2000){put(local,sid,progressRecord(r));dirty.remove(sid);deleted.remove(sid);}}
                }
            }else if(r!=null){
                JSONObject mark=deleted.optJSONObject(sid);if(mark!=null&&!reappeared(mark,r,"watchedAt"))continue;
                if(previous!=null&&!previous.optBoolean("deleted")&&timestamp(r.optLong("watchedAt"))>0&&timestamp(r.optLong("watchedAt"))<timestamp(previous.optLong("watchedAt")))continue;
                put(local,sid,progressRecord(r));deleted.remove(sid);
            }else if(priorPresent){local.remove(sid);put(deleted,sid,tombstone(now(),previous,"remote"));}
            else if(l!=null&&previous==null)dirtyHistory(b,sid,l,false,l.optLong("watchedAt"));
            put(base,sid,r==null?absent():progressRecord(r));
        }
        return conflicts;
    }
    private static JSONObject absent(){JSONObject o=new JSONObject();put(o,"deleted",true);return o;}
    public synchronized boolean beginWrite(Ticket t,JSONObject op)throws Exception{
        if(!valid(t))return false;JSONObject b=current(),pending=map(b,op.optString("type").equals("favorite")?"dirtyFavorites":"dirtyHistory").optJSONObject(op.optString("sid"));
        if(pending==null||pending.optLong("revision")!=op.optLong("revision"))return false;
        JSONObject before=copy(root);put(map(b,"sent"),op.optString("type")+":"+op.optString("sid"),copy(op));commit(before);return true;
    }
    /** A positive delete response plus a readable snapshot may acknowledge an absent windowed row.
     * Revisions prevent a late response from clearing a subsequent local edit.
     */
    public synchronized void confirmDeletion(Ticket t,JSONObject op,JSONObject snapshot)throws Exception{
        if(!valid(t))return;boolean favorite=op.optString("type").equals("favorite");
        if(favorite?op.optBoolean("value"):!op.optBoolean("deleted"))return;
        JSONObject part=snapshot.optJSONObject(favorite?"favorites":"history");if(part==null||!part.optBoolean("available")||rows(part).has(op.optString("sid")))return;
        JSONObject b=current(),dirty=map(b,favorite?"dirtyFavorites":"dirtyHistory"),pending=dirty.optJSONObject(op.optString("sid"));
        if(pending==null||pending.optLong("revision")!=op.optLong("revision"))return;JSONObject before=copy(root);dirty.remove(op.optString("sid"));put(map(b,favorite?"baseFavorites":"baseHistory"),op.optString("sid"),favorite?baseFavorite(false,0):absent());commit(before);
    }
    public synchronized List<JSONObject> pending(Ticket t){
        ArrayList<JSONObject> result=new ArrayList<>();if(!valid(t)||active.startsWith("pending:")||active.equals("guest"))return result;JSONObject b=current();
        for(String type:new String[]{"favorite","progress"}){JSONObject dirty=map(b,type.equals("favorite")?"dirtyFavorites":"dirtyHistory");for(String sid:keys(dirty)){JSONObject op=copy(dirty.optJSONObject(sid));put(op,"sid",sid);put(op,"type",type);result.add(op);}}
        return result;
    }
    public synchronized int pendingCount(){JSONObject b=current();return map(b,"dirtyFavorites").length()+map(b,"dirtyHistory").length();}
    public synchronized JSONObject status(){JSONObject b=current(),value=new JSONObject();put(value,"pending",pendingCount());put(value,"lastPullAt",b.optLong("lastPullAt"));put(value,"conflicts",b.optLong("conflicts"));put(value,"partial",b.optBoolean("partial"));put(value,"accountReady",!session.isEmpty()&&!active.startsWith("pending:"));return value;}
    public synchronized void requestMergeAgain()throws Exception{JSONObject before=copy(root);claimGuest=true;commit(before);}
}
