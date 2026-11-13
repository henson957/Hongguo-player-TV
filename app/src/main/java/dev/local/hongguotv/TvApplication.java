package dev.local.hongguotv;

import android.app.Application;
import android.content.SharedPreferences;
import com.hgtv.star.core.*;
import org.json.*;
import java.util.*;

public class TvApplication extends Application {
    public ContentRepository repository;
    public SharedPreferences preferences;
    public final Map<String,Detail> details=Collections.synchronizedMap(new LinkedHashMap<String,Detail>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Detail> e){ return size()>12; }
    });
    private long lastRegistration;
    public volatile String lastProblem="";
    public volatile long accountRevision;
    public volatile AccountProfile accountProfile;
    public volatile boolean accountSaved;
    public CloudSync sync;
    public BatchPlaybackResolver playback;
    public LibraryState library;
    public final java.util.concurrent.CopyOnWriteArrayList<Runnable> libraryListeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.ExecutorService libraryWriter=java.util.concurrent.Executors.newSingleThreadExecutor();
    public void libraryChanged(){for(Runnable listener:libraryListeners)listener.run();}
    private static JSONArray array(String value){try{return new JSONArray(value);}catch(Exception ignored){return new JSONArray();}}
    @Override public void onCreate() {
        super.onCreate();
        preferences=getSharedPreferences("hongguo_tv",MODE_PRIVATE);
        CrashDiagnostics.install(preferences);lastProblem=preferences.getString("last_problem","");
        repository=new ContentRepository();
        try{String cookie=AccountStore.load(preferences);if(!cookie.isEmpty()){repository.setCredentials(credentials(cookie));accountSaved=true;}}catch(Exception ignored){preferences.edit().remove("account_sealed").apply();}
        try {
            JSONObject identity=new JSONObject(preferences.getString("device","{}"));
            Map<String,String> map=new LinkedHashMap<>();
            Iterator<String> keys=identity.keys();
            while(keys.hasNext()){String key=keys.next();map.put(key,identity.getString(key));}
            repository.setDeviceIdentity(map);
            JSONObject cookies=new JSONObject(preferences.getString("device_cookies","{}"));Map<String,String> registered=new LinkedHashMap<>();Iterator<String> cookieKeys=cookies.keys();while(cookieKeys.hasNext()){String key=cookieKeys.next();registered.put(key,cookies.getString(key));}repository.restoreRegistrationCookies(registered);
        } catch(Exception ignored){}
        try{
            library=new LibraryState(preferences.getString("library_v2",""),array(preferences.getString("favorites","[]")),array(preferences.getString("history","[]")),value->{if(!preferences.edit().putString("library_v2",value).commit())throw new java.io.IOException("本机资料保存失败");});
            library.openSession(CloudSync.owner(repository.getCredentials()));
            library.importOldQueue(preferences.getString("sync_outbox","[]"));
            preferences.edit().remove("favorites").remove("history").remove("sync_outbox").apply();
        }catch(Exception error){throw new IllegalStateException("本机资料无法读取；原始数据仍保留",error);}
        playback=new BatchPlaybackResolver(repository,System::currentTimeMillis);sync=new CloudSync(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            int visible;
            public void onActivityStarted(android.app.Activity activity){if(++visible==1)sync.setVisible(true);}
            public void onActivityStopped(android.app.Activity activity){if(--visible==0)sync.setVisible(false);}
            public void onActivityCreated(android.app.Activity activity,android.os.Bundle state){}
            public void onActivityResumed(android.app.Activity activity){}
            public void onActivityPaused(android.app.Activity activity){}
            public void onActivitySaveInstanceState(android.app.Activity activity,android.os.Bundle state){}
            public void onActivityDestroyed(android.app.Activity activity){}
        });
    }
    public boolean ensureDevice(boolean renew) {
        synchronized(this){
            if(!renew&&!repository.getDeviceIdentity().isEmpty()&&!repository.registrationCookiesSnapshot().isEmpty())return true;
            if(System.currentTimeMillis()-lastRegistration<5000)return false;
            lastRegistration=System.currentTimeMillis();
        }
        boolean ok=repository.registerDevice()!=null;
        if(ok)preferences.edit().putString("device",new JSONObject(repository.getDeviceIdentity()).toString()).putString("device_cookies",new JSONObject(repository.registrationCookiesSnapshot()).toString()).apply();
        return ok;
    }
    public void recordProblem(String context,Throwable error){
        lastProblem=CrashDiagnostics.describe(context,error);preferences.edit().putString("last_problem",lastProblem).apply();
    }
    public Detail detail(String id) {
        final long revision;
        synchronized(this){
            Detail cached=details.get(id);
            if(cached!=null)return cached;
            revision=accountRevision;
        }
        Detail loaded=playback.detail(id);
        synchronized(this){
            if(revision!=accountRevision)throw new IllegalStateException("红果账号已切换，请重新打开");
            details.put(id,loaded);
            return loaded;
        }
    }
    int preferredQuality(){
        int value=preferences.getInt("playback_quality",0);
        return PlaybackQuality.INSTANCE.getOptions().contains(value)?value:0;
    }
    void setPreferredQuality(int value){
        if(value!=0&&!PlaybackQuality.INSTANCE.getOptions().contains(value))throw new IllegalArgumentException("清晰度设置无效");
        preferences.edit().putInt("playback_quality",value).apply();
    }
    int quality(){int preferred=preferredQuality();return preferred==0?Integer.MAX_VALUE:preferred;}
    private AccountCredentials credentials(String cookie){return new AccountCredentials(cookie,"","","","",0,Collections.emptyMap());}
    public synchronized void login(AccountCredentials value)throws Exception{
        library.openSession(CloudSync.owner(value));repository.setCredentials(value);accountProfile=null;accountRevision++;details.clear();
        if(playback!=null)playback.clear();
        if(sync!=null)sync.changed();libraryChanged();
        try{AccountStore.save(preferences,value.getCookie());accountSaved=true;}catch(Exception ignored){accountSaved=false;preferences.edit().remove("account_sealed").apply();}
    }
    public synchronized void logout()throws Exception{
        library.openSession("");repository.setCredentials(null);accountProfile=null;accountSaved=false;accountRevision++;details.clear();
        if(playback!=null)playback.clear();if(sync!=null)sync.changed();preferences.edit().remove("account_sealed").apply();libraryChanged();
    }
    public JSONArray history(){return library.history();}
    public JSONArray favorites(){return library.favorites();}
    public void progress(JSONObject series,int index,long position,long duration){
        progress(series,index,position,duration,System.currentTimeMillis());
    }
    public void progress(JSONObject series,int index,long position,long duration,long capturedAt){
        final LibraryState.Ticket ticket=library.ticket();
        libraryWriter.execute(()->{try{synchronized(library){if(!library.valid(ticket))return;if(library.progress(series,index,position,duration,capturedAt)){if(sync!=null)sync.kick(30000);libraryChanged();}}}catch(Exception problem){lastProblem="观看进度保存失败";}});
    }
    public boolean favorite(JSONObject series)throws Exception{
        boolean value=library.favorite(series);if(sync!=null)sync.kick(500);libraryChanged();return value;
    }
    public void clearHistory()throws Exception{library.clearHistory();if(sync!=null)sync.kick(500);libraryChanged();}
}

