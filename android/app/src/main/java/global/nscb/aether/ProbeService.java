package global.nscb.aether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

public final class ProbeService extends Service {
    private final LinkProbe.Listener listener = snap -> {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, build(text(snap)));
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("link", "Uplink", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification note = build("Measuring the uplink");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, note);
            }
        } catch (RuntimeException e) {
            LinkProbe.note("Could not hold the measure service: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        LinkProbe.start(this);
        LinkProbe.listen(listener);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        LinkProbe.unlisten(listener);
        LinkProbe.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build(String text) {
        return new Notification.Builder(this, "link")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("AETHER")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private static String text(LinkProbe.Snap snap) {
        String rtt = snap.rttMs == null ? "RTT —" : "RTT " + snap.rttMs + " ms";
        String speed = snap.speedMps == null ? "speed —" : Math.round(snap.speedMps * 2.2369362920544) + " mph";
        return snap.transport + " · " + rtt + " · " + speed;
    }
}
