package com.example.camviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

public class Notifier {

    public static final String ALERT_CHANNEL = "motion_alerts_v2";
    public static final String SERVICE_CHANNEL = "monitor_service";

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel alert = new NotificationChannel(
                ALERT_CHANNEL, "تنبيه الحركة", NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);

        NotificationChannel svc = new NotificationChannel(
                SERVICE_CHANNEL, "المراقبة بالخلفية", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(svc);
    }

    private static PendingIntent openApp(Context ctx, int code) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, code, i, flags);
    }

    public static void alert(Context ctx, int channel) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, ALERT_CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
                Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                b.setSound(sound);
                b.setPriority(Notification.PRIORITY_HIGH);
                b.setDefaults(Notification.DEFAULT_VIBRATE);
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("تنبيه حركة")
                    .setContentText("تم رصد حركة في الكاميرا " + channel)
                    .setContentIntent(openApp(ctx, 200 + channel))
                    .setWhen(System.currentTimeMillis())
                    .setAutoCancel(true);

            nm.notify(100 + channel, b.build());
        } catch (Exception ignored) {
        }
    }

    public static Notification serviceNotification(Context ctx) {
        return serviceNotification(ctx, "يتم رصد الحركة في الخلفية");
    }

    public static Notification serviceNotification(Context ctx, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, SERVICE_CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("مراقبة الكاميرات تعمل")
                .setContentText(text)
                .setContentIntent(openApp(ctx, 1))
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        return b.build();
    }
}
