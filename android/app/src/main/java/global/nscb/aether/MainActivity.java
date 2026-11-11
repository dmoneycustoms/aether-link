package global.nscb.aether;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.opengl.GLSurfaceView;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.BluetoothDevice;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final int PERMS = 7;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView speed, fix, path, rtt, sat, detail, probeState, liveBadge, integrity, trustLabel, alertBanner;
    private View speedPanel, header;
    private GlSpeedCluster speedGauge;
    private IntegrityRingView integrityRing;
    private PathArcView pathArc;
    private RadarView radar;
    private ProgressBar trustBar;
    private Button start, throughput;
    private TextView tabDrive, tabBus;
    private ScrollView pageDrive, pageBus;
    private TextView obdStatus, obdVin, obdRpm, obdKph, obdCoolant, obdLoad, obdThrottle, obdVolts, obdIat, obdMap, obdDtcs, obdLog, obdFull;
    private HhrFaultGlView hhrFaultGl;
    private boolean wantStart;
    private float displayedSpeed;
    private ValueAnimator speedAnim;
    private ObjectAnimator livePulse;
    private NativeBleObd obd;

    private final Runnable paint = new Runnable() {
        @Override
        public void run() {
            render(LinkProbe.copy());
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable inflateErr) {
            TextView err = new TextView(this);
            err.setText("Layout error: " + inflateErr.getMessage());
            err.setTextColor(0xFFF0A202);
            err.setPadding(40, 80, 40, 40);
            setContentView(err);
            return;
        }
        // CRASH_GUARD

        speed = findViewById(R.id.speed);
        speedPanel = findViewById(R.id.speed_panel);
        header = findViewById(R.id.header);
        speedGauge = findViewById(R.id.speed_gauge);
        integrityRing = findViewById(R.id.integrity_ring);
        pathArc = findViewById(R.id.path_arc);
        radar = findViewById(R.id.radar);
        fix = findViewById(R.id.fix);
        path = findViewById(R.id.path);
        rtt = findViewById(R.id.rtt);
        sat = findViewById(R.id.sat);
        detail = findViewById(R.id.detail);
        alertBanner = findViewById(R.id.alert_banner);
        probeState = findViewById(R.id.probe_state);
        liveBadge = findViewById(R.id.live_badge);
        integrity = findViewById(R.id.integrity);
        trustLabel = findViewById(R.id.trust_label);
        trustBar = findViewById(R.id.trust_bar);
        start = findViewById(R.id.start);
        throughput = findViewById(R.id.throughput);
        Button hotspot = findViewById(R.id.hotspot);
        Button auto = findViewById(R.id.auto);
        Button maps = findViewById(R.id.maps);
        tabDrive = findViewById(R.id.tab_drive);
        tabBus = findViewById(R.id.tab_bus);
        pageDrive = findViewById(R.id.page_drive);
        pageBus = findViewById(R.id.page_bus);
        obdStatus = findViewById(R.id.obd_status);
        obdVin = findViewById(R.id.obd_vin);
        obdRpm = findViewById(R.id.obd_rpm);
        obdKph = findViewById(R.id.obd_kph);
        obdCoolant = findViewById(R.id.obd_coolant);
        obdLoad = findViewById(R.id.obd_load);
        obdThrottle = findViewById(R.id.obd_throttle);
        obdVolts = findViewById(R.id.obd_volts);
        obdIat = findViewById(R.id.obd_iat);
        obdMap = findViewById(R.id.obd_map);
        obdDtcs = findViewById(R.id.obd_dtcs);
        obdLog = findViewById(R.id.obd_log);
        obdFull = findViewById(R.id.obd_full);
        try {
            android.widget.FrameLayout host = findViewById(R.id.hhr_fault_host);
            if (host != null) {
                hhrFaultGl = new HhrFaultGlView(this);
                host.addView(hhrFaultGl, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                hhrFaultGl.setPickListener((partId, title, faults) -> showPartFaultPopup(partId, title, faults));
            }
        } catch (Throwable glErr) {
            hhrFaultGl = null;
            try {
                android.widget.FrameLayout host = findViewById(R.id.hhr_fault_host);
                if (host != null) {
                    android.widget.TextView tv = new android.widget.TextView(this);
                    tv.setText("Fault map unavailable: " + glErr.getClass().getSimpleName());
                    tv.setTextColor(0xFFF0A202);
                    tv.setPadding(24, 24, 24, 24);
                    host.addView(tv);
                }
            } catch (Throwable ignored) {}
        }
        Button obdConnect = findViewById(R.id.obd_connect);
        Button obdDisconnect = findViewById(R.id.obd_disconnect);

        start.setOnClickListener(v -> {
            wantStart = true;
            if (missingPermissions()) requestNeeded();
            else begin();
        });
        throughput.setOnClickListener(v -> LinkProbe.measureThroughput());
        hotspot.setOnClickListener(v -> openHotspot());
        auto.setOnClickListener(v -> openAuto());
        maps.setOnClickListener(v -> openMaps());
        tabDrive.setOnClickListener(v -> showTab(true));
        tabBus.setOnClickListener(v -> showTab(false));

        obd = new NativeBleObd(this);
        obd.setListener(new NativeBleObd.Listener() {
            @Override public void onStatus(String line) { obdStatus.setText(line); }
            @Override public void onLog(String line) { obdLog.setText(line); }
            @Override public void onReading(NativeBleObd.Reading r) { paintObd(r); }
            @Override public void onScanFinished(java.util.List<String> labels, java.util.List<BluetoothDevice> devices) {
                if (labels == null || labels.isEmpty()) return;
                if (labels.size() == 1) return; // auto-connect already attempted for preferred
                String[] items = labels.toArray(new String[0]);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("BLE devices — pick CarPal / OBD")
                        .setItems(items, (d, which) -> {
                            if (which >= 0 && which < devices.size()) {
                                obd.connectDevice(devices.get(which), labels.get(which));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        obdConnect.setOnClickListener(v -> {
            if (missingBlePermissions()) requestBle();
            else obd.startScanAndConnect();
        });
        obdDisconnect.setOnClickListener(v -> obd.disconnect());

        livePulse = ObjectAnimator.ofFloat(liveBadge, "alpha", 1f, 0.35f, 1f);
        livePulse.setDuration(1400);
        livePulse.setRepeatCount(ValueAnimator.INFINITE);

        render(LinkProbe.copy());
        showTab(true);
        runEntrance();
    }

    private void runEntrance() {
        View root = findViewById(android.R.id.content);
        if (header != null) {
            header.setTranslationY(-40f);
            header.setAlpha(0f);
            header.animate().translationY(0f).alpha(1f).setDuration(420).setStartDelay(40).start();
        }
        if (speedPanel != null) {
            speedPanel.setScaleX(0.96f);
            speedPanel.setScaleY(0.96f);
            speedPanel.setAlpha(0f);
            speedPanel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480).setStartDelay(120).start();
        }
        if (start != null) {
            start.setTranslationY(24f);
            start.setAlpha(0f);
            start.animate().translationY(0f).alpha(1f).setDuration(400).setStartDelay(260).start();
        }
    }

    private void showTab(boolean drive) {
        pageDrive.setVisibility(drive ? View.VISIBLE : View.GONE);
        pageBus.setVisibility(drive ? View.GONE : View.VISIBLE);
        try {
            if (hhrFaultGl != null) {
                if (drive) hhrFaultGl.stopGl();
                else hhrFaultGl.startGl();
            }
        } catch (Throwable ignored) {}
        tabDrive.setTextColor(drive ? 0xFFF0A202 : 0xFF8F8774);
        tabBus.setTextColor(drive ? 0xFF8F8774 : 0xFFF0A202);
        pageDrive.setAlpha(0f);
        pageBus.setAlpha(0f);
        View shown = drive ? pageDrive : pageBus;
        shown.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private boolean obdLive;


    private void showPartFaultPopup(String partId, String title, java.util.List<HhrFaultGlView.Fault> faults) {
        StringBuilder body = new StringBuilder();
        body.append("PART\n").append(title).append("\n\n");
        body.append("LOCATION\n").append(partLocation(partId)).append("\n\n");
        if (faults == null || faults.isEmpty()) {
            body.append("FAULTS\nNone on this part.\n");
        } else {
            body.append("FAULTS (").append(faults.size()).append(")\n");
            for (HhrFaultGlView.Fault f : faults) {
                body.append("• ").append(f.code).append("\n  ").append(f.detail).append("\n\n");
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("ZOOM · " + title);
        b.setMessage(body.toString());
        b.setPositiveButton("Close", null);
        if (faults != null && !faults.isEmpty()) {
            b.setNeutralButton("Clear DTCs", (d, w) -> {
                new AlertDialog.Builder(this)
                        .setTitle("Clear all DTCs?")
                        .setMessage("Mode 04 clears stored codes and may turn off MIL. Ignition ON required.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (d2, w2) -> {
                            if (obd != null) obd.clearDtcs();
                        })
                        .show();
            });
        }
        b.show();
        if (obdStatus != null) {
            int n = faults == null ? 0 : faults.size();
            obdStatus.setText("MAP · " + title + " · " + n + " fault(s)");
        }
    }

    private static String partLocation(String id) {
        if (id == null) return "—";
        switch (id) {
            case "cyl1": return "Cylinder 1 — front of engine (transverse)";
            case "cyl2": return "Cylinder 2";
            case "cyl3": return "Cylinder 3";
            case "cyl4": return "Cylinder 4";
            case "eng": return "Engine bay center — powertrain";
            case "fuel": return "Fuel rail on intake manifold";
            case "ign": return "Coil-on-plug packs on valve cover";
            case "o2b1s1": return "Pre-cat O2 — exhaust manifold side";
            case "o2b1s2": return "Post-cat O2 — after converter";
            case "cat": return "Underbody catalytic converter";
            case "evap": return "EVAP canister / purge solenoid";
            case "trans": return "Transaxle under powertrain";
            case "abs": return "ABS module — typically left-front bay / frame";
            case "ecm": return "PCM in engine compartment";
            case "maf": return "Intake tube after air box";
            case "tps": return "Electronic throttle body";
            case "map": return "Intake manifold pressure sensor";
            case "body": return "BCM — interior body control";
            case "net": return "GMLAN high-speed network";
            case "batt": return "Battery / charging system";
            case "rad": return "Front radiator stack";
            case "iat": return "Intake air temperature sensor";
            case "ect": return "Engine coolant temperature sensor";
            case "egr": return "EGR valve on intake/exhaust path";
            case "vss": return "Vehicle speed sensor / trans output";
            case "abs_lf": return "Left-front wheel speed sensor";
            case "abs_rf": return "Right-front wheel speed sensor";
            case "abs_lr": return "Left-rear wheel speed sensor";
            case "abs_rr": return "Right-rear wheel speed sensor";
            default: return id;
        }
    }

    private void paintObd(NativeBleObd.Reading r) {
        obdLive = true;
        if (r.kph != null && Double.isFinite(r.kph)) {
            LinkProbe.setObdSpeedMps(r.kph / 3.6);
            SessionLogger.line("OBD", String.format(java.util.Locale.US, "kph=%.0f rpm=%s",
                    r.kph, r.rpm == null ? "-" : String.format(java.util.Locale.US, "%.0f", r.rpm)));
        }
        if (radar != null) {
            LinkProbe.Snap s = LinkProbe.copy();
            radar.setState(s.hasFix, s.rttMs != null, true, s.running);
        }
        if (r.vin != null) obdVin.setText("VIN " + r.vin);
        try { if (obdFull != null && r != null) obdFull.setText(r.fullReport()); } catch (Throwable ignored) {}
        if (hhrFaultGl != null) {
            java.util.ArrayList<String> all = new java.util.ArrayList<>();
            if (r.dtcs != null) all.addAll(r.dtcs);
            if (r.pendingDtcs != null) all.addAll(r.pendingDtcs);
            hhrFaultGl.setFaults(all);
        }
        if (r.dtcs != null && !r.dtcs.isEmpty() && obdDtcs != null)
            obdDtcs.setText("DTCs " + android.text.TextUtils.join(", ", r.dtcs));
        cell(obdRpm, "RPM", r.rpm, 0);
        cell(obdKph, "SPEED", r.kph, 0, " km/h");
        cell(obdCoolant, "COOLANT", r.coolantC, 0, " °C");
        cell(obdLoad, "LOAD", r.loadPct, 0, " %");
        cell(obdThrottle, "THROTTLE", r.throttlePct, 0, " %");
        cell(obdVolts, "MODULE V", r.volts, 2, " V");
        cell(obdIat, "IAT", r.iatC, 0, " °C");
        cell(obdMap, "MAP", r.mapKpa, 0, " kPa");
        if (r.dtcs.isEmpty()) obdDtcs.setText("DTCs — none / not read");
        else obdDtcs.setText("DTCs " + r.dtcs.toString());
    }

    private void cell(TextView tv, String label, Double v, int digits) {
        cell(tv, label, v, digits, "");
    }

    private void cell(TextView tv, String label, Double v, int digits, String unit) {
        if (v == null || !Double.isFinite(v)) {
            tv.setText(label + "\n—");
            return;
        }
        String num = digits == 0 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%." + digits + "f", v);
        tv.setText(label + "\n" + num + unit);
    }

    @Override protected void onResume() {
        super.onResume();
        if (speedGauge != null) {
            speedGauge.onResume();
            speedGauge.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        }
        // Only run probe while UI is visible — not all day in background
        LinkProbe.start(this);
        handler.post(paint);
    }

    @Override protected void onPause() {
        try { if (hhrFaultGl != null) hhrFaultGl.onPause(); } catch (Throwable ignored) {}
        handler.removeCallbacks(paint);
        if (livePulse != null) livePulse.cancel();
        if (speedGauge != null) {
            speedGauge.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            speedGauge.onPause();
        }
        // Kill GPS + ticker work; stop sticky measure service
        LinkProbe.stop();
        try {
            stopService(new Intent(this, ProbeService.class));
        } catch (Exception ignored) {}
        if (obd != null) {
            // leave OBD only if user wants — disconnect to save BT radio
            // obd.disconnect(); // keep optional; battery: disconnect
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (obd != null) obd.disconnect();
        super.onDestroy();
    }

    private boolean missingPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return true;
        return Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private boolean missingBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeeded() {
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMS);
    }

    private void requestBle() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMS + 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PERMS && wantStart && !missingPermissions()) begin();
        if (code == PERMS + 1 && !missingBlePermissions() && obd != null) obd.startScanAndConnect();
    }

    private void begin() {
        LinkProbe.start(this);
        try {
            Intent fg = new Intent(this, ProbeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(fg);
            else startService(fg);
        } catch (Exception e) {
            detail.setText("Probe service: " + e.getMessage());
        }
        start.setText("Measuring");
    }

    private void render(LinkProbe.Snap snap) {
        float target = 0f;
        if (snap.speedMps != null && Double.isFinite(snap.speedMps)) {
            target = (float) (snap.speedMps * 2.2369362920544);
        }
        animateSpeed(target);

        if (!snap.locationGranted) fix.setText("GPS permission not granted");
        else if (!snap.hasFix) fix.setText(snap.running ? "Waiting for a fix…" : "GPS off — tap Start measuring");
        else {
            String acc = snap.accuracyM == null ? "±— m" : String.format(Locale.US, "±%.0f m", snap.accuracyM);
            String hdg = snap.bearingDeg == null ? "HDG —" : String.format(Locale.US, "HDG %.0f°", snap.bearingDeg);
            fix.setText(String.format(Locale.US, "%s  %s  %.5f, %.5f", hdg, acc, snap.lat, snap.lon));
        }

        String battery = snap.batteryPct < 0 ? "" : " · " + snap.batteryPct + "%";
        path.setText((snap.transport == null ? "—" : snap.transport.toUpperCase(Locale.US)) + battery);
        if (snap.measuring) rtt.setText("measuring…");
        else if (snap.rttMs != null) {
            String down = snap.downMbps == null ? "" : String.format(Locale.US, " · ↓%.1f", snap.downMbps);
            rtt.setText("RTT " + snap.rttMs + " ms" + down);
        } else if (!snap.rttError.isEmpty()) rtt.setText("RTT fail");
        else rtt.setText(snap.running ? "RTT …" : "RTT —");

        String level = snap.integrityLevel == null ? "clean" : snap.integrityLevel;
        int trustPct = (int) Math.round(snap.trust * 100.0);
        if (trustBar != null) {
            ObjectAnimator.ofInt(trustBar, "progress", trustBar.getProgress(), Math.max(0, Math.min(100, trustPct))).setDuration(400).start();
            int tint = snap.quarantine || "degraded".equals(level) || "quarantine".equals(level)
                    ? 0xFFE0452A : "watch".equals(level) ? 0xFFE8B84A : 0xFF3ECF8E;
            trustBar.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));
        }
        if (trustLabel != null) trustLabel.setText(String.format(Locale.US, "TRUST %d%%", trustPct));

        String live = snap.running ? "PROBE LIVE" : "PROBE IDLE";
        probeState.setText(live + " · " + level.toUpperCase(Locale.US));
        liveBadge.setText(snap.running ? "LIVE" : "IDLE");
        liveBadge.setTextColor(snap.running ? 0xFFF0A202 : 0xFF8F8774);
        if (snap.running) {
            if (!livePulse.isRunning()) livePulse.start();
            if (speedPanel != null) speedPanel.setBackgroundResource(R.drawable.hud_panel_live);
        } else {
            livePulse.cancel();
            liveBadge.setAlpha(1f);
            if (speedPanel != null) speedPanel.setBackgroundResource(R.drawable.hud_panel);
        }

        String why = snap.integrityReason == null || snap.integrityReason == null || snap.integrityReason.isEmpty()
                ? "chain live · dual-path ready"
                : snap.integrityReason;
        integrity.setText("INTEGRITY · " + level.toUpperCase(Locale.US) + "\n" + why
                + "\n" + OnnxIntegrity.status());
        integrity.setTextColor(snap.quarantine || "degraded".equals(level) || "quarantine".equals(level)
                ? 0xFFE0452A : "watch".equals(level) ? 0xFFE8B84A : 0xFF3ECF8E);
        if (integrityRing != null) integrityRing.setTrust((float) snap.trust, level);
        if (pathArc != null) pathArc.setPath(snap.transport, snap.rttMs, !"offline".equalsIgnoreCase(snap.transport));
        if (radar != null) radar.setState(snap.hasFix, snap.rttMs != null || (snap.transport != null && !"offline".equalsIgnoreCase(snap.transport)), obdLive, snap.running);


        String hardware = snap.satelliteHardware ? "sat HW: yes" : "sat HW: no";
        String bearer = snap.satelliteTransport ? "bearer: SATELLITE" : "bearer: not satellite";
        sat.setText(hardware + " · " + bearer + " · " + OnnxIntegrity.status());

        String err = snap.detail.isEmpty() ? snap.downError : snap.detail;
        detail.setText(err == null ? "" : err);

        // In-app alerts (AETHER sensors — not Google incident feed)
        if (alertBanner != null) {
            java.util.ArrayList<String> alerts = new java.util.ArrayList<>();
            if (snap.quarantine || "quarantine".equals(level) || "degraded".equals(level)) {
                alerts.add("INTEGRITY " + level.toUpperCase(Locale.US)
                        + (snap.integrityReason == null || snap.integrityReason.isEmpty() ? "" : " — " + snap.integrityReason));
            }
            if (snap.rttMs != null && snap.rttMs > 250) {
                alerts.add("HIGH RTT " + snap.rttMs + " ms");
            }
            if ("offline".equalsIgnoreCase(snap.transport) || "UNKNOWN".equalsIgnoreCase(snap.transport)) {
                alerts.add("PATH OFFLINE");
            }
            if (snap.accuracyM != null && snap.accuracyM > 50) {
                alerts.add(String.format(Locale.US, "GPS ACCURACY WEAK ±%.0f m", snap.accuracyM));
            }
            if (alerts.isEmpty()) {
                alertBanner.setVisibility(android.view.View.GONE);
                alertBanner.setText("");
            } else {
                alertBanner.setVisibility(android.view.View.VISIBLE);
                StringBuilder sb = new StringBuilder("ALERT");
                for (String a : alerts) sb.append("\n· ").append(a);
                alertBanner.setText(sb.toString());
                boolean hard = snap.quarantine || "quarantine".equals(level);
                alertBanner.setTextColor(hard ? 0xFFE0452A : 0xFFE8B84A);
            }
        }

        throughput.setEnabled(!snap.measuring);
        start.setText(snap.running ? "Measuring" : "Start measuring");
    }

    private void animateSpeed(float target) {
        displayedSpeed = target;
        LinkProbe.Snap s = LinkProbe.copy();
        if (speedGauge != null) speedGauge.setSpeedMph(target, s.running);
        if (speed != null) {
            if (Math.abs(target) < 0.05f) speed.setText("0");
            else if (target < 10f) speed.setText(String.format(Locale.US, "%.1f", target));
            else speed.setText(String.format(Locale.US, "%.0f", target));
        }
        TextView unit = findViewById(R.id.speed_unit);
        if (unit != null) unit.setText(s.running ? "MPH · LIVE" : "MPH");
    }


    private void openMaps() {
        LinkProbe.Snap snap = LinkProbe.copy();
        Intent intent;
        if (snap.hasFix && !Double.isNaN(snap.lat) && !Double.isNaN(snap.lon)) {
            // Maps at fix with traffic layer (5m1!1e1)
            String uri = String.format(java.util.Locale.US,
                    "https://www.google.com/maps/@%.6f,%.6f,15z/data=!5m1!1e1",
                    snap.lat, snap.lon);
            intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
            intent.setPackage("com.google.android.apps.maps");
        } else {
            intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps"));
            intent.setPackage("com.google.android.apps.maps");
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Fallback without package lock
            try {
                Intent any = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(snap.hasFix
                                ? String.format(java.util.Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f",
                                snap.lat, snap.lon, snap.lat, snap.lon)
                                : "geo:0,0?q="));
                startActivity(any);
            } catch (Exception e2) {
                detail.setText("Google Maps not installed.");
            }
        }
    }

    private void openHotspot() {
        Intent tether = new Intent();
        tether.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
        try {
            startActivity(tether);
        } catch (RuntimeException first) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (RuntimeException second) {
                detail.setText("Could not open hotspot settings.");
            }
        }
    }

    private void openAuto() {
        final String pkg = "com.google.android.projection.gearhead";
        PackageManager pm = getPackageManager();

        // 1) Explicit activities used across AA versions (launch intent is often null)
        final String[] activities = {
                "com.google.android.gearhead.vanagon.VnLaunchPadActivity",
                "com.google.android.apps.auto.components.settings.SettingsEmbeddedActivity",
                "com.google.android.gearhead.settings.SettingsActivity",
                "com.google.android.projection.gearhead.companion.CompanionActivity",
                "com.google.android.projection.gearhead.MainActivity",
                "com.google.android.gearhead.first.run.FirstRunActivity"
        };
        for (String act : activities) {
            try {
                Intent i = new Intent();
                i.setClassName(pkg, act);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                detail.setText("Opened Android Auto (" + act.substring(act.lastIndexOf('.') + 1) + ").");
                return;
            } catch (Exception ignored) {
            }
        }

        // 2) Standard launch intent
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                detail.setText("Opened Android Auto.");
                return;
            }
        } catch (Exception ignored) {
        }

        // 3) Query any MAIN activity in the package
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory(Intent.CATEGORY_LAUNCHER);
            probe.setPackage(pkg);
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(probe, 0);
            if (list != null) {
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    detail.setText("Opened Android Auto.");
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // 4) App info page so user can open from there
        try {
            pm.getPackageInfo(pkg, 0);
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settings.setData(android.net.Uri.parse("package:" + pkg));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
            detail.setText("AA installed — opened app info. Open Android Auto from there, or plug USB.");
            return;
        } catch (PackageManager.NameNotFoundException e) {
            detail.setText("Android Auto not installed. Install from Play Store.");
        } catch (Exception e) {
            detail.setText("Could not open Android Auto: " + e.getMessage());
        }
    }
}
