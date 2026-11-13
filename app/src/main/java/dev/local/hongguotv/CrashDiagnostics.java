package dev.local.hongguotv;

import android.content.SharedPreferences;
import android.os.Build;

/** Local, bounded exception types/frames only; never stores request URLs, cookies or account values. */
final class CrashDiagnostics {
    static String describe(String context,Throwable error){
        StringBuilder result=new StringBuilder(context).append(" · Android ").append(Build.VERSION.RELEASE);
        Throwable current=error;
        for(int depth=0;current!=null&&depth<5;depth++){
            result.append(depth==0?" · ":" ← ").append(current.getClass().getSimpleName());
            StackTraceElement[] frames=current.getStackTrace();
            if(frames.length>0)result.append(" @ ").append(frames[0].getClassName()).append('.').append(frames[0].getMethodName()).append(':').append(frames[0].getLineNumber());
            if(current.getCause()==current)break;current=current.getCause();
        }
        return result.substring(0,Math.min(1800,result.length()));
    }
    static void install(SharedPreferences preferences){
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{preferences.edit().putString("last_problem",describe("应用异常退出",error)).commit();}catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
    }
}
