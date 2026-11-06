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
        public String vin;
        public final List<String> dtcs = new ArrayList<>();
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] CYCLE = {
            "010C", "010D", "0105", "0104", "0111", "0142", "010F", "010B"
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
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private int cycleIdx;
    private final Reading reading = new Reading();
    private final List<String> logLines = new ArrayList<>();

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

    private static boolean isObdSource(String source) {
        return source != null && source.contains("uuid-obd");
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

        // CarPal / classic dual-mode: try SPP first (GATT almost always drops on these)
        boolean preferSpp = type == BluetoothDevice.DEVICE_TYPE_CLASSIC
                || type == BluetoothDevice.DEVICE_TYPE_DUAL
                || type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
                || isObdName(label);
        if (preferSpp) {
            status("Connecting SPP (classic serial) " + label + "...");
            new Thread(() -> {
                if (connectSpp(device, label)) return;
                if (!running.get()) return;
                status("SPP failed — trying BLE GATT...");
                main.post(() -> connectGatt(device, label));
            }, "aether-spp").start();
        } else {
            status("Connecting BLE GATT " + label + "...");
            connectGatt(device, label);
        }
    }

    private void connectGatt(BluetoothDevice device, String label) {
        try {
            closeSppQuiet();
            if (gatt != null) {
                try { gatt.close(); } catch (Exception ignored) {}
            }
            int transport = BluetoothDevice.TRANSPORT_AUTO;
            try {
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                    transport = BluetoothDevice.TRANSPORT_LE;
                }
            } catch (Exception ignored) {}
            useSpp = false;
            gatt = device.connectGatt(app, false, gattCb, transport);
        } catch (Exception e) {
            status("GATT failed: " + e.getMessage());
            // last resort SPP
            new Thread(() -> connectSpp(device, label), "aether-spp2").start();
        }
    }

    /** Classic RFCOMM Serial Port Profile — real ELM327 / CarPal path. */
    private boolean connectSpp(BluetoothDevice device, String label) {
        closeSppQuiet();
        try {
            BluetoothSocket sock = null;
            try {
                sock = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (Exception e) {
                log("createRfcommSocketToServiceRecord: " + e.getMessage());
            }
            if (sock == null) {
                try {
                    // Fallback channel 1 (common for ELM clones)
                    java.lang.reflect.Method m = device.getClass()
                            .getMethod("createRfcommSocket", int.class);
                    sock = (BluetoothSocket) m.invoke(device, 1);
                } catch (Exception e) {
                    log("reflect RFCOMM: " + e.getMessage());
                }
            }
            if (sock == null) {
                main.post(() -> status("No RFCOMM socket — SPP unsupported"));
                return false;
            }
            sock.connect();
            sppSocket = sock;
            sppOut = sock.getOutputStream();
            sppIn = sock.getInputStream();
            useSpp = true;
            running.set(true);
            main.post(() -> status("SPP linked — ELM init..."));
            log("SPP connected " + label);
            initElmSpp();
            startSppReader();
            startSppPoller();
            return true;
        } catch (Exception e) {
            log("SPP connect fail: " + e.getMessage());
            closeSppQuiet();
            main.post(() -> status("SPP failed: " + e.getMessage()));
            return false;
        }
    }

    private void initElmSpp() {
        // Standard ELM327 wake sequence
        String[] boot = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"};
        for (String cmd : boot) {
            sppWrite(cmd);
            try { Thread.sleep(cmd.equals("ATZ") ? 1500 : 400); } catch (InterruptedException ignored) {}
        }
        sppWrite("0902"); // VIN
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        sppWrite("03"); // DTCs
    }

    private void startSppPoller() {
        main.post(new Runnable() {
            @Override public void run() {
                if (!running.get() || !useSpp) return;
                if (cycleIdx >= CYCLE.length) cycleIdx = 0;
                sppWrite(CYCLE[cycleIdx++]);
                main.postDelayed(this, 350);
            }
        });
    }

    private void sppWrite(String cmd) {
        if (sppOut == null || cmd == null) return;
        try {
            String line = cmd.trim() + "\r";
            sppOut.write(line.getBytes(StandardCharsets.US_ASCII));
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
        }
        sppReader = new Thread(() -> {
            byte[] buf = new byte[512];
            while (running.get() && useSpp && sppIn != null) {
                try {
                    int n = sppIn.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    String chunk = new String(buf, 0, n, StandardCharsets.US_ASCII);
                    synchronized (rx) {
                        rx.append(chunk);
                        drainRx();
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log("SPP read end: " + e.getMessage());
                        main.post(() -> status("Disconnected from adapter (SPP)"));
                    }
                    break;
                }
            }
        }, "aether-spp-rx");
        sppReader.start();
    }

    private void closeSppQuiet() {
        useSpp = false;
        try { if (sppReader != null) sppReader.interrupt(); } catch (Exception ignored) {}
        sppReader = null;
        try { if (sppIn != null) sppIn.close(); } catch (Exception ignored) {}
        try { if (sppOut != null) sppOut.close(); } catch (Exception ignored) {}
        try { if (sppSocket != null) sppSocket.close(); } catch (Exception ignored) {}
        sppIn = null;
        sppOut = null;
        sppSocket = null;
    }

    public void disconnect() {
        running.set(false);
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        closeSppQuiet();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        writeChar = null;
        notifyChar = null;
        status("Disconnected");
    }


    private static boolean isObdName(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.US);
        return n.contains("OBD") || n.contains("ELM") || n.contains("TOPDON")
                || n.contains("CARPAL") || n.contains("CAR PAL") || n.contains("CAR-PAL")
                || n.contains("V-LINK") || n.contains("VLINK") || n.contains("OBDII")
                || n.contains("OBD2") || n.contains("KIWI") || n.contains("LELINK")
                || n.contains("PLX") || n.contains("DONGLE") || n.contains("DIAG")
                || n.contains("ICAR") || n.contains("VGATE") || n.contains("VEEPEAK")
                || n.contains("BAFX") || n.contains("KONNWEI") || n.contains("MUCAR")
                || n.contains("THINKCAR") || n.contains("FIXD") || n.contains("AUTOPHIX")
                || n.contains("SPP") || n.contains("SERIAL") || n.contains("HC-05")
                || n.contains("HC-06") || n.contains("BLEOBD") || n.contains("OBDBLE");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = safeName(d);
            String source = "ble";
            if (result.getScanRecord() != null) {
                if (name == null || name.trim().isEmpty()) {
                    name = result.getScanRecord().getDeviceName();
                }
                // Common OBD/ELM/serial service UUIDs in advertisement
                java.util.List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid u : uuids) {
                        if (u == null) continue;
                        String s = u.toString().toLowerCase(Locale.US);
                        if (s.contains("fff0") || s.contains("ffe0") || s.contains("fff1")
                                || s.contains("1101") || s.contains("18f0")
                                || s.startsWith("0000fff") || s.startsWith("0000ffe")) {
                            source = "uuid-obd";
                            break;
                        }
                    }
                }
            }
            addFound(d, name, source);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) onScanResult(0, r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("BLE scan failed code " + errorCode);
            status("BLE fail " + errorCode + " - still using paired/classic");
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Linked — discovering services…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected status=" + status);
                if (running.get() && lastDevice != null && !useSpp) {
                    main.post(() -> status("GATT dropped — trying classic SPP..."));
                    final BluetoothDevice d = lastDevice;
                    final String lab = lastLabel != null ? lastLabel : "adapter";
                    new Thread(() -> {
                        if (!connectSpp(d, lab)) {
                            main.post(() -> status("Disconnected from adapter"));
                            running.set(false);
                        }
                    }, "aether-spp-fallback").start();
                } else {
                    main.post(() -> status("Disconnected from adapter"));
                    running.set(false);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            writeChar = null;
            notifyChar = null;
            for (BluetoothGattService svc : g.getServices()) {
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int p = ch.getProperties();
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writeChar = ch;
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        notifyChar = ch;
                    }
                }
            }
            if (writeChar == null) {
                status("No serial write characteristic — may not be ELM mode");
                log("Services found but no writable UART char");
                return;
            }
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            status("OBD UART up — AT init");
            main.postDelayed(() -> {
                queue("ATZ");
                queue("ATE0");
                queue("ATL0");
                queue("ATS0");
                queue("ATH0");
                queue("ATSP0");
                queue("0902");
                queue("03");
                kick();
                main.postDelayed(NativeBleObd.this::pollCycle, 2500);
            }, 500);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null) return;
            rx.append(new String(v, StandardCharsets.US_ASCII));
            if (rx.indexOf(">") >= 0 || rx.indexOf("\r") >= 0) {
                String msg = rx.toString();
                rx.setLength(0);
                busy.set(false);
                handleResponse(msg);
                kick();
            }
        }
    };

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
        if (rx.indexOf(">") < 0 && rx.indexOf("\r") < 0 && rx.indexOf("\n") < 0) return;
        String msg = rx.toString();
        rx.setLength(0);
        busy.set(false);
        handleResponse(msg);
        if (!useSpp) kick();
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

    private void emitReading() {
        final Reading copy = new Reading();
        copy.rpm = reading.rpm; copy.kph = reading.kph; copy.coolantC = reading.coolantC;
        copy.loadPct = reading.loadPct; copy.throttlePct = reading.throttlePct;
        copy.volts = reading.volts; copy.iatC = reading.iatC; copy.mapKpa = reading.mapKpa;
        copy.vin = reading.vin; copy.dtcs.addAll(reading.dtcs);
        main.post(() -> { if (listener != null) listener.onReading(copy); });
    }

    private void status(String s) {
        main.post(() -> { if (listener != null) listener.onStatus(s); });
    }

    private void log(String s) {
        synchronized (logLines) {
            logLines.add(s);
            while (logLines.size() > 24) logLines.remove(0);
        }
        final String joined;
        synchronized (logLines) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            joined = sb.toString().trim();
        }
        main.post(() -> { if (listener != null) listener.onLog(joined); });
    }
}
