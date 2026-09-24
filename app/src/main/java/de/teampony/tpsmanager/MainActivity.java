package de.teampony.tpsmanager;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://phenomenal-sfogliatella-09cbcb.netlify.app/";
    private WebView web;
    private Uri pendingPdf;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this); setContentView(web);
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest r){ runOnUiThread(() -> r.grant(r.getResources())); }
        });
        web.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView v,String u){ deliverPdf(); }});
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA},10);
        acceptIntent(getIntent());
        web.loadUrl(APP_URL);
    }

    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); setIntent(i); acceptIntent(i); deliverPdf(); }

    private void acceptIntent(Intent i){
        if(i==null)return;
        if(Intent.ACTION_VIEW.equals(i.getAction()) && i.getData()!=null) pendingPdf=i.getData();
        if(Intent.ACTION_SEND.equals(i.getAction())) pendingPdf=i.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private String displayName(Uri u){
        String n="Beleg.pdf";
        try(var c=getContentResolver().query(u,null,null,null,null)){ if(c!=null&&c.moveToFirst()){ int x=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(x>=0)n=c.getString(x); }}catch(Exception ignored){}
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

    @Override public void onBackPressed(){ if(web.canGoBack())web.goBack(); else super.onBackPressed(); }
}
