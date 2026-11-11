package global.nscb.aether;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Advanced tactical boot splash — multi-layer scan, rings, hex ticks, boot sequence.
 */
public final class SplashActivity extends AppCompatActivity {
    private static final long TOTAL_MS = 3800;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView boot;
    private ProgressBar rail;
    private int bootStep;

    private static final String[] BOOT_LINES = {
            "NSCB DUAL-PATH  ·  LOADING",
            "INTEGRITY GATE  ·  ONLINE",
            "SESSION LEDGER  ·  ARMED",
            "GL CLUSTER  ·  INIT",
            "PATH TELEMETRY  ·  LIVE",
            "AETHER MOBILE LINK  ·  READY"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF030302);

        SplashCanvas canvas = new SplashCanvas(this);
        root.addView(canvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams lpCenter = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lpCenter.gravity = Gravity.CENTER;
        lpCenter.leftMargin = 36;
        lpCenter.rightMargin = 36;
        root.addView(center, lpCenter);

        TextView brand = new TextView(this);
        brand.setText("AETHER");
        brand.setTextColor(0xFFF7F2E6);
        brand.setTextSize(44);
        brand.setLetterSpacing(0.36f);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        brand.setAlpha(0f);
        brand.setScaleX(0.88f);
        brand.setScaleY(0.88f);
        center.addView(brand);

        TextView sub = new TextView(this);
        sub.setText("MOBILE LINK LIVE");
        sub.setTextColor(0xFFF0A202);
        sub.setTextSize(13);
        sub.setLetterSpacing(0.45f);
        sub.setTypeface(android.graphics.Typeface.MONOSPACE);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 14, 0, 0);
        sub.setAlpha(0f);
        center.addView(sub);

        TextView tag = new TextView(this);
        tag.setText("DUAL-PATH  ·  GL  ·  LEDGER");
        tag.setTextColor(0xFF6E6758);
        tag.setTextSize(10);
        tag.setLetterSpacing(0.2f);
        tag.setTypeface(android.graphics.Typeface.MONOSPACE);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, 10, 0, 0);
        tag.setAlpha(0f);
        center.addView(tag);

        rail = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        rail.setMax(1000);
        rail.setProgress(0);
        LinearLayout.LayoutParams lpRail = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 6);
        lpRail.topMargin = 40;
        rail.setLayoutParams(lpRail);
        rail.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF0A202));
        rail.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1C1912));
        rail.setAlpha(0f);
        center.addView(rail);

        boot = new TextView(this);
        boot.setText("");
        boot.setTextColor(0xFFB0A890);
        boot.setTextSize(11);
        boot.setLetterSpacing(0.1f);
        boot.setTypeface(android.graphics.Typeface.MONOSPACE);
        boot.setGravity(Gravity.CENTER);
        boot.setPadding(0, 22, 0, 0);
        boot.setAlpha(0f);
        center.addView(boot);

        setContentView(root);

        ObjectAnimator bA = ObjectAnimator.ofFloat(brand, "alpha", 0f, 1f);
        ObjectAnimator bX = ObjectAnimator.ofFloat(brand, "scaleX", 0.88f, 1f);
        ObjectAnimator bY = ObjectAnimator.ofFloat(brand, "scaleY", 0.88f, 1f);
        ObjectAnimator sA = ObjectAnimator.ofFloat(sub, "alpha", 0f, 1f);
        ObjectAnimator tA = ObjectAnimator.ofFloat(tag, "alpha", 0f, 1f);
        ObjectAnimator rA = ObjectAnimator.ofFloat(rail, "alpha", 0f, 1f);
        ObjectAnimator kA = ObjectAnimator.ofFloat(boot, "alpha", 0f, 1f);
        bA.setDuration(700);
        bX.setDuration(700);
        bY.setDuration(700);
        sA.setDuration(500);
        sA.setStartDelay(220);
        tA.setDuration(450);
        tA.setStartDelay(400);
        rA.setDuration(400);
        rA.setStartDelay(480);
        kA.setDuration(400);
        kA.setStartDelay(520);
        AnimatorSet intro = new AnimatorSet();
        intro.playTogether(bA, bX, bY, sA, tA, rA, kA);
        intro.setInterpolator(new DecelerateInterpolator());
        intro.start();

        ValueAnimator prog = ValueAnimator.ofInt(0, 1000);
        prog.setDuration(TOTAL_MS - 500);
        prog.setStartDelay(350);
        prog.setInterpolator(new LinearInterpolator());
        prog.addUpdateListener(a -> rail.setProgress((int) a.getAnimatedValue()));
        prog.start();

        bootStep = 0;
        handler.postDelayed(this::nextBootLine, 450);

        handler.postDelayed(() -> {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(brand, "alpha", 1f, 0.55f, 1f);
            pulse.setDuration(1000);
            pulse.setRepeatCount(2);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pulse.start();
        }, 800);

        // exit scale
        handler.postDelayed(() -> {
            brand.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.3f).setDuration(280).start();
            sub.animate().alpha(0f).setDuration(280).start();
        }, TOTAL_MS - 320);

        handler.postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, TOTAL_MS);
    }

    private void nextBootLine() {
        if (bootStep >= BOOT_LINES.length || boot == null) return;
        boot.setText(BOOT_LINES[bootStep]);
        boot.setAlpha(0f);
        boot.setTranslationY(8f);
        boot.animate().alpha(1f).translationY(0f).setDuration(240).start();
        bootStep++;
        if (bootStep < BOOT_LINES.length) {
            handler.postDelayed(this::nextBootLine, 480);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    static final class SplashCanvas extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scan = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hex = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float t, ringT, hexT;

        SplashCanvas(android.content.Context c) {
            super(c);
            grid.setStrokeWidth(1f);
            line.setStrokeWidth(1.5f);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f);
            hex.setStyle(Paint.Style.STROKE);
            hex.setStrokeWidth(1.2f);
            core.setStyle(Paint.Style.FILL);

            ValueAnimator a1 = ValueAnimator.ofFloat(0f, 1f);
            a1.setDuration(2000);
            a1.setRepeatCount(ValueAnimator.INFINITE);
            a1.setInterpolator(new LinearInterpolator());
            a1.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
            a1.start();

            ValueAnimator a2 = ValueAnimator.ofFloat(0f, 1f);
            a2.setDuration(3200);
            a2.setRepeatCount(ValueAnimator.INFINITE);
            a2.setInterpolator(new LinearInterpolator());
            a2.addUpdateListener(a -> { ringT = (float) a.getAnimatedValue(); invalidate(); });
            a2.start();

            ValueAnimator a3 = ValueAnimator.ofFloat(0f, 360f);
            a3.setDuration(14000);
            a3.setRepeatCount(ValueAnimator.INFINITE);
            a3.setInterpolator(new LinearInterpolator());
            a3.addUpdateListener(a -> { hexT = (float) a.getAnimatedValue(); invalidate(); });
            a3.start();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float cx = w / 2f, cy = h / 2f;

            // dark radial core
            core.setShader(new RadialGradient(cx, cy, Math.min(w, h) * 0.55f,
                    new int[]{0xFF1A1408, 0xFF030302}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, core);

            // perspective-ish grid
            grid.setColor(0x18F0A202);
            int step = 36;
            for (int x = 0; x < w; x += step) c.drawLine(x, 0, x, h, grid);
            for (int y = 0; y < h; y += step) c.drawLine(0, y, w, y, grid);

            // expanding rings
            ring.setColor(0xFFF0A202);
            for (int i = 0; i < 4; i++) {
                float phase = (ringT + i / 4f) % 1f;
                float radius = 30f + phase * Math.min(w, h) * 0.48f;
                ring.setAlpha(Math.max(0, (int) ((1f - phase) * 100)));
                c.drawCircle(cx, cy, radius, ring);
            }

            // rotating hex frame
            hex.setColor(0x55F0A202);
            drawHex(c, cx, cy, Math.min(w, h) * 0.22f, hexT);
            hex.setColor(0x33F0A202);
            drawHex(c, cx, cy, Math.min(w, h) * 0.30f, -hexT * 0.7f);

            // crosshair
            line.setColor(0x55F0A202);
            float arm = 52f;
            c.drawLine(cx - arm, cy, cx + arm, cy, line);
            c.drawLine(cx, cy - arm, cx, cy + arm, line);
            c.drawCircle(cx, cy, 70, line);
            c.drawCircle(cx, cy, 110, line);

            // dual scan
            float band = h * 0.09f;
            drawScan(c, w, (h + band) * t - band, band, 0x35F0A202);
            drawScan(c, w, (h + band) * ((t + 0.42f) % 1f) - band, band * 0.55f, 0x18F0A202);

            // corner brackets with pulse
            float m = 24f, len = 40f;
            Paint br = new Paint(Paint.ANTI_ALIAS_FLAG);
            int ba = 80 + (int) (50 * Math.sin(t * Math.PI * 2));
            br.setColor((0xF0A202 & 0x00FFFFFF) | (ba << 24));
            br.setStrokeWidth(2.5f);
            c.drawLine(m, m, m + len, m, br);
            c.drawLine(m, m, m, m + len, br);
            c.drawLine(w - m, m, w - m - len, m, br);
            c.drawLine(w - m, m, w - m, m + len, br);
            c.drawLine(m, h - m, m + len, h - m, br);
            c.drawLine(m, h - m, m, h - m - len, br);
            c.drawLine(w - m, h - m, w - m - len, h - m, br);
            c.drawLine(w - m, h - m, w - m, h - m - len, br);

            // edge ticks
            br.setStrokeWidth(1.5f);
            for (int i = 0; i < 12; i++) {
                float yy = h * (i + 1) / 13f;
                c.drawLine(0, yy, 10, yy, br);
                c.drawLine(w, yy, w - 10, yy, br);
            }

            vignette.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xEE030302, 0x00030302, 0x00030302, 0xF8030302},
                    new float[]{0f, 0.2f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, vignette);
        }

        private void drawHex(Canvas c, float cx, float cy, float r, float rotDeg) {
            float[] xs = new float[6];
            float[] ys = new float[6];
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(rotDeg + i * 60);
                xs[i] = cx + (float) Math.cos(a) * r;
                ys[i] = cy + (float) Math.sin(a) * r;
            }
            for (int i = 0; i < 6; i++) {
                int j = (i + 1) % 6;
                c.drawLine(xs[i], ys[i], xs[j], ys[j], hex);
            }
        }

        private void drawScan(Canvas c, int w, float y, float band, int peak) {
            scan.setShader(new LinearGradient(0, y, 0, y + band,
                    new int[]{0x00000000, peak, 0x00000000},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, y, w, y + band, scan);
        }
    }
}
