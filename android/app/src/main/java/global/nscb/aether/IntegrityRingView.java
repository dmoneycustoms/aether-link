package global.nscb.aether;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class IntegrityRingView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float display = 0.92f;
    private String level = "CLEAN";
    private int color = 0xFF1DBF7A;
    private float spin;

    public IntegrityRingView(Context c) { super(c); init(); }
    public IntegrityRingView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bg.setStyle(Paint.Style.STROKE); bg.setStrokeWidth(14f); bg.setColor(0xFF1C1912); bg.setStrokeCap(Paint.Cap.ROUND);
        fg.setStyle(Paint.Style.STROKE); fg.setStrokeWidth(14f); fg.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(24f); glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL));
        label.setTextAlign(Paint.Align.CENTER); label.setTextSize(26f); label.setTypeface(Typeface.MONOSPACE);
        value.setTextAlign(Paint.Align.CENTER); value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); value.setTextSize(40f); value.setColor(0xFFF7F2E6);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 360f);
        a.setDuration(7000); a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(x -> { spin = (float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }

    public void setTrust(float t, String lvl) {
        level = lvl == null ? "CLEAN" : lvl.toUpperCase(java.util.Locale.US);
        if ("QUARANTINE".equals(level) || "DEGRADED".equals(level)) color = 0xFFFF3B2E;
        else if ("WATCH".equals(level)) color = 0xFFF0A202;
        else color = 0xFF1DBF7A;
        ValueAnimator anim = ValueAnimator.ofFloat(display, Math.max(0f, Math.min(1f, t)));
        anim.setDuration(450); anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(x -> { display = (float) x.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.74f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, 135f, 270f, false, bg);
        fg.setColor(color);
        glow.setColor((color & 0x00FFFFFF) | 0x55000000);
        float sweep = 270f * display;
        c.drawArc(oval, 135f, sweep, false, glow);
        c.drawArc(oval, 135f, sweep, false, fg);
        Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
        orb.setStyle(Paint.Style.STROKE); orb.setStrokeWidth(2f); orb.setColor((color & 0x00FFFFFF) | 0x44000000);
        c.drawArc(oval, spin, 50f, false, orb);
        double a = Math.toRadians(135f + sweep);
        Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
        tip.setColor(color);
        c.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 5f, tip);
        value.setShadowLayer(12f, 0f, 0f, color);
        c.drawText((int) (display * 100) + "%", cx, cy - 4f, value);
        value.setShadowLayer(0f, 0f, 0f, 0);
        label.setColor(color);
        c.drawText(level, cx, cy + 28f, label);
    }
}
