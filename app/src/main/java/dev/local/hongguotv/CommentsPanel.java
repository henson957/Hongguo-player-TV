package dev.local.hongguotv;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.hgtv.star.core.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Remote-friendly read-only comments. The dialog owns and cancels its requests. */
final class CommentsPanel {
    private final Activity activity;
    private final TvApplication app;
    private final String sid,episode;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AppCommentPages comments;
    private final PublicComments website;
    private RequestScope scope;
    private AlertDialog dialog;
    private LinearLayout rows;
    private TextView status;
    private boolean reviews,closed,busy,hasMore;
    private String cursor="";
    private int generation;
    private final Set<String> seen=new HashSet<>();
    CommentsPanel(Activity activity,TvApplication app,String sid,String episode){this.activity=activity;this.app=app;this.sid=sid;this.episode=episode;comments=new AppCommentPages(app.repository);website=new PublicComments(app.repository.getHttp());}
    private int dp(int n){return (int)(activity.getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(8),0,dp(8));return view;}
    void show(Runnable dismissed){
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),0,dp(22),0);
        status=text("正在读取本集评论…",15,0xffb4aabb);root.addView(status);
        ScrollView scroll=new ScrollView(activity);scroll.setFocusable(true);rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(315)));
        dialog=new AlertDialog.Builder(activity).setTitle("本集评论").setView(root).setPositiveButton("加载更多",null).setNeutralButton("查看剧评",null).setNegativeButton("返回",null).create();
        dialog.setOnDismissListener(d->{closed=true;generation++;if(scope!=null)scope.cancel();worker.shutdownNow();dismissed.run();});
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->load(false));dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{reviews=!reviews;dialog.setTitle(reviews?"整部剧评":"本集评论");dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(reviews?"查看本集评论":"查看剧评");load(true);});
        dialog.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels-dp(80),dp(900)),-2);load(true);
    }
    private void load(boolean reset){
        if(closed||busy&&!reset)return;if(reset){generation++;if(scope!=null)scope.cancel();cursor="";seen.clear();rows.removeAllViews();hasMore=true;}else if(!hasMore&& !seen.isEmpty())return;
        busy=true;final int token=generation;final boolean kind=reviews;final String from=cursor;final RequestScope request=new RequestScope();scope=request;
        status.setText("正在读取…");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        worker.execute(()->{
            try{
                JSONObject result=request.run(()->{
                    app.ensureDevice(false);
                    if(kind)return website.read(sid,episode).optJSONObject("reviews");
                    try{return comments.read(sid,episode,from);}catch(Exception failure){if(!from.isEmpty())throw failure;JSONObject preview=website.read(sid,episode).optJSONObject("comments");try{return preview.put("hasMore",false).put("limited",true);}catch(Exception e){throw new IllegalStateException(e);}}
                });
                activity.runOnUiThread(()->{if(closed||token!=generation)return;busy=false;JSONArray items=result.optJSONArray("items");if(items!=null)for(int i=0;i<items.length()&&seen.size()<200;i++){JSONObject row=items.optJSONObject(i);if(row==null||!seen.add(row.optString("id")))continue;LinearLayout item=new LinearLayout(activity);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(dp(10),dp(6),dp(10),dp(14));item.addView(text(row.optString("author","红果用户")+"    ·    赞 "+row.optLong("likes"),14,0xffff8eaf));TextView content=text(row.optString("content"),18,Color.WHITE);content.setTextIsSelectable(false);item.addView(content);View divider=new View(activity);divider.setBackgroundColor(0xff39313f);item.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));rows.addView(item);}cursor=result.optString("nextCursor");hasMore=result.optBoolean("hasMore")&&!cursor.isEmpty()&&seen.size()<200;status.setText(seen.isEmpty()?"暂无可显示的评论":("已显示 "+seen.size()+" 条"+(result.optBoolean("limited")||kind?" · 官网展示的部分内容":"")));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(hasMore?"加载更多":"刷新");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->load(!hasMore));});
            }catch(Exception failure){activity.runOnUiThread(()->{if(closed||token!=generation)return;busy=false;status.setText("评论暂时无法读取，请重试");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("重试");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->load(seen.isEmpty()));});}
        });
    }
    void close(){if(dialog!=null&&dialog.isShowing())dialog.dismiss();}
}
