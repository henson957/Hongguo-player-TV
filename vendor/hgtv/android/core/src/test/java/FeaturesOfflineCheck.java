import com.hgtv.star.core.*;
import dev.local.hongguotv.*;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** All HTTP responses are supplied in memory. No chain.proceed and no real registration. */
public class FeaturesOfflineCheck {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    interface Action {void run()throws Exception;}
    static void fails(Action action,String label)throws Exception{try{action.run();throw new AssertionError("Expected failure: "+label);}catch(Exception expected){check(true,label);}}
    static JSONObject o(Object... values)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put(values[i].toString(),values[i+1]);return o;}
    static JSONObject rendition(String episode)throws Exception{return o("video_list",o("video_0",o("codec_type","h264","quality_desc","720P","main_url","https://offline.invalid/"+episode+".mp4")));}
    static ContentRepository repo(Interceptor interceptor){OkHttpClient http=new OkHttpClient.Builder().addInterceptor(interceptor).build();ContentRepository repo=new ContentRepository(http,()->1794000000000L);repo.setDeviceIdentity(Collections.singletonMap("device_id","12345678912345"));repo.restoreRegistrationCookies(Collections.singletonMap("install_id","98765432198765"));return repo;}
    static Response reply(Request request,String text){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline").body(ResponseBody.create(MediaType.get("application/json"),text)).build();}
    static void batch()throws Exception{
        AtomicInteger requests=new AtomicInteger();AtomicLong now=new AtomicLong(1794000000000L);
        ContentRepository repo=repo(chain->{try{
            Request request=chain.request();requests.incrementAndGet();
            check(request.url().encodedPath().equals("/novel/player/multi_video_model/v1/"),"batch avoids legacy single-episode retry chain");
            check(request.header("Cookie").equals("install_id=98765432198765"),"batch preserves registered session cookies");
            check(request.url().queryParameter("ts")==null&&request.header("x-ss-stub")!=null,"playback signs the final request once with body digest");
            okio.Buffer bytes=new okio.Buffer();request.body().writeTo(bytes);JSONObject payload=new JSONObject(bytes.readUtf8());JSONArray ids=payload.getJSONObject("mixed_video_id_map").getJSONArray("1004");
            check(ids.length()<=16,"batch size is bounded to current and fifteen following episodes");
            JSONObject data=new JSONObject();for(int i=0;i<ids.length();i++)data.put(ids.getString(i),o("video_model",rendition(ids.getString(i))));
            return reply(request,o("code",0,"data",data).toString());
        }catch(Exception e){throw new IOException(e);}});
        BatchPlaybackResolver resolver=new BatchPlaybackResolver(repo,now::get);
        List<String> next=new ArrayList<>();for(int i=2;i<24;i++)next.add(String.valueOf(100000+i));
        check(resolver.resolve("900000","100001",next,720,false).getUrl().contains("100001"),"current episode resolves from its own batch key");
        check(resolver.resolve("900000","100002",Collections.emptyList(),720,true).getUrl().contains("100002")&&requests.get()==1,"next episode consumes the batch with zero new model requests");
        check(resolver.getLastTrace().equals("cache:hit"),"cache diagnostics do not contain URLs or credentials");
        resolver.resolve("900001","100002",Collections.emptyList(),720,false);check(requests.get()==2,"a different series cannot consume another series batch");
        resolver.invalidate("900000","100002");resolver.resolve("900000","100002",Collections.emptyList(),720,false);check(requests.get()==3,"explicit retry invalidates stale metadata");
        now.addAndGet(300001);resolver.resolve("900000","100003",Collections.emptyList(),720,false);check(requests.get()==4,"metadata expires instead of replaying an old address forever");
        repo.getHttp().dispatcher().executorService().shutdownNow();
        AtomicInteger legacyFallback=new AtomicInteger();ContentRepository direct=repo(chain->{try{JSONObject model=rendition("100001");model.put("fallback_api","https://unreachable.invalid/unused");if(!chain.request().url().encodedPath().contains("multi_video_model"))legacyFallback.incrementAndGet();return reply(chain.request(),o("code",0,"data",o("100001",o("video_model",model))).toString());}catch(Exception e){throw new IOException(e);}});
        check(new BatchPlaybackResolver(direct,now::get).resolve("900000","100001",Collections.emptyList(),720,false).getUrl().contains("100001")&&legacyFallback.get()==0,"inline playable URL is not delayed by an unnecessary fallback request");
        direct.getHttp().dispatcher().executorService().shutdownNow();
        AtomicInteger forbidden=new AtomicInteger();ContentRepository missing=repo(chain->{forbidden.incrementAndGet();if(chain.request().url().encodedPath().contains("device_register"))return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("offline").body(ResponseBody.create(null,"")).build();return reply(chain.request(),"{\"code\":0,\"data\":{\"999999\":{\"video_model\":{\"video_list\":{}}}}}");});
        fails(()->new BatchPlaybackResolver(missing,now::get).resolve("900000","100001",Arrays.asList("100002"),720,true),"unmatched batch result cannot play an unrelated episode");
        // Two speculative rounds allow four App routes and two web routes each, plus one device refresh.
        check(forbidden.get()<=13,"bad batch response has a bounded request count including public fallbacks");missing.getHttp().dispatcher().executorService().shutdownNow();
        AtomicInteger limits=new AtomicInteger();ContentRepository limited=repo(chain->{limits.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("offline limit").body(ResponseBody.create(null,"")).build();});
        fails(()->new BatchPlaybackResolver(limited,now::get).resolve("900000","100001",Collections.emptyList(),720,true),"server rate limit is surfaced");check(limits.get()==1,"rate limit cannot trigger identity rotation or alternate endpoint loops");limited.getHttp().dispatcher().executorService().shutdownNow();
        AtomicInteger calls=new AtomicInteger();ContentRepository cancelled=repo(chain->{calls.incrementAndGet();throw new AssertionError("No request after cancellation");});RequestScope scope=new RequestScope();scope.cancel();
        fails(()->scope.run(()->new BatchPlaybackResolver(cancelled,now::get).resolve("900000","100001",Collections.emptyList(),720,true)),"cancelled batch never reaches HTTP");check(calls.get()==0,"cancellation reaches the resolver transport");
        fails(()->RequestScope.Companion.withDeadline(System.nanoTime()-1,()->"late"),"expired whole-operation deadline fails immediately");cancelled.getHttp().dispatcher().executorService().shutdownNow();
    }
    static void outbox()throws Exception{
        String a="a".repeat(64),b="b".repeat(64);SyncOutbox queue=new SyncOutbox("[]");
        queue.put(a,"progress","100",o("position",1000));JSONObject previous=queue.first(a);queue.put(a,"progress","100",o("position",2000));queue.acknowledge(previous);
        check(queue.count(a)==1&&queue.first(a).getLong("position")==2000,"old acknowledgement cannot delete a newer progress checkpoint");
        queue.put(a,"favorite","100",o("value",true));queue.put(a,"favorite","100",o("value",false));check(queue.count(a)==2,"rapid favorite toggles coalesce into one desired state");
        check(queue.count(b)==0&&queue.first(b)==null,"an account cannot drain another account's pending writes");
        SyncOutbox restored=new SyncOutbox(queue.serialize());check(restored.count(a)==2,"pending writes survive serialization and restart");
        JSONObject copy=restored.first(a);copy.put("position",99999);check(restored.first(a).getLong("position")==2000,"outbox snapshots do not mutate persisted state");
        restored.acknowledge(restored.first(a));check(restored.count(a)==1,"successful current write is acknowledged once");restored.clear();check(restored.count(a)==0,"logout can discard old pending writes");
        check(!queue.serialize().contains("cookie")&&!queue.serialize().contains("sessionid"),"outbox never stores login credentials");
    }
    static void timeline()throws Exception{
        List<DanmakuTimeline.Item> rows=Arrays.asList(new DanmakuTimeline.Item("a","one",0,100),new DanmakuTimeline.Item("b","two",500,100),new DanmakuTimeline.Item("c","three",2000,100));
        List<DanmakuTimeline.Item> single=DanmakuTimeline.schedule(rows,1,.1f);check(single.size()==2&&single.get(1).id.equals("c"),"single lane drops crowding instead of overlapping text");
        check(DanmakuTimeline.schedule(rows,2,.1f).size()==3,"available lanes accept simultaneous comments");
        check(DanmakuTimeline.schedule(Arrays.asList(rows.get(0),rows.get(0)),2,.1f).size()==1,"duplicate comment IDs are displayed once");
        check(DanmakuTimeline.schedule(rows,0,.1f).isEmpty(),"disabled layout produces no display items");
        check(DanmakuTimeline.schedule(rows,2,Float.NaN).isEmpty(),"invalid speed cannot create unstable animation coordinates");
        check(DanmakuTimeline.schedule(Arrays.asList(new DanmakuTimeline.Item("x","bad",-1,100)),1,.1f).isEmpty(),"negative comment timestamps are ignored");
    }
    static JSONObject appRow(String sid,String episode,String id,long time)throws Exception{return o("comment",o("comment_id",id,"common",o("group_id",episode,"status",1,"content",o("text","示例评论"),"user_info",o("base_info",o("user_name","离线样例"))),"expand",o("book_id",sid,"offset_time",time),"stat",o("digg_count",3)));}
    static void comments()throws Exception{
        String sid="900000",ep="100001";JSONObject row=appRow(sid,ep,"c1",1000);
        JSONObject page=o("code",0,"data",o("common_list_info",o("has_more",false,"total",2),"data_list",new JSONArray().put(row).put(row)));
        check(AppCommentPages.Companion.parse(page,sid,ep,"").getJSONArray("items").length()==1,"episode comments deduplicate stable server IDs");
        fails(()->AppCommentPages.Companion.parse(page,sid,"100002",""),"comments for another episode are rejected");
        JSONObject stuck=o("code",0,"data",o("common_list_info",o("has_more",true,"cursor","{\"session_id\":\"test\",\"offset\":0}"),"data_list",new JSONArray()));
        fails(()->AppCommentPages.Companion.parse(stuck,sid,ep,"{\"session_id\":\"test\",\"offset\":0}"),"non-advancing comment cursor cannot loop forever");
        JSONObject timed=o("code",0,"data",o("extra",o("next_query_danmaku_list_time",30000),"data_list",new JSONArray().put(row).put(row).put(appRow(sid,"100002","bad",1000)).put(appRow(sid,ep,"outside",31000))));
        check(TimedComments.Companion.parse(timed,sid,ep,0,60000).getJSONArray("items").length()==1,"timed comments filter duplicates, wrong episodes and outside-window timestamps");
        fails(()->TimedComments.Companion.parse(timed,sid,ep,30000,60000),"non-advancing timed window is rejected");
        JSONObject review=o("content","公开剧评","user_name","离线样例","create_time",1,"digg_count",4);
        JSONObject data=o("seriesDetail",o("series_id",sid),"vid",ep,"episodeSocialInfo",o("reviews",o("reviews",new JSONArray().put(review))),"seriesSocialInfo",o("reviews",o("reviews",new JSONArray().put(review))));
        String html="<script>window._ROUTER_DATA="+o("loaderData",o("player",data))+";</script>";
        check(PublicComments.Companion.parse(html,sid,ep,null).getJSONObject("comments").getJSONArray("items").length()==1,"public website comments are available as a read-only fallback");
        fails(()->PublicComments.Companion.parse(html,"900001",ep,null),"public preview from another series is rejected");
        JSONObject player=o("seriesDetail",o("series_id",sid),"vid",ep,"video_player_info",rendition(ep));String web="<script>window._ROUTER_DATA="+o("loaderData",o("player_route",player))+";</script>";
        check(PlaybackStreams.INSTANCE.web(web,sid,ep,720).getUrl().contains(ep),"current public player seriesDetail shape resolves only its matching episode");
        fails(()->PlaybackStreams.INSTANCE.select(o("video_list",o("only",o("codec_type","h264","main_url","https://offline.invalid/encrypted.mp4","encrypt_info",o("encrypt",1)))),720),"numeric encryption flag with no key cannot be served as plaintext");
    }
    public static void main(String[] args)throws Exception{batch();outbox();timeline();comments();System.out.println("OFFLINE_FEATURE_CHECKS_PASSED="+checks);System.out.println("EXTERNAL_NETWORK_REQUESTS=0");}
}
