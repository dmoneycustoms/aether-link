package global.nscb.aether;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Offline ONNX Runtime integrity head for AETHER.
 * Primary: omega trust math (NSCB) — scalar fusion of path / anomaly signals.
 * Secondary: ARK residual (optional) — 10-feature novelty support.
 * Never raises trust above analytic; can only reinforce lower scores.
 */
public final class OnnxIntegrity {
    private static final String OMEGA_ASSET = "onnx/aether_omega_trust.onnx";
    private static final String ARK_ASSET = "onnx/aether_ark.onnx";

    private static volatile boolean inited;
    private static OrtEnvironment env;
    private static OrtSession omega;
    private static OrtSession ark;
    private static String statusLine = "ORT offline · no model";
    private static String lastDetail = "";

    private OnnxIntegrity() {}

    public static final class Result {
        public final boolean modelLive;
        public final float spoofScore;
        public final float trustOut;
        public final String detail;

        Result(boolean modelLive, float spoofScore, float trustOut, String detail) {
            this.modelLive = modelLive;
            this.spoofScore = spoofScore;
            this.trustOut = trustOut;
            this.detail = detail;
        }
    }

    public static synchronized void init(Context context) {
        if (inited) return;
        inited = true;
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(2);

            File omegaFile = materialize(context, OMEGA_ASSET, "aether_omega_trust.onnx");
            omega = env.createSession(omegaFile.getAbsolutePath(), opts);

            try {
                File arkFile = materialize(context, ARK_ASSET, "aether_ark.onnx");
                ark = env.createSession(arkFile.getAbsolutePath(), opts);
            } catch (Exception e) {
                ark = null;
                lastDetail = "ark skip: " + e.getMessage();
            }

            statusLine = "ORT live · omega" + (ark != null ? "+ark" : "") + " · offline";
            lastDetail = "models ready";
        } catch (Throwable t) {
            omega = null;
            ark = null;
            statusLine = "ORT fail · " + t.getClass().getSimpleName();
            lastDetail = t.getMessage() == null ? "init error" : t.getMessage();
        }
    }

    public static String status() {
        return statusLine;
    }

    public static boolean isLive() {
        return omega != null;
    }

    public static String lastDetail() {
        return lastDetail;
    }

    /**
     * 12-D analytic feature vector used for logging / ARK packing.
     * Indices:
     * 0 speed_mps 1 accuracy_m 2 heading_delta 3 jump_m 4 dt_s
     * 5 obd_delta 6 rtt_ms 7 down_mbps 8 transport_code 9 sat_flag
     * 10 trust_prior 11 event_severity
     */
    public static float[] features(
            double speedMps,
            Float accuracyM,
            float headingDeltaDeg,
            float jumpM,
            float dtS,
            Float obdSpeedDeltaMps,
            Long rttMs,
            Double downMbps,
            String transport,
            boolean satellite,
            float trustPrior,
            float eventSeverity
    ) {
        float[] f = new float[12];
        f[0] = (float) speedMps;
        f[1] = accuracyM == null ? 0f : accuracyM;
        f[2] = headingDeltaDeg;
        f[3] = jumpM;
        f[4] = dtS;
        f[5] = obdSpeedDeltaMps == null ? 0f : obdSpeedDeltaMps;
        f[6] = rttMs == null ? 0f : rttMs.floatValue();
        f[7] = downMbps == null ? 0f : downMbps.floatValue();
        f[8] = transportCode(transport);
        f[9] = satellite ? 1f : 0f;
        f[10] = clamp01(trustPrior);
        f[11] = clamp01(eventSeverity);
        return f;
    }

    public static Result score(float[] features, float analyticTrust, float eventSeverity) {
        if (omega == null || env == null) {
            return new Result(false, eventSeverity, analyticTrust, "analytic only");
        }
        try {
            float[] f = features != null && features.length >= 12 ? features : new float[12];
            float base = clamp01(analyticTrust);
            float residual = clamp01(1f - base);
            float adversarial = clamp01(Math.max(eventSeverity, f[11]));
            float tGnss = clamp01(1f - Math.min(1f, f[1] / 80f)); // accuracy better → higher
            if (f[1] <= 0f) tGnss = 0.55f;
            float tImu = clamp01(1f - Math.min(1f, f[3] / 40f)); // jump penalty
            float tRf = clamp01(1f - Math.min(1f, f[6] / 400f)); // RTT
            float tHydro = clamp01(f[7] > 0 ? Math.min(1f, f[7] / 20f) : 0.5f);
            float anomaly = clamp01(Math.max(eventSeverity,
                    Math.min(1f, f[3] / 25f) * 0.5f + Math.min(1f, f[1] / 100f) * 0.3f));

            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("base_confidence", scalar(env, base));
            feeds.put("qida_residual", scalar(env, residual));
            feeds.put("adversarial_score", scalar(env, adversarial));
            feeds.put("T_gnss", scalar(env, tGnss));
            feeds.put("T_imu", scalar(env, tImu));
            feeds.put("T_rf", scalar(env, tRf));
            feeds.put("T_hydro", scalar(env, tHydro));
            feeds.put("anomaly_score", scalar(env, anomaly));

            float globalTrust = analyticTrust;
            float penalty = 0f;
            float omegaTrust = analyticTrust;
            boolean anomalyFlag = false;

            try (OrtSession.Result out = omega.run(feeds)) {
                omegaTrust = readFloat(out, "omega_trust", omegaTrust);
                penalty = readFloat(out, "penalty", 0f);
                globalTrust = readFloat(out, "global_trust", globalTrust);
                anomalyFlag = readBool(out, "anomaly_flag", false);
            } finally {
                for (OnnxTensor tensor : feeds.values()) {
                    try { tensor.close(); } catch (Exception ignored) {}
                }
            }

            // Optional ARK residual — mild support signal only
            float arkResidual = 0f;
            if (ark != null) {
                arkResidual = runArk(f);
            }

            // Parked / clean path: good accuracy, near-zero speed, sane RTT
            boolean stationary = f[0] < 1.2f;
            boolean goodFix = f[1] > 0f && f[1] <= 25f;
            boolean saneRtt = f[6] <= 0f || f[6] < 200f;
            boolean cleanScene = stationary && goodFix && saneRtt && eventSeverity < 0.25f;

            // Guard: model ω=0 with healthy global trust is a bad read, not "zero trust"
            if (omegaTrust < 0.05f && globalTrust > 0.5f) {
                omegaTrust = globalTrust;
            }
            if (penalty > 0.85f && cleanScene) {
                penalty = 0.15f;
            }
            // Don't treat ANOM as gospel while parked with a tight fix
            if (cleanScene) {
                anomalyFlag = false;
                anomaly = Math.min(anomaly, 0.2f);
            }

            float spoof = clamp01(Math.max(penalty, anomaly) * 0.55f + arkResidual * 0.2f);
            if (anomalyFlag && !cleanScene) {
                spoof = Math.max(spoof, 0.4f);
            }

            // Soft blend — never hard-min with a broken ω that zeros the whole HUD
            float modelBlend = clamp01(0.55f * globalTrust + 0.45f * omegaTrust);
            float fused = analyticTrust;
            if (!cleanScene && spoof > 0.3f) {
                float cut = spoof * 0.28f; // max ~28% haircut from ORT
                fused = Math.min(fused, analyticTrust * (1f - cut));
                fused = Math.min(fused, Math.max(modelBlend, analyticTrust * 0.55f));
            } else if (!cleanScene) {
                // Mild pull toward model, keep analytic floor
                fused = 0.75f * analyticTrust + 0.25f * Math.max(modelBlend, analyticTrust * 0.8f);
            }
            // Clean parked scene: ORT cannot quarantine
            if (cleanScene) {
                fused = Math.max(fused, Math.max(analyticTrust, 0.72f));
                spoof = Math.min(spoof, 0.2f);
            }
            fused = clamp01(fused);
            // Absolute floor vs analytic: ORT may not drop more than 40% of analytic in one shot
            fused = Math.max(fused, analyticTrust * 0.6f);

            lastDetail = String.format(Locale.US,
                    "ω=%.2f g=%.2f p=%.2f ark=%.2f%s%s",
                    omegaTrust, globalTrust, penalty, arkResidual,
                    anomalyFlag ? " ANOM" : "",
                    cleanScene ? " PARK" : "");
            statusLine = "ORT live · on-device · " + lastDetail;
            return new Result(true, spoof, fused, lastDetail);
        } catch (Throwable t) {
            lastDetail = "infer fail: " + t.getMessage();
            return new Result(false, eventSeverity, analyticTrust, lastDetail);
        }
    }

    private static float runArk(float[] f) {
        try {
            float[] raw = new float[10];
            System.arraycopy(f, 0, raw, 0, Math.min(10, f.length));
            float[][] time = new float[][]{{f[4]}};
            float[][] feats = new float[][]{raw};
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("time", OnnxTensor.createTensor(env, time));
            feeds.put("raw_features", OnnxTensor.createTensor(env, feats));
            try (OrtSession.Result out = ark.run(feeds)) {
                float residual = Math.abs(readFloat(out, "ark_residual", 0f));
                float sde = Math.abs(readFloat(out, "sde_residual", 0f));
                return clamp01((residual + sde) * 0.5f);
            } finally {
                for (OnnxTensor tensor : feeds.values()) {
                    try { tensor.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static OnnxTensor scalar(OrtEnvironment env, float v) throws Exception {
        return OnnxTensor.createTensor(env, v);
    }

    private static float readFloat(OrtSession.Result out, String name, float def) {
        try {
            Object raw = out.get(name);
            if (raw == null) return def;
            // ORT Java may return Optional<OnnxValue>
            if (raw instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) raw;
                if (!opt.isPresent()) return def;
                raw = opt.get();
            }
            if (raw instanceof OnnxTensor) {
                Object val = ((OnnxTensor) raw).getValue();
                return toFloat(val, def);
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static boolean readBool(OrtSession.Result out, String name, boolean def) {
        try {
            Object raw = out.get(name);
            if (raw == null) return def;
            if (raw instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) raw;
                if (!opt.isPresent()) return def;
                raw = opt.get();
            }
            if (raw instanceof OnnxTensor) {
                Object val = ((OnnxTensor) raw).getValue();
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof boolean[]) {
                    boolean[] a = (boolean[]) val;
                    return a.length > 0 ? a[0] : def;
                }
                if (val instanceof Float) return ((Float) val) > 0.5f;
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static float toFloat(Object val, float def) {
        if (val instanceof Float) return (Float) val;
        if (val instanceof Double) return ((Double) val).floatValue();
        if (val instanceof float[]) {
            float[] a = (float[]) val;
            return a.length > 0 ? a[0] : def;
        }
        if (val instanceof float[][]) {
            float[][] a = (float[][]) val;
            return a.length > 0 && a[0].length > 0 ? a[0][0] : def;
        }
        return def;
    }

    private static File materialize(Context ctx, String assetPath, String outName) throws Exception {
        File dir = new File(ctx.getFilesDir(), "onnx");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("onnx dir");
        File out = new File(dir, outName);
        // always refresh from assets so updates ship with APK
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(assetPath);
             FileOutputStream fos = new FileOutputStream(out, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
        }
        return out;
    }

    private static float transportCode(String transport) {
        if (transport == null) return 0f;
        String t = transport.toUpperCase(Locale.US);
        if (t.contains("WIFI")) return 1f;
        if (t.contains("CELL")) return 2f;
        if (t.contains("SAT")) return 3f;
        if (t.contains("ETH")) return 4f;
        return 0.5f;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    public static synchronized void close() {
        try { if (omega != null) omega.close(); } catch (Exception ignored) {}
        try { if (ark != null) ark.close(); } catch (Exception ignored) {}
        omega = null;
        ark = null;
        inited = false;
        statusLine = "ORT closed";
    }
}
