// SPDX-License-Identifier: GPL-3.0-only
package dev.local.hongguotv;

import com.hgtv.star.core.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Progressive TV search: prefix completion and sibling seasons do not wait for scrolling. */
final class TvSearch implements AutoCloseable {
    private static final int LIMIT=240;
    private final TvApplication app;
    private final PinyinTitles pinyin;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final Map<String,Series> index=new LinkedHashMap<String,Series>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Series> row){return size()>1200;}
    };
    private volatile Session current;
    private volatile boolean closed;
    private interface Work {void run()throws Exception;}
    private static final class Cursor {int next=1;boolean more=true;}
    private static final class Session {
        final String id=UUID.randomUUID().toString(),query,romanQuery;
        final PinyinTitles pinyin;
        final Map<String,Integer> pinyinScores=new ConcurrentHashMap<>();
        final ContentRepository repository;
        final RequestScope scope=new RequestScope();
        final long revision;
        volatile long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        volatile boolean stopped;
        final Map<String,Series> rows=new LinkedHashMap<>();
        final Set<String> families=new HashSet<>(),webQueries=new HashSet<>();
        final Map<ContentType,Cursor> catalog=new LinkedHashMap<>();
        int pending,errors,nextApp=1;boolean appMore=true,appAvailable,initialScan,initializing=true;
        Session(String query,ContentRepository repository,long revision,PinyinTitles pinyin){this.query=query;this.repository=repository;this.revision=revision;this.pinyin=pinyin;romanQuery=PinyinTitles.normalizeQuery(query);for(ContentType type:new ContentType[]{ContentType.SHORT,ContentType.COMIC,ContentType.AI})catalog.put(type,new Cursor());}
        boolean roman(){return !romanQuery.isEmpty();}
        String onlineQuery(){return roman()?romanQuery:query;}
        boolean matches(String title){return SearchTitles.INSTANCE.matches(query,title)||roman()&&pinyinScore(title)<PinyinTitles.NO_MATCH;}
        int score(String title){
            int textScore=SearchTitles.INSTANCE.score(query,title);
            return roman()?Math.min(textScore,pinyinScore(title)):textScore;
        }
        private int pinyinScore(String title){
            return pinyinScores.computeIfAbsent(title,key->{
                int baseScore=pinyin.score(romanQuery,SeriesSeasons.INSTANCE.base(key));
                if(baseScore<10)return baseScore;
                return Math.min(baseScore,pinyin.score(romanQuery,key));
            });
        }
    }
    TvSearch(TvApplication app,PinyinTitles pinyin){this.app=app;this.pinyin=pinyin;}
    void remember(Collection<Series> rows){synchronized(index){for(Series row:rows)if(row.getId().matches("[0-9]{1,30}")&&!row.getTitle().trim().isEmpty())index.put(row.getId(),merge(index.get(row.getId()),row));}}
    private void remember(JSONArray values){ArrayList<Series> rows=new ArrayList<>();for(int i=0;i<values.length();i++){JSONObject row=values.optJSONObject(i);if(row!=null)rows.add(Series.Companion.fromJson(row));}remember(rows);}
    synchronized JSONObject start(String query)throws Exception{
        if(closed)throw new Exception("搜索界面已关闭");
        cancel("");remember(app.favorites());remember(app.history());
        ContentRepository repository;long revision;
        synchronized(app){repository=new ContentRepository(app.repository.getHttp().newBuilder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).build(),System::currentTimeMillis);repository.inheritDevice(app.repository);repository.setCredentials(app.repository.getCredentials());revision=app.accountRevision;}
        Session session=new Session(query,repository,revision,pinyin);current=session;
        synchronized(index){ArrayList<Series> known=new ArrayList<>();for(Series row:index.values())if(session.matches(row.getTitle()))known.add(row);known.sort(Comparator.comparingInt(row->session.score(row.getTitle())));for(Series row:known){if(session.rows.size()>=LIMIT)break;session.rows.put(row.getId(),row);}}
        website(session,session.onlineQuery());
        queue(session,7,()->suggestions(session));
        if(session.roman()){
            synchronized(session){session.initialScan=true;}
            queue(session,9,()->catalogPages(session));
        }
        queue(session,9,()->appPages(session));
        expandFamilies(session);
        boolean scan;
        synchronized(session){session.initializing=false;scan=session.pending==0&&!session.initialScan&&matching(session)==0;if(scan)session.initialScan=true;}
        if(scan)queue(session,9,()->catalogPages(session));
        return snapshot(session.id);
    }
    private boolean live(Session session){return !closed&&current==session&&!session.stopped&&session.revision==app.accountRevision;}
    private void queue(Session session,int seconds,Work work){
        if(!live(session)||System.nanoTime()>=session.deadline)return;
        synchronized(session){session.pending++;}
        try{workers.execute(()->{
            try{
                if(!live(session))return;
                session.scope.run(()->RequestScope.Companion.withDeadline(Math.min(session.deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)),()->{
                    try{if(live(session))work.run();return null;}catch(Exception error){throw new IllegalStateException(error);}
                }));
            }catch(LinkageError error){failCompatibility(session,error);}
            catch(Exception error){if(live(session))synchronized(session){session.errors++;}}
            finally{
                boolean scan=false;
                synchronized(session){session.pending=Math.max(0,session.pending-1);if(live(session)&&session.pending==0&&!session.initializing&&!session.initialScan&&matching(session)==0&&System.nanoTime()+TimeUnit.SECONDS.toNanos(3)<session.deadline){session.initialScan=true;scan=true;}}
                if(scan)queue(session,9,()->catalogPages(session));
            }
        });}catch(RejectedExecutionException stopped){synchronized(session){session.pending=Math.max(0,session.pending-1);}}
    }
    private void failCompatibility(Session session,LinkageError error){
        synchronized(session){session.errors++;session.stopped=true;}
        session.scope.cancel();app.recordProblem("搜索组件兼容性异常",error);
    }
    private int matching(Session session){int count=0;for(Series row:session.rows.values())if(session.matches(row.getTitle()))count++;return count;}
    private static String choose(String value,String previous){return value==null||value.isEmpty()?previous:value;}
    static String preferredTitle(String previous,String incoming){
        boolean oldPart=SeriesSeasons.INSTANCE.parse(previous).getMatched(),newPart=SeriesSeasons.INSTANCE.parse(incoming).getMatched();
        if(oldPart&&!newPart)return previous;if(newPart&&!oldPart)return incoming;
        String before=SearchTitles.INSTANCE.key(previous),after=SearchTitles.INSTANCE.key(incoming);
        return before.length()>after.length()&&before.startsWith(after)?previous:incoming;
    }
    private static Series merge(Series previous,Series incoming){
        if(previous==null)return incoming;
        return new Series(incoming.getId(),preferredTitle(previous.getTitle(),incoming.getTitle()),choose(incoming.getCover(),previous.getCover()),choose(incoming.getDescription(),previous.getDescription()),choose(incoming.getBadge(),previous.getBadge()),choose(incoming.getTags(),previous.getTags()));
    }
    private void add(Session session,Collection<Series> rows,boolean matchingOnly){
        if(!live(session))return;remember(rows);
        synchronized(session){
            for(Series row:rows){
                if(!row.getId().matches("[0-9]{1,30}")||row.getTitle().trim().isEmpty()||((matchingOnly||session.roman())&&!session.matches(row.getTitle())))continue;
                if(!session.rows.containsKey(row.getId())&&session.rows.size()>=LIMIT){
                    Series worst=null;int score=session.score(row.getTitle());
                    for(Series candidate:session.rows.values())if(session.score(candidate.getTitle())>score&&(worst==null||session.score(candidate.getTitle())>session.score(worst.getTitle())))worst=candidate;
                    if(worst==null)continue;session.rows.remove(worst.getId());
                }
                session.rows.put(row.getId(),merge(session.rows.get(row.getId()),row));
            }
        }
        expandFamilies(session);
    }
    private void website(Session session,String query){
        if(query.length()>80||query.trim().isEmpty())return;
        synchronized(session){if(session.webQueries.size()>=4||!session.webQueries.add(SearchTitles.INSTANCE.key(query)))return;}
        queue(session,6,()->add(session,session.repository.televisionWebsiteSearch(query).getItems(),false));
    }
    private void suggestions(Session session){
        List<SearchSuggestion> suggestions=session.repository.suggestions(session.onlineQuery(),20);ArrayList<Series> rows=new ArrayList<>();Set<String> completions=new HashSet<>();
        for(SearchSuggestion suggestion:suggestions){
            if(!live(session))return;
            Series series=suggestion.getSeries();if(series!=null&&session.matches(series.getTitle()))rows.add(series);
            String word=suggestion.getWordType();if(word.equals("category")||word.equals("short_play_category")||word.equals("short_play_kol_user"))continue;
            String completed=SeriesSeasons.INSTANCE.base(suggestion.getName());
            if(completions.size()<3&&session.matches(completed)&&!SearchTitles.INSTANCE.key(completed).equals(SearchTitles.INSTANCE.key(session.onlineQuery()))&&completions.add(SearchTitles.INSTANCE.key(completed)))website(session,completed);
        }
        add(session,rows,true);
    }
    private void appPages(Session session)throws Exception{
        if(session.repository.getDeviceIdentity().isEmpty()||session.repository.registrationCookiesSnapshot().isEmpty()){
            if(!live(session))return;app.ensureDevice(false);if(!live(session))return;session.repository.inheritDevice(app.repository);
        }
        for(int n=0;n<3&&live(session);n++){
            int page;synchronized(session){if(!session.appMore)return;page=session.nextApp;}
            CatalogPage result;
            try{result=session.repository.televisionAppSearch(session.onlineQuery(),page);}
            catch(Exception error){synchronized(session){session.appAvailable=false;}throw error;}
            if(!live(session))return;
            synchronized(session){session.appAvailable=true;session.nextApp=page+1;session.appMore=result.getHasMore();}
            add(session,result.getItems(),false);
        }
    }
    private ArrayList<Series> ordered(Session session){
        ArrayList<Series> rows=new ArrayList<>(session.rows.values());
        Map<String,Integer> scores=new HashMap<>();
        for(Series row:rows){String family=SearchTitles.INSTANCE.family(row.getTitle());int score=session.score(row.getTitle());scores.put(family,Math.min(score,scores.getOrDefault(family,100)));}
        rows.sort(Comparator.comparingInt((Series row)->scores.get(SearchTitles.INSTANCE.family(row.getTitle())))
            .thenComparing(row->SearchTitles.INSTANCE.family(row.getTitle()))
            .thenComparingInt(row->session.score(row.getTitle()))
            .thenComparingInt(row->SeriesSeasons.INSTANCE.season(row.getTitle()))
            .thenComparing(Series::getTitle).thenComparing(Series::getId));return rows;
    }
    private void expandFamilies(Session session){
        if(!live(session)||SearchTitles.INSTANCE.family(session.query).length()<2)return;
        ArrayList<Series> seeds=new ArrayList<>();
        synchronized(session){for(Series row:ordered(session)){
            if(session.score(row.getTitle())>=30)continue;
            String family=SearchTitles.INSTANCE.family(row.getTitle());if(session.families.size()>=3&&!family.equals(SearchTitles.INSTANCE.family(session.query)))continue;
            if(family.isEmpty()||!session.families.add(family))continue;seeds.add(row);
        }}
        for(Series seed:seeds){
            String base=SeriesSeasons.INSTANCE.base(seed.getTitle());
            if(!SearchTitles.INSTANCE.key(base).equals(SearchTitles.INSTANCE.key(session.query)))website(session,base);
            queue(session,7,()->add(session,session.repository.related(seed.getId(),seed.getTitle()).getSeasons(),true));
        }
    }
    private void catalogPages(Session session)throws Exception{
        Exception last=null;boolean answered=false;
        for(Map.Entry<ContentType,Cursor> entry:session.catalog.entrySet()){
            for(int n=0;n<3&&live(session);n++){
                Cursor cursor=entry.getValue();int page;synchronized(session){if(!cursor.more)break;page=cursor.next;}
                try{
                    CatalogPage result=session.repository.televisionCatalogPage(page,entry.getKey());answered=true;if(!live(session))return;
                    synchronized(session){cursor.next=page+1;cursor.more=result.getHasMore();}
                    add(session,result.getItems(),true);
                }catch(Exception error){last=error;break;}
            }
        }
        if(!answered&&last!=null)throw last;
    }
    synchronized JSONObject more(String id)throws Exception{
        Session session=current;if(session==null||!session.id.equals(id))throw new Exception("搜索已结束，请重新搜索");
        if(session.stopped||session.revision!=app.accountRevision)return start(session.query);
        synchronized(session){if(session.pending>0)return snapshot(id);session.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);session.families.clear();session.webQueries.clear();}
        boolean useApp=session.appAvailable&&session.appMore;
        if(useApp)queue(session,10,()->appPages(session));
        if(!useApp||session.roman())queue(session,12,()->catalogPages(session));
        expandFamilies(session);
        return snapshot(id);
    }
    JSONObject snapshot(String id)throws Exception{
        Session session=current;if(session==null||!session.id.equals(id))throw new Exception("搜索已结束，请重新搜索");
        synchronized(session){
            ArrayList<Series> ordered=ordered(session);JSONArray items=new JSONArray(),families=new JSONArray();
            Map<String,List<Series>> grouped=new LinkedHashMap<>();
            for(Series row:ordered)grouped.computeIfAbsent(SearchTitles.INSTANCE.family(row.getTitle()),key->new ArrayList<>()).add(row);
            for(Series row:ordered){JSONObject value=row.toJson();List<Series> siblings=grouped.get(SearchTitles.INSTANCE.family(row.getTitle()));
                if(siblings.size()>1||SeriesSeasons.INSTANCE.parse(row.getTitle()).getMatched())value.put("seasonLabel",SeriesSeasons.INSTANCE.badge(row.getTitle()));items.put(value);}
            for(List<Series> rows:grouped.values()){
                if(families.length()>=3)break;if(rows.size()<2||!session.matches(rows.get(0).getTitle()))continue;
                rows.sort(Comparator.comparingInt(row->SeriesSeasons.INSTANCE.season(row.getTitle())));
                JSONArray seasons=new JSONArray();Map<Integer,Integer> duplicates=new HashMap<>();for(Series row:rows){int season=SeriesSeasons.INSTANCE.season(row.getTitle());duplicates.put(season,duplicates.getOrDefault(season,0)+1);}
                for(Series row:rows){int season=SeriesSeasons.INSTANCE.season(row.getTitle());seasons.put(new JSONObject().put("id",row.getId()).put("title",row.getTitle()).put("label",duplicates.get(season)>1?row.getTitle():SeriesSeasons.INSTANCE.badge(row.getTitle())));}
                families.put(new JSONObject().put("title",SeriesSeasons.INSTANCE.base(rows.get(0).getTitle())).put("items",seasons));
            }
            boolean more=false;for(Cursor cursor:session.catalog.values())more=more||cursor.more;
            return new JSONObject().put("session",session.id).put("keyword",session.query).put("items",items).put("families",families)
                .put("searching",!session.stopped&&session.pending>0).put("hasMore",session.rows.size()<LIMIT&&(more||session.appAvailable&&session.appMore))
                .put("matched",matching(session)).put("partial",session.errors>0).put("stopped",session.stopped);
        }
    }
    synchronized void cancel(String id){Session session=current;if(session!=null&&(id.isEmpty()||session.id.equals(id))){session.stopped=true;session.scope.cancel();}}
    @Override public void close(){closed=true;cancel("");workers.shutdownNow();}
}
