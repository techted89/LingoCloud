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
    private static final int LOCAL_PORT = 18181;

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
            Log.e(TAG, "Failed to start Foreground Service: " + e.getMessage());
            // Android 12+ throws ForegroundServiceStartNotAllowedException
        }

        // TODO: Start local HTTP server if implementing client-server architecture
        // startLocalServer();

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
        // TODO: Stop local HTTP server
        // stopLocalServer();
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

    /**
     * Update notification text
     */
    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LingoCloud Translator")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_menu_translate)
                .setOngoing(true)
                .setSilent(true)
                .build();
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    /*
     * Local HTTP Server Implementation (Optional Extension)
     *
     * Uncomment and implement if you want a true client-server architecture
     * where hooked apps communicate via HTTP to localhost:18181
     *
     * private NanoHTTPD server;
     *
     * private void startLocalServer() {
     *     try {
     *         server = new TranslationHttpServer(LOCAL_PORT);
     *         server.start();
     *         Log.d(TAG, "Local translation server started on port " + LOCAL_PORT);
     *     } catch (IOException e) {
     *         Log.e(TAG, "Failed to start local server", e);
     *     }
     * }
     *
     * private void stopLocalServer() {
     *     if (server != null) {
     *         server.stop();
     *         Log.d(TAG, "Local translation server stopped");
     *     }
     * }
     *
     * private static class TranslationHttpServer extends NanoHTTPD {
     *     TranslationHttpServer(int port) {
     *         super(port);
     *     }
     *
     *     @Override
     *     public Response serve(IHTTPSession session) {
     *         String uri = session.getUri();
     *         if ("/translate".equals(uri)) {
     *             Map<String, String> params = session.getParms();
     *             String text = params.get("q");
     *             // Process translation...
     *             return newFixedLengthResponse(Response.Status.OK,
     *                 "application/json", "{\"result\":\"translated\"}");
     *         }
     *         return newFixedLengthResponse(Response.Status.NOT_FOUND,
     *             "text/plain", "Not Found");
     *     }
     * }
     */
}
