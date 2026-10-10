package de.teampony.tpsmanager;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class NotificationWorker extends Worker {
    private static final String BASE="https://vljbatzgfnlotpfnnlco.supabase.co";
    private static final String APIKEY="sb_publishable_IODOjr3bcQNRwZcWN5y3RA_UVmZCugB";

    public NotificationWorker(@NonNull Context context,@NonNull WorkerParameters params){ super(context,params); }

    private static String read(InputStream in) throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
        while((n=in.read(b))>0)out.write(b,0,n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String refreshToken(SharedPreferences p) throws Exception{
        String refresh=p.getString("refresh_token","");
        if(refresh.isEmpty()) return p.getString("access_token","");
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+"/auth/v1/token?grant_type=refresh_token").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("apikey",APIKEY);
        String body=new JSONObject().put("refresh_token",refresh).toString();
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code=c.getResponseCode(); String s=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());
        if(code<200||code>=300) return p.getString("access_token","");
        JSONObject o=new JSONObject(s);
        String access=o.optString("access_token","");
        String newRefresh=o.optString("refresh_token",refresh);
        if(!access.isEmpty())p.edit().putString("access_token",access).putString("refresh_token",newRefresh).apply();
        return access;
    }

    private JSONArray unread(String token,String userId) throws Exception{
        String q=BASE+"/rest/v1/booking_notifications?select=id,title,body,created_at&user_id=eq."+URLEncoder.encode(userId,"UTF-8")+"&read_at=is.null&order=created_at.desc&limit=20";
        HttpURLConnection c=(HttpURLConnection)new URL(q).openConnection();
        c.setRequestProperty("apikey",APIKEY); c.setRequestProperty("Authorization","Bearer "+token);
        int code=c.getResponseCode(); String s=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());
        if(code<200||code>=300) throw new IOException("HTTP "+code);
        return new JSONArray(s);
    }

    private void show(int count,JSONArray rows){
        Context ctx=getApplicationContext(); NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("tps_booking_updates","TPS Manager Hinweise",NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(true); nm.createNotificationChannel(ch);
        }
        if(count<=0){nm.cancel(4201);return;}
        Intent launch=new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(ctx,4201,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        JSONObject latest=rows.optJSONObject(0);
        String title=count==1?(latest==null?"Neue Homepage-Anfrage":latest.optString("title","Neue Homepage-Anfrage")):count+" neue Homepage-Anfragen";
        String text=latest==null?"":latest.optString("body","");
        Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,"tps_booking_updates"):new Notification.Builder(ctx);
        nb.setContentTitle(title).setContentText(text).setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pi).setAutoCancel(false).setOnlyAlertOnce(true).setNumber(count);
        nm.notify(4201,nb.build());
    }

    @NonNull @Override public Result doWork(){
        try{
            SharedPreferences p=getApplicationContext().getSharedPreferences("tps_native",Context.MODE_PRIVATE);
            String user=p.getString("user_id",""); if(user.isEmpty())return Result.success();
            String token=refreshToken(p); if(token.isEmpty())return Result.retry();
            JSONArray rows=unread(token,user); show(rows.length(),rows); return Result.success();
        }catch(Exception e){return Result.retry();}
    }
}
