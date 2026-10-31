package hu.ntamas.plumbob;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the process alive while an animation runs with the screen off. It holds nothing itself:
 * the activity's handler and GATT callbacks keep working as long as the process is not killed.
 */
public class KeepAliveService extends Service {
    static final String CHANNEL = "plumbob";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Plumbob animáció", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(getResources().getIdentifier("plumbob", "drawable", getPackageName()))
            .setContentTitle("Plumbob LED")
            .setContentText("Animáció megy a lámpákon")
            .setContentIntent(open)
            .setOngoing(true)
            .build();
        // The microphone type is needed for "Zene" to keep hearing with the screen off; it is only legal when
        // RECORD_AUDIO is granted (the caller sets "mic" only then) and when started from the foreground.
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (Build.VERSION.SDK_INT >= 30 && intent != null && intent.getBooleanExtra("mic", false)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, type);
        else startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
