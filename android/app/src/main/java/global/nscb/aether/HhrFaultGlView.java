package global.nscb.aether;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL ES 2.0 on TextureView. Renderer starts only after startGl() — never crashes inflate.
 */
public final class HhrFaultGlView extends TextureView implements TextureView.SurfaceTextureListener {
    public interface PickListener {
        void onPartPicked(String partId, String title, List<Fault> faults);
    }

    public static final class Fault {
        public final String code;
        public final String detail;
        public Fault(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }
    }

    public static final class PartDef {
        public final String id, title;
        public final float x, y, w, h;
        public PartDef(String id, String title, float x, float y, float w, float h) {
            this.id = id; this.title = title; this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    private PickListener pickListener;
    private final List<PartDef> parts = new ArrayList<>();
    private final Map<String, List<Fault>> faultsByPart = new HashMap<>();
    private final Set<String> hot = new HashSet<>();
    private volatile String selectedId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean glEnabled = new AtomicBoolean(false);
    private RenderThread renderThread;
    private SurfaceTexture pendingSurface;
    private int viewW, viewH;

    public HhrFaultGlView(Context c) {
        super(c);
        safeInit();
    }

    public HhrFaultGlView(Context c, AttributeSet a) {
        super(c, a);
        safeInit();
    }

    private void safeInit() {
        try {
            setOpaque(true);
            setSurfaceTextureListener(this);
            buildParts();
            setBackgroundColor(0xFF12100C);
        } catch (Throwable ignored) {}
    }

    private void buildParts() {
        parts.clear();
        parts.add(new PartDef("rad", "Radiator / cooling fans", 0f, 0.78f, 0.55f, 0.10f));
        parts.add(new PartDef("batt", "Battery / charging", 0.50f, 0.78f, 0.22f, 0.10f));
        parts.add(new PartDef("eng", "Engine block / sensors", 0f, 0.55f, 0.42f, 0.16f));
        parts.add(new PartDef("cyl1", "Cylinder 1", -0.30f, 0.57f, 0.14f, 0.12f));
        parts.add(new PartDef("cyl2", "Cylinder 2", -0.12f, 0.57f, 0.14f, 0.12f));
        parts.add(new PartDef("cyl3", "Cylinder 3", 0.06f, 0.57f, 0.14f, 0.12f));
        parts.add(new PartDef("cyl4", "Cylinder 4", 0.24f, 0.57f, 0.14f, 0.12f));
        parts.add(new PartDef("fuel", "Fuel rail / injectors", -0.48f, 0.55f, 0.16f, 0.14f));
        parts.add(new PartDef("ign", "Ignition coils (COP)", 0.48f, 0.55f, 0.16f, 0.14f));
        parts.add(new PartDef("maf", "MAF / air intake", -0.36f, 0.34f, 0.24f, 0.11f));
        parts.add(new PartDef("tps", "Throttle body (ETB)", 0f, 0.34f, 0.24f, 0.11f));
        parts.add(new PartDef("map", "MAP / intake pressure", 0.36f, 0.34f, 0.24f, 0.11f));
        parts.add(new PartDef("iat", "IAT sensor", -0.36f, 0.20f, 0.22f, 0.09f));
        parts.add(new PartDef("ect", "Coolant temp (ECT)", 0.20f, 0.20f, 0.22f, 0.09f));
        parts.add(new PartDef("ecm", "ECM / PCM", 0.52f, 0.34f, 0.18f, 0.12f));
        parts.add(new PartDef("o2b1s1", "O2 B1S1 pre-cat", -0.42f, 0.02f, 0.26f, 0.11f));
        parts.add(new PartDef("o2b1s2", "O2 B1S2 post-cat", -0.42f, -0.12f, 0.26f, 0.11f));
        parts.add(new PartDef("cat", "Catalytic converter", 0f, -0.05f, 0.30f, 0.14f));
        parts.add(new PartDef("egr", "EGR valve", 0.28f, 0.05f, 0.20f, 0.10f));
        parts.add(new PartDef("evap", "EVAP canister / purge", 0.42f, -0.08f, 0.28f, 0.12f));
        parts.add(new PartDef("body", "BCM body control", -0.52f, -0.20f, 0.16f, 0.12f));
        parts.add(new PartDef("net", "GMLAN / network", 0.52f, -0.20f, 0.16f, 0.12f));
        parts.add(new PartDef("trans", "Transmission / TCM", 0f, -0.38f, 0.50f, 0.16f));
        parts.add(new PartDef("vss", "Vehicle speed sensor", 0.40f, -0.38f, 0.20f, 0.12f));
        parts.add(new PartDef("abs", "ABS module", 0f, -0.60f, 0.36f, 0.12f));
        parts.add(new PartDef("abs_lf", "LF wheel speed", -0.45f, -0.72f, 0.20f, 0.10f));
        parts.add(new PartDef("abs_rf", "RF wheel speed", 0.45f, -0.72f, 0.20f, 0.10f));
        parts.add(new PartDef("abs_lr", "LR wheel speed", -0.45f, -0.85f, 0.20f, 0.10f));
        parts.add(new PartDef("abs_rr", "RR wheel speed", 0.45f, -0.85f, 0.20f, 0.10f));
    }

    public void setPickListener(PickListener l) {
        this.pickListener = l;
    }

    /** Call when BUS tab shown — starts GL thread safely. */
    public void startGl() {
        glEnabled.set(true);
        if (pendingSurface != null && renderThread == null) {
            startThread(pendingSurface, viewW, viewH);
        }
    }

    public void stopGl() {
        glEnabled.set(false);
        stopThread();
    }

    public void setFaults(List<String> codes) {
        try {
            synchronized (faultsByPart) {
                faultsByPart.clear();
                hot.clear();
                if (codes != null) {
                    for (String c : codes) {
                        if (c == null || c.length() < 2) continue;
                        HhrFaultMapView.FaultPin pin = HhrFaultMapView.mapDtc(c.trim().toUpperCase(Locale.US));
                        List<Fault> list = faultsByPart.get(pin.partId);
                        if (list == null) {
                            list = new ArrayList<>();
                            faultsByPart.put(pin.partId, list);
                        }
                        list.add(new Fault(pin.code, pin.detail));
                        hot.add(pin.partId);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public void onResume() {
        if (glEnabled.get()) startGl();
    }

    public void onPause() {
        stopThread();
    }

    private void startThread(SurfaceTexture st, int w, int h) {
        stopThread();
        if (st == null || w <= 0 || h <= 0) return;
        running.set(true);
        renderThread = new RenderThread(st, w, h);
        renderThread.start();
    }

    private void stopThread() {
        running.set(false);
        if (renderThread != null) {
            renderThread.shutdown();
            try { renderThread.join(500); } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        pendingSurface = surface;
        viewW = width;
        viewH = height;
        if (glEnabled.get()) startThread(surface, width, height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        viewW = width;
        viewH = height;
        if (renderThread != null) renderThread.resize(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        pendingSurface = null;
        stopThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP || viewW <= 0 || viewH <= 0) return true;
        try {
            float aspect = viewW / (float) viewH;
            float nx = (e.getX() / viewW) * 2f - 1f;
            float ny = 1f - (e.getY() / viewH) * 2f;
            float px = nx * aspect;
            PartDef hit = null;
            for (int i = parts.size() - 1; i >= 0; i--) {
                PartDef p = parts.get(i);
                if (Math.abs(px - p.x) <= p.w / 2f && Math.abs(ny - p.y) <= p.h / 2f) {
                    hit = p;
                    break;
                }
            }
            if (hit != null && pickListener != null) {
                selectedId = hit.id;
                List<Fault> f;
                synchronized (faultsByPart) {
                    f = faultsByPart.get(hit.id);
                    f = f == null ? new ArrayList<>() : new ArrayList<>(f);
                }
                final PartDef h = hit;
                final List<Fault> ff = f;
                post(() -> {
                    try { pickListener.onPartPicked(h.id, h.title, ff); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private final class RenderThread extends Thread {
        private final SurfaceTexture surfaceTexture;
        private volatile int width, height;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private android.opengl.EGLContext eglContext;
        private android.opengl.EGLDisplay eglDisplay;
        private android.opengl.EGLSurface eglSurface;
        private int program, aPos, uColor, uMvp;
        private final float[] mvp = new float[16];
        private FloatBuffer quadBuf;
        private final float[] quad = new float[8];

        RenderThread(SurfaceTexture st, int w, int h) {
            super("aether-hhr-gl");
            surfaceTexture = st;
            width = w;
            height = h;
        }

        void resize(int w, int h) { width = w; height = h; }
        void shutdown() { alive.set(false); interrupt(); }

        @Override
        public void run() {
            try {
                if (!initEgl()) return;
                initGl();
            } catch (Throwable t) {
                releaseEgl();
                return;
            }
            long t0 = System.nanoTime();
            while (alive.get() && running.get()) {
                try {
                    int w = width, h = height;
                    if (w <= 0 || h <= 0) {
                        Thread.sleep(32);
                        continue;
                    }
                    float aspect = w / (float) Math.max(1, h);
                    float time = (System.nanoTime() - t0) / 1e9f;
                    GLES20.glViewport(0, 0, w, h);
                    GLES20.glClearColor(0.07f, 0.06f, 0.04f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    Matrix.orthoM(mvp, 0, -aspect, aspect, -1f, 1f, -1f, 1f);
                    GLES20.glUseProgram(program);

                    drawRect(0f, 0f, 0.70f, 1.70f, 0.18f, 0.16f, 0.12f, 1f);
                    drawRect(0f, 0f, 0.42f, 0.30f, 0.12f, 0.28f, 0.36f, 0.45f);

                    Set<String> hotSnap;
                    String sel;
                    synchronized (faultsByPart) {
                        hotSnap = new HashSet<>(hot);
                        sel = selectedId;
                    }
                    for (PartDef p : parts) {
                        boolean isHot = hotSnap.contains(p.id);
                        boolean isSel = p.id != null && p.id.equals(sel);
                        float pulse = isHot ? 0.05f * (float) Math.sin(time * 5.0) : 0f;
                        float r, g, b, a;
                        if (isHot) {
                            r = 0.92f; g = 0.25f; b = 0.15f; a = 0.90f;
                            if (p.id.startsWith("o2")) { r = 0.15f; g = 0.78f; b = 0.65f; }
                            else if ("evap".equals(p.id) || "egr".equals(p.id)) { r = 0.72f; g = 0.35f; b = 0.95f; }
                            else if ("trans".equals(p.id) || "vss".equals(p.id)) { r = 0.25f; g = 0.45f; b = 0.95f; }
                            else if (p.id.startsWith("abs")) { r = 0.95f; g = 0.15f; b = 0.40f; }
                            else if ("fuel".equals(p.id)) { r = 1f; g = 0.55f; b = 0.12f; }
                            else if ("ecm".equals(p.id) || "net".equals(p.id)) { r = 0.95f; g = 0.85f; b = 0.2f; }
                        } else if (isSel) {
                            r = 0.94f; g = 0.64f; b = 0.08f; a = 0.75f;
                        } else {
                            r = 0.42f; g = 0.38f; b = 0.28f; a = 0.80f;
                        }
                        drawRect(p.x, p.y, p.w + pulse, p.h + pulse, r, g, b, a);
                        drawFrame(p.x, p.y, p.w, p.h,
                                isHot || isSel ? 0.98f : 0.70f,
                                isHot || isSel ? 0.85f : 0.65f,
                                isHot || isSel ? 0.35f : 0.45f, 1f);
                    }
                    android.opengl.EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                    Thread.sleep(33);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }
            releaseEgl();
        }

        private void drawRect(float cx, float cy, float w, float h, float r, float g, float b, float a) {
            float x0 = cx - w / 2f, x1 = cx + w / 2f, y0 = cy - h / 2f, y1 = cy + h / 2f;
            quad[0] = x0; quad[1] = y0; quad[2] = x1; quad[3] = y0;
            quad[4] = x0; quad[5] = y1; quad[6] = x1; quad[7] = y1;
            if (quadBuf == null) {
                quadBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
            quadBuf.clear();
            quadBuf.put(quad).position(0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniform4f(uColor, r, g, b, a);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadBuf);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void drawFrame(float cx, float cy, float w, float h, float r, float g, float b, float a) {
            float x0 = cx - w / 2f, x1 = cx + w / 2f, y0 = cy - h / 2f, y1 = cy + h / 2f;
            float[] line = {x0, y0, x1, y0, x1, y1, x0, y1, x0, y0};
            FloatBuffer lb = ByteBuffer.allocateDirect(line.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            lb.put(line).position(0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniform4f(uColor, r, g, b, a);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, lb);
            GLES20.glLineWidth(3f);
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, 5);
        }

        private boolean initEgl() {
            eglDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == android.opengl.EGL14.EGL_NO_DISPLAY) return false;
            int[] ver = new int[2];
            if (!android.opengl.EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false;
            int[] attrib = {
                    android.opengl.EGL14.EGL_RED_SIZE, 8,
                    android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                    android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    android.opengl.EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] num = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, num, 0) || num[0] == 0)
                return false;
            int[] ctxAttr = {android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE};
            eglContext = android.opengl.EGL14.eglCreateContext(eglDisplay, configs[0],
                    android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            if (eglContext == null) return false;
            int[] surfAttr = {android.opengl.EGL14.EGL_NONE};
            eglSurface = android.opengl.EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture, surfAttr, 0);
            if (eglSurface == null) return false;
            return android.opengl.EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        private void initGl() {
            String vs = "attribute vec2 aPos; uniform mat4 uMvp; void main(){gl_Position=uMvp*vec4(aPos,0.0,1.0);}";
            String fs = "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}";
            int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(v, vs);
            GLES20.glCompileShader(v);
            int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(f, fs);
            GLES20.glCompileShader(f);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, v);
            GLES20.glAttachShader(program, f);
            GLES20.glLinkProgram(program);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uColor = GLES20.glGetUniformLocation(program, "uColor");
            uMvp = GLES20.glGetUniformLocation(program, "uMvp");
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private void releaseEgl() {
            try {
                if (eglDisplay != null) {
                    android.opengl.EGL14.eglMakeCurrent(eglDisplay, android.opengl.EGL14.EGL_NO_SURFACE,
                            android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != null) android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != null) android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext);
                    android.opengl.EGL14.eglTerminate(eglDisplay);
                }
            } catch (Throwable ignored) {}
            eglDisplay = null;
            eglSurface = null;
            eglContext = null;
        }
    }
}
