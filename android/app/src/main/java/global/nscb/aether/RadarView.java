package global.nscb.aether;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

public final class RadarView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float angle;
    private boolean gps, path, obd, live;

    public RadarView(Context c) { super(c); init(); }
    public RadarView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(1.5f);
        sweep.setStyle(Paint.Style.STROKE); sweep.setStrokeWidth(2.5f);
        cross.setStyle(Paint.Style.STROKE); cross.setStrokeWidth(1f); cross.setColor(0x28F0A202);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 360f);
        a.setDuration(2800); a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(x -> { angle = (float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }

    public void setState(boolean gpsFix, boolean pathOk, boolean obdLive, boolean probeLive) {
        gps = gpsFix; path = pathOk; obd = obdLive; live = probeLive; invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy) * 0.88f;
        ring.setColor(0x33F0A202);
        for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, maxR * (i / 4f), ring);
        c.drawLine(cx - maxR, cy, cx + maxR, cy, cross);
        c.drawLine(cx, cy - maxR, cx, cy + maxR, cross);
        // sweep wedge feel
        SweepGradient sg = new SweepGradient(cx, cy, new int[]{0x00F0A202, 0x44F0A202, 0x00F0A202}, new float[]{0f, 0.08f, 0.2f});
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(angle - 90f, cx, cy);
        sg.setLocalMatrix(m);
        Paint wedge = new Paint(Paint.ANTI_ALIAS_FLAG);
        wedge.setShader(sg);
        c.drawCircle(cx, cy, maxR, wedge);
        double rad = Math.toRadians(angle - 90);
        sweep.setColor(0xAAF0A202);
        c.drawLine(cx, cy, cx + (float) Math.cos(rad) * maxR, cy + (float) Math.sin(rad) * maxR, sweep);
        drawBlip(c, cx, cy, maxR * 0.55f, 35f, gps, 0xFF1DBF7A);
        drawBlip(c, cx, cy, maxR * 0.72f, 200f, path, 0xFFF0A202);
        drawBlip(c, cx, cy, maxR * 0.42f, 300f, obd, 0xFF4DB7FF);
        if (live) {
            blip.setColor(0xFFF0A202);
            c.drawCircle(cx, cy, 3.5f, blip);
        }
    }

    private void drawBlip(Canvas c, float cx, float cy, float dist, float deg, boolean on, int color) {
        if (!on) return;
        double r = Math.toRadians(deg - 90);
        float x = cx + (float) Math.cos(r) * dist;
        float y = cy + (float) Math.sin(r) * dist;
        blip.setColor((color & 0x00FFFFFF) | 0x55000000);
        c.drawCircle(x, y, 11f, blip);
        blip.setColor(color);
        c.drawCircle(x, y, 4.5f, blip);
    }
}
