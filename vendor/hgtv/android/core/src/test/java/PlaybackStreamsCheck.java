// Offline regression checks for local TV playback adaptation. No external requests are sent.
import com.hgtv.star.core.*;
import okhttp3.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;

public class PlaybackStreamsCheck {
    private static int checks;
    private static JSONObject object(Object...pairs)throws Exception{
        JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put(pairs[i].toString(),pairs[i+1]);return o;
    }
    private static JSONObject variant(String codec,int p,String address)throws Exception{
        return object("codec_type",codec,"quality_desc",p+"P","main_url",address);
    }
    private static JSONObject list(JSONObject...options)throws Exception{
        JSONObject rows=new JSONObject();for(int i=0;i<options.length;i++)rows.put("video_"+i,options[i]);return object("video_list",rows);
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Action {void run()throws Exception;}
    private static void fails(Action action,String marker,String label)throws Exception{
        try{action.run();throw new AssertionError("Expected failure: "+label);}
        catch(NoCompatibleStreamException e){check(e.getMessage().contains(marker),label);}
        catch(IOException e){check(e.getMessage().contains(marker),label);}
    }
    private static String html(String series,String episode,JSONObject player)throws Exception{
        JSONObject page=object("series_id",series,"vid",episode,"video_player_info",player);
        return "<script>window._ROUTER_DATA="+object("loaderData",object("player_(series)_(vid)/page",page))+";</script>";
    }
    private static String publicPage(String address)throws Exception{
        JSONObject page=object("series_id","100","vid","200","protectedPlayback",true);
        String scripts="<script>window._ROUTER_DATA="+object("loaderData",object("player_(series)_(vid)/page",page))+";</script>";
        return scripts+"<script type=\"application/ld+json\">"+object("@type","VideoObject","contentUrl",address)+"</script>";
    }
    private static class Fixture implements AutoCloseable{
        final List<String> requests=new ArrayList<>();
        final ContentRepository repo;
        Fixture(JSONObject fallback,JSONObject direct,String firstWeb,String secondWeb)throws Exception{
            JSONObject model=direct==null?new JSONObject():new JSONObject(direct.toString());
            model.put("fallback_api",object("fallback_api","https://fallback.example/resolve"));
            String modelResponse=object("code",0,"data",object("video_model",model.toString())).toString();
            String fallbackResponse=object("video_info",object("data",fallback)).toString();
            OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{
                HttpUrl u=chain.request().url();requests.add(u.host()+u.encodedPath());
                String response;
                if(u.encodedPath().equals("/novel/player/video_model/v1/"))response=modelResponse;
                else if(u.host().equals("fallback.example"))response=fallbackResponse;
                else if(u.host().equals("hongguoduanju.com")&&u.encodedPath().equals("/player/100/200"))response=firstWeb;
                else if(u.host().equals("novelquickapp.com")&&u.encodedPath().equals("/player/100/200"))response=secondWeb;
                else throw new AssertionError("Unexpected request, network prohibited: "+u.host()+u.encodedPath());
                if(response==null)response="<html>no player</html>";
                // Never call chain.proceed: this response is entirely in memory.
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
                    .body(ResponseBody.create(MediaType.get("application/json; charset=utf-8"),response)).build();
            }).build();
            repo=new ContentRepository(client,()->1794000000000L);
        }
        public void close(){repo.getHttp().connectionPool().evictAll();repo.getHttp().dispatcher().executorService().shutdown();}
    }
    private static void checkPlaybackSignature() {
        long time=1800000000999L;
        double span=4294967296.0;
        Iterator<Double> entropy=List.of(4294967295L/span,0x04030201/span,0.0,0.0).iterator();
        Map<String,String> values=new LinkedHashMap<>();
        values.put("aid","8662");
        values.put("device_id","7000000000000000001");
        values.put("iid","7000000000000000002");
        values.put("version_name","7.1.3.32");
        values.put("_rticket",Long.toString(time));
        values.put("ts","1800000000");
        byte[] body="{\"video_id\":\"123\",\"biz_param\":{\"video_platform\":3}}".getBytes(StandardCharsets.UTF_8);
        var signed=new PlaybackSigner(()->time,values,entropy::next,"offline").signPlayback("https://example.invalid/model",values,body,time);
        // Public November 11 test vector referenced in vendor/playback/NOTICE.md.
        check(signed.getHeaders().get("x-argus").equals("8oGzD48Lx0I+DCXDwk+l2r54SBRLSexXn2DlWQdGZE1UFZXj1GWup1ztpbF3eGeKcJAItaddhQwgYm7odM2byEYHtJ0pNvNUAoumWtQPqducerNIGe0Hs3z5V7oJDVgEySh6Kqc69jI8T+zU13SblzavSSZGSegC030jul66JPvjsBGzZoerKy88GDYDbumRNPCtqnfztLNNlGwmnA+0Tv+dnBC1xJ9rIEBThybfhq8wL5YqxxstLwuLGffOIlbxoQdheycfZBDpJG5kiK4eX7b3gr+PVkmCZBkJleZIYDl22UQS7nQGJKyu/a0U2MC7oWo="),"complete Argus matches public request vector");
        check(signed.getHeaders().get("x-ladon").equals("AQIDBPhgjvKhYEWp2lodHDSBnITF8APHUjBLe2ja98bNrRn+"),"complete Ladon matches public request vector");
        check(!signed.getHeaders().containsKey("x-medusa")&&signed.getHeaders().get("x-ss-req-ticket").equals(Long.toString(time))
            &&Arrays.equals(signed.getBody(),body),"playback uses the signed bytes and excludes account Medusa");
    }

    private static void checkSecondEpisodeBatch() throws Exception {
        JSONObject episode1=list(variant("h264",360,"https://media.example/first.mp4"));
        JSONObject episode2=list(variant("h264",1080,"https://media.example/second-1080.mp4"),
            variant("h264",480,"https://media.example/second-480.mp4"));
        String reply=object("code",0,"data",object("video_model_data",object("200",episode1,"201",episode2))).toString();
        List<String> calls=new ArrayList<>();
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{
            Request req=chain.request();
            calls.add(req.url().encodedPath());
            if(!req.url().encodedPath().equals("/novel/player/multi_video_model/v1/"))throw new AssertionError("Unexpected playback request");
            okio.Buffer payload=new okio.Buffer();
            Objects.requireNonNull(req.body()).writeTo(payload);
            JSONArray ids=new JSONObject(payload.readUtf8()).getJSONObject("mixed_video_id_map").getJSONArray("1004");
            if(ids.length()!=2||!ids.getString(0).equals("200")||!ids.getString(1).equals("201"))throw new AssertionError("Wrong episode IDs");
            boolean fullSignature=Base64.getDecoder().decode(req.header("x-argus")).length>4
                &&Base64.getDecoder().decode(req.header("x-ladon")).length>4&&req.header("x-medusa")==null;
            return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
                .body(ResponseBody.create(MediaType.get("application/json"),fullSignature?reply:"")).build();
        }).build();
        ContentRepository repo=new ContentRepository(client,System::currentTimeMillis);
        repo.setDeviceIdentity(Map.of("device_id","123456789012345678","iid","223456789012345678"));
        var resolver=new BatchPlaybackResolver(repo,System::currentTimeMillis);
        try {
            check(resolver.resolve("100","200",List.of("201"),1080,false).getUrl().endsWith("/first.mp4"),"TV sends complete playback headers on a multi-episode request");
            check(resolver.resolve("100","201",List.of(),720,true).getUrl().endsWith("/second-480.mp4")&&calls.size()==1,
                "next episode prefetch uses its cached model and respects the manual ceiling");
            check(resolver.resolve("100","201",List.of(),Integer.MAX_VALUE,true).getUrl().endsWith("/second-1080.mp4")&&calls.size()==1,
                "switching back to Auto can select a higher variant from the same batch");
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    public static void main(String[] args)throws Exception{
        checkPlaybackSignature();
        checkSecondEpisodeBatch();
        PlaybackStreams p=PlaybackStreams.INSTANCE;
        JSONObject ordinary=list(variant("h264",1080,"https://media.example/1080.mp4"),variant("h264",480,"https://media.example/480.mp4"));
        check(p.select(ordinary,720).getUrl().endsWith("/480.mp4"),"quality limit chooses available lower variant");
        JSONObject portrait=object("video_list",new JSONArray().put(object("video_meta",object("codec_type","h264","vwidth",720,"vheight",1280),"main_url","https://media.example/portrait.mp4")));
        check(p.select(portrait,720).getQuality().equals("720P"),"array and portrait video_meta are parsed");
        JSONObject backup=list(object("codec_type","h264","quality_desc","720P","backup_url",new JSONArray().put("https://media.example/backup.mp4")));
        check(p.select(backup,720).getUrl().endsWith("/backup.mp4"),"backup-only URL is accepted");
        String encoded=Base64.getEncoder().encodeToString("https://media.example/base64.mp4".getBytes(StandardCharsets.UTF_8));
        check(p.select(list(variant("h264",720,encoded)),720).getUrl().endsWith("/base64.mp4"),"base64 plaintext URL is decoded");
        JSONObject plainSeed=list(variant("h264",720,"https://media.example/plain.mp4"));plainSeed.put("key_seed","invalid-seed");
        check(p.select(plainSeed,720).getUrl().endsWith("/plain.mp4"),"plaintext URL is not decrypted merely because key_seed exists");
        JSONObject mixed=list(variant("bytevc2",720,"https://media.example/unsupported.mp4"),variant("h264",480,"https://media.example/compatible.mp4"));
        check(p.select(mixed,720).getUrl().endsWith("/compatible.mp4"),"unsupported variant cannot hide a valid alternative");
        JSONObject keyBad=variant("h264",720,"https://media.example/encrypted.mp4");keyBad.put("encrypt_info",object("encrypt",true,"spade_a","invalid"));
        check(p.select(list(keyBad,variant("h264",480,"https://media.example/clear.mp4")),720).getKey()==null,"invalid encrypted variant cannot be treated as plaintext");
        JSONObject unsupported=list(variant("bytevc2",720,"https://media.example/private.mp4"));
        fails(()->p.select(unsupported,720),"bytevc2","format-only failure has diagnostic codec counts");
        JSONObject nested=object("video_list",new JSONArray().put(object("video_meta",object("codec_type","bytevc2"),"main_url","https://media.example/private.mp4")));
        fails(()->p.select(nested,720),"bytevc2","nested unsupported codec is detected");
        JSONObject player=object("main_url","https://media.example/web.mp4","codec_type","h264","width",720,"height",1280);
        check(p.web(html("100","200",player),"100","200",720).getUrl().endsWith("/web.mp4"),"matching public web episode is parsed");
        fails(()->p.web(html("100","201",player),"100","200",720),"未返回所选分集","web preview from another episode is rejected");
        String publicUrl="https://hongguoduanju.com/video/public/200/360p.mp4";
        StreamInfo publicStream=p.web(publicPage(publicUrl),"100","200",720);
        check(publicStream.getUrl().equals(publicUrl)&&publicStream.getQuality().equals("360P")&&publicStream.getKey()==null,
            "the official published MP4 can be selected at its declared quality");
        fails(()->p.web(publicPage(publicUrl.replace("/200/","/201/")),"100","200"),"未获取到本集","a public URL must match the selected episode");
        fails(()->p.web(publicPage(publicUrl.replace("hongguoduanju.com","example.invalid")),"100","200"),"未获取到本集","unrelated hosts are not accepted as official public media");
        fails(()->p.web(publicPage(""),"100","200"),"未获取到本集","an unpublished URL is never generated");
        try(Fixture f=new Fixture(ordinary,null,null,null)){
            check(f.repo.stream("100","200",720).getUrl().endsWith("/480.mp4")&&f.requests.size()==2,"working App route avoids additional web requests");
        }
        try(Fixture f=new Fixture(unsupported,null,html("100","200",player),null)){
            check(f.repo.stream("100","200",720).getUrl().endsWith("/web.mp4")&&f.requests.size()==3,"App formats exhausted falls back to matching web H264");
        }
        try(Fixture f=new Fixture(unsupported,null,"<html>no player</html>",html("100","200",player))){
            check(f.repo.stream("100","200",720).getUrl().endsWith("/web.mp4")&&f.requests.size()==4,"secondary public web player works when primary has no data");
        }
        try(Fixture f=new Fixture(unsupported,null,html("100","201",player),html("100","201",player))){
            fails(()->f.repo.stream("100","200",720),"未返回所选分集","wrong-episode web responses cannot become successful playback");
        }
        try(Fixture f=new Fixture(unsupported,portrait,null,null)){
            check(f.repo.stream("100","200",720).getUrl().endsWith("/portrait.mp4")&&f.requests.size()==2,"direct App model remains usable when legacy fplay is incompatible");
        }
        fails(()->p.select(list(variant("h264",720,"javascript:invalid")),720),"无效地址1","unsupported URL schemes are rejected without revealing URL data");
        System.out.println("OFFLINE_PLAYBACK_CHECKS_PASSED="+checks);
        System.out.println("EXTERNAL_NETWORK_REQUESTS=0");
    }
}

