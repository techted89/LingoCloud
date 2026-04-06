package com.LingoCloudTranslate.lingocloud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * TranslationServer - Foreground Service for LingoCloud
 * Android 15 (SDK 35) Compatible
 *
 * This service runs in the background to:
 * - Keep the translation module alive during app switches
 * - Provide a local HTTP endpoint for inter-process translation requests
 * - Manage translation caching and rate limiting
 * - Display persistent notification for FGS requirements
 */
public class TranslationServer extends Service {
    private static final String TAG = "LingoCloud";
    private static final String CHANNEL_ID = "lingocloud_service";
    private static final int NOTIFICATION_ID = 18181;

    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;

    public class LocalBinder extends Binder {
        TranslationServer getService() {
            return TranslationServer.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TranslationServer created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "TranslationServer started");

        try {
            // Start as foreground service (required for Android 15)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            isRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Foreground Service: " + e.getMessage(), e);
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
            // Android 12+ throws ForegroundServiceStartNotAllowedException
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "TranslationServer destroyed");
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Create notification channel for Android O+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "LingoCloud Translation Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background translation service for LingoCloud");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Build persistent notification for foreground service
     */
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LingoCloud Translator")
            .setContentText("UI translation service is active")
            .setSmallIcon(R.drawable.ic_menu_translate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

}
