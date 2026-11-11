package global.nscb.aether;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;

/** Lightweight scan-line + vignette overlay for futuristic HUD chrome. */
public final class HudOverlayView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float offset;
    private ValueAnimator anim;

    public HudOverlayView(Context c) { super(c); init(); }
    public HudOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    public HudOverlayView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        linePaint.setStyle(Paint.Style.FILL);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(4200);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim != null && !anim.isRunning()) anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // soft vignette
        vignettePaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x22000000, 0x00000000, 0x00000000, 0x33000000},
                new float[]{0f, 0.2f, 0.8f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, vignettePaint);

        // horizontal scan band
        float bandH = h * 0.12f;
        float y = (h + bandH) * offset - bandH;
        linePaint.setShader(new LinearGradient(0, y, 0, y + bandH,
                new int[]{0x00F0A202, 0x28F0A202, 0x00F0A202},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, y, w, y + bandH, linePaint);

        // fine grid ticks top
        linePaint.setShader(null);
        linePaint.setColor(0x14F0A202);
        for (int x = 0; x < w; x += 48) {
            canvas.drawRect(x, 0, x + 1, 10, linePaint);
        }
    }
}
