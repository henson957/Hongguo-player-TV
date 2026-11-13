package dev.local.hongguotv;

import com.hgtv.star.core.*;
import org.json.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Foreground reconciliation: verify identity, pull/merge, write revisions, read back, then merge again. */
final class CloudSync {
    private final TvApplication app;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduled;
    private volatile RequestScope active;
    private volatile boolean visible,running;
    private volatile String message="登录后自动合并本机与云端资料";
    private int failures;
    private boolean again;
    CloudSync(TvApplication app){this.app=app;}
    static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    static String owner(AccountCredentials credentials){return credentials==null?"":digest(credentials.getCookie());}
    private boolean enabled(){return app.preferences.getBoolean("cloud_sync",true);}
    synchronized void setVisible(boolean value){visible=value;if(value)kick(500);else{if(scheduled!=null)scheduled.cancel(false);scheduled=null;if(active!=null)active.cancel();}}
    synchronized void changed(){if(active!=null)active.cancel();failures=0;again=true;message=enabled()?"等待合并账号资料":"自动合并已关闭，本机修改已保留";kick(500);}
    synchronized void kick(long delay){
        if(!visible||!enabled()||app.repository.getCredentials()==null)return;
        if(running){if(delay<30000)again=true;return;}
        if(scheduled!=null&&!scheduled.isDone()){if(scheduled.getDelay(TimeUnit.MILLISECONDS)<=delay)return;scheduled.cancel(false);}
        scheduled=worker.schedule(this::drain,delay,TimeUnit.MILLISECONDS);
    }
    synchronized void retry(){failures=0;again=true;message="正在合并本机与云端资料";kick(0);}
    JSONObject status()throws Exception{return app.library.status().put("enabled",enabled()).put("running",running).put("message",message);}
    void syncLocal()throws Exception{if(app.repository.getCredentials()==null)throw new Exception("请先登录红果账号");if(!enabled())throw new Exception("请先开启自动合并");retry();}
    private boolean valid(LibraryState.Ticket ticket,AccountCredentials credentials){
        return enabled()&&visible&&app.library.valid(ticket)&&owner(credentials).equals(owner(app.repository.getCredentials()));
    }
    private void notifyUi(){app.libraryChanged();}
    private void drain(){
        final AccountCredentials credentials;final LibraryState.Ticket start;
        synchronized(app){credentials=app.repository.getCredentials();start=app.library.ticket();}
        synchronized(this){scheduled=null;if(!visible||!enabled()||credentials==null)return;running=true;again=false;}
        RequestScope scope=new RequestScope();active=scope;message="正在合并本机与云端资料";notifyUi();
        try{
            scope.run(()->RequestScope.Companion.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(25),()->{try{
                if(!valid(start,credentials))return null;
                app.ensureDevice(false);
                ContentRepository repository=new ContentRepository(app.repository.getHttp(),System::currentTimeMillis);repository.inheritDevice(app.repository);repository.setCredentials(credentials);
                AccountProfile profile=repository.accountLibraryProfile();
                RequestScope.Companion.ensureActive();
                if(profile==null||profile.getAnonymous()||profile.getUserId().trim().isEmpty())throw new IllegalStateException("账号资料暂不可用，请重新登录");
                if(!valid(start,credentials))return null;
                LibraryState.Ticket ticket=app.library.bind(start,digest("hongguo:"+profile.getUserId()));
                if(ticket==null||!valid(ticket,credentials))return null;
                synchronized(app){if(valid(ticket,credentials))app.accountProfile=profile;}
                notifyUi();
                JSONObject snapshot=repository.accountLibrarySnapshot();
                RequestScope.Companion.ensureActive();
                if(!valid(ticket,credentials))return null;
                app.library.merge(ticket,snapshot,null);notifyUi();
                int writes=0;
                for(JSONObject candidate:app.library.pending(ticket)){
                    if(writes>=4||!valid(ticket,credentials))break;
                    String type=candidate.optString("type"),sid=candidate.optString("sid");
                    JSONObject part=snapshot.optJSONObject(type.equals("favorite")?"favorites":"history");
                    if(part==null||!part.optBoolean("available"))continue;
                    RequestScope.Companion.ensureActive();
                    if(!app.library.beginWrite(ticket,candidate))continue;
                    if(!valid(ticket,credentials))break;
                    boolean ok;
                    if(type.equals("favorite"))ok=candidate.optBoolean("value")?repository.accountAddFavorite(sid):repository.accountRemoveFavorite(sid);
                    else if(candidate.optBoolean("deleted"))ok=repository.accountDeleteHistory(sid);
                    else {
                        JSONObject entry=candidate.getJSONObject("entry");String episode=entry.optString("episode");
                        if(!episode.matches("[0-9]{1,30}")){
                            Detail detail=repository.detail(sid);int index=entry.optInt("index");
                            if(index<0||index>=detail.getEpisodes().size())continue;
                            episode=detail.getEpisodes().get(index);entry.put("episode",episode).put("episodeCount",detail.getEpisodes().size());
                        }
                        RequestScope.Companion.ensureActive();if(!valid(ticket,credentials))break;
                        ok=repository.accountUploadProgress(sid,episode,entry.optInt("index"),entry.optLong("position"),entry.optLong("duration"),entry.optInt("episodeCount"),entry.optLong("watchedAt"));
                    }
                    writes++;
                    if(!ok)throw new IllegalStateException("云端暂未确认修改");
                    snapshot=repository.accountLibrarySnapshot();RequestScope.Companion.ensureActive();
                    if(!valid(ticket,credentials))return null;
                    app.library.merge(ticket,snapshot,candidate);app.library.confirmDeletion(ticket,candidate,snapshot);notifyUi();
                }
                JSONObject favorites=snapshot.optJSONObject("favorites"),history=snapshot.optJSONObject("history");
                if(favorites==null||history==null||!favorites.optBoolean("available")||!history.optBoolean("available"))throw new IllegalStateException("部分云端列表暂不可用");
                failures=0;
                int pending=app.library.pendingCount();
                message=pending>0?"本机修改已保留，等待云端确认 "+pending+" 项":"本机与云端资料已合并";
                return null;
            }catch(Exception problem){throw new IllegalStateException(problem);}}));
        }catch(Exception failure){if(validSession(credentials)){failures++;message="云端暂不可用，本机资料已保留，将自动重试";}}
        finally{
            if(active==scope)active=null;
            synchronized(this){running=false;boolean soon=again;again=false;long delay=failures>0?Math.min(300000,5000L*(1L<<Math.min(failures,6))):soon?1000:30000;kick(delay);}
            notifyUi();
        }
    }
    private boolean validSession(AccountCredentials credentials){return enabled()&&visible&&owner(credentials).equals(owner(app.repository.getCredentials()));}
}
