package dev.local.hongguotv;

import com.hgtv.star.core.*;
import java.io.*;
import okhttp3.*;

/** Owns one resolved URL. Media3 handles cancellable media preloading separately. */
final class PreparedPlayback implements Closeable {
    final StreamInfo stream;
    final RemoteVideo remote;
    final String mediaId;
    PreparedPlayback(StreamInfo stream,RemoteVideo remote,String mediaId){this.stream=stream;this.remote=remote;this.mediaId=mediaId;}
    static final class Preparation {
        final RequestScope scope=new RequestScope();
        volatile RemoteVideo remote;
        void cancel(){scope.cancel();RemoteVideo r=remote;if(r!=null)r.close();}
        PreparedPlayback load(TvApplication app,String sid,String ep,int quality,boolean speculative,String mediaId){
            return load(app,sid,ep,quality,speculative,mediaId,java.util.Collections.emptyList());
        }
        PreparedPlayback load(TvApplication app,String sid,String ep,int quality,boolean speculative,String mediaId,java.util.List<String> ahead){
            return load(app,sid,ep,quality,speculative,mediaId,ahead,java.util.Collections.emptySet());
        }
        PreparedPlayback load(TvApplication app,String sid,String ep,int quality,boolean speculative,String mediaId,java.util.List<String> ahead,java.util.Set<String> rejected){
            return load(app,sid,ep,quality,speculative,mediaId,ahead,rejected,0);
        }
        PreparedPlayback load(TvApplication app,String sid,String ep,int quality,boolean speculative,String mediaId,
                java.util.List<String> ahead,java.util.Set<String> rejected,int contentType){
            final long accountRevision=app.accountRevision;
            return scope.run(()->RequestScope.Companion.withDeadline(System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(speculative?12:22),()->{
                // The resolver owns bounded device preparation. Joining search registration here
                // could use up the playback deadline before the first model request was sent.
                StreamInfo info=app.playback.resolve(sid,ep,ahead,quality,speculative,rejected,contentType);
                RequestScope.Companion.ensureActive();
                if(app.accountRevision!=accountRevision)throw new IllegalStateException("播放账号已切换");
                // Publish the URL immediately. The Media3 loader owns all header/sample preloading.
                RemoteVideo candidate=info.getKey()==null?null:new RemoteVideo(app.repository.getHttp(),info);
                remote=candidate;
                return new PreparedPlayback(info,candidate,mediaId+":"+PlaybackStreams.INSTANCE.fingerprint(info));
            }));
        }
    }
    @Override public void close(){if(remote!=null)remote.close();}
}
