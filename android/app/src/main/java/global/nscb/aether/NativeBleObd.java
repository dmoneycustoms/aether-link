package global.nscb.aether;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native BLE OBD — TOPDON CarPal / ELM327 style.
 * Scans all named BLE devices, prefers OBD-like names, exposes picker list.
 */
@SuppressLint("MissingPermission")
public final class NativeBleObd {
    public interface Listener {
        void onStatus(String line);
        void onLog(String line);
        void onReading(Reading r);
        /** Called when scan ends with candidates (may be empty). */
        void onScanFinished(List<String> labels, List<BluetoothDevice> devices);
    }

    public static final class Reading {
        public Double rpm, kph, coolantC, loadPct, throttlePct, volts, iatC, mapKpa;
        public Double stftB1, ltftB1, stftB2, ltftB2;
        public Double fuelPressureKpa, timingDeg, mafGps, runtimeSec;
        public Double distMilKm, fuelLevelPct, baroKpa, ambientC;
        public Double absLoadPct, commandedAfr, relThrottlePct, accelPct;
        public Double catB1S1C, o2B1S1V, o2B1S2V;
        public Double oilC, ethanolPct;
        public String vin, calId, ecuName, fuelType, obdStd;
        public String protocol;
        public final List<String> dtcs = new ArrayList<>();
        public final List<String> pendingDtcs = new ArrayList<>();
        public final Map<String, String> raw = new LinkedHashMap<>();
        public long updatedAt;

        /** Full HHR diagnostic dump for advanced panel. */
        public String fullReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("═══ AETHER HHR / GM DIAGNOSTIC ═══\n");
            sb.append("Updated ").append(updatedAt > 0 ? new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(updatedAt)) : "-").append("\n");
            sb.append("Protocol ").append(protocol != null ? protocol : "CAN/ELM").append("\n\n");
            sb.append("── IDENTITY ──\n");
            sb.append("VIN      ").append(vin != null ? vin : "—").append("\n");
            sb.append("CAL ID   ").append(calId != null ? calId : "—").append("\n");
            sb.append("ECU      ").append(ecuName != null ? ecuName : "—").append("\n");
            sb.append("Fuel     ").append(fuelType != null ? fuelType : "—").append("\n");
            sb.append("OBD std  ").append(obdStd != null ? obdStd : "—").append("\n\n");
            sb.append("── POWERTRAIN LIVE ──\n");
            sb.append(String.format(java.util.Locale.US, "RPM        %s\n", f(rpm, 0)));
            sb.append(String.format(java.util.Locale.US, "Speed      %s km/h\n", f(kph, 0)));
            sb.append(String.format(java.util.Locale.US, "Load       %s %%\n", f(loadPct, 1)));
            sb.append(String.format(java.util.Locale.US, "Abs load   %s %%\n", f(absLoadPct, 1)));
            sb.append(String.format(java.util.Locale.US, "Throttle   %s %%\n", f(throttlePct, 1)));
            sb.append(String.format(java.util.Locale.US, "Rel thr    %s %%\n", f(relThrottlePct, 1)));
            sb.append(String.format(java.util.Locale.US, "Accel ped  %s %%\n", f(accelPct, 1)));
            sb.append(String.format(java.util.Locale.US, "Timing     %s °\n", f(timingDeg, 1)));
            sb.append(String.format(java.util.Locale.US, "MAF        %s g/s\n", f(mafGps, 2)));
            sb.append(String.format(java.util.Locale.US, "MAP        %s kPa\n", f(mapKpa, 0)));
            sb.append(String.format(java.util.Locale.US, "Baro       %s kPa\n", f(baroKpa, 0))).append("\n");
            sb.append("── TEMPERATURES ──\n");
            sb.append(String.format(java.util.Locale.US, "Coolant    %s °C\n", f(coolantC, 0)));
            sb.append(String.format(java.util.Locale.US, "IAT        %s °C\n", f(iatC, 0)));
            sb.append(String.format(java.util.Locale.US, "Ambient    %s °C\n", f(ambientC, 0)));
            sb.append(String.format(java.util.Locale.US, "Oil (PID)  %s °C\n", f(oilC, 0)));
            sb.append(String.format(java.util.Locale.US, "Cat B1S1   %s °C\n", f(catB1S1C, 0))).append("\n");
            sb.append("── FUEL / TRIMS ──\n");
            sb.append(String.format(java.util.Locale.US, "STFT B1    %s %%\n", f(stftB1, 1)));
            sb.append(String.format(java.util.Locale.US, "LTFT B1    %s %%\n", f(ltftB1, 1)));
            sb.append(String.format(java.util.Locale.US, "STFT B2    %s %%\n", f(stftB2, 1)));
            sb.append(String.format(java.util.Locale.US, "LTFT B2    %s %%\n", f(ltftB2, 1)));
            sb.append(String.format(java.util.Locale.US, "Fuel lvl   %s %%\n", f(fuelLevelPct, 0)));
            sb.append(String.format(java.util.Locale.US, "Fuel press %s kPa\n", f(fuelPressureKpa, 0)));
            sb.append(String.format(java.util.Locale.US, "Cmd AFR    %s\n", f(commandedAfr, 2)));
            sb.append(String.format(java.util.Locale.US, "Ethanol    %s %%\n", f(ethanolPct, 0))).append("\n");
            sb.append("── O2 / ELECTRICAL ──\n");
            sb.append(String.format(java.util.Locale.US, "O2 B1S1    %s V\n", f(o2B1S1V, 3)));
            sb.append(String.format(java.util.Locale.US, "O2 B1S2    %s V\n", f(o2B1S2V, 3)));
            sb.append(String.format(java.util.Locale.US, "Module V   %s V\n", f(volts, 2))).append("\n");
            sb.append("── DISTANCE / TIME ──\n");
            sb.append(String.format(java.util.Locale.US, "Runtime    %s s\n", f(runtimeSec, 0)));
            sb.append(String.format(java.util.Locale.US, "MIL dist   %s km\n", f(distMilKm, 0))).append("\n");
            sb.append("── DTCs (stored) ──\n");
            if (dtcs.isEmpty()) sb.append("(none)\n");
            else for (String d : dtcs) sb.append("  ").append(d).append("\n");
            sb.append("\n── DTCs (pending) ──\n");
            if (pendingDtcs.isEmpty()) sb.append("(none)\n");
            else for (String d : pendingDtcs) sb.append("  ").append(d).append("\n");
            if (!raw.isEmpty()) {
                sb.append("\n── RAW PID SNAPSHOT ──\n");
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
                }
            }
            return sb.toString();
        }

        private static String f(Double v, int digits) {
            if (v == null || v.isNaN()) return "—";
            return String.format(java.util.Locale.US, "%." + digits + "f", v);
        }
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] CYCLE = {
            // Primary live
            "010C", "010D", "0105", "0104", "0111", "0142", "010F", "010B",
            // Trims / fuel
            "0106", "0107", "0108", "0109", "010A", "012F", "0151",
            // Air / timing / throttle
            "010E", "0110", "0133", "0146", "0143", "0144", "0145", "0147", "0148", "0149", "014A", "014C",
            // O2 / cat / EGR
            "0114", "0115", "011C", "012C", "012D", "012E", "013C", "013D",
            // Runtime / MIL / oil / ethanol / boost related
            "011F", "0121", "0130", "0131", "015C", "0152", "015B",
            // Identity + codes (slower cadence via same list)
            "0902", "03", "07"
    };

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothSocket sppSocket;
    private InputStream sppIn;
    private OutputStream sppOut;
    private Thread sppReader;
    private volatile boolean useSpp;
    private BluetoothDevice lastDevice;
    private String lastLabel;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private final StringBuilder rx = new StringBuilder();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ecuLinked = new AtomicBoolean(false);
    private final AtomicBoolean sppConnecting = new AtomicBoolean(false);
    private final AtomicInteger connectGen = new AtomicInteger(0);
    private volatile boolean gattOnlyMode = false;
    private final Object sppIoLock = new Object();
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private int cycleIdx;
    private final Reading reading = new Reading();

    public NativeBleObd(Context context) {
        this.app = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void startScanAndConnect() {
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            status("Bluetooth is OFF - turn it on");
            return;
        }
        final BluetoothAdapter adapter = bm.getAdapter();
        found.clear();
        running.set(true);
        status("Full discovery: every path (paired/classic/BLE/connected)...");
        log("=== OBD discovery start ===");

        seedPaired(adapter);
        seedConnectedProfiles(bm);
        startClassicDiscovery(adapter);
        startBleScan(adapter, "pass1");

        // Mid-scan: re-seed paired + classic restart + BLE pass 2
        main.postDelayed(() -> {
            if (!running.get()) return;
            seedPaired(adapter);
            seedConnectedProfiles(bm);
            try {
                if (adapter.isDiscovering()) adapter.cancelDiscovery();
                adapter.startDiscovery();
                log("Classic discovery restart");
            } catch (Exception e) {
                log("Classic restart: " + e.getMessage());
            }
            startBleScan(adapter, "pass2");
            status("Discovery pass 2 - " + found.size() + " device(s)");
        }, 7000);

        // Pass 3 BLE balanced + paired again
        main.postDelayed(() -> {
            if (!running.get()) return;
            seedPaired(adapter);
            startBleScan(adapter, "pass3-balanced");
            status("Discovery pass 3 - " + found.size() + " device(s)");
        }, 14000);

        main.postDelayed(this::finishScan, 22000);
    }

    private void seedPaired(BluetoothAdapter adapter) {
        try {
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                addFound(d, safeName(d), "paired");
            }
            log("Paired seeded n=" + bonded.size());
        } catch (Exception e) {
            log("Paired seed fail: " + e.getMessage());
        }
    }

    private void seedConnectedProfiles(BluetoothManager bm) {
        int[] profiles = new int[] {
                BluetoothProfile.GATT,
                BluetoothProfile.GATT_SERVER,
                1, // A2DP
                2, // HEADSET
                3, // HEALTH legacy
        };
        for (int profile : profiles) {
            try {
                java.util.List<BluetoothDevice> list = bm.getConnectedDevices(profile);
                if (list == null) continue;
                for (BluetoothDevice d : list) {
                    addFound(d, safeName(d), "connected-p" + profile);
                }
                if (!list.isEmpty()) log("Connected profile " + profile + " n=" + list.size());
            } catch (Exception ignored) {
            }
        }
    }

    private void startClassicDiscovery(BluetoothAdapter adapter) {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            try { app.unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
            app.registerReceiver(classicReceiver, filter);
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            boolean ok = adapter.startDiscovery();
            log("Classic startDiscovery=" + ok);
        } catch (Exception e) {
            log("Classic error: " + e.getMessage());
        }
    }

    private void startBleScan(BluetoothAdapter adapter, String tag) {
        try {
            if (scanner != null) {
                try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                log("No BLE scanner (" + tag + ")");
                return;
            }
            int mode = "pass3-balanced".equals(tag)
                    ? ScanSettings.SCAN_MODE_BALANCED
                    : ScanSettings.SCAN_MODE_LOW_LATENCY;
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(mode)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, scanCb);
            log("BLE scan " + tag + " mode=" + mode);
        } catch (Exception e) {
            log("BLE " + tag + " fail: " + e.getMessage());
        }
    }

    private static String safeName(BluetoothDevice d) {
        if (d == null) return null;
        try {
            String n = d.getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void addFound(BluetoothDevice d, String name, String source) {
        if (d == null) return;
        String addr;
        try {
            addr = d.getAddress();
        } catch (Exception e) {
            return;
        }
        if (addr == null) return;
        // de-dupe by MAC
        for (String k : new ArrayList<>(found.keySet())) {
            if (k.contains(addr)) return;
        }
        if (name == null || name.trim().isEmpty()) {
            name = "Unknown-" + addr.replace(":", "");
        }
        boolean obd = isObdName(name) || isObdSource(source);
        String tag = obd ? "OBD?" : source;
        String key = "[" + tag + "] " + name + " [" + addr + "]";
        found.put(key, d);
        log("Seen (" + source + "): " + key);
        status("Seen " + found.size() + " - " + name);
    }


    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (d != null && (name == null || name.trim().isEmpty())) {
                    name = safeName(d);
                }
                String src = BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) ? "bond" : "classic";
                addFound(d, name, src);
            }
        }
    };

    private void finishScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        try {
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null) {
                if (bm.getAdapter().isDiscovering()) bm.getAdapter().cancelDiscovery();
                seedPaired(bm.getAdapter());
                seedConnectedProfiles(bm);
            }
        } catch (Exception ignored) {}
        try { app.unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
        log("=== OBD discovery end n=" + found.size() + " ===");
        List<String> labels = new ArrayList<>();
        List<BluetoothDevice> devices = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<BluetoothDevice> preferredDev = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<BluetoothDevice> otherDev = new ArrayList<>();
        for (Map.Entry<String, BluetoothDevice> e : found.entrySet()) {
            String name = e.getKey();
            if (isObdName(name)) {
                preferred.add(name);
                preferredDev.add(e.getValue());
            } else {
                other.add(name);
                otherDev.add(e.getValue());
            }
        }
        labels.addAll(preferred);
        devices.addAll(preferredDev);
        labels.addAll(other);
        devices.addAll(otherDev);
        if (labels.isEmpty()) {
            status("No devices. Pair TOPDON in phone BT settings, ignition ON, then scan again.");
            log("Empty after paired+classic+BLE. Pair adapter in system Bluetooth first.");
        } else {
            status("Found " + labels.size() + " device(s) — pick one");
            log("Scan: " + labels);
        }
        main.post(() -> {
            if (listener != null) listener.onScanFinished(labels, devices);
        });
        // Auto-connect if exactly one preferred OBD-like device
        if (preferredDev.size() == 1) {
            connectDevice(preferredDev.get(0), preferred.get(0));
        }
    }


    public void connectDevice(BluetoothDevice device, String label) {
        if (device == null) return;
        lastDevice = device;
        lastLabel = label;
        running.set(true);
        useSpp = false;
        // Stop discovery so classic RFCOMM can connect
        try {
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null && bm.getAdapter().isDiscovering()) {
                bm.getAdapter().cancelDiscovery();
            }
        } catch (Exception ignored) {}
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}

        int type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        try { type = device.getType(); } catch (Exception ignored) {}
        log("Connect type=" + type + " bond=" + device.getBondState() + " " + label);

        // EXCLUSIVE modes — never run SPP and GATT at the same time (status 147/133).
        // CarPal: BLE works in other apps; classic RFCOMM is silent. Try GATT first.
        final int gen = connectGen.incrementAndGet();
        hardCloseAll();
        status("Connecting OBD exclusive — kill TOPDON, key RUN…");
        new Thread(() -> {
            try {
                // --- Phase 1: BLE GATT only ---
                gattOnlyMode = true;
                main.post(() -> status("Phase 1 · BLE GATT only…"));
                boolean bleOk = connectGattBlocking(device, label, gen);
                if (bleOk || gen != connectGen.get()) return;

                // --- Phase 2: classic SPP only (after full radio idle) ---
                gattOnlyMode = false;
                hardCloseAll();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                if (gen != connectGen.get()) return;
                main.post(() -> status("Phase 2 · classic SPP only…"));
                if (connectSpp(device, label)) return;

                main.post(() -> status("OBD failed both BLE and SPP — unplug 5s, re-pair, retry"));
            } catch (Throwable t) {
                log("connect flow: " + t.getMessage());
                main.post(() -> status("Connect error: " + t.getMessage()));
            }
        }, "aether-obd-connect").start();
    }

    /** Close every BT link so the stack is idle before the next profile. */
    private void hardCloseAll() {
        try { closeSppQuiet(); } catch (Exception ignored) {}
        writeChar = null;
        notifyChar = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        synchronized (rx) { rx.setLength(0); }
        busy.set(false);
        ecuLinked.set(false);
        useSpp = false;
    }

    /**
     * Blocking-style GATT connect for exclusive phase 1.
     * autoConnect=false, LE then AUTO, waits for services or failure.
     */
    private boolean connectGattBlocking(BluetoothDevice device, String label, int gen) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Temporary latch callback wrapper: use existing gattCb but signal when
        // we either get writeChar or disconnect without recovery.
        main.post(() -> status("BLE connecting (autoConnect=false)…"));
        try {
            hardCloseAll();
            running.set(true);
            useSpp = false;
            // autoConnect MUST be false for ELM clones
            gatt = device.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            log("GATT connect " + label + " transport=LE type=" + device.getType() + " autoConnect=false");
        } catch (Exception e) {
            log("GATT LE open: " + e.getMessage());
            try {
                gatt = device.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_AUTO);
            } catch (Exception e2) {
                log("GATT AUTO open: " + e2.getMessage());
                return false;
            }
        }

        // Wait up to 12s for UART chars to appear (set in onServicesDiscovered)
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline && gen == connectGen.get()) {
            if (writeChar != null) {
                ok.set(true);
                break;
            }
            if (gatt == null) break;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        if (ok.get()) {
            main.post(() -> status("BLE UART ready"));
            return true; // init already queued in onServicesDiscovered
        }
        log("GATT phase failed or timed out gen=" + gen);
        hardCloseAll();
        return false;
    }

    private void connectGatt(BluetoothDevice device, String label) {
        // Legacy entry — redirect to exclusive flow is preferred; keep for safety
        gattOnlyMode = true;
        hardCloseAll();
        running.set(true);
        useSpp = false;
        try {
            gatt = device.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            log("GATT connect " + label + " transport=LE type=" + device.getType());
        } catch (Exception e) {
            try {
                gatt = device.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_AUTO);
            } catch (Exception e2) {
                status("GATT failed: " + e2.getMessage());
            }
        }
    }

    private boolean connectSpp(BluetoothDevice device, String label) {
        if (!sppConnecting.compareAndSet(false, true)) {
            log("SPP connect ignored — already connecting");
            return false;
        }
        try {
            closeSppQuiet();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            try {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null) {
                    try { bm.getAdapter().cancelDiscovery(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            try {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    main.post(() -> status("Pairing CarPal…"));
                    try { device.createBond(); } catch (Exception ignored) {}
                    for (int i = 0; i < 40; i++) {
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED) break;
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            // Try UUID SPP first, then raw RFCOMM channels 1..5 (many clones only answer on ch1/ch2)
            java.util.List<Object[]> attempts = new java.util.ArrayList<>();
            attempts.add(new Object[]{"uuid-insecure", 0});
            attempts.add(new Object[]{"uuid-secure", -1});
            for (int ch = 1; ch <= 5; ch++) attempts.add(new Object[]{"ch" + ch, ch});

            for (Object[] att : attempts) {
                String tag = (String) att[0];
                int ch = (Integer) att[1];
                closeSppQuiet();
                running.set(true);
                BluetoothSocket sock = null;
                try {
                    main.post(() -> status("SPP try " + tag + "…"));
                    if (ch == 0) {
                        sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    } else if (ch == -1) {
                        sock = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    } else {
                        java.lang.reflect.Method m = device.getClass()
                                .getMethod("createInsecureRfcommSocket", int.class);
                        sock = (BluetoothSocket) m.invoke(device, ch);
                    }
                    sock.connect();
                } catch (Exception e) {
                    log("try " + tag + " connect fail: " + e.getMessage());
                    try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                    continue;
                }

                try {
                    sppSocket = sock;
                    synchronized (sppIoLock) {
                        sppOut = sock.getOutputStream();
                        sppIn = sock.getInputStream();
                    }
                } catch (IOException ioe) {
                    log("try " + tag + " streams: " + ioe.getMessage());
                    try { sock.close(); } catch (Exception ignored) {}
                    sppSocket = null;
                    continue;
                }

                useSpp = true;
                ecuLinked.set(false);
                synchronized (rx) { rx.setLength(0); }
                startSppReader();
                // Serial buffer settle — CarPal/ELM clones need time after RFCOMM up
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                // Wake UART with bare CR a few times (no AT yet)
                for (int w = 0; w < 3; w++) {
                    try {
                        synchronized (sppIoLock) {
                            if (sppOut != null) {
                                sppOut.write(new byte[]{0x0D});
                                sppOut.flush();
                            }
                        }
                    } catch (Exception ignored) {}
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }
                synchronized (rx) { rx.setLength(0); }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}

                // PROBE: long ATZ — adapter firmware often boots on first reset
                log("SPP probe " + tag + " connected=" + sock.isConnected());
                String probe = sppCommandWait("ATZ", 8000);
                if (!probeAlive(probe)) {
                    // Second ATZ after extra delay (some need double reset)
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    synchronized (rx) { rx.setLength(0); }
                    probe = sppCommandWait("ATZ", 8000);
                }
                if (probeAlive(probe)) {
                    log("SPP channel ALIVE via " + tag + " RX=" + sanitize(probe));
                    main.post(() -> status("ELM alive · " + tag));
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    boolean linked = initElmSppAfterReset();
                    startSppPoller();
                    final boolean ok = linked;
                    main.post(() -> status(ok ? ("ECU LIVE · " + tag) : ("ELM up, no ECU · key RUN · " + tag)));
                    return true;
                }
                log("SPP probe " + tag + " silent — next channel");
                closeSppQuiet();
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            main.post(() -> status("No ELM on any RFCOMM channel — adapter silent"));
            log("All SPP channels silent after ATZ");
            return false;
        } finally {
            sppConnecting.set(false);
        }
    }

    private static boolean probeAlive(String probe) {
        if (probe == null || probe.isEmpty()) return false;
        String u = probe.toUpperCase(Locale.US);
        // Any ELM-like token
        if (u.contains("ELM") || u.contains("OBD") || u.contains("OK") || u.contains("SEARCHING")
                || u.contains(">") || u.contains("ATZ") || u.contains("?") || u.contains("V1")
                || u.contains("STN") || u.contains("CARPAL") || u.contains("TOPDON")) {
            return true;
        }
        // Printable length
        int print = 0;
        for (int i = 0; i < probe.length(); i++) {
            char c = probe.charAt(i);
            if (c >= 32 && c < 127) print++;
        }
        return print >= 2;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String one = s.replace('\r', ' ').replace('\n', ' ').trim();
        return one.length() > 80 ? one.substring(0, 80) : one;
    }

    /**
     * After ATZ already succeeded on this channel — finish config + ECU search.
     * Does not send ATZ again.
     */
    private boolean initElmSppAfterReset() {
        ecuLinked.set(false);
        sppCommandWait("ATE0", 1000);
        sppCommandWait("ATL0", 600);
        sppCommandWait("ATS0", 600);
        sppCommandWait("ATH0", 600);
        sppCommandWait("ATAL", 600);
        sppCommandWait("ATSTFF", 800);
        sppCommandWait("ATAT1", 600);
        sppCommandWait("ATCAF1", 600);
        sppCommandWait("ATCFC1", 600);

        main.post(() -> status("OBD · searching ECU (key RUN)…"));
        sppCommandWait("ATSP0", 1000);
        String r = sppCommandWait("0100", 15000);
        if (!isPidOk(r)) {
            sppCommandWait("ATSP6", 1000);
            r = sppCommandWait("0100", 12000);
        }
        if (!isPidOk(r)) {
            sppCommandWait("ATSP7", 1000);
            r = sppCommandWait("0100", 10000);
        }
        if (!isPidOk(r)) {
            sppCommandWait("ATTP6", 1000);
            r = sppCommandWait("0100", 10000);
        }
        if (isPidOk(r)) {
            ecuLinked.set(true);
            reading.protocol = "CAN/ELM linked";
            log("ECU LINKED: " + sanitize(r));
            main.post(() -> status("OBD ECU linked — live PIDs"));
            sppCommandWait("ATDP", 1000);
            sppCommandWait("0902", 4000);
            sppCommandWait("03", 2000);
            return true;
        }
        log("0100 failed after live ELM — key RUN? power?");
        main.post(() -> status("ELM OK · no ECU — key to RUN"));
        return false;
    }

    private boolean initElmSpp() {
        // Full path including ATZ (legacy)
        sppCommandWait("ATZ", 4000);
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        return initElmSppAfterReset();
    }

    private static boolean isPidOk(String r) {
        if (r == null || r.isEmpty()) return false;
        String u = r.toUpperCase(Locale.US).replace(" ", "");
        if (u.contains("UNABLETOCONNECT") || u.contains("BUSINIT") || u.contains("CANERROR")) {
            return u.contains("4100");
        }
        if (u.contains("NODATA") || u.contains("NO DATA")) return false;
        // Real Mode 01 PID support bitfield
        return u.contains("4100") || (u.contains("41") && u.contains("00") && !u.contains("SEARCHING"));
    }

    private String sppCommandWait(String cmd, long timeoutMs) {
        if (cmd == null) return null;
        if (sppSocket == null || !sppSocket.isConnected() || sppOut == null) {
            log("TX skip (no socket) " + cmd);
            return null;
        }
        busy.set(true);
        synchronized (rx) { rx.setLength(0); }
        try {
            synchronized (sppIoLock) {
                if (sppOut == null) {
                    busy.set(false);
                    return null;
                }
                sppOut.write((cmd.trim() + "\r").getBytes(StandardCharsets.US_ASCII));
                sppOut.flush();
            }
            log("TX " + cmd);
        } catch (IOException e) {
            log("TX fail: " + e.getMessage());
            busy.set(false);
            running.set(false);
            return null;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline && running.get() && useSpp) {
            if (sppSocket == null || !sppSocket.isConnected()) {
                log("TX mid-fail socket gone");
                break;
            }
            String snap;
            synchronized (rx) { snap = rx.toString(); }
            String up = snap.toUpperCase(Locale.US);
            boolean hasPrompt = snap.indexOf('>') >= 0;
            if (up.contains("SEARCHING") && !hasPrompt) {
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                continue;
            }
            if (hasPrompt || up.contains("UNABLE TO CONNECT") || up.contains("NO DATA")
                    || up.contains("NODATA") || up.contains("CAN ERROR") || up.contains("BUS INIT")
                    || up.contains("OK")) {
                try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                synchronized (rx) { last = rx.toString(); }
                if (hasPrompt || up.contains("UNABLE") || up.contains("NO DATA") || up.contains("NODATA")
                        || up.contains("STOPPED") || (up.contains("OK") && hasPrompt)) {
                    break;
                }
            }
            try { Thread.sleep(40); } catch (InterruptedException ignored) {}
        }
        synchronized (rx) {
            if (last.isEmpty()) last = rx.toString();
            rx.setLength(0);
        }
        if (last.length() > 0) {
            String one = last.replace('\r', ' ').replace('\n', ' ').trim();
            if (one.length() > 120) one = one.substring(0, 120);
            log("RX " + one);
            try { handleResponse(last); } catch (Throwable ignored) {}
        } else {
            log("RX (timeout/empty) " + cmd);
        }
        busy.set(false);
        return last;
    }

    private void startSppPoller() {
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            while (running.get() && useSpp) {
                try {
                    if (busy.get()) {
                        Thread.sleep(50);
                        continue;
                    }
                    if (!ecuLinked.get()) {
                        main.post(() -> status("OBD · retry ECU 0100 (key ON)…"));
                        String r = sppCommandWait("0100", 12000);
                        if (isPidOk(r)) {
                            ecuLinked.set(true);
                            main.post(() -> status("OBD ECU linked — live PIDs"));
                            sppCommandWait("ATDP", 800);
                        } else {
                            Thread.sleep(3000);
                        }
                        continue;
                    }
                    if (cycleIdx >= CYCLE.length) cycleIdx = 0;
                    String cmd = CYCLE[cycleIdx++];
                    sppCommandWait(cmd, 2500);
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    log("poll: " + t.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }, "aether-spp-poll").start();
    }

    private void sppWrite(String cmd) {
        // fire-and-forget only for rare cases; prefer sppCommandWait
        if (sppOut == null || cmd == null) return;
        try {
            sppOut.write((cmd.trim() + "\r").getBytes(StandardCharsets.US_ASCII));
            sppOut.flush();
            log("TX " + cmd);
        } catch (IOException e) {
            log("TX fail: " + e.getMessage());
            main.post(() -> status("SPP write lost — disconnected"));
            running.set(false);
        }
    }

    private void startSppReader() {
        if (sppReader != null) {
            try { sppReader.interrupt(); } catch (Exception ignored) {}
            sppReader = null;
        }
        sppReader = new Thread(() -> {
            byte[] buf = new byte[512];
            while (running.get() && useSpp) {
                InputStream in;
                synchronized (sppIoLock) { in = sppIn; }
                if (in == null) break;
                try {
                    int n = in.read(buf);
                    if (n < 0) {
                        log("SPP EOF (read -1)");
                        break;
                    }
                    if (n == 0) continue;
                    // Prefer printable ASCII; log hex if mostly binary
                    StringBuilder ascii = new StringBuilder(n);
                    int printable = 0;
                    for (int i = 0; i < n; i++) {
                        int b = buf[i] & 0xFF;
                        if (b == 0x0D || b == 0x0A || (b >= 0x20 && b <= 0x7E)) {
                            ascii.append((char) b);
                            if (b >= 0x20) printable++;
                        } else {
                            ascii.append('.');
                        }
                    }
                    if (printable == 0 && n > 0) {
                        StringBuilder hex = new StringBuilder("HEX ");
                        for (int i = 0; i < Math.min(n, 24); i++) {
                            hex.append(String.format(Locale.US, "%02X ", buf[i] & 0xFF));
                        }
                        log(hex.toString().trim());
                    }
                    synchronized (rx) {
                        rx.append(ascii);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log("SPP read IO: " + e.getMessage());
                    }
                    break;
                } catch (Throwable t) {
                    log("SPP read: " + t.getMessage());
                    break;
                }
            }
            if (running.get()) {
                main.post(() -> status("SPP link lost"));
            }
            running.set(false);
            useSpp = false;
        }, "aether-spp-rx");
        sppReader.setDaemon(true);
        sppReader.start();
    }

    private void closeSppQuiet() {
        useSpp = false;
        ecuLinked.set(false);
        try { if (sppReader != null) sppReader.interrupt(); } catch (Exception ignored) {}
        sppReader = null;
        try { if (sppIn != null) sppIn.close(); } catch (Exception ignored) {}
        try { if (sppOut != null) sppOut.close(); } catch (Exception ignored) {}
        try { if (sppSocket != null) sppSocket.close(); } catch (Exception ignored) {}
        sppIn = null;
        sppOut = null;
        sppSocket = null;
    }

    /** Mode 04 — clear stored DTCs / MIL. Call with ignition ON. */
    public void clearDtcs() {
        new Thread(() -> {
            if (useSpp && sppOut != null) {
                main.post(() -> status("Clearing DTCs (Mode 04)…"));
                String r = sppCommandWait("04", 4000);
                log("CLR " + (r != null ? r.replace('\r', ' ').replace('\n', ' ').trim() : "timeout"));
                reading.dtcs.clear();
                reading.pendingDtcs.clear();
                sppCommandWait("03", 2000);
                sppCommandWait("07", 2000);
                main.post(() -> status("DTC clear sent — recheck codes"));
                emitReading();
            } else {
                main.post(() -> status("Connect OBD before clearing DTCs"));
            }
        }, "aether-clr").start();
    }

    public void disconnect() {
        connectGen.incrementAndGet();
        running.set(false);
        hardCloseAll();
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        pending.clear();
        status("Disconnected");
    }

    private static boolean isObdName(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.US);
        return n.contains("OBD") || n.contains("ELM") || n.contains("TOPDON")
                || n.contains("CARPAL") || n.contains("CAR PAL") || n.contains("CAR-PAL")
                || n.contains("VGATE") || n.contains("OBDLINK") || n.contains("KONNWEI")
                || n.contains("VEEPEAK") || n.contains("BAFX") || n.contains("DUNBIKE")
                || n.contains("HC-05") || n.contains("HC-06") || n.contains("BLEOBD") || n.contains("OBDBLE");
    }

    private static boolean isObdSource(String source) {
        if (source == null) return false;
        return source.contains("paired") || source.contains("classic") || source.contains("OBD")
                || source.contains("ble") || source.contains("uuid-obd") || source.contains("bond");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String name = d.getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null || name.trim().isEmpty()) {
                name = "Unknown-" + d.getAddress().replace(":", "");
            }
            addFound(d, name, "ble");
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) onScanResult(0, r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("BLE scan failed code " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("GATT state new=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gattOnlyMode) {
                    log("GATT connected outside gattOnlyMode — ignoring");
                    try { g.disconnect(); } catch (Exception ignored) {}
                    return;
                }
                main.post(() -> NativeBleObd.this.status("BLE connected — stabilize…"));
                main.postDelayed(() -> {
                    try {
                        if (gatt != null && gattOnlyMode) {
                            boolean d = gatt.discoverServices();
                            log("discoverServices=" + d);
                        }
                    } catch (Exception ex) {
                        log("discover: " + ex.getMessage());
                    }
                }, 500);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected status=" + status);
                writeChar = null;
                notifyChar = null;
                if (gattOnlyMode) {
                    main.post(() -> NativeBleObd.this.status("BLE disconnected (" + status + ")"));
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            writeChar = null;
            notifyChar = null;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT services status=" + status);
                main.post(() -> NativeBleObd.this.status("BLE service discover failed"));
                return;
            }
            java.util.List<BluetoothGattCharacteristic> writable = new java.util.ArrayList<>();
            java.util.List<BluetoothGattCharacteristic> notifiable = new java.util.ArrayList<>();
            for (BluetoothGattService svc : g.getServices()) {
                if (svc == null) continue;
                String su = svc.getUuid().toString().toLowerCase(Locale.US);
                log("GATT svc " + su);
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    if (ch == null) continue;
                    int p = ch.getProperties();
                    String cu = ch.getUuid().toString().toLowerCase(Locale.US);
                    boolean canWrite = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                    boolean canNotify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (canWrite) writable.add(ch);
                    if (canNotify) notifiable.add(ch);
                    log("  char " + cu + " props=" + p + (canWrite ? " W" : "") + (canNotify ? " N" : ""));
                }
            }
            writeChar = preferChar(writable, true);
            notifyChar = preferChar(notifiable, false);
            if (writeChar == null && !writable.isEmpty()) writeChar = writable.get(0);
            if (notifyChar == null && !notifiable.isEmpty()) notifyChar = notifiable.get(0);
            if (notifyChar == null && writeChar != null) notifyChar = writeChar;
            if (writeChar == null) {
                log("GATT no writable characteristic");
                main.post(() -> NativeBleObd.this.status("BLE: no UART write char"));
                return;
            }
            log("GATT WRITE " + writeChar.getUuid());
            if (notifyChar != null) {
                log("GATT NOTIFY " + notifyChar.getUuid());
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
                if (d == null) {
                    d = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                }
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            main.post(() -> NativeBleObd.this.status("BLE UART up — ELM init"));
            main.postDelayed(() -> {
                synchronized (rx) { rx.setLength(0); }
                pending.clear();
                queue("ATZ");
                queue("ATE0");
                queue("ATL0");
                queue("ATS0");
                queue("ATH0");
                queue("ATSTFF");
                queue("ATSP0");
                queue("0100");
                queue("0902");
                queue("03");
                kick();
                main.postDelayed(NativeBleObd.this::pollCycle, 3000);
            }, 800);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null || v.length == 0) return;
            String chunk = new String(v, StandardCharsets.US_ASCII);
            log("BLE RX " + chunk.replace('\r', ' ').replace('\n', ' ').trim());
            synchronized (rx) {
                rx.append(chunk);
                String msg = rx.toString();
                if (msg.indexOf('>') >= 0 || msg.contains("OK") || msg.toUpperCase(Locale.US).contains("ELM")
                        || msg.toUpperCase(Locale.US).contains("41")) {
                    String out = msg;
                    rx.setLength(0);
                    busy.set(false);
                    if (out.toUpperCase(Locale.US).contains("4100") || out.toUpperCase(Locale.US).contains("41 00")) {
                        ecuLinked.set(true);
                        main.post(() -> NativeBleObd.this.status("ECU LIVE · BLE"));
                    }
                    handleResponse(out);
                    kick();
                }
            }
        }
    };

    private static BluetoothGattCharacteristic preferChar(
            java.util.List<BluetoothGattCharacteristic> list, boolean forWrite) {
        if (list == null || list.isEmpty()) return null;
        String[] prefer = {
                "fff2", "fff1", "fff3", "ff01", "ff02", "ff03",
                "6e400002", "6e400003",
                "ffe1", "ffe0", "feff", "0000fff1", "0000fff2"
        };
        for (String key : prefer) {
            for (BluetoothGattCharacteristic ch : list) {
                String u = ch.getUuid().toString().toLowerCase(java.util.Locale.US);
                if (u.contains(key)) return ch;
            }
        }
        return list.get(0);
    }

    private void queue(String cmd) { pending.add(cmd); }

    private void kick() {
        if (!running.get() || busy.get() || writeChar == null || gatt == null) return;
        String cmd = pending.poll();
        if (cmd == null) return;
        busy.set(true);
        writeChar.setValue((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        boolean ok = gatt.writeCharacteristic(writeChar);
        log(cmd + (ok ? " → …" : " → write fail"));
        if (!ok) busy.set(false);
        main.postDelayed(() -> {
            if (busy.get()) {
                busy.set(false);
                log(cmd + " → timeout");
                kick();
            }
        }, 2500);
    }

    private void pollCycle() {
        if (!running.get() || gatt == null) return;
        queue(CYCLE[cycleIdx % CYCLE.length]);
        cycleIdx++;
        kick();
        main.postDelayed(this::pollCycle, 900);
    }


    /** Flush ASCII ELM buffer for both SPP and GATT paths. */
    private void drainRx() {
        // SPP path: reader only accumulates; sppCommandWait consumes.
        // GATT path still processes on frame boundaries.
        if (useSpp) return;
        if (rx.indexOf(">") < 0 && rx.indexOf("\r") < 0 && rx.indexOf("\n") < 0) return;
        String msg = rx.toString();
        rx.setLength(0);
        busy.set(false);
        handleResponse(msg);
        kick();
    }

    private void handleResponse(String msg) {
        String compact = msg.replaceAll("\\s+", " ").trim();
        log(compact.length() > 90 ? compact.substring(0, 90) : compact);
        String upper = msg.toUpperCase(Locale.US);
        parsePid(upper, msg, "410C", 2, (b) -> reading.rpm = (b[0] * 256 + b[1]) / 4.0);
        parsePid(upper, msg, "410D", 1, (b) -> reading.kph = (double) b[0]);
        parsePid(upper, msg, "4105", 1, (b) -> reading.coolantC = b[0] - 40.0);
        parsePid(upper, msg, "4104", 1, (b) -> reading.loadPct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "4111", 1, (b) -> reading.throttlePct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "4142", 2, (b) -> reading.volts = (b[0] * 256 + b[1]) / 1000.0);
        parsePid(upper, msg, "410F", 1, (b) -> reading.iatC = b[0] - 40.0);
        parsePid(upper, msg, "410B", 1, (b) -> reading.mapKpa = (double) b[0]);
        parsePid(upper, msg, "4106", 1, (b) -> reading.stftB1 = (b[0] - 128) * 100.0 / 128.0);
        parsePid(upper, msg, "4107", 1, (b) -> reading.ltftB1 = (b[0] - 128) * 100.0 / 128.0);
        parsePid(upper, msg, "4108", 1, (b) -> reading.stftB2 = (b[0] - 128) * 100.0 / 128.0);
        parsePid(upper, msg, "4109", 1, (b) -> reading.ltftB2 = (b[0] - 128) * 100.0 / 128.0);
        parsePid(upper, msg, "410A", 1, (b) -> reading.fuelPressureKpa = b[0] * 3.0);
        parsePid(upper, msg, "410E", 1, (b) -> reading.timingDeg = b[0] / 2.0 - 64.0);
        parsePid(upper, msg, "4110", 2, (b) -> reading.mafGps = (b[0] * 256 + b[1]) / 100.0);
        parsePid(upper, msg, "411F", 2, (b) -> reading.runtimeSec = (double) (b[0] * 256 + b[1]));
        parsePid(upper, msg, "4121", 2, (b) -> reading.distMilKm = (double) (b[0] * 256 + b[1]));
        parsePid(upper, msg, "412F", 1, (b) -> reading.fuelLevelPct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "4133", 1, (b) -> reading.baroKpa = (double) b[0]);
        parsePid(upper, msg, "413C", 2, (b) -> reading.catB1S1C = (b[0] * 256 + b[1]) / 10.0 - 40.0);
        parsePid(upper, msg, "4143", 2, (b) -> reading.absLoadPct = (b[0] * 256 + b[1]) * 100.0 / 255.0);
        parsePid(upper, msg, "4144", 2, (b) -> reading.commandedAfr = (b[0] * 256 + b[1]) / 32768.0 * 14.7);
        parsePid(upper, msg, "4145", 1, (b) -> reading.relThrottlePct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "4146", 1, (b) -> reading.ambientC = b[0] - 40.0);
        parsePid(upper, msg, "4149", 1, (b) -> reading.accelPct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "4147", 1, (b) -> reading.relThrottlePct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "414A", 1, (b) -> reading.accelPct = b[0] * 100.0 / 255.0);
        parsePid(upper, msg, "412C", 1, (b) -> reading.raw.put("EGR_CMD", String.format(java.util.Locale.US, "%.1f%%", b[0] * 100.0 / 255.0)));
        parsePid(upper, msg, "412D", 1, (b) -> reading.raw.put("EGR_ERR", String.format(java.util.Locale.US, "%.1f%%", (b[0] - 128) * 100.0 / 128.0)));
        parsePid(upper, msg, "412E", 1, (b) -> reading.raw.put("PURGE", String.format(java.util.Locale.US, "%.1f%%", b[0] * 100.0 / 255.0)));
        parsePid(upper, msg, "4131", 2, (b) -> reading.raw.put("CLR_DIST", (b[0] * 256 + b[1]) + " km"));
        parsePid(upper, msg, "4114", 2, (b) -> reading.o2B1S1V = b[0] / 200.0);
        parsePid(upper, msg, "4115", 2, (b) -> reading.o2B1S2V = b[0] / 200.0);
        parsePid(upper, msg, "415C", 1, (b) -> reading.oilC = b[0] - 40.0);
        parsePid(upper, msg, "4152", 1, (b) -> reading.ethanolPct = b[0] * 100.0 / 255.0);
        // OBD standard
        if (upper.contains("411C")) {
            int[] b = bytesAfter(msg, "411C");
            if (b != null && b.length >= 1) reading.obdStd = obdStdName(b[0]);
        }
        if (upper.contains("4151")) {
            int[] b = bytesAfter(msg, "4151");
            if (b != null && b.length >= 1) reading.fuelType = fuelTypeName(b[0]);
        }
        if (upper.contains("4902")) {
            String hex = msg.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
            int at = hex.indexOf("4902");
            if (at >= 0) {
                StringBuilder vin = new StringBuilder();
                for (int i = at + 4; i + 1 < hex.length(); i += 2) {
                    int c = Integer.parseInt(hex.substring(i, i + 2), 16);
                    if (c >= 32 && c < 127) vin.append((char) c);
                }
                String v = vin.toString().replaceAll("[^A-Za-z0-9]", "");
                if (v.length() >= 8) reading.vin = v.substring(0, Math.min(17, v.length()));
            }
        }
        // Mode 03 / 07 DTCs
        if (upper.contains("43") || upper.contains("47")) {
            parseDtcs(msg, upper.contains("47") ? reading.pendingDtcs : reading.dtcs);
        }
        // stash compact raw
        String rawSnap = msg.replaceAll("\\s+", " ").trim();
        if (rawSnap.length() > 8 && rawSnap.length() < 80) {
            String key = rawSnap.length() >= 4 ? rawSnap.substring(0, Math.min(8, rawSnap.length())) : "rx";
            reading.raw.put(key, rawSnap.length() > 40 ? rawSnap.substring(0, 40) : rawSnap);
            while (reading.raw.size() > 24) {
                String first = reading.raw.keySet().iterator().next();
                reading.raw.remove(first);
            }
        }
        reading.updatedAt = System.currentTimeMillis();
        emitReading();
    }

    private interface BytesConsumer { void accept(int[] b); }

    private void parsePid(String upper, String msg, String key, int need, BytesConsumer c) {
        if (!upper.contains(key)) return;
        int[] b = bytesAfter(msg, key);
        if (b != null && b.length >= need) c.accept(b);
    }

    private int[] bytesAfter(String msg, String key) {
        String hex = msg.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        int at = hex.indexOf(key);
        if (at < 0) return null;
        String rest = hex.substring(at + key.length());
        int n = Math.min(4, rest.length() / 2);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16);
        return out;
    }


    private static String obdStdName(int v) {
        switch (v) {
            case 1: return "OBD-II (CARB)";
            case 2: return "OBD (EPA)";
            case 3: return "OBD + OBD-II";
            case 6: return "EOBD";
            case 7: return "EOBD + OBD-II";
            default: return "std=" + v;
        }
    }

    private static String fuelTypeName(int v) {
        switch (v) {
            case 1: return "Gasoline";
            case 4: return "Diesel";
            case 16: return "Hybrid gasoline";
            default: return "type=" + v;
        }
    }

    private void parseDtcs(String msg, List<String> into) {
        if (into == null) return;
        String hex = msg.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        int at = hex.indexOf("43");
        if (at < 0) at = hex.indexOf("47");
        if (at < 0) return;
        String rest = hex.substring(at + 2);
        if (rest.length() >= 2) {
            try {
                int count = Integer.parseInt(rest.substring(0, 2), 16);
                if (count <= 16) rest = rest.substring(2);
            } catch (Exception ignored) {}
        }
        into.clear();
        for (int i = 0; i + 3 < rest.length() && into.size() < 8; i += 4) {
            try {
                int a = Integer.parseInt(rest.substring(i, i + 2), 16);
                int b = Integer.parseInt(rest.substring(i + 2, i + 4), 16);
                if (a == 0 && b == 0) continue;
                char sys = "PCBU".charAt((a >> 6) & 3);
                int d1 = (a >> 4) & 3;
                int d2 = a & 0xF;
                int d3 = (b >> 4) & 0xF;
                int d4 = b & 0xF;
                into.add(String.format(Locale.US, "%c%d%X%X%X", sys, d1, d2, d3, d4));
            } catch (Exception ignored) {}
        }
    }

    private void emitReading() {
        final Reading copy = new Reading();
        copy.rpm = reading.rpm; copy.kph = reading.kph; copy.coolantC = reading.coolantC;
        copy.loadPct = reading.loadPct; copy.throttlePct = reading.throttlePct;
        copy.volts = reading.volts; copy.iatC = reading.iatC; copy.mapKpa = reading.mapKpa;
        copy.stftB1 = reading.stftB1; copy.ltftB1 = reading.ltftB1;
        copy.stftB2 = reading.stftB2; copy.ltftB2 = reading.ltftB2;
        copy.fuelPressureKpa = reading.fuelPressureKpa; copy.timingDeg = reading.timingDeg;
        copy.mafGps = reading.mafGps; copy.runtimeSec = reading.runtimeSec;
        copy.distMilKm = reading.distMilKm; copy.fuelLevelPct = reading.fuelLevelPct;
        copy.baroKpa = reading.baroKpa; copy.ambientC = reading.ambientC;
        copy.absLoadPct = reading.absLoadPct; copy.commandedAfr = reading.commandedAfr;
        copy.relThrottlePct = reading.relThrottlePct; copy.accelPct = reading.accelPct;
        copy.catB1S1C = reading.catB1S1C; copy.o2B1S1V = reading.o2B1S1V; copy.o2B1S2V = reading.o2B1S2V;
        copy.oilC = reading.oilC; copy.ethanolPct = reading.ethanolPct;
        copy.vin = reading.vin; copy.calId = reading.calId; copy.ecuName = reading.ecuName;
        copy.fuelType = reading.fuelType; copy.obdStd = reading.obdStd;
        copy.protocol = reading.protocol != null ? reading.protocol : "ATSP6/CAN";
        copy.dtcs.addAll(reading.dtcs);
        copy.pendingDtcs.addAll(reading.pendingDtcs);
        copy.raw.putAll(reading.raw);
        copy.updatedAt = reading.updatedAt;
        main.post(() -> { if (listener != null) listener.onReading(copy); });
    }

    private final StringBuilder logBuf = new StringBuilder();

    private void status(String s) {
        main.post(() -> {
            if (listener != null) listener.onStatus(s);
        });
    }

    private void log(String s) {
        if (s == null) return;
        synchronized (logBuf) {
            logBuf.append(s);
            logBuf.append('\n');
            if (logBuf.length() > 2200) {
                logBuf.delete(0, logBuf.length() - 1800);
            }
            final String snap = logBuf.toString();
            main.post(() -> {
                if (listener != null) listener.onLog(snap);
            });
        }
    }

}
