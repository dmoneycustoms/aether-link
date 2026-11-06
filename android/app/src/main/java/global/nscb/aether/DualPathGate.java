package global.nscb.aether;

/**
 * NSCB-style dual-path disagreement: GPS speed vs OBD speed vs path health.
 * Returns severity 0..1 and a short reason. Analytic only — lowers trust, never invents RF.
 */
public final class DualPathGate {
    public static final class Result {
        public final float severity;
        public final String reason;

        public Result(float severity, String reason) {
            this.severity = severity;
            this.reason = reason == null ? "" : reason;
        }
    }

    private DualPathGate() {}

    /**
     * @param gpsMps GPS speed m/s (nullable)
     * @param obdMps OBD speed m/s (nullable)
     * @param accuracyM GPS accuracy meters (nullable)
     * @param rttMs path RTT (nullable)
     * @param transport path label
     */
    public static Result evaluate(
            Double gpsMps,
            Double obdMps,
            Float accuracyM,
            Long rttMs,
            String transport
    ) {
        float sev = 0f;
        String reason = "";

        if (gpsMps != null && obdMps != null
                && Double.isFinite(gpsMps) && Double.isFinite(obdMps)) {
            double delta = Math.abs(gpsMps - obdMps);
            // > 4 m/s (~9 mph) disagreement starts to matter; > 12 m/s is hard
            if (delta > 12.0) {
                sev = Math.max(sev, 0.85f);
                reason = String.format(java.util.Locale.US, "gps/obd delta %.1f m/s", delta);
            } else if (delta > 4.0) {
                sev = Math.max(sev, 0.45f + (float) ((delta - 4.0) / 16.0));
                reason = String.format(java.util.Locale.US, "gps/obd delta %.1f m/s", delta);
            }
        }

        if (accuracyM != null && accuracyM > 40f) {
            float a = Math.min(0.55f, (accuracyM - 40f) / 120f);
            if (a > sev) {
                sev = a;
                reason = String.format(java.util.Locale.US, "weak GPS ±%.0f m", accuracyM);
            }
        }

        if (rttMs != null && rttMs > 280) {
            float r = Math.min(0.5f, (rttMs - 280f) / 600f);
            if (r > sev * 0.9f) {
                sev = Math.max(sev, r);
                reason = reason.isEmpty() ? ("high RTT " + rttMs + " ms") : reason + " · RTT";
            }
        }

        if (transport != null && "offline".equalsIgnoreCase(transport)) {
            sev = Math.max(sev, 0.4f);
            if (reason.isEmpty()) reason = "path offline";
        }

        sev = Math.max(0f, Math.min(1f, sev));
        return new Result(sev, reason);
    }
}
