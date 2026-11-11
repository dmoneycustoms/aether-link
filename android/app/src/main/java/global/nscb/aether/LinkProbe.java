package global.nscb.aether;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live phone path. Numbers stay empty until a real reading exists. */
public final class LinkProbe {
    public static final class Snap {
        public boolean running;
        public boolean locationGranted;
        public boolean hasFix;
        public double lat;
        public double lon;
        public Float accuracyM;
        public Double speedMps;
        public Float bearingDeg;
        public String transport = "UNKNOWN";
        public boolean satelliteHardware;
        public boolean satelliteTransport;
        public Long rttMs;
        public String rttError = "";
        public Double downMbps;
        public String downError = "";
        public boolean measuring;
        public int batteryPct = -1;
        public String detail = "";
        /** Integrity / anti-spoof (NSCB-style lightweight gate) */
        public double trust = 0.92;
        public String integrityLevel = "clean";
        public boolean quarantine = false;
        public String integrityReason = "";

        Snap copy() {
            Snap s = new Snap();
            s.running = running;
            s.locationGranted = locationGranted;
            s.hasFix = hasFix;
            s.lat = lat;
            s.lon = lon;
            s.accuracyM = accuracyM;
            s.speedMps = speedMps;
            s.bearingDeg = bearingDeg;
            s.transport = transport;
            s.satelliteHardware = satelliteHardware;
            s.satelliteTransport = satelliteTransport;
            s.rttMs = rttMs;
            s.rttError = rttError;
            s.downMbps = downMbps;
            s.downError = downError;
            s.measuring = measuring;
            s.batteryPct = batteryPct;
            s.detail = detail;
            s.trust = trust;
            s.integrityLevel = integrityLevel;
            s.quarantine = quarantine;
            s.integrityReason = integrityReason;
            return s;
        }
    }

    public interface Listener {
        void onSnap(Snap snap);
    }

    private static final Object LOCK = new Object();
    private static final Snap SNAP = new Snap();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aether-tick");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aether-xfer");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static android.location.LocationListener locationListener;
    private static android.location.LocationManager locationManager;
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicBoolean LOCATION_WIRED = new AtomicBoolean(false);
    private static Context app;
    private static Integer satelliteTransportId;
    private static double prevLat = Double.NaN;
    private static double prevLon = Double.NaN;
    private static long prevAt = 0L;
    private static Double obdSpeedMps = null;
    private static long obdSpeedAt = 0L;

    private LinkProbe() {}

    public static void listen(Listener listener) {
        LISTENERS.add(listener);
        listener.onSnap(copy());
    }

    public static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static Snap copy() {
        synchronized (LOCK) {
            return SNAP.copy();
        }
    }

    public static void note(String detail) {
        synchronized (LOCK) {
            SNAP.detail = detail == null ? "" : detail;
        }
        publish();
    }

    /** Feed OBD vehicle speed (m/s) from NativeBleObd for dual-path gate. */
    public static void setObdSpeedMps(Double mps) {
        synchronized (LOCK) {
            obdSpeedMps = mps;
            obdSpeedAt = System.currentTimeMillis();
        }
    }

    public static void start(Context context) {
        app = context.getApplicationContext();
        OnnxIntegrity.init(app);
        SessionLogger.start(app);
        SessionLogger.line("PROBE_START", "ok");
        synchronized (LOCK) {
            SNAP.running = true;
            SNAP.satelliteHardware = app.getPackageManager().hasSystemFeature("android.hardware.telephony.satellite");
            SNAP.locationGranted = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            SNAP.detail = "";
        }
        if (!STARTED.compareAndSet(false, true)) {
            ACTIVE.set(true);
            wireLocation();
            publish();
            return;
        }
        wireNetwork();
        wireLocation();
        ACTIVE.set(true);
        TICKER.scheduleWithFixedDelay(() -> {
            if (!ACTIVE.get()) return;
            try {
                tick();
            } catch (Throwable t) {
                note(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 0, 3, TimeUnit.SECONDS);
        publish();
    }

    /** Stop GPS/network work when UI is backgrounded — battery. */
    public static void stop() {
        ACTIVE.set(false);
        synchronized (LOCK) {
            SNAP.running = false;
        }
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception ignored) {}
        locationListener = null;
        SessionLogger.line("PROBE_STOP", "background");
        publish();
    }

    public static void measureThroughput() {
        if (app == null) return;
        if (!BUSY.compareAndSet(false, true)) return;
        synchronized (LOCK) {
            SNAP.measuring = true;
            SNAP.downError = "";
        }
        publish();
        WORK.execute(() -> {
            try {
                double mbps = downloadMbps("https://speed.cloudflare.com/__down?bytes=262144");
                synchronized (LOCK) {
                    SNAP.downMbps = mbps;
                    SNAP.downError = "";
                    SNAP.measuring = false;
                }
            } catch (Exception e) {
                synchronized (LOCK) {
                    SNAP.downError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    SNAP.measuring = false;
                }
            } finally {
                BUSY.set(false);
                publish();
            }
        });
    }

    private static void tick() {
        readBattery();
        readTransport();
        Long rtt = null;
        String error = "";
        try {
            rtt = timeGet("https://www.gstatic.com/generate_204");
        } catch (Exception first) {
            try {
                rtt = timeGet("https://1.1.1.1/cdn-cgi/trace");
            } catch (Exception second) {
                error = second.getMessage() == null ? "no answer" : second.getMessage();
            }
        }
        synchronized (LOCK) {
            SNAP.rttMs = rtt;
            SNAP.rttError = error;
        }
        publish();
    }

    private static void readBattery() {
        if (app == null) return;
        Intent bat = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat == null) return;
        int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale <= 0 ? -1 : Math.round(level * 100f / scale);
        synchronized (LOCK) {
            SNAP.batteryPct = pct;
        }
    }

    private static void readTransport() {
        if (app == null) return;
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        Network net = cm.getActiveNetwork();
        if (net == null) {
            synchronized (LOCK) {
                SNAP.transport = "OFFLINE";
                SNAP.satelliteTransport = false;
            }
            return;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return;
        String transport = "OTHER";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "WIFI";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "CELLULAR";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ETHERNET";
        boolean sat = false;
        int satId = satelliteId();
        if (satId >= 0) sat = caps.hasTransport(satId);
        if (sat) transport = "SATELLITE";
        synchronized (LOCK) {
            SNAP.transport = transport;
            SNAP.satelliteTransport = sat;
        }
    }

    private static int satelliteId() {
        if (satelliteTransportId != null) return satelliteTransportId;
        try {
            Field field = NetworkCapabilities.class.getField("TRANSPORT_SATELLITE");
            satelliteTransportId = field.getInt(null);
        } catch (Throwable ignored) {
            satelliteTransportId = -1;
        }
        return satelliteTransportId;
    }

    private static void wireNetwork() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    readTransport();
                    publish();
                }

                @Override
                public void onLost(Network network) {
                    synchronized (LOCK) {
                        SNAP.transport = "OFFLINE";
                        SNAP.satelliteTransport = false;
                    }
                    publish();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    readTransport();
                    publish();
                }
            });
        } catch (RuntimeException e) {
            note("Network callback refused: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private static void wireLocation() {
        if (app == null) return;
        boolean granted = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        synchronized (LOCK) {
            SNAP.locationGranted = granted;
        }
        if (!granted) {
            publish();
            return;
        }
        if (!LOCATION_WIRED.compareAndSet(false, true)) {
            publish();
            return;
        }
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                onLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };
        try {
            locationManager = lm;
            locationListener = listener;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 3f, listener, Looper.getMainLooper());
        } catch (RuntimeException ignored) {
            /* no gps provider */
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, listener, Looper.getMainLooper());
        } catch (RuntimeException ignored) {
            /* no network provider */
        }
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) onLocation(last);
        } catch (RuntimeException ignored) {
            /* last known denied */
        }
    }

    private static void onLocation(Location location) {
        // Network provider fixes are coarse — don't use them for jump/trust (causes red pulses indoors)
        String provider = location.getProvider();
        boolean fromGps = provider == null || LocationManager.GPS_PROVIDER.equals(provider);

        double lat = location.getLatitude();
        double lon = location.getLongitude();
        Double speed = location.hasSpeed() ? (double) location.getSpeed() : null;
        Float acc = location.hasAccuracy() ? location.getAccuracy() : null;
        long now = System.currentTimeMillis();
        String reason = "";
        double trust;
        boolean quarantine = false;
        synchronized (LOCK) {
            trust = SNAP.trust;

            // Stale OBD (>4s) does not participate in dual-path
            Double obdFresh = null;
            if (obdSpeedMps != null && obdSpeedAt > 0 && (now - obdSpeedAt) < 4000L) {
                obdFresh = obdSpeedMps;
            }

            if (fromGps) {
                // Impossible speed (~200 mph)
                if (speed != null && speed > 90.0) {
                    reason = String.format(java.util.Locale.US, "speed %.1f m/s over envelope", speed);
                    trust = Math.max(0.05, trust - 0.35);
                    quarantine = true;
                }
                // Jump / teleport — GPS only, higher floor so indoor jitter does not trip
                if (!Double.isNaN(prevLat) && prevAt > 0) {
                    long dt = now - prevAt;
                    if (dt > 200 && dt < 4000) {
                        double dist = haversineM(prevLat, prevLon, lat, lon);
                        double maxPossible = 90.0 * (dt / 1000.0) + 80.0;
                        if (dist > 250 && dist > maxPossible) {
                            reason = String.format(java.util.Locale.US, "jump %.0f m in %.1f s", dist, dt / 1000.0);
                            trust = Math.max(0.05, trust - 0.35);
                            quarantine = true;
                        }
                    }
                }
                // Accuracy collapse while moving
                if (acc != null && acc > 80f && speed != null && speed > 5.0) {
                    if (reason.isEmpty()) reason = String.format(java.util.Locale.US, "accuracy ±%.0f m while moving", acc);
                    trust = Math.max(0.05, trust - 0.12);
                }

                // Dual-path only with fresh OBD and meaningful motion (not parked noise)
                DualPathGate.Result dp = DualPathGate.evaluate(
                        speed, obdFresh, acc, SNAP.rttMs, SNAP.transport);
                boolean moving = (speed != null && speed > 1.5) || (obdFresh != null && obdFresh > 1.5);
                if (moving && dp.severity > 0.15f) {
                    // Apply as a soft floor event, not a continuous bleed every second
                    double hit = dp.severity * 0.25;
                    trust = Math.max(0.05, trust - hit);
                    if (reason.isEmpty()) reason = "dual-path " + dp.reason;
                    else reason = reason + " · " + dp.reason;
                    if (trust < 0.28) quarantine = true;
                }
            }

            // Always recover when no active hard reason this tick (was blocked by dual-path reason)
            if (reason.isEmpty()) {
                trust = Math.min(0.99, trust + 0.02);
            }

            // Optional ONNX spoof head — can only lower trust
            float severity = reason.isEmpty() ? 0f : 0.4f;
            float jumpM = 0f;
            float dtS = 0f;
            if (!Double.isNaN(prevLat) && prevAt > 0) {
                long dtm = now - prevAt;
                dtS = dtm / 1000f;
                jumpM = (float) haversineM(prevLat, prevLon, lat, lon);
            }
            float[] feats = OnnxIntegrity.features(
                    speed == null ? 0.0 : speed,
                    acc,
                    0f,
                    jumpM,
                    dtS,
                    null,
                    SNAP.rttMs,
                    SNAP.downMbps,
                    SNAP.transport,
                    SNAP.satelliteTransport,
                    (float) trust,
                    severity
            );
            OnnxIntegrity.Result ort = OnnxIntegrity.score(feats, (float) trust, severity);
            if (ort.modelLive && ort.trustOut < trust - 0.03f) {
                trust = ort.trustOut;
                if (reason.isEmpty()) reason = "ort " + ort.detail;
                else if (!reason.contains("ort ")) reason = reason + " · ort " + ort.detail;
                // ORT alone must not slam quarantine while analytic path is healthy
                if (trust < 0.28 && severity >= 0.35f && !reason.startsWith("ort ")) {
                    quarantine = true;
                }
            }

            String level = quarantine || trust < 0.28 ? "quarantine" : trust < 0.5 ? "degraded" : trust < 0.75 ? "watch" : "clean";
            SNAP.hasFix = true;
            SNAP.lat = lat;
            SNAP.lon = lon;
            SNAP.accuracyM = acc;
            SNAP.speedMps = speed;
            SNAP.bearingDeg = location.hasBearing() ? location.getBearing() : null;
            SNAP.trust = trust;
            SNAP.integrityLevel = level;
            SNAP.quarantine = quarantine;
            SNAP.integrityReason = reason;
            SessionLogger.line("FIX", String.format(java.util.Locale.US,
                    "spd=%.2f trust=%.2f lvl=%s path=%s %s",
                    speed == null ? -1 : speed, trust, level,
                    SNAP.transport == null ? "-" : SNAP.transport, reason));
        }
        if (fromGps) {
            prevLat = lat;
            prevLon = lon;
            prevAt = now;
        }
        publish();
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static Long timeGet(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        long t0 = System.nanoTime();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            byte[] buf = new byte[256];
            while (in.read(buf) != -1) {
                /* drain */
            }
            in.close();
        }
        c.disconnect();
        if (code >= 400) throw new java.io.IOException("HTTP " + code);
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static double downloadMbps(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        long t0 = System.nanoTime();
        int code = c.getResponseCode();
        if (code >= 400) throw new java.io.IOException("HTTP " + code);
        InputStream in = c.getInputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) total += n;
        in.close();
        c.disconnect();
        double seconds = (System.nanoTime() - t0) / 1_000_000_000d;
        if (seconds <= 0 || total <= 0) throw new java.io.IOException("empty body");
        return (total * 8d) / seconds / 1_000_000d;
    }

    private static void publish() {
        Snap snap = copy();
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) listener.onSnap(snap);
        });
    }
}
