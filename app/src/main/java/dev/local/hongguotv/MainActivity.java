package dev.local.hongguotv;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.hgtv.star.core.*;
import org.json.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String UI_URL="file:///android_asset/ui/index.html";
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private volatile WebView web;
    private FrameLayout root;
    private LinearLayout startup;
    private TextView startupMessage;
    private Button retryInterface;
    private boolean resumed,pageReady,softwareLayer;
    private int frameCheck;
    private Runnable frameTimeout,showResumeMessage;
    private TvApplication app;
    private TvSearch search;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private volatile boolean closed;
    private final Runnable libraryListener=()->runOnUiThread(()->{if(resumed&&pageReady)web.evaluateJavascript("window.TVLibraryChanged&&TVLibraryChanged()",null);});
    private CommentsPanel commentsPanel;
    private static final class LoginSession {
        final String id=java.util.UUID.randomUUID().toString();
        final AccountWebLogin client;long lastSms;
        LoginSession(TvApplication app){client=new AccountWebLogin(app.repository.getHttp());}
    }
    private volatile LoginSession loginSession;
    private synchronized void cancelLogin(String id){LoginSession s=loginSession;if(s!=null&&(id.isEmpty()||id.equals(s.id))){loginSession=null;s.client.cancel();}}
    private synchronized LoginSession beginLogin(){cancelLogin("");LoginSession s=new LoginSession(app);loginSession=s;return s;}
    private LoginSession session(JSONObject args)throws Exception{LoginSession s=loginSession;if(s==null||!s.id.equals(args.optString("session")))throw new Exception("登录已结束，请重新获取");return s;}
    private JSONObject accountStatus()throws Exception{
        AccountProfile p=app.accountProfile;
        return new JSONObject().put("signedIn",app.repository.getSignedIn()).put("saved",app.accountSaved)
            .put("nickname",p==null?"红果账号":p.getNickname()).put("displayId",p==null?"":p.getDisplayId())
            .put("profileLoaded",p!=null).put("sync",app.sync.status());
    }
    private JSONObject completeLogin(LoginSession session,AccountCredentials credentials)throws Exception{
        synchronized(this){if(closed||loginSession!=session)throw new Exception("登录已取消");app.login(credentials);loginSession=null;}
        return accountStatus().put("confirmed",true);
    }
    private JSONObject accountAction(String method,JSONObject args)throws Exception{
        switch(method){
            case "account":return accountStatus();
            case "cloudSyncStatus":return app.sync.status();
            case "cloudSyncToggle":app.preferences.edit().putBoolean("cloud_sync",args.optBoolean("enabled")).apply();app.sync.changed();return app.sync.status();
            case "cloudSyncNow":app.sync.retry();return app.sync.status();
            case "cloudSyncLocal":app.sync.syncLocal();return app.sync.status();
            case "authCancel":cancelLogin(args.optString("session"));return new JSONObject();
            case "authQr":{
                LoginSession s=beginLogin();AccountWebLogin.Qr qr=s.client.qr();
                if(loginSession!=s)throw new Exception("登录已取消");
                byte[] raw=qr.getImage();if(raw.length>1024*1024)throw new Exception("二维码图片过大");
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                android.graphics.BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw new Exception("二维码图片无效，请改用验证码登录");
                return new JSONObject().put("session",s.id).put("image","data:image/png;base64,"+android.util.Base64.encodeToString(raw,android.util.Base64.NO_WRAP)).put("expiresIn",qr.getExpiresIn());
            }
            case "authPoll":{
                LoginSession s=session(args);if(!s.client.poll())return new JSONObject().put("confirmed",false);
                return completeLogin(s,s.client.credentials());
            }
            case "authSend":{
                String number=args.optString("phone").trim();
                if(!number.matches("(?:1[0-9]{10}|\\+[0-9 ]{7,20})"))throw new Exception("请输入 11 位手机号，或以 + 开头的国际号码");
                LoginSession s=args.optString("session").isEmpty()?beginLogin():session(args);
                synchronized(s){if(System.currentTimeMillis()-s.lastSms<60000)throw new Exception("请稍后再发送验证码");s.lastSms=System.currentTimeMillis();}
                try{AccountWebLogin.Sent sent=s.client.send(number);if(loginSession!=s)throw new Exception("登录已取消");return new JSONObject().put("session",s.id).put("retrySeconds",Math.max(60,Math.min(300,sent.getRetrySeconds())));}
                catch(Exception e){s.lastSms=0;throw e;}
            }
            case "authVerify":{
                String code=args.optString("code");if(!code.matches("[0-9]{4,8}"))throw new Exception("请输入短信中的验证码");
                LoginSession s=session(args);return completeLogin(s,s.client.verify(code));
            }
            case "accountLogout":cancelLogin("");app.logout();return accountStatus();
            case "accountRefresh":{
                AccountCredentials credentials=app.repository.getCredentials();if(credentials==null)throw new Exception("请先登录红果账号");
                app.ensureDevice(false);AccountProfile profile=app.repository.accountProfile();
                if(app.repository.getCredentials()!=credentials)throw new Exception("账号已更换，请刷新");
                if(profile==null||profile.getAnonymous())throw new Exception("暂未取得账号资料，登录可能已失效，可退出后重新扫码");
                app.accountProfile=profile;app.sync.retry();return accountStatus();
            }
            case "cloudFavorites":case "cloudHistory":return libraryPage(method.equals("cloudHistory"),args);
            default:throw new Exception("账号操作暂不可用");
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);app=(TvApplication)getApplication();search=new TvSearch(app,new PinyinTitles(()->{
            try{return PinyinTitles.read(getAssets().open("pinyin.tsv"));}
            catch(java.io.IOException error){app.recordProblem("无法读取拼音词表",error);return java.util.Collections.emptyMap();}
        }));fullscreen();
        root=new FrameLayout(this);root.setBackgroundColor(0xff101014);
        startup=new LinearLayout(this);startup.setOrientation(LinearLayout.VERTICAL);startup.setGravity(Gravity.CENTER);
        startup.setBackgroundColor(0xff101014);
        startupMessage=new TextView(this);startupMessage.setTextSize(22);startupMessage.setTextColor(0xfff3f3f7);
        startup.addView(startupMessage);
        retryInterface=new Button(this);retryInterface.setText("重试打开");retryInterface.setOnClickListener(v->openInterface(true));
        startup.addView(retryInterface);
        root.addView(startup,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        openInterface(false);
    }
    private void showStartup(String message,boolean retry){
        startupMessage.setText(message);
        retryInterface.setVisibility(retry?View.VISIBLE:View.GONE);
        startup.setVisibility(View.VISIBLE);
        if(retry)retryInterface.requestFocus();
    }
    private void openInterface(boolean software){
        discardInterface();
        softwareLayer=software;
        showStartup(software?"正在重新打开红果电视…":"正在打开红果电视…",false);
        try{
            WebView view=new WebView(this);web=view;
            view.setBackgroundColor(0xff101014);
            if(software)view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            WebSettings settings=view.getSettings();
            settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setMediaPlaybackRequiresUserGesture(true);
            view.setWebViewClient(Build.VERSION.SDK_INT>=26?new RendererClient():new InterfaceClient());
            view.setWebChromeClient(new WebChromeClient());
            view.addJavascriptInterface(new Bridge(view),"TVNative");
            root.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
            view.loadUrl(UI_URL);
            if(resumed)checkInterface();
        }catch(RuntimeException | LinkageError error){
            interfaceFailed("无法初始化电视界面",error);
        }
    }
    private class InterfaceClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !url.startsWith("file:///android_asset/ui/");}
        @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return shouldOverrideUrlLoading(view,request.getUrl().toString());}
        @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
            if(view==web&&request.isForMainFrame())interfaceFailed("电视界面加载失败",new IllegalStateException("WebView code "+error.getErrorCode()));
        }
    }
    @android.annotation.TargetApi(26)
    private final class RendererClient extends InterfaceClient {
        @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
            if(view==web)interfaceFailed(detail.didCrash()?"电视界面渲染异常退出":"电视界面渲染进程已停止",new IllegalStateException("WebView renderer exited"));
            return true;
        }
    }
    private void stopFrameCheck(){
        ++frameCheck;
        if(frameTimeout!=null)uiHandler.removeCallbacks(frameTimeout);
        if(showResumeMessage!=null)uiHandler.removeCallbacks(showResumeMessage);
        frameTimeout=null;showResumeMessage=null;
    }
    private boolean checking(WebView view,int check){return resumed&&web==view&&frameCheck==check;}
    private void checkInterface(){
        stopFrameCheck();
        WebView view=web;int check=frameCheck;
        frameTimeout=()->{if(checking(view,check))interfaceFailed("电视界面启动或恢复超时",new TimeoutException("WebView did not become ready"));};
        uiHandler.postDelayed(frameTimeout,8000);
        if(pageReady){
            showResumeMessage=()->{if(checking(view,check))showStartup("正在恢复红果电视…",false);};
            uiHandler.postDelayed(showResumeMessage,600);
            view.evaluateJavascript("window.TVResume&&TVResume();window.TVReady===true",ready->{
                if(checking(view,check)&&"true".equals(ready))awaitInterfaceFrame(view,check);
            });
        }
    }
    private void awaitInterfaceFrame(WebView view,int check){
        try{
            view.postVisualStateCallback(check,new WebView.VisualStateCallback(){
                @Override public void onComplete(long requestId){
                    if(!checking(view,check))return;
                    boolean wasLoading=startup.getVisibility()==View.VISIBLE;
                    stopFrameCheck();startup.setVisibility(View.GONE);
                    if(wasLoading)view.requestFocus();
                    view.invalidate();
                }
            });
            view.invalidate();
        }catch(RuntimeException | LinkageError error){
            if(checking(view,check))interfaceFailed("无法恢复电视界面渲染",error);
        }
    }
    private void discardInterface(){
        stopFrameCheck();pageReady=false;
        WebView old=web;web=null;
        if(old==null)return;
        root.removeView(old);
        old.removeJavascriptInterface("TVNative");
        old.destroy();
    }
    private void interfaceFailed(String reason,Throwable error){
        if(closed)return;
        app.recordProblem(reason,error);
        cancelLogin("");search.cancel("");
        if(commentsPanel!=null)commentsPanel.close();
        discardInterface();
        if(!softwareLayer){
            showStartup("正在重新打开红果电视…",false);
            if(resumed)uiHandler.post(()->{if(!closed&&resumed&&web==null&&!softwareLayer)openInterface(true);});
        }else showStartup("红果电视界面暂时无法打开",true);
    }
    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private JSONArray series(CatalogPage page) {JSONArray rows=new JSONArray();for(Series s:page.getItems())rows.put(s.toJson());return rows;}
    private JSONObject page(CatalogPage page)throws Exception{search.remember(page.getItems());return new JSONObject().put("items",series(page)).put("hasMore",page.getHasMore());}
    private JSONObject resume(JSONObject value,JSONObject record,int count)throws Exception{
        int index=Math.max(0,record.optInt("index"));long position=Math.max(0,record.optLong("position")),duration=record.optLong("duration");
        if(duration>0&&position>=duration&&index+1<count){index++;position=0;}
        return value.put("resumeIndex",index).put("resumePosition",position);
    }
    private JSONObject libraryPage(boolean history,JSONObject args)throws Exception{
        JSONArray all=history?app.history():app.favorites(),items=new JSONArray();int limit=Math.max(1,Math.min(2000,args.optInt("limit",24))),start=(Math.max(1,Math.min(2000,args.optInt("page",1)))-1)*limit;
        for(int i=start;i<all.length()&&i<start+limit;i++)items.put(all.getJSONObject(i));return new JSONObject().put("items",items).put("hasMore",start+limit<all.length()).put("signedIn",app.repository.getSignedIn()).put("sync",app.sync.status());
    }
    private JSONObject result(WebView owner,String method,JSONObject args)throws Exception {
        if(method.startsWith("auth")||method.startsWith("account")||method.startsWith("cloud"))return accountAction(method,args);
        switch(method) {
            case "openComments":{
                Detail detail=app.detail(args.getString("id"));int index=Math.max(0,Math.min(detail.getEpisodes().size()-1,args.optInt("index")));
                if(detail.getEpisodes().isEmpty())throw new Exception("暂无分集评论");
                runOnUiThread(()->{if(resumed&&owner==web){commentsPanel=new CommentsPanel(this,app,detail.getSeries().getId(),detail.getEpisodes().get(index));commentsPanel.show(()->commentsPanel=null);}});return new JSONObject();
            }
            case "featurePreference":{
                String name=args.optString("name");int value=args.optInt("value");String text=args.optString("value");
                if(name.equals("danmaku_mode")&&java.util.Arrays.asList("hidden","single","top","full").contains(text))app.preferences.edit().putString(name,text).apply();
                else if(name.equals("danmaku_size")&&(value==16||value==20||value==24))app.preferences.edit().putInt(name,value).apply();
                else if(name.equals("playback_quality"))app.setPreferredQuality(args.optInt("value",-1));
                else throw new Exception("设置值无效");
                return new JSONObject();
            }
            case "home":return page(app.repository.rank(Math.max(1,args.optInt("page",1)),RankBoard.REAL));
            case "rank":return page(app.repository.rank(Math.max(1,args.optInt("page",1)),RankBoard.Companion.fromStored(args.optString("type","real"))));
            case "catalog":{
                String type=args.optString("type","real"),tag=args.optString("tag","");
                String path=type.equals("comic")?"comic-drama":type.equals("ai")?"ai-drama":"real-drama";
                if(!tag.isEmpty()&&tag.matches("[a-z-]{1,30}"))path+="/"+tag;
                return page(app.repository.category(path,Math.max(1,args.optInt("page",1))));
            }
            case "search":{
                String keyword=args.optString("keyword").trim();
                if(keyword.isEmpty()||keyword.length()>80)throw new Exception("请输入 1–80 字的剧名或关键词");
                return args.optInt("page",1)<=1||args.optString("session").isEmpty()?search.start(keyword):search.more(args.optString("session"));
            }
            case "searchStatus":return search.snapshot(args.optString("session"));
            case "searchCancel":search.cancel(args.optString("session"));return new JSONObject();
            case "detail":{
                Detail d=app.detail(args.getString("id"));
                search.remember(java.util.Collections.singletonList(d.getSeries()));
                JSONObject value=d.getSeries().toJson();
                value.put("episodes",new JSONArray(d.getEpisodes()));
                JSONArray old=app.history();
                for(int i=0;i<old.length();i++){JSONObject h=old.optJSONObject(i);if(h!=null&&h.optString("id").equals(d.getSeries().getId())){resume(value,h,d.getEpisodes().size());break;}}
                boolean favorite=false;JSONArray fs=app.favorites();
                for(int i=0;i<fs.length();i++)if(fs.getJSONObject(i).optString("id").equals(d.getSeries().getId()))favorite=true;
                return new JSONObject().put("detail",value).put("favorite",favorite);
            }
            case "play":{
                String id=args.getString("id");Detail d=app.detail(id);
                int index=Math.max(0,Math.min(d.getEpisodes().size()-1,args.optInt("index")));
                JSONObject record=d.getSeries().toJson().put("episodes",new JSONArray(d.getEpisodes()))
                    .put("contentType",d.getContentType());
                runOnUiThread(()->{if(resumed&&owner==web){Intent intent=new Intent(MainActivity.this,PlayerActivity.class).putExtra("series",record.toString()).putExtra("index",index);if(args.optBoolean("cloudResume"))intent.putExtra("position",Math.max(0,args.optLong("position")));startActivity(intent);}});
                return new JSONObject().put("started",true);
            }
            case "history":return libraryPage(true,args);
            case "favorites":return libraryPage(false,args);
            case "libraryEntry":{
                String id=args.optString("id");JSONObject value=new JSONObject().put("favorite",false).put("resumeIndex",0).put("resumePosition",0);
                JSONArray favorites=app.favorites(),history=app.history();for(int i=0;i<favorites.length();i++)if(id.equals(favorites.getJSONObject(i).optString("id")))value.put("favorite",true);
                for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if(id.equals(row.optString("id"))){Detail detail=app.details.get(id);resume(value,row,detail==null?row.optInt("episodeCount"):detail.getEpisodes().size());break;}}return value;
            }
            case "favorite":return new JSONObject().put("favorite",app.favorite(app.detail(args.getString("id")).getSeries().toJson()));
            case "settings":return new JSONObject().put("version",getPackageManager().getPackageInfo(getPackageName(),0).versionName).put("android",Build.VERSION.RELEASE).put("model",Build.MODEL).put("lastProblem",app.lastProblem).put("quality",app.preferredQuality()).put("danmakuMode",app.preferences.getString("danmaku_mode","top")).put("danmakuSize",app.preferences.getInt("danmaku_size",20)).put("signedIn",app.repository.getSignedIn()).put("syncEnabled",app.preferences.getBoolean("cloud_sync",true));
            case "clearHistory":app.clearHistory();return new JSONObject().put("cleared",true);
            case "licenses":{
                java.io.ByteArrayOutputStream content=new java.io.ByteArrayOutputStream();
                try(java.io.InputStream in=getAssets().open("licenses.txt")){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)content.write(buffer,0,n);}
                String heading="红果电视测试版 · GPL-3.0-only\n"
                    +"内容核心：ps1001/HGTV，派生自 pan8664716/HGTV 与 N3urda/hongguoTV。\n"
                    +"Kotlin、Media3、OkHttp、Okio：Apache-2.0；Bouncy Castle、jsoup：MIT。\n"
                    +"完整对应源码随 APK 提供。\n\n";
                return new JSONObject().put("text",heading+content.toString("UTF-8"));
            }
            default:throw new Exception("此操作暂不可用");
        }
    }
    public final class Bridge {
        private final WebView owner;
        Bridge(WebView owner){this.owner=owner;}
        @JavascriptInterface public void ready(){
            runOnUiThread(()->{
                if(closed||owner!=web||pageReady)return;
                pageReady=true;
                if(resumed)awaitInterfaceFrame(owner,frameCheck);
            });
        }
        @JavascriptInterface public void call(String id,String method,String json) {
            if(closed||owner!=web||id==null||!id.matches("[0-9]{1,12}")||json==null||json.length()>8192)return;
            try{workers.execute(()->{
                if(closed||owner!=web)return;
                JSONObject response=new JSONObject();
                try {response.put("ok",true).put("data",result(owner,method,new JSONObject(json)));}
                catch(Exception | LinkageError problem){
                    if(closed||owner!=web)return;
                    String message=problem.getMessage();if(message==null||message.trim().isEmpty())message=problem.getClass().getSimpleName();
                    if(problem instanceof LinkageError){if(search!=null&&method.startsWith("search"))search.cancel("");app.recordProblem("组件兼容性异常："+method,problem);message="当前系统暂时无法初始化此功能，可在设置中查看最近提示";}
                    // Signed request URLs, cookies and media keys are never returned to the page.
                    if(message.contains("http:")||message.contains("https:"))message="网络请求失败，请稍后重试";
                    if(method.startsWith("auth")||method.startsWith("account")||method.startsWith("cloud"))message=message.replaceAll("[0-9]{6,}","••••");
                    else if(!(problem instanceof LinkageError))app.lastProblem=method+": "+message;
                    try{response.put("ok",false).put("error",message);}catch(Exception ignored){}
                }
                final String script="window.TVReply&&TVReply("+JSONObject.quote(id)+","+response.toString()+")";
                runOnUiThread(()->{if(!closed&&owner==web)owner.evaluateJavascript(script,null);});
            });}catch(RejectedExecutionException ignored){}
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_MENU&&pageReady){if(event.getAction()==KeyEvent.ACTION_UP)web.evaluateJavascript("window.TVMenu&&TVMenu()",null);return true;}
        return super.dispatchKeyEvent(event);
    }
    private void confirmExit(){new AlertDialog.Builder(this).setTitle("退出红果电视？").setNegativeButton("继续观看",null).setPositiveButton("退出",(d,w)->finish()).show();}
    @Override public void onBackPressed() {
        WebView current=web;
        if(!pageReady||startup.getVisibility()==View.VISIBLE){confirmExit();return;}
        current.evaluateJavascript("window.TVBack?TVBack():false",answer->{if(resumed&&web==current&&"false".equals(answer))confirmExit();});
    }
    @Override protected void onStart(){super.onStart();app.libraryListeners.add(libraryListener);}
    @Override protected void onResume(){
        super.onResume();resumed=true;fullscreen();
        if(web!=null){web.onResume();checkInterface();}
        else if(!softwareLayer)openInterface(true);
    }
    @Override protected void onPause(){resumed=false;stopFrameCheck();if(web!=null)web.onPause();super.onPause();}
    @Override protected void onStop(){app.libraryListeners.remove(libraryListener);cancelLogin("");if(search!=null)search.cancel("");if(commentsPanel!=null)commentsPanel.close();if(pageReady)web.evaluateJavascript("window.TVSuspend&&TVSuspend()",null);super.onStop();}
    @Override protected void onDestroy(){closed=true;cancelLogin("");if(search!=null)search.close();workers.shutdownNow();discardInterface();super.onDestroy();}
}

