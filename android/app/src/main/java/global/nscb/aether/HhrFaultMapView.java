package global.nscb.aether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive top-down HHR map — tap parts for exact fault + location.
 */
public final class HhrFaultMapView extends View {

    public interface Listener {
        void onPartSelected(String partId, String title, List<String> faults);
    }

    public static final class Part {
        public final String id;
        public final String title;
        public final float nx, ny, nw, nh; // normalized 0-1 within car bounds
        public final RectF hit = new RectF();
        public Part(String id, String title, float nx, float ny, float nw, float nh) {
            this.id = id; this.title = title;
            this.nx = nx; this.ny = ny; this.nw = nw; this.nh = nh;
        }
    }

    public static final class FaultPin {
        public final String code;
        public final String partId;
        public final String partTitle;
        public final String detail;
        public FaultPin(String code, String partId, String partTitle, String detail) {
            this.code = code;
            this.partId = partId;
            this.partTitle = partTitle;
            this.detail = detail;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<FaultPin> pins = new ArrayList<>();
    private final Map<String, List<FaultPin>> byPart = new HashMap<>();
    private final Set<String> hot = new HashSet<>();
    private String selectedId;
    private float pulse;
    private Listener listener;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float carLeft, carTop, carW, carH;

    public HhrFaultMapView(Context c) { super(c); init(); }
    public HhrFaultMapView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        body.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        zonePaint.setStyle(Paint.Style.FILL);
        pinPaint.setStyle(Paint.Style.FILL);
        glow.setStyle(Paint.Style.FILL);
        panel.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.MONOSPACE);
        setClickable(true);
        setWillNotDraw(false);
        buildParts();
    }

    public void setListener(Listener l) { this.listener = l; }

    private void buildParts() {
        parts.clear();
        // Front is top of screen — HHR top-down layout
        parts.add(new Part("rad", "Radiator / cooling", 0.35f, 0.02f, 0.30f, 0.06f));
        parts.add(new Part("eng", "Engine block", 0.28f, 0.09f, 0.44f, 0.14f));
        parts.add(new Part("cyl1", "Cylinder 1", 0.30f, 0.10f, 0.10f, 0.06f));
        parts.add(new Part("cyl2", "Cylinder 2", 0.40f, 0.10f, 0.10f, 0.06f));
        parts.add(new Part("cyl3", "Cylinder 3", 0.50f, 0.10f, 0.10f, 0.06f));
        parts.add(new Part("cyl4", "Cylinder 4", 0.60f, 0.10f, 0.10f, 0.06f));
        parts.add(new Part("ign", "Ignition / coils", 0.68f, 0.09f, 0.14f, 0.10f));
        parts.add(new Part("fuel", "Fuel rail / injectors", 0.18f, 0.10f, 0.12f, 0.12f));
        parts.add(new Part("maf", "MAF / intake air", 0.22f, 0.22f, 0.16f, 0.07f));
        parts.add(new Part("tps", "Throttle body", 0.42f, 0.22f, 0.16f, 0.07f));
        parts.add(new Part("map", "MAP sensor", 0.60f, 0.22f, 0.14f, 0.07f));
        parts.add(new Part("o2b1s1", "O2 sensor B1S1 (pre-cat)", 0.18f, 0.32f, 0.18f, 0.07f));
        parts.add(new Part("o2b1s2", "O2 sensor B1S2 (post-cat)", 0.18f, 0.40f, 0.18f, 0.07f));
        parts.add(new Part("cat", "Catalytic converter", 0.40f, 0.34f, 0.20f, 0.10f));
        parts.add(new Part("evap", "EVAP canister / purge", 0.64f, 0.34f, 0.18f, 0.10f));
        parts.add(new Part("ecm", "ECM / PCM", 0.78f, 0.20f, 0.14f, 0.10f));
        parts.add(new Part("trans", "Transmission", 0.30f, 0.52f, 0.40f, 0.14f));
        parts.add(new Part("abs", "ABS module / wheel speed", 0.28f, 0.82f, 0.44f, 0.10f));
        parts.add(new Part("body", "Body control module", 0.08f, 0.48f, 0.12f, 0.12f));
        parts.add(new Part("net", "GMLAN / network bus", 0.42f, 0.48f, 0.16f, 0.06f));
        parts.add(new Part("batt", "Battery / charging", 0.78f, 0.08f, 0.14f, 0.08f));
    }

    public void setFaults(List<String> dtcs) {
        pins.clear();
        byPart.clear();
        hot.clear();
        if (dtcs != null) {
            for (String d : dtcs) {
                if (d == null || d.length() < 2) continue;
                FaultPin p = mapDtc(d.trim().toUpperCase(Locale.US));
                pins.add(p);
                hot.add(p.partId);
                List<FaultPin> list = byPart.get(p.partId);
                if (list == null) {
                    list = new ArrayList<>();
                    byPart.put(p.partId, list);
                }
                list.add(p);
            }
        }
        if (selectedId != null && !hot.contains(selectedId) && byPart.get(selectedId) == null) {
            // keep selection for browsing cold parts
        }
        invalidate();
    }

    public void setPulse(float t) {
        pulse = t;
        invalidate();
    }

    /** Exact DTC → part + human detail for 2010 GM / HHR family. */
    public static FaultPin mapDtc(String code) {
        char sys = code.charAt(0);
        int num = 0;
        try {
            if (code.length() >= 5) num = Integer.parseInt(code.substring(1));
        } catch (Exception ignored) {}

        if (sys == 'C') {
            String pid = "abs";
            String title = "ABS module";
            if (num == 35 || num == 50) { pid = "abs_lf"; title = "LF wheel speed"; }
            else if (num == 40 || num == 55) { pid = "abs_rf"; title = "RF wheel speed"; }
            else if (num == 45 || num == 60) { pid = "abs_lr"; title = "LR wheel speed"; }
            else if (num == 50 || num == 65) { pid = "abs_rr"; title = "RR wheel speed"; }
            return new FaultPin(code, pid, title, exactC(num, code));
        }
        if (sys == 'B') {
            return new FaultPin(code, "body", "Body control module",
                    "Body control fault " + code + " — doors, lighting, BCM circuit.");
        }
        if (sys == 'U') {
            return new FaultPin(code, "net", "GMLAN / network bus",
                    "Network/bus fault " + code + " — lost communication on GMLAN.");
        }

        // Cylinder-specific misfires P0301-P0304
        if (num >= 301 && num <= 304) {
            int cyl = num - 300;
            return new FaultPin(code, "cyl" + cyl, "Cylinder " + cyl,
                    "Misfire cylinder " + cyl + " — coil, plug, injector, or compression.");
        }
        if (num == 300) {
            return new FaultPin(code, "ign", "Ignition / coils",
                    "Random/multiple misfire — fuel quality, coils, vacuum leak.");
        }
        if (num >= 305 && num < 400) {
            return new FaultPin(code, "ign", "Ignition / coils",
                    "Ignition system " + code + " — coil/module circuit.");
        }
        if (num == 101 || num == 102) {
            return new FaultPin(code, "maf", "MAF / intake air",
                    "MAF circuit " + code + " — airflow sensor or wiring.");
        }
        if (num == 106 || num == 107) {
            return new FaultPin(code, "map", "MAP sensor",
                    "MAP circuit " + code + " — manifold pressure sensor.");
        }
        if (num == 110 || num == 111) {
            return new FaultPin(code, "iat", "IAT sensor",
                    "IAT circuit " + code + " — intake air temperature sensor.");
        }
        if (num == 112 || num == 113) {
            return new FaultPin(code, "ect", "Coolant temp (ECT)",
                    "Coolant temp circuit " + code + " — ECT sensor.");
        }
        if (num == 117 || num == 118) {
            return new FaultPin(code, "eng", "Engine block",
                    "Coolant temp " + code + " — ECT range/performance.");
        }
        if (num >= 120 && num <= 123) {
            return new FaultPin(code, "tps", "Throttle body",
                    "Throttle/pedal " + code + " — TPS or electronic throttle.");
        }
        if (num >= 130 && num <= 135) {
            return new FaultPin(code, "o2b1s1", "O2 sensor B1S1 (pre-cat)",
                    "O2 bank1 sensor1 " + code + " — upstream AFR/O2.");
        }
        if (num >= 136 && num <= 141) {
            return new FaultPin(code, "o2b1s2", "O2 sensor B1S2 (post-cat)",
                    "O2 bank1 sensor2 " + code + " — downstream O2.");
        }
        if (num >= 171 && num <= 174) {
            return new FaultPin(code, "fuel", "Fuel rail / injectors",
                    "Fuel trim " + code + " — vacuum leak, injectors, MAF, exhaust leak.");
        }
        if (num >= 200 && num < 300) {
            return new FaultPin(code, "fuel", "Fuel rail / injectors",
                    "Injector/fuel " + code + " — cylinder fuel delivery.");
        }
        if (num >= 420 && num <= 430) {
            return new FaultPin(code, "cat", "Catalytic converter",
                    "Catalyst efficiency " + code + " — converter or O2 skew.");
        }
        if (num >= 440 && num < 460) {
            return new FaultPin(code, "evap", "EVAP canister / purge",
                    "EVAP " + code + " — purge valve, canister, gas cap, leak.");
        }
        if (num >= 400 && num < 430) {
            return new FaultPin(code, "egr", "EGR valve",
                    "EGR " + code + " — valve or position sensor.");
        }
        if (num >= 430 && num < 440) {
            return new FaultPin(code, "evap", "EVAP canister / purge",
                    "Emission control " + code + " — EVAP related.");
        }
        if (num >= 500 && num < 560) {
            return new FaultPin(code, "tps", "Throttle body",
                    "Idle/speed " + code + " — IAC/ETB or load fault.");
        }
        if (num >= 560 && num < 600) {
            return new FaultPin(code, "batt", "Battery / charging",
                    "System voltage " + code + " — alternator/battery circuit.");
        }
        if (num >= 600 && num < 700) {
            return new FaultPin(code, "ecm", "ECM / PCM",
                    "Control module " + code + " — PCM internal or keep-alive.");
        }
        if (num >= 700 && num < 800) {
            return new FaultPin(code, "trans", "Transmission",
                    "Transmission " + code + " — shift solenoid, ratio, TCC.");
        }
        if (num >= 100 && num < 200) {
            return new FaultPin(code, "eng", "Engine block",
                    "Fuel/air metering " + code + " — sensor or wiring.");
        }
        return new FaultPin(code, "eng", "Engine block",
                "Powertrain fault " + code + " — see full diagnostic dump.");
    }

    private static String exactC(int num, String code) {
        if (num == 35 || num == 35) return "LF wheel speed " + code;
        return "Chassis/ABS fault " + code;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX();
            float y = e.getY();
            // reverse iterate — smaller parts on top of engine
            for (int i = parts.size() - 1; i >= 0; i--) {
                Part p = parts.get(i);
                if (p.hit.contains(x, y)) {
                    selectedId = p.id;
                    invalidate();
                    if (listener != null) {
                        List<String> lines = new ArrayList<>();
                        List<FaultPin> fp = byPart.get(p.id);
                        if (fp != null) {
                            for (FaultPin f : fp) {
                                lines.add(f.code + " — " + f.detail);
                            }
                        }
                        listener.onPartSelected(p.id, p.title, lines);
                    }
                    return true;
                }
            }
            selectedId = null;
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w < 10 || h < 10) return;
        c.drawColor(0xFF0A0A08);

        float cx = w * 0.5f;
        float cy = h * 0.48f;
        carW = w * 0.50f;
        carH = h * 0.62f;
        carLeft = cx - carW / 2f;
        carTop = cy - carH / 2f;
        float carRight = carLeft + carW;
        float carBot = carTop + carH;

        Path car = new Path();
        car.moveTo(cx - carW * 0.28f, carTop);
        car.lineTo(cx + carW * 0.28f, carTop);
        car.quadTo(carRight, carTop + carH * 0.08f, carRight, carTop + carH * 0.16f);
        car.lineTo(carRight, carBot - carH * 0.10f);
        car.quadTo(carRight, carBot, cx + carW * 0.28f, carBot);
        car.lineTo(cx - carW * 0.28f, carBot);
        car.quadTo(carLeft, carBot, carLeft, carBot - carH * 0.10f);
        car.lineTo(carLeft, carTop + carH * 0.16f);
        car.quadTo(carLeft, carTop + carH * 0.08f, cx - carW * 0.28f, carTop);
        car.close();

        body.setShader(new LinearGradient(carLeft, carTop, carRight, carBot,
                0xFF1C1A16, 0xFF0C0B09, Shader.TileMode.CLAMP));
        c.drawPath(car, body);
        stroke.setStrokeWidth(3f);
        stroke.setColor(0xFF8F8774);
        c.drawPath(car, stroke);

        // cabin
        zonePaint.setColor(0x3322AACC);
        c.drawRoundRect(new RectF(cx - carW * 0.26f, carTop + carH * 0.30f,
                cx + carW * 0.26f, carTop + carH * 0.50f), 14, 14, zonePaint);

        // wheels
        zonePaint.setColor(0xFF2A2824);
        float wr = carW * 0.065f;
        c.drawCircle(carLeft - 2, carTop + carH * 0.20f, wr, zonePaint);
        c.drawCircle(carRight + 2, carTop + carH * 0.20f, wr, zonePaint);
        c.drawCircle(carLeft - 2, carBot - carH * 0.16f, wr, zonePaint);
        c.drawCircle(carRight + 2, carBot - carH * 0.16f, wr, zonePaint);

        for (Part p : parts) {
            float l = carLeft + p.nx * carW;
            float t = carTop + p.ny * carH;
            float r = l + p.nw * carW;
            float b = t + p.nh * carH;
            p.hit.set(l, t, r, b);
            boolean on = hot.contains(p.id);
            boolean sel = p.id.equals(selectedId);
            float inflate = on ? 1f + 0.04f * (float) Math.sin(pulse * Math.PI * 2) : 1f;
            float cxz = (l + r) / 2f;
            float cyz = (t + b) / 2f;
            float bw = (r - l) * inflate;
            float bh = (b - t) * inflate;
            RectF rr = new RectF(cxz - bw / 2, cyz - bh / 2, cxz + bw / 2, cyz + bh / 2);

            if (on) {
                int col = 0xFFE0452A;
                if (p.id.startsWith("cyl")) col = 0xFFFF4444;
                else if (p.id.startsWith("o2")) col = 0xFF33CCAA;
                else if (p.id.equals("evap")) col = 0xFFCC66FF;
                else if (p.id.equals("trans")) col = 0xFF4488FF;
                else if (p.id.equals("abs")) col = 0xFFFF2266;
                else if (p.id.equals("fuel")) col = 0xFFFF8800;
                else if (p.id.equals("ecm")) col = 0xFFFFEE44;
                glow.setShader(new RadialGradient(cxz, cyz, Math.max(bw, bh),
                        (col & 0x00FFFFFF) | 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
                c.drawCircle(cxz, cyz, Math.max(bw, bh) * 0.9f, glow);
                glow.setShader(null);
                zonePaint.setColor((col & 0x00FFFFFF) | 0xBB000000);
                c.drawRoundRect(rr, 10, 10, zonePaint);
                stroke.setColor(col);
                stroke.setStrokeWidth(sel ? 3.5f : 2f);
                c.drawRoundRect(rr, 10, 10, stroke);
                // fault count badge
                List<FaultPin> fp = byPart.get(p.id);
                int n = fp != null ? fp.size() : 0;
                pinPaint.setColor(0xFFFFF6E8);
                c.drawCircle(rr.right - 6, rr.top + 6, 12, pinPaint);
                text.setColor(0xFF1A0800);
                text.setTextSize(18f);
                String ns = String.valueOf(n);
                c.drawText(ns, rr.right - 6 - text.measureText(ns) / 2f, rr.top + 12, text);
            } else {
                zonePaint.setColor(sel ? 0x44F0A202 : 0x18F0A202);
                c.drawRoundRect(rr, 10, 10, zonePaint);
                stroke.setColor(sel ? 0xFFF0A202 : 0x338F8774);
                stroke.setStrokeWidth(sel ? 2.5f : 1.2f);
                c.drawRoundRect(rr, 10, 10, stroke);
            }
            text.setTextSize(Math.min(20f, bh * 0.45f));
            text.setColor(on ? 0xFFFFF6E8 : (sel ? 0xFFF0A202 : 0x668F8774));
            String lab = shortLabel(p.id);
            float tw = text.measureText(lab);
            if (tw < bw - 4) {
                c.drawText(lab, cxz - tw / 2f, cyz + text.getTextSize() * 0.35f, text);
            }
        }

        // header
        text.setTextSize(26f);
        text.setColor(0xFFF0A202);
        c.drawText("HHR INTERACTIVE FAULT MAP", 20, 36, text);
        text.setTextSize(18f);
        text.setColor(0xFF8F8774);
        c.drawText("Tap a part · " + (pins.isEmpty() ? "no DTCs" : pins.size() + " fault(s)"), 20, 60, text);

        // detail card
        drawDetailCard(c, w, h);
    }

    private void drawDetailCard(Canvas c, float w, float h) {
        float cardTop = h * 0.78f;
        panel.setColor(0xEE14120E);
        c.drawRoundRect(new RectF(12, cardTop, w - 12, h - 10), 16, 16, panel);
        stroke.setColor(0xFFF0A202);
        stroke.setStrokeWidth(1.5f);
        c.drawRoundRect(new RectF(12, cardTop, w - 12, h - 10), 16, 16, stroke);

        text.setTextSize(22f);
        text.setColor(0xFFF0A202);
        if (selectedId == null) {
            text.setColor(0xFF8F8774);
            text.setTextSize(20f);
            c.drawText("Select a part on the vehicle", 28, cardTop + 36, text);
            if (!pins.isEmpty()) {
                text.setTextSize(18f);
                float y = cardTop + 64;
                for (int i = 0; i < Math.min(3, pins.size()); i++) {
                    FaultPin f = pins.get(i);
                    text.setColor(0xFFE0452A);
                    c.drawText(f.code, 28, y, text);
                    text.setColor(0xFFC8B89A);
                    c.drawText(f.partTitle, 120, y, text);
                    y += 24;
                }
            }
            return;
        }
        Part part = null;
        for (Part p : parts) if (p.id.equals(selectedId)) { part = p; break; }
        String title = part != null ? part.title : selectedId;
        c.drawText(title, 28, cardTop + 32, text);
        text.setTextSize(18f);
        text.setColor(0xFF8F8774);
        c.drawText("Location: " + locationHint(selectedId), 28, cardTop + 54, text);

        List<FaultPin> fp = byPart.get(selectedId);
        float y = cardTop + 80;
        if (fp == null || fp.isEmpty()) {
            text.setColor(0xFF8F8774);
            c.drawText("No DTCs on this part", 28, y, text);
        } else {
            for (FaultPin f : fp) {
                if (y > h - 20) break;
                text.setColor(0xFFE0452A);
                text.setTextSize(20f);
                c.drawText(f.code, 28, y, text);
                text.setColor(0xFFE8E0D0);
                text.setTextSize(16f);
                // wrap detail
                String d = f.detail;
                if (d.length() > 48) d = d.substring(0, 48) + "…";
                c.drawText(d, 28, y + 20, text);
                y += 44;
            }
        }
    }

    private static String shortLabel(String id) {
        switch (id) {
            case "cyl1": return "C1";
            case "cyl2": return "C2";
            case "cyl3": return "C3";
            case "cyl4": return "C4";
            case "o2b1s1": return "O2↑";
            case "o2b1s2": return "O2↓";
            case "eng": return "ENG";
            case "fuel": return "FUEL";
            case "ign": return "IGN";
            case "trans": return "TRANS";
            case "abs": return "ABS";
            case "evap": return "EVAP";
            case "cat": return "CAT";
            case "ecm": return "ECM";
            case "maf": return "MAF";
            case "map": return "MAP";
            case "tps": return "TPS";
            case "body": return "BCM";
            case "net": return "BUS";
            case "batt": return "BATT";
            case "rad": return "RAD";
            default: return id;
        }
    }

    private static String locationHint(String id) {
        switch (id) {
            case "cyl1": return "Front bank, cylinder 1 (passenger side GM transverse)";
            case "cyl2": return "Cylinder 2";
            case "cyl3": return "Cylinder 3";
            case "cyl4": return "Cylinder 4 (driver side on transverse 2.2/2.4)";
            case "eng": return "Engine bay — center front";
            case "fuel": return "Fuel rail on intake";
            case "ign": return "Coil-near-plug packs on valve cover";
            case "o2b1s1": return "Exhaust manifold / pre-catalyst";
            case "o2b1s2": return "Post-catalyst exhaust";
            case "cat": return "Underbody catalyst";
            case "evap": return "Canister / purge near tank or bay";
            case "trans": return "Transaxle under powertrain";
            case "abs": return "ABS module — typically LF frame / underhood";
            case "ecm": return "PCM in engine bay";
            case "maf": return "Intake tube after airbox";
            case "tps": return "Throttle body on intake";
            case "map": return "Intake manifold";
            case "body": return "BCM interior";
            case "net": return "GMLAN backbone";
            case "batt": return "Battery tray";
            case "rad": return "Front of bay";
            default: return id;
        }
    }
}
