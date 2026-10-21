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
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.*;
import com.google.firebase.messaging.FirebaseMessaging;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import android.widget.Toast;
import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://phenomenal-sfogliatella-09cbcb.netlify.app/";
    private WebView web;
    private Uri pendingPdf;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean fileChooserIsDocx=false;
    private boolean fileChooserIsMultiple=false;
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final String FILE_MANAGER_PLUS_PACKAGE = "com.alphainventor.filemanager";
    private static final String PREF_LAST_MEDIA_URI = "last_media_location_uri";
    private static final int SAVE_DOCUMENT_REQUEST = 1202;
    private static final int NAS_FOLDER_REQUEST = 1203;
    private static final int AUDIO_FOLDER_REQUEST = 1204;
    private static final String PREF_AUDIO_FOLDER_URI = "preferred_audio_folder_uri";
    private static final String PREF_AUDIO_FOLDER_NAME = "preferred_audio_folder_name";
    private byte[] pendingSaveBytes;
    private String pendingSaveName;
    private String pendingDocxXmlB64;
    private String pendingDocxName;
    private String pendingDocxFormat;
    private Uri pendingWordUri;
    private String pendingWordMime;
    private static final int DOCX_CHUNK_SIZE=48000;
    private static final int NAS_AUDIO_CHUNK_SIZE=48000;
    private String pendingNasAudioB64;
    private String pendingNasAudioName;
    private String pendingNasAudioMime;
    private android.media.MediaPlayer nasAudioPlayer;
    private final java.util.HashSet<String> allowedAudioFolderUris=new java.util.HashSet<>();
    private final java.util.HashSet<String> allowedAudioFileUris=new java.util.HashSet<>();
    private float swipeDownX, swipeDownY;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        web = new WebView(this); setContentView(web);
        createNotificationChannel();
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if(task.isSuccessful()) FCMRegistration.saveAndRegister(MainActivity.this, task.getResult());
        });
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
                fileChooserIsDocx=isDocxChooser(params);
                fileChooserIsMultiple=params!=null && params.getMode()==WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
                Intent intent;
                if(fileChooserIsDocx){
                    intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
                    applyLastNasLocation(intent);
                }else if(fileChooserIsMultiple){
                    if(launchMultipleMediaPicker(params)) return true;
                    fileChooserCallback=null;
                    fileChooserIsDocx=false;
                    fileChooserIsMultiple=false;
                    return false;
                }else{
                    try { intent=params.createIntent(); }
                    catch(Exception e){
                        intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","application/pdf"});
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(intent,FILE_CHOOSER_REQUEST); return true; }
                catch(Exception e){ fileChooserCallback=null; fileChooserIsDocx=false; fileChooserIsMultiple=false; Toast.makeText(MainActivity.this,"Dateiauswahl konnte nicht geöffnet werden.",Toast.LENGTH_LONG).show(); return false; }
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


    private java.util.ArrayList<String> collectAcceptedMimeTypes(WebChromeClient.FileChooserParams params){
        java.util.ArrayList<String> mimeTypes=new java.util.ArrayList<>();
        try{
            String[] accept=params==null?null:params.getAcceptTypes();
            if(accept!=null)for(String raw:accept){
                if(raw==null)continue;
                for(String part:raw.split(",")){
                    String t=part.trim().toLowerCase();
                    if(t.contains("/") && !mimeTypes.contains(t))mimeTypes.add(t);
                }
            }
        }catch(Exception ignored){}
        if(mimeTypes.isEmpty()){
            mimeTypes.add("image/*");
            mimeTypes.add("video/*");
        }
        return mimeTypes;
    }

    private void configureMultipleMediaIntent(Intent intent,WebChromeClient.FileChooserParams params){
        java.util.ArrayList<String> mimeTypes=collectAcceptedMimeTypes(params);
        if(mimeTypes.size()==1) intent.setType(mimeTypes.get(0));
        else{
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,mimeTypes.toArray(new String[mimeTypes.size()]));
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    private void applyLastMediaLocation(Intent intent){
        if(Build.VERSION.SDK_INT<26 || intent==null)return;
        try{
            String saved=getSharedPreferences("tps_native",MODE_PRIVATE).getString(PREF_LAST_MEDIA_URI,"");
            if(saved!=null&&!saved.isEmpty()) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(saved));
        }catch(Exception ignored){}
    }

    private void rememberLastMediaLocation(Uri uri,int flags){
        if(uri==null)return;
        try{
            int takeFlags=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if(takeFlags!=0)getContentResolver().takePersistableUriPermission(uri,takeFlags);
        }catch(Exception ignored){}
        try{
            getSharedPreferences("tps_native",MODE_PRIVATE).edit().putString(PREF_LAST_MEDIA_URI,uri.toString()).apply();
        }catch(Exception ignored){}
    }

    private boolean launchMultipleMediaPicker(WebChromeClient.FileChooserParams params){
        // Bevorzugt Dateimanager +, wenn er den Android-Auswahldialog anbietet.
        // ACTION_GET_CONTENT ist der uebliche Vertrag fuer externe Dateimanager.
        try{
            Intent fm=new Intent(Intent.ACTION_GET_CONTENT);
            configureMultipleMediaIntent(fm,params);
            fm.setPackage(FILE_MANAGER_PLUS_PACKAGE);
            applyLastMediaLocation(fm);
            startActivityForResult(fm,FILE_CHOOSER_REQUEST);
            return true;
        }catch(Exception ignored){}

        // Sicherer Fallback: Android Storage Access Framework. Hier kann TPS den
        // zuletzt erfolgreich verwendeten Ort per EXTRA_INITIAL_URI wieder oeffnen.
        try{
            Intent system=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            configureMultipleMediaIntent(system,params);
            applyLastMediaLocation(system);
            startActivityForResult(system,FILE_CHOOSER_REQUEST);
            return true;
        }catch(Exception e){
            Toast.makeText(MainActivity.this,"Mehrfachauswahl konnte nicht geöffnet werden.",Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean isDocxChooser(WebChromeClient.FileChooserParams params){
        try{
            String[] types=params==null?null:params.getAcceptTypes();
            if(types!=null)for(String t:types){
                String s=String.valueOf(t).toLowerCase();
                if(s.contains("wordprocessingml")||s.contains(".docx")||s.contains("msword")) return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    private void applyLastNasLocation(Intent intent){
        if(Build.VERSION.SDK_INT<26 || intent==null)return;
        try{
            android.content.SharedPreferences p=getSharedPreferences("tps_native",MODE_PRIVATE);
            String saved=p.getString("preferred_nas_folder_uri","");
            if(saved==null||saved.isEmpty()) saved=p.getString("last_docx_location_uri","");
            if(saved!=null&&!saved.isEmpty()) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(saved));
        }catch(Exception ignored){}
    }

    private void rememberNasLocation(Uri uri,int flags){
        if(uri==null)return;
        try{
            int takeFlags=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if(takeFlags!=0)getContentResolver().takePersistableUriPermission(uri,takeFlags);
        }catch(Exception ignored){}
        Uri location=uri;
        try{
            if(Build.VERSION.SDK_INT>=19 && DocumentsContract.isDocumentUri(this,uri)){
                String id=DocumentsContract.getDocumentId(uri);
                int slash=id==null?-1:id.lastIndexOf('/');
                if(slash>0){
                    String parent=id.substring(0,slash);
                    Uri p=DocumentsContract.buildDocumentUri(uri.getAuthority(),parent);
                    if(p!=null)location=p;
                }
            }
        }catch(Exception ignored){}
        try{
            getSharedPreferences("tps_native",MODE_PRIVATE).edit().putString("last_docx_location_uri",location.toString()).apply();
        }catch(Exception ignored){}
    }

    private String nasFolderDisplayName(Uri uri){
        if(uri==null)return "Ponyschule";
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){
                int x=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(x>=0){
                    String n=c.getString(x);
                    if(n!=null&&!n.trim().isEmpty())return n.trim();
                }
            }
        }catch(Exception ignored){}
        try{
            String id=DocumentsContract.getTreeDocumentId(uri);
            if(id!=null&&!id.isEmpty()){
                int slash=Math.max(id.lastIndexOf('/'),id.lastIndexOf(':'));
                String n=slash>=0?id.substring(slash+1):id;
                if(!n.isEmpty())return n;
            }
        }catch(Exception ignored){}
        return "Ponyschule";
    }


    private Uri getSavedAudioTree(){
        try{
            String u=getSharedPreferences("tps_native",MODE_PRIVATE).getString(PREF_AUDIO_FOLDER_URI,"");
            return (u==null||u.isEmpty())?null:Uri.parse(u);
        }catch(Exception ignored){ return null; }
    }

    private Uri treeRootDocument(Uri tree){
        if(tree==null)return null;
        try{
            String id=DocumentsContract.getTreeDocumentId(tree);
            return DocumentsContract.buildDocumentUriUsingTree(tree,id);
        }catch(Exception e){ return tree; }
    }

    private String documentIdFor(Uri uri){
        if(uri==null)return "";
        try{return DocumentsContract.getDocumentId(uri);}catch(Exception ignored){}
        try{return DocumentsContract.getTreeDocumentId(uri);}catch(Exception ignored){}
        return "";
    }

    private boolean isAudioFile(String name,String mime){
        String n=name==null?"":name.toLowerCase();
        String m=mime==null?"":mime.toLowerCase();
        return m.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a") ||
               n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".opus");
    }

    private String jsonError(String message){
        try{
            org.json.JSONObject o=new org.json.JSONObject();
            o.put("ok",false);o.put("error",message==null?"Unbekannter Fehler":message);
            return o.toString();
        }catch(Exception ignored){return "{\"ok\":false}";}
    }

    private String listAudioFolderInternal(String folderUriText){
        Uri tree=getSavedAudioTree();
        if(tree==null)return jsonError("Bitte zuerst den Musikordner auswählen.");
        try{
            Uri root=treeRootDocument(tree);
            if(root==null)return jsonError("Musikordner konnte nicht geöffnet werden.");
            if(allowedAudioFolderUris.isEmpty())allowedAudioFolderUris.add(root.toString());

            Uri folder=(folderUriText==null||folderUriText.trim().isEmpty())?root:Uri.parse(folderUriText);
            if(!allowedAudioFolderUris.contains(folder.toString()))return jsonError("Dieser Ordner gehört nicht zur freigegebenen Musikbibliothek.");

            String folderId=documentIdFor(folder);
            if(folderId.isEmpty())return jsonError("Ordner-ID konnte nicht gelesen werden.");
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folderId);

            java.util.ArrayList<org.json.JSONObject> dirs=new java.util.ArrayList<>();
            java.util.ArrayList<org.json.JSONObject> files=new java.util.ArrayList<>();
            String[] projection=new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            };

            try(android.database.Cursor c=getContentResolver().query(children,projection,null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    String docId=c.getString(0);
                    String name=c.getString(1);
                    String mime=c.getString(2);
                    long size=c.isNull(3)?0L:c.getLong(3);
                    long modified=c.isNull(4)?0L:c.getLong(4);
                    if(docId==null||docId.isEmpty())continue;
                    Uri child=DocumentsContract.buildDocumentUriUsingTree(tree,docId);
                    boolean isDir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if(!isDir&&!isAudioFile(name,mime))continue;

                    org.json.JSONObject item=new org.json.JSONObject();
                    item.put("name",name==null?"":name);
                    item.put("uri",child.toString());
                    item.put("isDir",isDir);
                    item.put("mime",mime==null?"":mime);
                    item.put("size",size);
                    item.put("modified",modified);
                    if(isDir){
                        allowedAudioFolderUris.add(child.toString());
                        dirs.add(item);
                    }else{
                        allowedAudioFileUris.add(child.toString());
                        files.add(item);
                    }
                }
            }

            java.util.Comparator<org.json.JSONObject> cmp=(a,b)->a.optString("name","").compareToIgnoreCase(b.optString("name",""));
            java.util.Collections.sort(dirs,cmp); java.util.Collections.sort(files,cmp);
            org.json.JSONArray arr=new org.json.JSONArray();
            for(org.json.JSONObject x:dirs)arr.put(x);
            for(org.json.JSONObject x:files)arr.put(x);

            org.json.JSONObject out=new org.json.JSONObject();
            out.put("ok",true);
            out.put("folderUri",folder.toString());
            out.put("rootUri",root.toString());
            out.put("folderName",nasFolderDisplayName(folder));
            out.put("items",arr);
            return out.toString();
        }catch(Exception e){
            return jsonError("Musikordner konnte nicht gelesen werden: "+e.getMessage());
        }
    }

    private void stopNasAudioPlayer(){
        try{
            if(nasAudioPlayer!=null){
                try{nasAudioPlayer.stop();}catch(Exception ignored){}
                try{nasAudioPlayer.release();}catch(Exception ignored){}
            }
        }finally{nasAudioPlayer=null;}
    }

    private boolean playNasAudioInternal(String uriText){
        if(uriText==null||!allowedAudioFileUris.contains(uriText))return false;
        runOnUiThread(()->{
            try{
                stopNasAudioPlayer();
                android.media.MediaPlayer p=new android.media.MediaPlayer();
                p.setDataSource(MainActivity.this,Uri.parse(uriText));
                p.setOnPreparedListener(mp->mp.start());
                p.setOnCompletionListener(mp->{
                    stopNasAudioPlayer();
                    if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-nas-audio-ended'));",null);
                });
                p.setOnErrorListener((mp,what,extra)->{
                    stopNasAudioPlayer();
                    if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-nas-audio-ended'));",null);
                    return true;
                });
                nasAudioPlayer=p;
                p.prepareAsync();
            }catch(Exception e){
                stopNasAudioPlayer();
                Toast.makeText(MainActivity.this,"Audio konnte nicht abgespielt werden: "+e.getMessage(),Toast.LENGTH_LONG).show();
            }
        });
        return true;
    }

    private synchronized String prepareNasAudioInternal(String uriText){
        if(uriText==null||!allowedAudioFileUris.contains(uriText))return jsonError("Audiodatei ist nicht freigegeben.");
        Uri uri=Uri.parse(uriText);
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)return jsonError("Audiodatei konnte nicht geöffnet werden.");
            byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0){
                out.write(buf,0,n);
                if(out.size()>30*1024*1024)throw new IOException("Audiodatei ist größer als 30 MB");
            }
            byte[] bytes=out.toByteArray();
            pendingNasAudioB64=Base64.encodeToString(bytes,Base64.NO_WRAP);
            pendingNasAudioName=displayName(uri);
            pendingNasAudioMime=getContentResolver().getType(uri);
            if(pendingNasAudioMime==null||pendingNasAudioMime.isEmpty())pendingNasAudioMime="audio/mpeg";
            org.json.JSONObject o=new org.json.JSONObject();
            o.put("ok",true);
            o.put("name",pendingNasAudioName);
            o.put("mime",pendingNasAudioMime);
            o.put("size",bytes.length);
            o.put("chunks",(pendingNasAudioB64.length()+NAS_AUDIO_CHUNK_SIZE-1)/NAS_AUDIO_CHUNK_SIZE);
            return o.toString();
        }catch(Exception e){
            pendingNasAudioB64=null;pendingNasAudioName=null;pendingNasAudioMime=null;
            return jsonError("Audiodatei konnte nicht vorbereitet werden: "+e.getMessage());
        }
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
          .setContentText(count==1?"Neue Homepage-Anfrage":count+" neue Homepage-Anfragen")
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
        @JavascriptInterface public String getAppVersion(){
            try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
            catch(Exception e){ return "unbekannt"; }
        }
        @JavascriptInterface public long getAppVersionCode(){
            try {
                android.content.pm.PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);
                return Build.VERSION.SDK_INT>=28 ? p.getLongVersionCode() : p.versionCode;
            } catch(Exception e){ return -1; }
        }
        @JavascriptInterface public synchronized String getPendingDocxName(){ return pendingDocxName==null?"":pendingDocxName; }
        @JavascriptInterface public synchronized String getPendingDocxFormat(){ return pendingDocxFormat==null?"":pendingDocxFormat; }
        @JavascriptInterface public synchronized String getPendingWordName(){ return pendingDocxName==null?"":pendingDocxName; }
        @JavascriptInterface public synchronized String getPendingWordMime(){ return pendingWordMime==null?"application/octet-stream":pendingWordMime; }
        @JavascriptInterface public synchronized int refreshPendingWordAttachment(){
            if(pendingWordUri==null)return -1;
            try(InputStream in=getContentResolver().openInputStream(pendingWordUri); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)return -1;
                byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))>0){
                    out.write(buf,0,n);
                    if(out.size()>8*1024*1024)throw new IOException("Word-Datei ist größer als 8 MB");
                }
                pendingDocxXmlB64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                return out.size();
            }catch(Exception e){
                pendingDocxXmlB64=null;
                final String msg=e.getMessage();
                runOnUiThread(()->Toast.makeText(MainActivity.this,"Word-Anhang konnte nicht gelesen werden: "+msg,Toast.LENGTH_LONG).show());
                return -1;
            }
        }
        @JavascriptInterface public synchronized int getPendingDocxChunkCount(){
            if(pendingDocxXmlB64==null||pendingDocxXmlB64.isEmpty())return 0;
            return (pendingDocxXmlB64.length()+DOCX_CHUNK_SIZE-1)/DOCX_CHUNK_SIZE;
        }
        @JavascriptInterface public synchronized int getPendingWordChunkCount(){ return getPendingDocxChunkCount(); }
        @JavascriptInterface public synchronized String getPendingDocxChunk(int index){
            if(pendingDocxXmlB64==null||index<0)return "";
            int start=index*DOCX_CHUNK_SIZE;
            if(start>=pendingDocxXmlB64.length())return "";
            int end=Math.min(start+DOCX_CHUNK_SIZE,pendingDocxXmlB64.length());
            return pendingDocxXmlB64.substring(start,end);
        }
        @JavascriptInterface public synchronized String getPendingWordChunk(int index){ return getPendingDocxChunk(index); }
        @JavascriptInterface public void openPendingWordForEdit(){
            final Uri u;
            final String mime;
            synchronized(this){ u=pendingWordUri; mime=pendingWordMime; }
            if(u==null){ runOnUiThread(()->Toast.makeText(MainActivity.this,"Bitte zuerst ein Word-Dokument auswählen.",Toast.LENGTH_LONG).show()); return; }
            runOnUiThread(()->{
                try{
                    Intent edit=new Intent(Intent.ACTION_EDIT);
                    edit.setDataAndType(u,(mime==null||mime.isEmpty())?"application/octet-stream":mime);
                    edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    edit.setClipData(ClipData.newUri(getContentResolver(),"TPS Word-Dokument",u));
                    startActivity(edit);
                }catch(Exception e){
                    try{
                        Intent view=new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(u,(mime==null||mime.isEmpty())?"application/octet-stream":mime);
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        startActivity(view);
                        Toast.makeText(MainActivity.this,"Kein direkter Bearbeitungsmodus gefunden. Dokument wurde geöffnet.",Toast.LENGTH_LONG).show();
                    }catch(Exception ex){
                        Toast.makeText(MainActivity.this,"Keine App zum Öffnen/Bearbeiten von Word-Dateien gefunden.",Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
        @JavascriptInterface public synchronized void clearPendingDocx(){
            pendingDocxXmlB64=null; pendingDocxName=null; pendingDocxFormat=null; pendingWordUri=null; pendingWordMime=null;
        }
        @JavascriptInterface public boolean hasNasFolder(){
            String u=getSharedPreferences("tps_native",MODE_PRIVATE).getString("preferred_nas_folder_uri","");
            return u!=null&&!u.isEmpty();
        }
        @JavascriptInterface public String getNasFolderName(){
            String n=getSharedPreferences("tps_native",MODE_PRIVATE).getString("preferred_nas_folder_name","");
            return n==null?"":n;
        }
        @JavascriptInterface public void chooseNasFolder(){
            runOnUiThread(()->{
                try{
                    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    if(Build.VERSION.SDK_INT>=26){
                        String saved=getSharedPreferences("tps_native",MODE_PRIVATE).getString("preferred_nas_folder_uri","");
                        if(saved!=null&&!saved.isEmpty())i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(saved));
                    }
                    startActivityForResult(i,NAS_FOLDER_REQUEST);
                }catch(Exception e){
                    Toast.makeText(MainActivity.this,"NAS-Ordnerauswahl konnte nicht geöffnet werden.",Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public boolean hasAudioFolder(){
            String u=getSharedPreferences("tps_native",MODE_PRIVATE).getString(PREF_AUDIO_FOLDER_URI,"");
            return u!=null&&!u.isEmpty();
        }
        @JavascriptInterface public String getAudioFolderName(){
            String n=getSharedPreferences("tps_native",MODE_PRIVATE).getString(PREF_AUDIO_FOLDER_NAME,"");
            return n==null?"":n;
        }
        @JavascriptInterface public void chooseAudioFolder(){
            runOnUiThread(()->{
                try{
                    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    if(Build.VERSION.SDK_INT>=26){
                        android.content.SharedPreferences p=getSharedPreferences("tps_native",MODE_PRIVATE);
                        String saved=p.getString(PREF_AUDIO_FOLDER_URI,"");
                        if(saved==null||saved.isEmpty())saved=p.getString("preferred_nas_folder_uri","");
                        if(saved!=null&&!saved.isEmpty())i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(saved));
                    }
                    startActivityForResult(i,AUDIO_FOLDER_REQUEST);
                }catch(Exception e){
                    Toast.makeText(MainActivity.this,"Musikordner konnte nicht ausgewählt werden.",Toast.LENGTH_LONG).show();
                }
            });
        }
        @JavascriptInterface public String listAudioFolder(String folderUri){
            return listAudioFolderInternal(folderUri);
        }
        @JavascriptInterface public boolean playNasAudio(String uri){
            return playNasAudioInternal(uri);
        }
        @JavascriptInterface public void stopNasAudio(){ runOnUiThread(()->stopNasAudioPlayer()); }
        @JavascriptInterface public synchronized String prepareNasAudio(String uri){ return prepareNasAudioInternal(uri); }
        @JavascriptInterface public synchronized int getPreparedNasAudioChunkCount(){
            if(pendingNasAudioB64==null||pendingNasAudioB64.isEmpty())return 0;
            return (pendingNasAudioB64.length()+NAS_AUDIO_CHUNK_SIZE-1)/NAS_AUDIO_CHUNK_SIZE;
        }
        @JavascriptInterface public synchronized String getPreparedNasAudioChunk(int index){
            if(pendingNasAudioB64==null||index<0)return "";
            int start=index*NAS_AUDIO_CHUNK_SIZE;
            if(start>=pendingNasAudioB64.length())return "";
            int end=Math.min(start+NAS_AUDIO_CHUNK_SIZE,pendingNasAudioB64.length());
            return pendingNasAudioB64.substring(start,end);
        }
        @JavascriptInterface public synchronized String getPreparedNasAudioName(){ return pendingNasAudioName==null?"":pendingNasAudioName; }
        @JavascriptInterface public synchronized String getPreparedNasAudioMime(){ return pendingNasAudioMime==null?"audio/mpeg":pendingNasAudioMime; }
        @JavascriptInterface public synchronized void clearPreparedNasAudio(){
            pendingNasAudioB64=null;pendingNasAudioName=null;pendingNasAudioMime=null;
        }

        @JavascriptInterface public void saveBase64File(String fileName,String mimeType,String base64Data){
            runOnUiThread(() -> {
                try{
                    byte[] bytes=Base64.decode(base64Data,Base64.DEFAULT);
                    if(bytes.length>25*1024*1024) throw new IOException("Datei ist größer als 25 MB");
                    pendingSaveBytes=bytes;
                    pendingSaveName=(fileName==null||fileName.trim().isEmpty())?"Workshop-Vorlage.docx":fileName.trim();
                    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType((mimeType==null||mimeType.trim().isEmpty())?"application/octet-stream":mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE,pendingSaveName);
                    applyLastNasLocation(intent);
                    startActivityForResult(intent,SAVE_DOCUMENT_REQUEST);
                }catch(Exception e){
                    pendingSaveBytes=null;
                    pendingSaveName=null;
                    Toast.makeText(MainActivity.this,"Datei konnte nicht vorbereitet werden: "+e.getMessage(),Toast.LENGTH_LONG).show();
                    if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-native-file-saved',{detail:{ok:false}}));",null);
                }
            });
        }
        @JavascriptInterface public void syncSession(String json){
            try{
                org.json.JSONObject o=new org.json.JSONObject(json);
                getSharedPreferences("tps_native",MODE_PRIVATE).edit()
                    .putString("access_token",o.optString("access_token",""))
                    .putString("refresh_token",o.optString("refresh_token",""))
                    .putString("user_id",o.optString("user_id",""))
                    .apply();
                scheduleNotificationWorker();
                FCMRegistration.registerSaved(MainActivity.this);
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

    private boolean prepareDocxForWeb(Uri u){
        if(u==null)return false;
        String name=displayName(u);
        try(InputStream raw=getContentResolver().openInputStream(u)){
            if(raw==null)throw new IOException("Datei konnte nicht geöffnet werden");
            String lower=(name==null?"":name).toLowerCase();
            String mime=getContentResolver().getType(u);
            if(mime==null||mime.trim().isEmpty()){
                mime=lower.endsWith(".docx")
                    ?"application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    :lower.endsWith(".doc")?"application/msword":"application/octet-stream";
            }
            byte[] payload;
            String format;
            if(lower.endsWith(".doc") && !lower.endsWith(".docx")){
                try(HWPFDocument doc=new HWPFDocument(raw); WordExtractor extractor=new WordExtractor(doc)){
                    String text=extractor.getText();
                    if(text==null||text.trim().isEmpty())throw new IOException("In der DOC-Datei wurde kein lesbarer Text gefunden");
                    payload=text.getBytes(StandardCharsets.UTF_8);
                    format="text";
                }
            }else{
                byte[] xmlBytes=null;
                try(ZipInputStream zin=new ZipInputStream(new BufferedInputStream(raw))){
                    ZipEntry entry;
                    while((entry=zin.getNextEntry())!=null){
                        if("word/document.xml".equals(entry.getName())){
                            ByteArrayOutputStream out=new ByteArrayOutputStream();
                            byte[] buf=new byte[8192]; int n;
                            while((n=zin.read(buf))>0){
                                out.write(buf,0,n);
                                if(out.size()>8*1024*1024)throw new IOException("Word-Dokumentinhalt ist zu groß");
                            }
                            xmlBytes=out.toByteArray();
                            break;
                        }
                    }
                }
                if(xmlBytes==null||xmlBytes.length==0)throw new IOException("Word-Inhalt wurde nicht gefunden");
                payload=xmlBytes;
                format="xml";
            }
            synchronized(this){
                pendingWordUri=u;
                pendingWordMime=mime;
                pendingDocxName=(name==null||name.trim().isEmpty())?"Workshop-Dokument":name;
                pendingDocxFormat=format;
                pendingDocxXmlB64=Base64.encodeToString(payload,Base64.NO_WRAP);
            }
            if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-android-docx-ready'));",null);
            return true;
        }catch(Exception e){
            synchronized(this){
                pendingWordUri=null; pendingWordMime=null; pendingDocxName=null; pendingDocxFormat=null; pendingDocxXmlB64=null;
            }
            Toast.makeText(this,"Word-Datei konnte nicht gelesen werden: "+e.getMessage(),Toast.LENGTH_LONG).show();
            return false;
        }
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
        if(requestCode==NAS_FOLDER_REQUEST){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
                Uri tree=data.getData();
                try{
                    int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(tree,flags);
                }catch(Exception ignored){}
                String name=nasFolderDisplayName(tree);
                getSharedPreferences("tps_native",MODE_PRIVATE).edit()
                    .putString("preferred_nas_folder_uri",tree.toString())
                    .putString("preferred_nas_folder_name",name)
                    .apply();
                Toast.makeText(this,"NAS-Ordner gespeichert: "+name,Toast.LENGTH_SHORT).show();
                if(web!=null){
                    String safe=name.replace("\\","\\\\").replace("'","\\'");
                    web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-nas-folder-changed',{detail:{name:'"+safe+"'}}));",null);
                }
            }
            return;
        }

        if(requestCode==AUDIO_FOLDER_REQUEST){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
                Uri tree=data.getData();
                try{
                    int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(tree,flags);
                }catch(Exception ignored){}
                String name=nasFolderDisplayName(tree);
                getSharedPreferences("tps_native",MODE_PRIVATE).edit()
                    .putString(PREF_AUDIO_FOLDER_URI,tree.toString())
                    .putString(PREF_AUDIO_FOLDER_NAME,name)
                    .apply();
                allowedAudioFolderUris.clear();
                allowedAudioFileUris.clear();
                Uri root=treeRootDocument(tree);
                if(root!=null)allowedAudioFolderUris.add(root.toString());
                Toast.makeText(this,"Musikordner gespeichert: "+name,Toast.LENGTH_SHORT).show();
                if(web!=null){
                    String safe=name.replace("\\","\\\\").replace("'","\\'");
                    web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-audio-folder-changed',{detail:{name:'"+safe+"'}}));",null);
                }
            }
            return;
        }
        if(requestCode==SAVE_DOCUMENT_REQUEST){
            boolean ok=false;
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null && pendingSaveBytes!=null){
                try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"w")){
                    if(out==null)throw new IOException("Speicherziel konnte nicht geöffnet werden");
                    out.write(pendingSaveBytes);
                    out.flush();
                    rememberNasLocation(data.getData(),data.getFlags());
                    ok=true;
                    Toast.makeText(this,"Word-Vorlage gespeichert",Toast.LENGTH_SHORT).show();
                }catch(Exception e){
                    Toast.makeText(this,"Speichern fehlgeschlagen: "+e.getMessage(),Toast.LENGTH_LONG).show();
                }
            }
            pendingSaveBytes=null;
            pendingSaveName=null;
            final boolean saved=ok;
            if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('tps-native-file-saved',{detail:{ok:"+(saved?"true":"false")+"}}));",null);
            return;
        }
        if(requestCode==FILE_CHOOSER_REQUEST && fileChooserCallback!=null){
            Uri[] result=null;
            if(resultCode==RESULT_OK){
                if(fileChooserIsMultiple && data!=null){
                    java.util.LinkedHashSet<Uri> picked=new java.util.LinkedHashSet<>();
                    try{
                        ClipData clip=data.getClipData();
                        if(clip!=null){
                            for(int i=0;i<clip.getItemCount();i++){
                                Uri u=clip.getItemAt(i).getUri();
                                if(u!=null)picked.add(u);
                            }
                        }
                    }catch(Exception ignored){}
                    if(data.getData()!=null)picked.add(data.getData());
                    if(!picked.isEmpty()){
                        result=picked.toArray(new Uri[picked.size()]);
                        rememberLastMediaLocation(result[0],data.getFlags());
                    }
                }
                if(result==null) result=WebChromeClient.FileChooserParams.parseResult(resultCode,data);
                if(result==null && data!=null && data.getData()!=null) result=new Uri[]{data.getData()};
                if(fileChooserIsMultiple && result!=null && result.length>0 && data!=null) rememberLastMediaLocation(result[0],data.getFlags());
                if(fileChooserIsDocx){
                    Uri selected=(data!=null&&data.getData()!=null)?data.getData():(result!=null&&result.length>0?result[0]:null);
                    if(selected!=null){
                        rememberNasLocation(selected,data==null?Intent.FLAG_GRANT_READ_URI_PERMISSION:data.getFlags());
                        prepareDocxForWeb(selected);
                    }
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback=null;
                    fileChooserIsDocx=false;
                    fileChooserIsMultiple=false;
                    return;
                }
            }
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback=null;
            fileChooserIsDocx=false;
            fileChooserIsMultiple=false;
        }
    }

    @Override protected void onDestroy(){
        stopNasAudioPlayer();
        super.onDestroy();
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
