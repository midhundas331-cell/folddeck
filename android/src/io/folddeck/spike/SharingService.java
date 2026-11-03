package io.folddeck.spike;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the phone microphone/camera usable by the laptop while FoldDeck is hidden or
 * locked. Android only lets a backgrounded app record from a microphone|camera
 * foreground service that was started while it was visible (PhoneSharing does that
 * when a stream attaches). It does no work itself; the capture lives in PhoneSharing.
 */
public final class SharingService extends Service {
    static final String TYPES = "types";
    private static final String CHANNEL = "phone_sharing";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int types = intent == null ? 0 : intent.getIntExtra(TYPES, 0);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone sharing",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Phone sharing")
                .setContentText("Your laptop can use this phone's inputs while connected")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            startForeground(1, notification, types);
        } catch (RuntimeException e) {
            // Started without a visible activity or a granted permission: no background inputs.
            Log.w("FoldDeck/Share", "foreground service refused", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
