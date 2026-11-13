package dev.local.hongguotv;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;
import java.util.*;
import java.util.function.*;

final class DanmakuOverlay extends View {
    private final TvApplication app;
    private final LongSupplier position;
    private final BooleanSupplier playing;
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<DanmakuTimeline.Item> items=Collections.emptyList();
    private JSONArray raw=new JSONArray();
    private float velocity,rowHeight;
    DanmakuOverlay(Context context,TvApplication app,LongSupplier position,BooleanSupplier playing){super(context);this.app=app;this.position=position;this.playing=playing;setFocusable(false);setClickable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void items(JSONArray values){raw=values;rebuild();}
    void clear(){raw=new JSONArray();items=Collections.emptyList();invalidate();}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){rebuild();}
    private void rebuild(){
        String mode=app.preferences.getString("danmaku_mode","top");float density=getResources().getDisplayMetrics().density;
        float size=app.preferences.getInt("danmaku_size",20)*density;fill.setTextSize(size);fill.setColor(Color.WHITE);fill.setAlpha(app.preferences.getInt("danmaku_alpha",80)*255/100);fill.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));stroke.set(fill);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(3*density);stroke.setColor(0xff181019);
        rowHeight=size*1.55f;velocity=(getWidth()+200*density)/9000f*(app.preferences.getInt("danmaku_speed",100)/100f);
        int lanes=mode.equals("single")?1:Math.max(1,Math.min(mode.equals("full")?12:5,(int)(getHeight()*(mode.equals("full")?.78f:.35f)/Math.max(1,rowHeight))));
        ArrayList<DanmakuTimeline.Item> values=new ArrayList<>();for(int i=0;i<raw.length();i++){JSONObject value=raw.optJSONObject(i);if(value==null)continue;String text=value.optString("text");if(text.length()>100)text=text.substring(0,100)+"…";values.add(new DanmakuTimeline.Item(value.optString("id"),text,value.optLong("timeMS",-1),fill.measureText(text)));}
        items=mode.equals("hidden")?Collections.emptyList():DanmakuTimeline.schedule(values,lanes,velocity);invalidate();
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);long now=position.getAsLong();for(DanmakuTimeline.Item item:items){float x=getWidth()-(now-item.time)*velocity;if(now<item.time||x+item.width<0)continue;float y=rowHeight*(item.lane+1);canvas.drawText(item.text,x,y,stroke);canvas.drawText(item.text,x,y,fill);}if(!items.isEmpty()&&playing.getAsBoolean())postInvalidateDelayed(33);}
}
