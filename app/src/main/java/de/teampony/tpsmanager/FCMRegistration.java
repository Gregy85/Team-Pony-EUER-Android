package de.teampony.tpsmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class FCMRegistration {
    private static final String BASE="https://vljbatzgfnlotpfnnlco.supabase.co";
    private static final String APIKEY="sb_publishable_IODOjr3bcQNRwZcWN5y3RA_UVmZCugB";

    private FCMRegistration(){}

    public static void saveAndRegister(Context ctx,String token){
        if(token==null || token.trim().isEmpty()) return;
        SharedPreferences p=ctx.getSharedPreferences("tps_native",Context.MODE_PRIVATE);
        p.edit().putString("fcm_token",token).apply();
        registerAsync(ctx,token);
    }

    public static void registerSaved(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences("tps_native",Context.MODE_PRIVATE);
        String token=p.getString("fcm_token","");
        if(!token.isEmpty()) registerAsync(ctx,token);
    }

    private static void registerAsync(Context ctx,String token){
        new Thread(() -> {
            try{
                SharedPreferences p=ctx.getSharedPreferences("tps_native",Context.MODE_PRIVATE);
                String access=p.getString("access_token","");
                String user=p.getString("user_id","");
                if(access.isEmpty() || user.isEmpty()) return;

                HttpURLConnection c=(HttpURLConnection)new URL(BASE+"/rest/v1/rpc/register_fcm_device").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Content-Type","application/json");
                c.setRequestProperty("apikey",APIKEY);
                c.setRequestProperty("Authorization","Bearer "+access);

                String device=(Build.MANUFACTURER+" "+Build.MODEL).trim();
                JSONObject body=new JSONObject()
                    .put("p_token",token)
                    .put("p_device_name",device)
                    .put("p_platform","android");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=c.getOutputStream()){ out.write(bytes); }
                c.getResponseCode();
                c.disconnect();
            }catch(Exception ignored){}
        }).start();
    }
}
