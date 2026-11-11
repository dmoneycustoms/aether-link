package global.nscb.aether;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public final class PathArcView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float quality = 0.7f;
    private String transport = "—";
    private String rttLabel = "RTT —";
    private int color = 0xFF1DBF7A;

    public PathArcView(Context c) { super(c); init(); }
    public PathArcView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bg.setStyle(Paint.Style.STROKE); bg.setStrokeWidth(12f); bg.setColor(0xFF1C1912); bg.setStrokeCap(Paint.Cap.ROUND);
        fg.setStyle(Paint.Style.STROKE); fg.setStrokeWidth(12f); fg.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(20f); glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL));
        text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); text.setTextSize(32f); text.setColor(0xFFF7F2E6);
        sub.setTextAlign(Paint.Align.CENTER); sub.setTextSize(24f); sub.setTypeface(Typeface.MONOSPACE);
    }

    public void setPath(String transport, Long rttMs, boolean online) {
        this.transport = transport == null ? "—" : transport.toUpperCase(java.util.Locale.US);
        if (rttMs == null) {
            rttLabel = "RTT —";
            quality = online ? 0.5f : 0.12f;
        } else {
            rttLabel = "RTT " + rttMs + " ms";
            if (rttMs < 40) quality = 0.96f;
            else if (rttMs < 80) quality = 0.8f;
            else if (rttMs < 150) quality = 0.55f;
            else if (rttMs < 300) quality = 0.32f;
            else quality = 0.14f;
        }
        if ("OFFLINE".equals(this.transport)) quality = 0.1f;
        if (quality > 0.7f) color = 0xFF1DBF7A;
        else if (quality > 0.4f) color = 0xFFF0A202;
        else color = 0xFFFF3B2E;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.72f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, 150f, 240f, false, bg);
        fg.setColor(color);
        glow.setColor((color & 0x00FFFFFF) | 0x55000000);
        float sweep = 240f * quality;
        c.drawArc(oval, 150f, sweep, false, glow);
        c.drawArc(oval, 150f, sweep, false, fg);
        text.setShadowLayer(10f, 0f, 0f, color);
        c.drawText(transport, cx, cy - 2f, text);
        text.setShadowLayer(0f, 0f, 0f, 0);
        sub.setColor(color);
        c.drawText(rttLabel, cx, cy + 28f, sub);
    }
}
