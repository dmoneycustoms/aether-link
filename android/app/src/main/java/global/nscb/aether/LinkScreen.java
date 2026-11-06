package global.nscb.aether;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Locale;

/** Dense Android Auto pane — speed, path, integrity, GPS, optional OBD. */
public final class LinkScreen extends Screen {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 800);
        }
    };

    public LinkScreen(@NonNull CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                // Auto-start probe when the car host opens us
                try {
                    LinkProbe.start(carContext);
                    carContext.startForegroundService(
                            new android.content.Intent(carContext, ProbeService.class));
                } catch (Exception ignored) {
                    try {
                        LinkProbe.start(carContext);
                    } catch (Exception ignored2) {
                    }
                }
                handler.post(tick);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                handler.removeCallbacks(tick);
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        LinkProbe.Snap snap = LinkProbe.copy();

        String speed = snap.speedMps == null
                ? "—"
                : String.format(Locale.US, "%.0f mph", snap.speedMps * 2.2369362920544);
        String hdg = snap.bearingDeg == null ? "HDG —" : String.format(Locale.US, "HDG %.0f°", snap.bearingDeg);
        String acc = !snap.locationGranted
                ? "GPS permission needed on phone"
                : !snap.hasFix
                    ? (snap.running ? "Waiting for fix…" : "Probe idle")
                    : snap.accuracyM == null
                        ? "GPS locked"
                        : String.format(Locale.US, "GPS ±%.0f m", snap.accuracyM);

        String path = snap.satelliteTransport
                ? "SATELLITE"
                : (snap.transport == null ? "—" : snap.transport.toUpperCase(Locale.US));
        String rtt = snap.rttMs == null
                ? (snap.rttError == null || snap.rttError.isEmpty() ? "RTT —" : "RTT fail")
                : "RTT " + snap.rttMs + " ms";
        String down = snap.downMbps == null ? "" : String.format(Locale.US, "  ↓%.1f", snap.downMbps);

        String level = snap.integrityLevel == null ? "clean" : snap.integrityLevel;
        int trustPct = (int) Math.round(snap.trust * 100.0);
        String integ = String.format(Locale.US, "TRUST %d%% · %s", trustPct, level.toUpperCase(Locale.US));
        String reason = snap.integrityReason == null || snap.integrityReason.isEmpty()
                ? "dual-path ready"
                : snap.integrityReason;

        String fixLine = snap.hasFix
                ? String.format(Locale.US, "%.5f, %.5f", snap.lat, snap.lon)
                : "no fix yet";

        String sat = (snap.satelliteHardware ? "sat HW yes" : "sat HW no")
                + (snap.satelliteTransport ? " · bearer SAT" : " · bearer terrestrial");

        Pane.Builder pane = new Pane.Builder()
                .addRow(new Row.Builder()
                        .setTitle(speed + "  ·  " + (snap.running ? "LIVE" : "IDLE"))
                        .addText(hdg + "  ·  " + acc)
                        .build())
                .addRow(new Row.Builder()
                        .setTitle("PATH  " + path)
                        .addText(rtt + down)
                        .build())
                .addRow(new Row.Builder()
                        .setTitle("INTEGRITY  " + integ)
                        .addText(reason)
                        .build())
                .addRow(new Row.Builder()
                        .setTitle("FIX")
                        .addText(fixLine)
                        .build())
                .addRow(new Row.Builder()
                        .setTitle("RADIO")
                        .addText(sat)
                        .build());

        if (!snap.running) {
            pane.addRow(new Row.Builder()
                    .setTitle("ACTION")
                    .addText("Open AETHER on phone → Start measuring if this stays idle")
                    .build());
        }

        return new PaneTemplate.Builder(pane.build())
                .setTitle("AETHER LINK")
                .setHeaderAction(Action.APP_ICON)
                .build();
    }
}
