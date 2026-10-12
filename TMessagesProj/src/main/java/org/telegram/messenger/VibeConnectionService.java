package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * Vibgram built-in notifications (no extra app): a quiet foreground service keeps
 * the Telegram connection open so new messages arrive and notify right away.
 * Used when no ntfy / UnifiedPush app is installed.
 */
public class VibeConnectionService extends Service {

    private static final String CHANNEL_ID = "vibe_connection";
    private static final int NOTIFICATION_ID = 0x7651;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, VibeConnectionService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, VibeConnectionService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN);
                    channel.setDescription("Keeps Vibgram connected so messages arrive instantly. You can hide this.");
                    channel.setShowBadge(false);
                    channel.enableVibration(false);
                    channel.setSound(null, null);
                    nm.createNotificationChannel(channel);
                }
            }
            Intent open = new Intent(this, LaunchActivity.class);
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("Vibgram is connected")
                    .setContentText("New messages arrive instantly")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
