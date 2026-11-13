package dev.local.hongguotv;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.ui.*;
import com.hgtv.star.core.*;
import org.json.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlayerActivity extends Activity {
    private TvApplication app;
    private JSONObject series;
    private int contentType;
    private JSONArray episodes;
    private int index;
    private volatile int generation;
    private ExoPlayer player;
    private PreparedPlayback currentReady,queuedReady,warmingReady;
    private DefaultPreloadManager mediaPreloader;
    private int warmingIndex=-1,prefetchAttempts;
    private long nextPrefetchAttempt,lastCloudCheckpoint;
    private long observedPosition=-1,savedPosition=-1,lastMovementAt,lastLocalCheckpoint;
    private int observedIndex=-1,savedIndex=-1;
    private int queuedIndex=-1;
    private String attemptedNext="";
    private long lastDuration;
    private volatile PreparedPlayback.Preparation foreground;
    private PlayerView video;
    private DanmakuOverlay danmakuOverlay;
    private DanmakuController danmaku;
    private CommentsPanel commentsPanel;
    private LinearLayout panel;
    private TextView title,status,clock;
    private SeekBar seek;
    private Button toggle;
    private static final float[] PLAYBACK_SPEEDS={1f,1.5f,2f};
    private float playbackSpeed=1f;
    private Button qualityButton,speedButton;
    private volatile boolean running;
    private boolean userPaused,dialogOpen,seeking,advancing;
    private long position,bufferingSince,waitingForFrameSince;
    private String renderedMediaId="",blockedNext="",blockedCurrent="";
    private boolean quietPreparation;
    private final PlaybackAttempts attempts=new PlaybackAttempts();
    private java.util.concurrent.atomic.AtomicReference<String> frameTarget;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final ExecutorService preloadWorker=Executors.newSingleThreadExecutor();
    private final EpisodePreloader<PreparedPlayback> preloader=new EpisodePreloader<>(preloadWorker,System::currentTimeMillis,90000);
    private final Runnable hide=()->{if(!dialogOpen&&player!=null&&player.getPlayerError()==null&&player.isPlaying()){panel.setVisibility(View.GONE);video.requestFocus();}};
    private final Runnable tick=new Runnable(){@Override public void run(){save(false);updateTime();watchPlayback();prepareNext();syncProgress(false);handler.postDelayed(this,1000);}};
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable shape(int color,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));if(border!=0)d.setStroke(dp(2),border);return d;}
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setAllCaps(false);
        b.setPadding(dp(12),0,dp(12),0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMinWidth(0);b.setMinimumWidth(0);
        StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{android.R.attr.state_focused},shape(0xffb51e4a,0xffff91b0));bg.addState(new int[]{},shape(0xff2b2b34,0));b.setBackground(bg);
        b.setOnClickListener(v->{action.run();showPanel();});return b;
    }
    private Button action(LinearLayout row,String label,Runnable callback){Button b=button(label,callback);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(43));p.setMargins(0,0,dp(9),0);row.addView(b,p);return b;}
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);app=(TvApplication)getApplication();
        float storedSpeed=app.preferences.getFloat("playback_speed",1f);
        for(float speed:PLAYBACK_SPEEDS)if(speed==storedSpeed)playbackSpeed=speed;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fullscreen();
        try {
            series=new JSONObject(getIntent().getStringExtra("series"));episodes=series.getJSONArray("episodes");
            contentType=series.optInt("contentType");
            if(episodes.length()==0)throw new Exception("暂无可播放分集");
            index=Math.max(0,Math.min(episodes.length()-1,saved==null?getIntent().getIntExtra("index",0):saved.getInt("index")));
            position=saved==null?(getIntent().hasExtra("position")?getIntent().getLongExtra("position",0):resumePosition()):saved.getLong("position");
        }catch(Exception e){Toast.makeText(this,"剧集数据无效",Toast.LENGTH_LONG).show();finish();return;}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        video=new PlayerView(this);video.setUseController(false);video.setFocusable(true);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);video.setKeepContentOnPlayerReset(true);
        danmakuOverlay=new DanmakuOverlay(this,app,()->player==null?0:player.getCurrentPosition(),()->player!=null&&player.isPlaying());root.addView(danmakuOverlay,new FrameLayout.LayoutParams(-1,-1));danmaku=new DanmakuController(app,danmakuOverlay);
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(26),dp(20),dp(26),dp(18));
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00101014,0xee101014,0xff101014}));
        title=text("",23,Color.WHITE);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        LinearLayout info=new LinearLayout(this);info.setGravity(Gravity.CENTER_VERTICAL);panel.addView(info);
        status=text("正在获取播放地址…",14,0xffb6b6c2);status.setPadding(0,dp(7),dp(12),dp(6));
        info.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        clock=text("",14,0xffa0a0af);info.addView(clock,new LinearLayout.LayoutParams(-2,-2));
        seek=new SeekBar(this);seek.setMax(1000);seek.setFocusable(false);seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffff2f62));panel.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar s){seeking=true;handler.removeCallbacks(hide);}
            @Override public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);seeking=false;showPanel();}
            @Override public void onProgressChanged(SeekBar s,int v,boolean user){}
        });
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);panel.addView(row);
        toggle=button("暂停",this::toggle);LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(dp(88),dp(43));toggleParams.setMargins(0,0,dp(9),0);row.addView(toggle,toggleParams);
        action(row,"上一集",()->change(index-1));
        action(row,"下一集",()->change(index+1));
        action(row,"选集",this::chooseEpisodes);
        action(row,"重试",this::retryPlayback);
        LinearLayout options=new LinearLayout(this);options.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams optionsParams=new LinearLayout.LayoutParams(-1,-2);optionsParams.topMargin=dp(8);
        panel.addView(options,optionsParams);
        qualityButton=action(options,"清晰度",this::chooseQuality);
        speedButton=action(options,speedLabel(),this::chooseSpeed);
        action(options,"弹幕",this::chooseDanmaku);
        action(options,"评论",this::showComments);
        TextView help=text("左右：快退/快进    菜单：控制栏    返回：收起/退出",12,0xff858592);
        options.addView(help,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(panel,p);setContentView(root);video.requestFocus();
        updateTitle();
    }
    private void fullscreen(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    private long resumePosition(){
        JSONArray history=app.history();
        for(int i=0;i<history.length();i++){JSONObject h=history.optJSONObject(i);if(h!=null&&h.optString("id").equals(series.optString("id"))&&h.optInt("index")==index){long p=h.optLong("position"),d=h.optLong("duration");return d>0&&p>d-3000?0:p;}}
        return 0;
    }
    private void updateTitle(){title.setText(series.optString("title")+"  ·  第 "+(index+1)+" 集 / "+episodes.length()+" 集");}
    private String attemptKey(int n){return series.optString("id")+":"+episodes.optString(n)+":"+app.quality()+":"+app.accountRevision;}
    private int episodeQuality(int n){return attempts.ceiling(attemptKey(n),app.quality());}
    private String episodeKey(int n){return attemptKey(n)+":"+episodeQuality(n);}
    private java.util.List<String> ahead(int from){java.util.ArrayList<String> result=new java.util.ArrayList<>();for(int i=from+1;i<episodes.length()&&result.size()<15;i++)result.add(episodes.optString(i));return result;}
    private void openEpisode(boolean initial){openEpisode(initial,false);}
    private void openEpisode(boolean initial,boolean automatic) {
        quietPreparation=automatic&&panel.getVisibility()!=View.VISIBLE;
        final int token=++generation;release();updateTitle();status.setText(automatic?"正在切换可播放清晰度…":"正在准备播放…");if(!quietPreparation)showPanel();handler.removeCallbacks(hide);
        final String key=episodeKey(index),episode=episodes.optString(index);final int requested=episodeQuality(index);
        final java.util.Set<String> rejected=attempts.rejected(attemptKey(index),app.quality());final java.util.List<String> following=ahead(index);
        if(!preloader.contains(key))preloader.clear();
        attemptedNext="";prefetchAttempts=0;nextPrefetchAttempt=0;
        final PreparedPlayback.Preparation job=new PreparedPlayback.Preparation();foreground=job;
        worker.execute(()->{
            PreparedPlayback ready=null;
            try {
                ready=preloader.claim(key,true);
                if(token!=generation||!running){if(ready!=null)ready.close();return;}
                if(ready==null){
                    ready=job.load(app,series.optString("id"),episode,requested,false,key,following,rejected,contentType);
                }
                final PreparedPlayback result=ready;
                runOnUiThread(()->{
                    if(token!=generation||!running){result.close();return;}
                    foreground=null;currentReady=result;createPlayer(result);
                });
            }catch(Exception problem){
                if(ready!=null)ready.close();
                String message=problem.getMessage();
                if(message==null||message.trim().isEmpty())message="请求失败";
                if(message.contains("http:")||message.contains("https:"))message="网络请求失败";
                final String reason=message;
                runOnUiThread(()->{
                    if(token!=generation||!running)return;
                    app.lastProblem="play: "+reason;
                    status.setText("暂时无法播放："+reason+"。可调整清晰度或重试。");
                    panel.setVisibility(View.VISIBLE);toggle.setText("播放");toggle.requestFocus();
                });
            }
        });
    }
    private androidx.media3.exoplayer.source.MediaSource source(PreparedPlayback ready) {
        MediaItem item=new MediaItem.Builder().setUri(ready.stream.getUrl()).setMediaId(ready.mediaId).build();
        if(ready.remote!=null)return new ProgressiveMediaSource.Factory(()->new VideoDataSource(ready.remote)).createMediaSource(item);
        String host=Uri.parse(ready.stream.getUrl()).getHost();
        String referer=contentType==ContentRepositoryKt.CREATOR_VIDEO_CONTENT_TYPE
            ?"https://novelquickapp.com/":"https://novel.snssdk.com/";
        if("hongguoduanju.com".equals(host))referer="https://hongguoduanju.com/";
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(java.util.Collections.singletonMap("Referer",referer));
        return new ProgressiveMediaSource.Factory(http).createMediaSource(item);
    }
    private String quality(){return currentReady==null?"":currentReady.stream.getQuality();}
    private void createPlayer(PreparedPlayback ready) {
        lastCloudCheckpoint=SystemClock.elapsedRealtime();bufferingSince=0;waitingForFrameSince=lastCloudCheckpoint;renderedMediaId="";
        observedIndex=savedIndex=index;observedPosition=savedPosition=position;lastMovementAt=0;lastLocalCheckpoint=0;
        DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(15000,45000,1000,2000)
            .setTargetBufferBytes(16*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();
        DefaultRenderersFactory renderers=new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        DefaultPreloadManager.Builder builder=new DefaultPreloadManager.Builder(this,n->n==index+1?DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(8000):null)
            .setRenderersFactory(renderers).setLoadControl(load);
        player=builder.buildExoPlayer();mediaPreloader=builder.build();video.setPlayer(player);
        final ExoPlayer session=player;final DefaultPreloadManager manager=mediaPreloader;
        final java.util.concurrent.atomic.AtomicReference<String> target=new java.util.concurrent.atomic.AtomicReference<>(ready.mediaId);frameTarget=target;
        session.setVideoFrameMetadataListener((time,releaseTime,format,mediaFormat)->{
            String media=target.getAndSet(null);if(media!=null)handler.post(()->{if(player==session&&currentReady!=null&&media.equals(currentReady.mediaId)){renderedMediaId=media;waitingForFrameSince=0;}});
        });
        mediaPreloader.addListener(new PreloadManagerListener(){
            @Override public void onCompleted(MediaItem item){if(mediaPreloader==manager&&warmingReady!=null&&item.mediaId.equals(warmingReady.mediaId))attachWarming(true);}
            @Override public void onError(PreloadException error){
                if(mediaPreloader!=manager||warmingReady==null||!error.mediaItem.mediaId.equals(warmingReady.mediaId))return;
                PreparedPlayback failed=warmingReady;int next=warmingIndex;warmingReady=null;warmingIndex=-1;
                manager.remove(error.mediaItem);lowerNext(failed,next,safeReason(error.getCause()));failed.close();
                boolean ended=player!=null&&player.getPlaybackState()==Player.STATE_ENDED;advancing=false;prepareNext();if(ended)continueAfterEnd();
            }
        });
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlaybackSpeed(playbackSpeed);
        player.addListener(new Player.Listener(){
            @Override public void onMediaItemTransition(MediaItem item,int reason){
                if(player==session&&running)adoptQueued(item);
            }
            @Override public void onPlaybackStateChanged(int state){
                if(player!=session||!running)return;
                if(state==Player.STATE_ENDED){position=0;save();if(index+1<episodes.length())continueAfterEnd();else{status.setText("本剧已播放完");showPanel();handler.removeCallbacks(hide);}}
                else if(state==Player.STATE_BUFFERING)status.setText("正在缓冲… · "+quality());
                else if(state==Player.STATE_READY){status.setText(quality()+" · 左右快进/快退，菜单选集");if(panel.getVisibility()==View.VISIBLE){handler.removeCallbacks(hide);handler.postDelayed(hide,6500);}prepareNext();}
            }
            @Override public void onIsPlayingChanged(boolean playing){if(player==session)toggle.setText(playing?"暂停":"播放");}
            @Override public void onRenderedFirstFrame(){if(player==session&&currentReady!=null){renderedMediaId=currentReady.mediaId;waitingForFrameSince=0;}}
            @Override public void onPlayerError(PlaybackException e){
                if(player!=session||!running)return;
                adoptQueued(session.getCurrentMediaItem());
                if(!recoverQueued(e))fallbackCurrent(e.getErrorCodeName());
            }
        });
        player.setMediaSource(source(ready));if(position>0)player.seekTo(position);player.prepare();player.setPlayWhenReady(!userPaused);if(!quietPreparation)showPanel();prepareNext();
    }
    private void adoptQueued(MediaItem item){
        if(item==null||queuedReady==null||!item.mediaId.equals(queuedReady.mediaId))return;
        // The existing player advances its prepared queue; no new address request or control overlay.
        app.progress(series,index,Math.max(0,lastDuration),Math.max(0,lastDuration));
        if(app.sync!=null)app.sync.kick(500);
        PreparedPlayback previous=currentReady;currentReady=queuedReady;queuedReady=null;
        index=queuedIndex;queuedIndex=-1;position=0;lastDuration=0;attemptedNext="";prefetchAttempts=0;nextPrefetchAttempt=0;lastCloudCheckpoint=0;
        observedIndex=savedIndex=index;observedPosition=savedPosition=0;lastMovementAt=0;lastLocalCheckpoint=0;
        advancing=false;
        updateTitle();status.setText(quality()+" · 连续播放");
        int before=player.getCurrentMediaItemIndex();if(before>0)player.removeMediaItems(0,before);
        if(previous!=null&&mediaPreloader!=null)mediaPreloader.remove(new MediaItem.Builder().setUri(previous.stream.getUrl()).setMediaId(previous.mediaId).build());
        if(previous!=null)previous.close();
        prepareNext();
        bufferingSince=0;waitingForFrameSince=SystemClock.elapsedRealtime();renderedMediaId="";
        if(frameTarget!=null)frameTarget.set(currentReady.mediaId);
    }
    private void lowerNext(PreparedPlayback failed,int next,String reason){
        app.lastProblem="第 "+(next+1)+" 集 "+failed.stream.getQuality()+" 预加载失败："+reason;
        if(!attempts.lower(attemptKey(next),app.quality(),failed.stream))blockedNext=attemptKey(next);
        prefetchAttempts=0;attemptedNext="";nextPrefetchAttempt=0;
    }
    private boolean recoverQueued(PlaybackException error){
        if(queuedReady==null||!(error instanceof ExoPlaybackException))return false;
        ExoPlaybackException ex=(ExoPlaybackException)error;if(ex.mediaPeriodId==null)return false;
        Timeline timeline=player.getCurrentTimeline();int periodIndex=timeline.getIndexOfPeriod(ex.mediaPeriodId.periodUid);
        if(periodIndex==C.INDEX_UNSET)return false;
        int window=timeline.getPeriod(periodIndex,new Timeline.Period()).windowIndex;
        if(window<0||window>=player.getMediaItemCount()||!player.getMediaItemAt(window).mediaId.equals(queuedReady.mediaId))return false;
        PreparedPlayback failed=queuedReady;int next=queuedIndex;queuedReady=null;queuedIndex=-1;
        player.removeMediaItem(window);if(mediaPreloader!=null)mediaPreloader.remove(new MediaItem.Builder().setUri(failed.stream.getUrl()).setMediaId(failed.mediaId).build());
        lowerNext(failed,next,error.getErrorCodeName());failed.close();
        player.prepare();player.setPlayWhenReady(!userPaused);prepareNext();return true;
    }
    private void fallbackCurrent(String reason){
        if(!running||player==null||currentReady==null||blockedCurrent.equals(attemptKey(index)))return;
        position=Math.max(0,player.getCurrentPosition());app.lastProblem="第 "+(index+1)+" 集 "+quality()+"："+reason;
        if(attempts.lower(attemptKey(index),app.quality(),currentReady.stream)){
            preloader.clear();openEpisode(false,true);
        }else{
            blockedCurrent=attemptKey(index);
            status.setText("当前清晰度设置下没有可播放的片源。可调整清晰度或重试。");showPanel();handler.removeCallbacks(hide);toggle.requestFocus();
        }
    }
    private void watchPlayback(){
        if(player==null||currentReady==null||player.getPlayerError()!=null||!player.getPlayWhenReady()||dialogOpen||blockedCurrent.equals(attemptKey(index))){bufferingSince=0;waitingForFrameSince=0;return;}
        long now=SystemClock.elapsedRealtime();
        if(player.getPlaybackState()==Player.STATE_BUFFERING){if(bufferingSince==0)bufferingSince=now;if(now-bufferingSince>=15000)fallbackCurrent("缓冲超时");}
        else{bufferingSince=0;if(player.getPlaybackState()==Player.STATE_READY&&!currentReady.mediaId.equals(renderedMediaId)){
            if(waitingForFrameSince==0)waitingForFrameSince=now;
            if(now-waitingForFrameSince>=15000)fallbackCurrent("未能显示视频画面");
        }}
    }
    private void retryPlayback(){
        save();if(player!=null&&player.getPlaybackState()==Player.STATE_ENDED&&index+1<episodes.length()){index++;position=resumePosition();}
        attempts.clear();blockedNext="";blockedCurrent="";preloader.clear();app.playback.invalidate(series.optString("id"),episodes.optString(index));openEpisode(false);
    }
    private String safeReason(Throwable problem){if(problem==null)return "未知错误";while(problem.getCause()!=null)problem=problem.getCause();String message=problem.getMessage();if(message==null)return problem.getClass().getSimpleName();return message.contains("http:")||message.contains("https:")?problem.getClass().getSimpleName():message.substring(0,Math.min(180,message.length()));}
    private void attachWarming(boolean loaded){
        if(warmingReady==null||player==null||mediaPreloader==null||!running)return;
        PreparedPlayback ready=warmingReady;int next=warmingIndex;warmingReady=null;warmingIndex=-1;
        MediaItem item=new MediaItem.Builder().setUri(ready.stream.getUrl()).setMediaId(ready.mediaId).build();
        MediaSource source=loaded?mediaPreloader.getMediaSource(item):null;
        if(source==null){mediaPreloader.remove(item);source=source(ready);}
        queuedReady=ready;queuedIndex=next;boolean ended=player.getPlaybackState()==Player.STATE_ENDED;
        player.addMediaSource(source);
        if(ended&&player.hasNextMediaItem()){player.seekToNextMediaItem();player.setPlayWhenReady(!userPaused);}
    }
    private void prepareNext(){
        if(player==null||currentReady==null||queuedReady!=null||warmingReady!=null||advancing||index+1>=episodes.length()||!running)return;
        final int next=index+1;if(blockedNext.equals(attemptKey(next)))return;final String key=episodeKey(next);
        if(!key.equals(attemptedNext)){attemptedNext=key;prefetchAttempts=0;nextPrefetchAttempt=0;}
        Exception failed=preloader.failure(key);
        if(failed!=null){app.lastProblem="next #"+(next+1)+" address: "+safeReason(failed)+" ["+app.playback.getLastTrace()+"]";preloader.claim(key,false);app.playback.invalidate(series.optString("id"),episodes.optString(next));nextPrefetchAttempt=SystemClock.elapsedRealtime()+3000;}
        if(!preloader.contains(key)&&SystemClock.elapsedRealtime()>=nextPrefetchAttempt&&prefetchAttempts<3){
            prefetchAttempts++;
            final int quality=episodeQuality(next);final java.util.Set<String> rejected=attempts.rejected(attemptKey(next),app.quality());final java.util.List<String> following=ahead(next);
            PreparedPlayback.Preparation job=new PreparedPlayback.Preparation();
            preloader.start(key,()->job.load(app,series.optString("id"),episodes.optString(next),quality,true,key,following,rejected,contentType),job::cancel);
        }
        PreparedPlayback ready=preloader.claim(key,false);
        if(ready!=null){
            if(!key.equals(episodeKey(next))){ready.close();return;}
            warmingReady=ready;warmingIndex=next;mediaPreloader.setCurrentPlayingIndex(index);mediaPreloader.add(source(ready),next);mediaPreloader.invalidate();
        }
    }
    private void continueAfterEnd(){
        if(advancing)return;
        if(blockedNext.equals(attemptKey(index+1))){status.setText("下一集没有可播放的片源。可调整清晰度或重试。");showPanel();handler.removeCallbacks(hide);return;}
        if(warmingReady!=null){attachWarming(true);return;}
        if(queuedReady!=null&&player.hasNextMediaItem()){player.seekToNextMediaItem();player.setPlayWhenReady(!userPaused);return;}
        advancing=true;final int token=++generation,next=index+1,quality=episodeQuality(next);final String key=episodeKey(next);
        final java.util.Set<String> rejected=attempts.rejected(attemptKey(next),app.quality());final java.util.List<String> following=ahead(next);
        final PreparedPlayback.Preparation job=new PreparedPlayback.Preparation();foreground=job;
        // A late preload joins the same future. Keep the picture and existing player while waiting.
        worker.execute(()->{
            PreparedPlayback ready=null;
            try{
                ready=preloader.claim(key,true);
                if(token!=generation||!running){if(ready!=null)ready.close();return;}
                if(ready==null)ready=job.load(app,series.optString("id"),episodes.optString(next),quality,false,key,following,rejected,contentType);
                final PreparedPlayback result=ready;
                runOnUiThread(()->{
                    if(token!=generation||!running||player==null){result.close();return;}
                    foreground=null;queuedReady=result;queuedIndex=next;player.addMediaSource(source(result));
                    if(player.hasNextMediaItem())player.seekToNextMediaItem();player.setPlayWhenReady(!userPaused);
                });
            }catch(Exception failure){
                if(ready!=null)ready.close();
                final String reason=safeReason(failure);runOnUiThread(()->{if(token==generation&&running){advancing=false;app.lastProblem="next #"+(next+1)+": "+reason+" ["+app.playback.getLastTrace()+"]";status.setText("下一集播放失败："+reason+"。可选择下一集或重试。");showPanel();handler.removeCallbacks(hide);}});
            }
        });
    }
    private void toggle(){if(player==null){openEpisode(false);return;}userPaused=player.getPlayWhenReady();player.setPlayWhenReady(!userPaused);showPanel();}
    private void updateTime(){
        if(player==null)return;long duration=player.getDuration(),current=player.getCurrentPosition();
        if(duration>0)lastDuration=duration;
        clock.setText(time(current)+" / "+time(Math.max(0,duration)));
        if(!seeking&&duration>0)seek.setProgress((int)(current*1000/duration));
        if(danmaku!=null)danmaku.tick(series.optString("id"),episodes.optString(index),current,duration);
    }
    private String time(long ms){long sec=Math.max(0,ms/1000);return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private void save(){save(true);}
    private void save(boolean force){
        if(series==null||player==null)return;long current=player.getCurrentPosition(),duration=Math.max(0,player.getDuration());
        if(player.getPlaybackState()==Player.STATE_ENDED)current=Math.max(current,Math.max(duration,lastDuration));
        if(player.getPlaybackState()!=Player.STATE_READY&&player.getPlaybackState()!=Player.STATE_ENDED)return;
        position=current;if(observedIndex!=index||observedPosition!=current){observedIndex=index;observedPosition=current;lastMovementAt=System.currentTimeMillis();}
        if(lastMovementAt==0||(savedIndex==index&&savedPosition==current))return;
        long now=SystemClock.elapsedRealtime();if(!force&&now-lastLocalCheckpoint<5000)return;
        savedIndex=index;savedPosition=current;lastLocalCheckpoint=now;app.progress(series,index,current,duration,lastMovementAt);
    }
    private void syncProgress(boolean force){if(player==null||app.sync==null||series==null)return;long now=SystemClock.elapsedRealtime();if(!force&&(!player.isPlaying()||now-lastCloudCheckpoint<30000))return;lastCloudCheckpoint=now;app.sync.kick(force?500:0);}
    private void release(){
        advancing=false;bufferingSince=0;waitingForFrameSince=0;renderedMediaId="";frameTarget=null;
        if(danmaku!=null)danmaku.clear();
        if(player!=null){ExoPlayer old=player;player=null;video.setPlayer(null);old.release();}
        if(mediaPreloader!=null){mediaPreloader.release();mediaPreloader=null;}
        if(currentReady!=null){currentReady.close();currentReady=null;}
        if(queuedReady!=null){queuedReady.close();queuedReady=null;}queuedIndex=-1;
        if(warmingReady!=null){warmingReady.close();warmingReady=null;}warmingIndex=-1;
        PreparedPlayback.Preparation job=foreground;foreground=null;if(job!=null)job.cancel();
    }
    private void change(int next){
        if(next<0||next>=episodes.length()){Toast.makeText(this,next<0?"已经是第一集":"已经是最后一集",Toast.LENGTH_SHORT).show();return;}
        if(advancing&&next==index+1)return;
        if(player!=null&&queuedReady!=null&&queuedIndex==next){userPaused=false;player.seekToNextMediaItem();player.play();return;}
        save();syncProgress(true);index=next;position=resumePosition();userPaused=false;openEpisode(false);
    }
    private void showPanel(){if(panel==null)return;panel.setVisibility(View.VISIBLE);handler.removeCallbacks(hide);handler.postDelayed(hide,6500);}
    private void chooseQuality(){
        int[] values={0,1080,720,480};
        String[] labels={"自动（最高可播）","1080P","720P","480P"};
        int selected=0;
        for(int i=1;i<values.length;i++)if(values[i]==app.preferredQuality())selected=i;
        String current=quality();
        showPlaybackOptions(qualityButton,current.isEmpty()?"播放清晰度":"播放清晰度 · 当前 "+current,labels,selected,
            which->setQuality(values[which]));
    }
    private void setQuality(int value){
        if(value==app.preferredQuality())return;
        if(player!=null){
            adoptQueued(player.getCurrentMediaItem());
            save();
            position=Math.max(0,player.getCurrentPosition());
        }
        app.setPreferredQuality(value);
        if(player!=null&&player.getPlaybackState()==Player.STATE_ENDED){
            if(index+1>=episodes.length())return;
            index++;
            position=0;
        }
        attempts.clear();blockedCurrent="";blockedNext="";
        preloader.clear();
        openEpisode(false);
    }
    private String speedLabel(){return "倍速 "+PlaybackSpeed.INSTANCE.label(playbackSpeed);}
    private void chooseSpeed(){
        String[] labels=new String[PLAYBACK_SPEEDS.length];
        int selected=0;
        for(int i=0;i<PLAYBACK_SPEEDS.length;i++){
            labels[i]=PlaybackSpeed.INSTANCE.label(PLAYBACK_SPEEDS[i]);
            if(PLAYBACK_SPEEDS[i]==playbackSpeed)selected=i;
        }
        showPlaybackOptions(speedButton,"播放倍速",labels,selected,which->{
            playbackSpeed=PLAYBACK_SPEEDS[which];
            app.preferences.edit().putFloat("playback_speed",playbackSpeed).apply();
            if(player!=null)player.setPlaybackSpeed(playbackSpeed);
            speedButton.setText(speedLabel());
        });
    }
    private void showPlaybackOptions(Button trigger,String heading,String[] labels,int selected,java.util.function.IntConsumer onSelect){
        dialogOpen=true;
        handler.removeCallbacks(hide);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(heading)
            .setSingleChoiceItems(labels,selected,(d,which)->{d.dismiss();onSelect.accept(which);})
            .setNegativeButton("返回",null).create();
        dialog.setOnDismissListener(d->{dialogOpen=false;showPanel();trigger.requestFocus();});
        dialog.show();
        dialog.getListView().requestFocus();
        dialog.getListView().setSelection(selected);
    }
    private void chooseEpisodes(){
        dialogOpen=true;handler.removeCallbacks(hide);
        String[] labels=new String[episodes.length()];for(int i=0;i<labels.length;i++)labels[i]="第 "+(i+1)+" 集";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选集 · "+series.optString("title"))
            .setSingleChoiceItems(labels,index,(d,which)->{d.dismiss();change(which);}).setNegativeButton("返回",null).create();
        dialog.setOnDismissListener(d->{dialogOpen=false;showPanel();});dialog.show();
    }
    private void chooseDanmaku(){dialogOpen=true;handler.removeCallbacks(hide);String[] values={"hidden","single","top","full"},labels={"关闭弹幕","单行弹幕","上方弹幕","全屏弹幕"};int selected=java.util.Arrays.asList(values).indexOf(app.preferences.getString("danmaku_mode","top"));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("弹幕显示").setSingleChoiceItems(labels,Math.max(0,selected),(d,which)->{app.preferences.edit().putString("danmaku_mode",values[which]).apply();danmaku.clear();d.dismiss();}).setNegativeButton("返回",null).create();dialog.setOnDismissListener(d->{dialogOpen=false;showPanel();});dialog.show();}
    private void showComments(){dialogOpen=true;handler.removeCallbacks(hide);boolean resume=player!=null&&player.isPlaying();if(player!=null)player.pause();commentsPanel=new CommentsPanel(this,app,series.optString("id"),episodes.optString(index));commentsPanel.show(()->{commentsPanel=null;dialogOpen=false;if(running&&player!=null&&resume&&!userPaused)player.play();showPanel();});}
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(dialogOpen)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
            if(key!=KeyEvent.KEYCODE_MENU&&panel.getVisibility()==View.VISIBLE&&!video.hasFocus())return super.dispatchKeyEvent(event);
            if(event.getAction()==KeyEvent.ACTION_UP){showPanel();toggle.requestFocus();}return true;
        }
        boolean picture=video.hasFocus()||panel.getVisibility()!=View.VISIBLE;
        if(picture&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)){
            if(event.getAction()==KeyEvent.ACTION_UP)toggle();return true;
        }
        if(picture&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&player!=null){player.seekTo(Math.max(0,player.getCurrentPosition()+(key==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000)));showPanel();updateTime();}return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(panel.getVisibility()==View.VISIBLE&&player!=null&&player.getPlayerError()==null){panel.setVisibility(View.GONE);video.requestFocus();}else finish();}
    @Override protected void onStart(){super.onStart();running=true;if(episodes!=null){openEpisode(true);handler.post(tick);}}
    @Override protected void onStop(){running=false;++generation;if(commentsPanel!=null)commentsPanel.close();handler.removeCallbacksAndMessages(null);save();syncProgress(true);preloader.clear();release();super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){save();out.putInt("index",index);out.putLong("position",position);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){running=false;++generation;handler.removeCallbacksAndMessages(null);preloader.clear();release();if(danmaku!=null)danmaku.close();worker.shutdownNow();preloadWorker.shutdownNow();super.onDestroy();}
}

