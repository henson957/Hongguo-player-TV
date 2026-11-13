import com.hgtv.star.core.*;
import dev.local.hongguotv.EpisodePreloader;
import okhttp3.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** In-memory HTTP fixtures and deterministic ownership/concurrency checks. No chain.proceed(). */
public class LoginAndPreloadCheck {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    interface Respond {String body(Request request,Response.Builder response)throws IOException;}
    static OkHttpClient client(Respond respond){return new OkHttpClient.Builder().addInterceptor(chain->{
        Request request=chain.request();if(!request.url().host().equals("hongguoduanju.com"))throw new AssertionError("Unexpected host; external network prohibited");
        Response.Builder response=new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline");
        String body=respond.body(request,response);return response.body(ResponseBody.create(MediaType.get("application/json"),body)).build();
    }).build();}
    static String qr(Response.Builder response){response.addHeader("Set-Cookie","passport_csrf_token=fixture; Path=/; Secure; HttpOnly");return "{\"message\":\"success\",\"data\":{\"token\":\"fixture-token\",\"qrcode\":\"aW1hZ2U=\",\"expire_time\":180}}";}
    static String mix(String value){StringBuilder s=new StringBuilder();for(byte b:value.getBytes(java.nio.charset.StandardCharsets.UTF_8))s.append(Integer.toHexString((b&255)^5));return s.toString();}
    static void fail(Runnable action,String marker,String label){try{action.run();throw new AssertionError(label);}catch(Exception e){check(e.getMessage()!=null&&e.getMessage().contains(marker),label);}}
    static void loginChecks()throws Exception{
        List<String> paths=new ArrayList<>();
        AccountWebLogin login=new AccountWebLogin(client((request,response)->{
            paths.add(request.url().encodedPath());
            check("513755".equals(request.url().queryParameter("aid"))||request.url().encodedPath().equals("/auth/complete"),"official website account identity");
            switch(request.url().encodedPath()){
                case "/passport/web/get_qrcode/":return qr(response);
                case "/passport/web/check_qrconnect/":return "{\"message\":\"success\",\"data\":{\"status\":\"3\",\"redirect_url\":\"/auth/complete\"}}";
                case "/auth/complete":response.addHeader("Set-Cookie","sessionid=fixture-only; Path=/; Secure; HttpOnly");response.addHeader("Set-Cookie","unrelated=bad; Domain=example.com; Path=/");return "ok";
                case "/passport/account/info/v2/":return "{\"message\":\"success\",\"data\":{\"user_id\":\"fixture-user\"}}";
                default:throw new AssertionError("Unexpected endpoint");
            }
        }));
        check(Arrays.equals("image".getBytes(),login.qr().getImage()),"QR image decoded without a third-party QR service");
        check(login.poll(),"QR confirmation completes official redirect");
        AccountCredentials credentials=login.credentials();
        check(credentials.getCookie().contains("sessionid=fixture-only")&&!credentials.getCookie().contains("unrelated"),"only valid matching-domain cookies exported");
        check(paths.equals(Arrays.asList("/passport/web/get_qrcode/","/passport/web/check_qrconnect/","/auth/complete","/passport/account/info/v2/")),"session verified before credentials returned");
        AccountWebLogin foreign=new AccountWebLogin(client((request,response)->request.url().encodedPath().endsWith("get_qrcode/")?qr(response):"{\"message\":\"success\",\"data\":{\"status\":\"confirmed\",\"redirect_url\":\"https://example.com/login\"}}"));
        foreign.qr();fail(()->foreign.poll(),"不支持","cross-domain confirmation rejected before any foreign request");
        AccountWebLogin sms=new AccountWebLogin(client((request,response)->{
            if(request.url().encodedPath().endsWith("get_qrcode/"))return qr(response);
            check("fixture".equals(request.header("x-tt-passport-csrf-token")),"SMS keeps the same CSRF session");
            FormBody form=(FormBody)request.body();Map<String,String> data=new HashMap<>();for(int i=0;i<form.size();i++)data.put(form.name(i),form.value(i));
            check(mix("+86 13800138000").equals(data.get("mobile")),"SMS phone uses website mixed form encoding");
            if(request.url().encodedPath().endsWith("send_code/"))return "{\"message\":\"success\",\"data\":{\"retry_time\":60}}";
            if(request.url().encodedPath().endsWith("sms_login/")){
                check("true".equals(data.get("login_only")),"SMS logs into an existing account");
                return "{\"message\":\"error\",\"data\":{\"error_code\":1105,\"verify_center_decision_conf\":\"fixture\"}}";
            }
            throw new AssertionError("Unexpected endpoint");
        }));
        check(sms.send("13800138000").getRetrySeconds()==60,"SMS response preserves resend cooldown");
        fail(()->sms.verify("123456"),"扫码","verification challenge surfaced rather than bypassed");
        AccountWebLogin expired=new AccountWebLogin(client((request,response)->request.url().encodedPath().endsWith("get_qrcode/")?qr(response):"{\"message\":\"success\",\"data\":{\"status\":\"expired\"}}"));
        expired.qr();fail(()->expired.poll(),"过期","expired QR is not reported as logged in");
        AccountWebLogin cancelled=new AccountWebLogin(client((request,response)->{throw new AssertionError("Cancelled request must not execute");}));cancelled.cancel();fail(()->cancelled.qr(),"取消","cancel prevents further login requests");
    }
    static class Value implements Closeable {final AtomicInteger closed=new AtomicInteger();public void close(){closed.incrementAndGet();}}
    static void preloadChecks()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();AtomicLong time=new AtomicLong(1000);AtomicInteger fetches=new AtomicInteger(),aborts=new AtomicInteger();
        EpisodePreloader<Value> preload=new EpisodePreloader<>(executor,time::get,90000);
        try{
            CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);Value value=new Value();
            Callable<Value> loader=()->{fetches.incrementAndGet();entered.countDown();finish.await();return value;};
            preload.start("next",loader,aborts::incrementAndGet);if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("loader did not start");
            preload.start("next",loader,aborts::incrementAndGet);check(preload.claim("next",false)==null,"UI never blocks waiting for an unfinished preload");
            Future<Value> claim=CompletableFuture.supplyAsync(()->preload.claim("next",true));finish.countDown();
            check(claim.get(2,TimeUnit.SECONDS)==value&&fetches.get()==1,"episode end joins the in-flight fetch without resolving the URL twice");
            check(value.closed.get()==0&&preload.claim("next",false)==null,"claim transfers ownership exactly once");value.close();
            Value old=new Value();CountDownLatch produced=new CountDownLatch(1);preload.start("old",()->{produced.countDown();return old;},aborts::incrementAndGet);produced.await();
            Value claimed=preload.claim("old",true);check(claimed==old,"prepared episode is reusable before expiry");claimed.close();
            Value stale=new Value();preload.start("expired",()->stale,aborts::incrementAndGet);
            // Join the executor task without consuming the stored result.
            executor.submit(()->{}).get();time.addAndGet(90001);
            check(preload.claim("expired",true)==null&&stale.closed.get()==1,"expired URL is discarded and its resources are closed");
            CountDownLatch running=new CountDownLatch(1),unblock=new CountDownLatch(1);Value late=new Value();
            preload.start("skip",()->{running.countDown();while(true){try{unblock.await();break;}catch(InterruptedException ignored){}}return late;},()->{aborts.incrementAndGet();unblock.countDown();});
            running.await();preload.clear();executor.shutdown();executor.awaitTermination(2,TimeUnit.SECONDS);
            check(late.closed.get()==1&&!preload.contains("skip"),"skip or stop cancels work and closes late completions");
            check(aborts.get()>=1,"cancellation reaches the owning network preparation");
        }finally{preload.clear();executor.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{loginChecks();preloadChecks();System.out.println("OFFLINE_LOGIN_PRELOAD_CHECKS_PASSED="+checks);System.out.println("EXTERNAL_NETWORK_REQUESTS=0");}
}
