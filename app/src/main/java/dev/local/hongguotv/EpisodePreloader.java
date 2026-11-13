package dev.local.hongguotv;

import java.io.Closeable;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** One speculative episode. A claim transfers ownership; cancellation closes stale completions. */
public final class EpisodePreloader<T extends Closeable> {
    private final Executor executor;
    private final LongSupplier clock;
    private final long ttl;
    private Entry current;
    private final class Entry {
        final String key; final Runnable abort; FutureTask<Void> task;
        T value; boolean done; long readyAt;Exception failure;
        Entry(String key,Runnable abort){this.key=key;this.abort=abort;}
    }
    public EpisodePreloader(Executor executor,LongSupplier clock,long ttl){this.executor=executor;this.clock=clock;this.ttl=ttl;}
    public synchronized boolean contains(String key){return current!=null&&current.key.equals(key);}
    public synchronized void start(String key,Callable<T> load,Runnable abort){
        if(contains(key))return;
        clear();final Entry entry=new Entry(key,abort);current=entry;
        entry.task=new FutureTask<>(()->{
            T value=null;
            try{value=load.call();}catch(Exception problem){entry.failure=problem;}
            synchronized(this){
                if(current==entry&&!Thread.currentThread().isInterrupted()){
                    entry.value=value;entry.readyAt=clock.getAsLong();entry.done=true;
                }else close(value);
            }
            return null;
        });
        executor.execute(entry.task);
    }
    public synchronized Exception failure(String key){return current!=null&&current.key.equals(key)&&current.done?current.failure:null;}
    public T claim(String key,boolean wait) {
        Entry entry;
        synchronized(this){entry=current;if(entry==null||!entry.key.equals(key)||(!wait&&!entry.done))return null;}
        if(wait)try{entry.task.get();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();synchronized(this){if(current==entry)clear();}return null;}catch(Exception ignored){}
        synchronized(this){
            if(current!=entry)return null;
            current=null;
            T value=entry.value;entry.value=null;
            long age=clock.getAsLong()-entry.readyAt;
            if(value!=null&&age>=0&&age<=ttl)return value;
            close(value);return null;
        }
    }
    public synchronized void clear(){
        Entry entry=current;current=null;
        if(entry!=null){entry.abort.run();if(entry.task!=null)entry.task.cancel(true);close(entry.value);entry.value=null;}
    }
    private void close(T value){if(value!=null)try{value.close();}catch(Exception ignored){}}
}
