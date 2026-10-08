package de.teampony.tpsmanager;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Color;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.*;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import android.widget.Toast;
import java.io.*;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://phenomenal-sfogliatella-09cbcb.netlify.app/";
    private WebView web;
    private Uri pendingPdf;
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private float swipeDownX, swipeDownY;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        web = new WebView(this); setContentView(web);
        createNotificationChannel();
        web.addJavascriptInterface(new AndroidBridge(), "AndroidTPS");
        web.setOnTouchListener((v,e)->{
            if(e.getAction()==android.view.MotionEvent.ACTION_DOWN){ swipeDownX=e.getX(); swipeDownY=e.getY(); }
            if(e.getAction()==android.view.MotionEvent.ACTION_UP){
                float dx=e.getX()-swipeDownX, dy=Math.abs(e.getY()-swipeDownY);
                boolean swipeRight = swipeDownX < web.getWidth()*0.30f && dx > web.getWidth()*0.25f;
                boolean swipeLeft = swipeDownX > web.getWidth()*0.70f && dx < -web.getWidth()*0.25f;
                if((swipeRight || swipeLeft) && dy < web.getHeight()*0.18f){
                    web.evaluateJavascript("(function(){try{if(window.tpsCloseScanner){window.tpsCloseScanner();return 'closed'}const b=[...document.querySelectorAll('button,[role=button]')].find(x=>/zurück|schließen|abbrechen|close|kamera schließen/i.test((x.innerText||x.getAttribute('aria-label')||'')));if(b){b.click();return 'closed'}return 'none'}catch(e){return 'err'}})();", value->{ if("\"none\"".equals(value)&&web.canGoBack()) web.goBack(); });
                    return true;
                }
            }
            return false;
        });
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest r){ runOnUiThread(() -> r.grant(r.getResources())); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params){
                if(fileChooserCallback!=null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback=callback;
                Intent intent;
                try { intent=params.createIntent(); }
                catch(Exception e){
                    intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","application/pdf"});
                }
                try { startActivityForResult(intent,FILE_CHOOSER_REQUEST); return true; }
                catch(Exception e){ fileChooserCallback=null; Toast.makeText(MainActivity.this,"Dateiauswahl konnte nicht geöffnet werden.",Toast.LENGTH_LONG).show(); return false; }
            }
        });
        web.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView v,String u){
            deliverPdf();
            // Android 15+ keeps the WebView itself inside the system bars. Also expose
            // native inset values to the web app for fixed headers/scanner overlays.
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.graphics.Rect r=new android.graphics.Rect();
                getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
                float d=getResources().getDisplayMetrics().density;
                int top=Math.max(0,Math.round(r.top/d));
                int bottom=Math.max(0,Math.round((getResources().getDisplayMetrics().heightPixels-r.bottom)/d));
                String js="(function(){try{document.documentElement.style.setProperty('--tps-native-safe-top','"+top+"px');document.documentElement.style.setProperty('--tps-native-safe-bottom','"+bottom+"px');let s=document.getElementById('tps-android-safearea');if(!s){s=document.createElement('style');s.id='tps-android-safearea';s.textContent=':root{--tps-safe-top:max(env(safe-area-inset-top,0px),var(--tps-native-safe-top,0px));--tps-safe-bottom:max(env(safe-area-inset-bottom,0px),var(--tps-native-safe-bottom,0px))}';document.head.appendChild(s)}}catch(e){}})();";
                v.evaluateJavascript(js,null);
            }
        }});
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA},10);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
        }
        acceptIntent(getIntent());
        web.loadUrl(APP_URL);
    }


    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("tps_booking_updates","TPS Manager Hinweise",NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Neue Buchungen und Hinweise im TPS Manager");
            ch.setShowBadge(true);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void updateBadgeNotification(int count){
        NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if(count<=0){ nm.cancel(4201); return; }
        Intent launch=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,4201,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder nb=Build.VERSION.SDK_INT>=26
            ? new android.app.Notification.Builder(this,"tps_booking_updates")
            : new android.app.Notification.Builder(this);
        nb.setContentTitle("TPS Manager")
          .setContentText(count==1?"1 neuer Hinweis":count+" neue Hinweise")
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentIntent(pi)
          .setAutoCancel(false)
          .setOnlyAlertOnce(true)
          .setNumber(count);
        nm.notify(4201,nb.build());
    }

    private void scheduleNotificationWorker(){
        PeriodicWorkRequest req=new PeriodicWorkRequest.Builder(NotificationWorker.class,15,TimeUnit.MINUTES).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tps-notification-poll", ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public class AndroidBridge {
        @JavascriptInterface public void setBadgeCount(int count){ runOnUiThread(()->updateBadgeNotification(count)); }
        @JavascriptInterface public void syncSession(String json){
            try{
                org.json.JSONObject o=new org.json.JSONObject(json);
                getSharedPreferences("tps_native",MODE_PRIVATE).edit()
                    .putString("access_token",o.optString("access_token",""))
                    .putString("refresh_token",o.optString("refresh_token",""))
                    .putString("user_id",o.optString("user_id",""))
                    .apply();
                scheduleNotificationWorker();
            }catch(Exception ignored){}
        }
    }

    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); setIntent(i); acceptIntent(i); deliverPdf(); }

    private void acceptIntent(Intent i){
        if(i==null)return;
        if(Intent.ACTION_VIEW.equals(i.getAction()) && i.getData()!=null) pendingPdf=i.getData();
        if(Intent.ACTION_SEND.equals(i.getAction())) pendingPdf=i.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private String displayName(Uri u){
        String n="Beleg.pdf";
        try(android.database.Cursor c=getContentResolver().query(u,null,null,null,null)){ if(c!=null&&c.moveToFirst()){ int x=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(x>=0)n=c.getString(x); }}catch(Exception ignored){}
        return n==null?"Beleg.pdf":n;
    }

    private void deliverPdf(){
        if(pendingPdf==null || web.getProgress()<80)return;
        Uri u=pendingPdf; pendingPdf=null;
        try(InputStream in=getContentResolver().openInputStream(u); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0){ out.write(buf,0,n); if(out.size()>20*1024*1024) throw new IOException("PDF zu groß"); }
            String b64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
            String name=displayName(u).replace("\\","\\\\").replace("'","\\'");
            String js="(async()=>{try{const b=atob('"+b64+"'),a=new Uint8Array(b.length);for(let i=0;i<b.length;i++)a[i]=b.charCodeAt(i);const f=new File([a],'"+name+"',{type:'application/pdf'});window.dispatchEvent(new CustomEvent('tps-android-pdf',{detail:{file:f}}));const inputs=[...document.querySelectorAll(\"input[type=file]\")];const inp=inputs.find(x=>(x.accept||'').toLowerCase().includes('pdf'))||inputs[0];if(inp){const d=new DataTransfer();d.items.add(f);inp.files=d.files;inp.dispatchEvent(new Event('change',{bubbles:true}));}else{alert('PDF empfangen. Bitte in TPS Manager Beleg scannen öffnen.');}}catch(e){alert('PDF konnte nicht übernommen werden: '+e.message)}})();";
            web.evaluateJavascript(js,null);
        }catch(Exception e){ Toast.makeText(this,"PDF konnte nicht geöffnet werden: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==FILE_CHOOSER_REQUEST && fileChooserCallback!=null){
            Uri[] result=null;
            if(resultCode==RESULT_OK){
                result=WebChromeClient.FileChooserParams.parseResult(resultCode,data);
                if(result==null && data!=null && data.getData()!=null) result=new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback=null;
        }
    }

    private void nativeBackFallback(){
        if(web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override public void onBackPressed(){
        if(web==null){ super.onBackPressed(); return; }
        web.evaluateJavascript("(function(){try{return (window.teamPonyHandleAndroidBack&&window.teamPonyHandleAndroidBack())?'handled':'none'}catch(e){return 'none'}})();", value -> {
            if(!"\"handled\"".equals(value)) nativeBackFallback();
        });
    }
}
