package de.teampony.tpsmanager;

import android.app.*;
import android.content.*;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class TPSFirebaseMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token){
        super.onNewToken(token);
        FCMRegistration.saveAndRegister(this,token);
    }

    @Override public void onMessageReceived(RemoteMessage message){
        super.onMessageReceived(message);
        String title=message.getData().get("title");
        String body=message.getData().get("body");
        String countText=message.getData().get("badge");
        if(title==null || title.isEmpty()) title="TPS Manager";
        if(body==null || body.isEmpty()) body="Neue Nachricht";
        int count=1;
        try{ count=Math.max(1,Integer.parseInt(countText)); }catch(Exception ignored){}
        showNotification(title,body,count);
    }

    private void showNotification(String title,String body,int count){
        NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("tps_booking_updates","TPS Manager Hinweise",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Neue Buchungen und Hinweise im TPS Manager");
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        Intent launch=new Intent(this,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,4201,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb=Build.VERSION.SDK_INT>=26
            ? new Notification.Builder(this,"tps_booking_updates")
            : new Notification.Builder(this);
        nb.setContentTitle(title)
          .setContentText(body)
          .setStyle(new Notification.BigTextStyle().bigText(body))
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentIntent(pi)
          .setAutoCancel(true)
          .setNumber(count);

        nm.notify(4201,nb.build());
    }
}
