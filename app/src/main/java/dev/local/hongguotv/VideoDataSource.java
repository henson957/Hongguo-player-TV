// Adapted from ps1001/HGTV VideoDataSource.kt; GPL-3.0-only.
package dev.local.hongguotv;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import com.hgtv.star.core.RemoteVideo;
import java.io.*;
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class VideoDataSource extends BaseDataSource {
    private final RemoteVideo video;
    private long position,remaining;
    private byte[] buffer=new byte[0];
    private int offset;
    private boolean opened;
    private Uri uri;
    VideoDataSource(RemoteVideo video){super(true);this.video=video;}
    @Override public long open(DataSpec spec)throws IOException{
        video.prepare();
        transferInitializing(spec);uri=spec.uri;position=spec.position;
        if(position>video.getTotal())throw new IOException("读取位置超出视频长度");
        remaining=spec.length==C.LENGTH_UNSET?video.getTotal()-position:Math.min(spec.length,video.getTotal()-position);
        buffer=new byte[0];offset=0;opened=true;transferStarted(spec);return remaining;
    }
    @Override public int read(byte[] target,int targetOffset,int length)throws IOException{
        if(length==0)return 0;if(remaining==0)return C.RESULT_END_OF_INPUT;
        if(offset>=buffer.length){buffer=video.read(position,(int)Math.min(512*1024L,remaining));offset=0;}
        if(buffer.length==0)throw new EOFException("视频分段意外结束");
        int count=(int)Math.min(Math.min(length,buffer.length-offset),remaining);
        System.arraycopy(buffer,offset,target,targetOffset,count);offset+=count;position+=count;remaining-=count;bytesTransferred(count);return count;
    }
    @Override public Uri getUri(){return uri;}
    @Override public void close(){buffer=new byte[0];uri=null;if(opened){opened=false;transferEnded();}}
}

