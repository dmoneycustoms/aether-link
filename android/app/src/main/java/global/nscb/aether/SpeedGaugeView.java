package global.nscb.aether;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class SpeedGaugeView extends View {
    private final Paint arcBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float display;
    private boolean live;
    private float pulse = 1f;
    private float shimmer;

    public SpeedGaugeView(Context c) { super(c); init(); }
    public SpeedGaugeView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null); // blur glow
        arcBg.setStyle(Paint.Style.STROKE);
        arcBg.setStrokeWidth(22f);
        arcBg.setStrokeCap(Paint.Cap.ROUND);
        arcFg.setStyle(Paint.Style.STROKE);
        arcFg.setStrokeWidth(22f);
        arcFg.setStrokeCap(Paint.Cap.ROUND);
        arcGlow.setStyle(Paint.Style.STROKE);
        arcGlow.setStrokeWidth(36f);
        arcGlow.setStrokeCap(Paint.Cap.ROUND);
        arcGlow.setMaskFilter(new BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL));
        tick.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setColor(0xFFF7F2E6);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.MONOSPACE);
        ValueAnimator p = ValueAnimator.ofFloat(0.55f, 1f, 0.55f);
        p.setDuration(1600);
        p.setRepeatCount(ValueAnimator.INFINITE);
        p.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); if (live) invalidate(); });
        p.start();
        ValueAnimator s = ValueAnimator.ofFloat(0f, 1f);
        s.setDuration(5000);
        s.setRepeatCount(ValueAnimator.INFINITE);
        s.addUpdateListener(a -> { shimmer = (float) a.getAnimatedValue(); invalidate(); });
        s.start();
    }

    public void setSpeedMph(float mph, boolean isLive) {
        live = isLive;
        ValueAnimator anim = ValueAnimator.ofFloat(display, Math.max(0f, mph));
        anim.setDuration(360);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { display = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private int heatColor(float frac) {
        // green -> amber -> red with continuous blend
        if (frac < 0.45f) {
            float t = frac / 0.45f;
            return lerpColor(0xFF1DBF7A, 0xFFF0A202, t);
        }
        float t = (frac - 0.45f) / 0.55f;
        return lerpColor(0xFFF0A202, 0xFFFF3B2E, t);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f + size * 0.02f;
        float r = size * 0.40f;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        // radial vignette hub
        hub.setShader(new RadialGradient(cx, cy, r * 1.15f,
                new int[]{0x331A1508, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.15f, hub);

        float start = 140f, sweep = 260f;
        float max = 120f;
        float frac = Math.min(1f, display / max);
        int accent = heatColor(frac);

        arcBg.setColor(0xFF1C1912);
        c.drawArc(oval, start, sweep, false, arcBg);

        // shimmering track
        ring.setColor(0x22F0A202);
        ring.setAlpha((int) (40 + 30 * Math.sin(shimmer * Math.PI * 2)));
        c.drawCircle(cx, cy, r + 20f, ring);

        if (frac > 0.002f) {
            SweepGradient sg = new SweepGradient(cx, cy,
                    new int[]{0xFF1DBF7A, 0xFFF0A202, 0xFFFF3B2E, 0xFFFF3B2E},
                    new float[]{0f, 0.4f, 0.75f, 1f});
            Matrix m = new Matrix();
            m.preRotate(start - 90f, cx, cy);
            sg.setLocalMatrix(m);
            arcFg.setShader(sg);
            float fgSweep = sweep * frac;
            if (live) {
                arcGlow.setColor((accent & 0x00FFFFFF) | ((int) (90 * pulse) << 24));
                c.drawArc(oval, start, fgSweep, false, arcGlow);
            }
            c.drawArc(oval, start, fgSweep, false, arcFg);
            arcFg.setShader(null);

            // tip beacon
            double a = Math.toRadians(start + fgSweep);
            float tx = cx + (float) Math.cos(a) * r;
            float ty = cy + (float) Math.sin(a) * r;
            Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
            tip.setColor(accent);
            tip.setMaskFilter(new BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(tx, ty, 8f * pulse, tip);
            tip.setMaskFilter(null);
            c.drawCircle(tx, ty, 4f, tip);
        }

        // ticks
        for (int i = 0; i <= 13; i++) {
            boolean major = (i % 2 == 0);
            float ang = (float) Math.toRadians(start + sweep * (i / 13f));
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float inner = r - (major ? 16f : 9f);
            tick.setStrokeWidth(major ? 3.5f : 1.8f);
            tick.setColor(major ? (0xAA000000 | (accent & 0x00FFFFFF)) : 0x44F0A202);
            c.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * (r + 3f), cy + sin * (r + 3f), tick);
        }

        text.setTextSize(size * 0.24f);
        text.setShadowLayer(live ? 16f * pulse : 0f, 0f, 0f, accent);
        String label = display < 0.05f ? "0" : (display < 10f
                ? String.format(java.util.Locale.US, "%.1f", display)
                : String.format(java.util.Locale.US, "%.0f", display));
        c.drawText(label, cx, cy + text.getTextSize() * 0.32f, text);
        text.setShadowLayer(0f, 0f, 0f, 0);

        sub.setTextSize(size * 0.048f);
        sub.setColor(live ? accent : 0xFF8F8774);
        c.drawText(live ? "MPH  ·  LIVE" : "MPH", cx, cy + size * 0.17f, sub);
    }
}
