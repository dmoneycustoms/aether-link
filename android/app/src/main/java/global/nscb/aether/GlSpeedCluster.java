package global.nscb.aether;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 2.0 tactical speed cluster — fragment-shader arc, heat color, glow, pulse.
 */
public final class GlSpeedCluster extends GLSurfaceView {
    private final ClusterRenderer renderer;

    public GlSpeedCluster(Context context) {
        this(context, null);
    }

    public GlSpeedCluster(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        renderer = new ClusterRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setSpeedMph(float mph, boolean live) {
        renderer.setTarget(Math.max(0f, mph), live);
    }

    static final class ClusterRenderer implements Renderer {
        private static final String VSH =
                "attribute vec2 aPos;\n" +
                "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }\n";

        private static final String FSH =
                "precision mediump float;\n" +
                "uniform vec2 uRes;\n" +
                "uniform float uSpeed;\n" +
                "uniform float uLive;\n" +
                "uniform float uTime;\n" +
                "vec3 heatCol(float f){\n" +
                "  vec3 g = vec3(0.11, 0.75, 0.48);\n" +
                "  vec3 a = vec3(0.94, 0.64, 0.01);\n" +
                "  vec3 r = vec3(1.0, 0.23, 0.18);\n" +
                "  if(f < 0.45) return mix(g, a, clamp(f/0.45,0.0,1.0));\n" +
                "  return mix(a, r, clamp((f-0.45)/0.55,0.0,1.0));\n" +
                "}\n" +
                "void main(){\n" +
                "  vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / min(uRes.x, uRes.y);\n" +
                "  uv.y = -uv.y;\n" +
                "  float R = 0.40;\n" +
                "  float frac = clamp(uSpeed / 120.0, 0.0, 1.0);\n" +
                "  float start = radians(140.0);\n" +
                "  float full = radians(260.0);\n" +
                "  float ang = atan(uv.y, uv.x);\n" +
                "  if(ang < 0.0) ang += 6.2831853;\n" +
                "  float dist = length(uv);\n" +
                "  float inTrack = step(start, ang) * step(ang, start + full);\n" +
                "  float track = (1.0 - smoothstep(0.0, 0.01, abs(dist - R) - 0.026)) * inTrack;\n" +
                "  vec3 col = vec3(0.09, 0.08, 0.05) * track;\n" +
                "  float active = step(start, ang) * step(ang, start + full * max(frac, 0.001));\n" +
                "  float arc = (1.0 - smoothstep(0.0, 0.01, abs(dist - R) - 0.008)) * active;\n" +
                "  vec3 hc = heatCol(frac);\n" +
                "  float pulse = 0.6 + 0.4 * sin(uTime * 3.5);\n" +
                "  col += hc * arc;\n" +
                "  float glow = exp(-abs(dist - R) * 12.0) * active * (0.4 + 0.3 * pulse * uLive);\n" +
                "  col += hc * glow;\n" +
                "  float outer = exp(-abs(dist - (R + 0.08)) * 35.0)\n" +
                "             * (0.18 + 0.12 * sin(uTime * 2.2 + ang * 6.0)) * uLive;\n" +
                "  col += vec3(0.94, 0.64, 0.01) * outer;\n" +
                "  float hub = exp(-dist * dist * 7.0) * 0.14;\n" +
                "  col += vec3(0.18, 0.14, 0.06) * hub;\n" +
                "  float tipAng = start + full * frac;\n" +
                "  vec2 tip = vec2(cos(tipAng), sin(tipAng)) * R;\n" +
                "  float tipD = length(uv - tip);\n" +
                "  col += hc * exp(-tipD * 50.0) * (1.2 + 0.6 * pulse) * step(0.008, frac);\n" +
                "  // soft ticks\n" +
                "  for(float i = 0.0; i <= 12.0; i += 1.0){\n" +
                "    float ta = start + full * (i / 12.0);\n" +
                "    vec2 dir = vec2(cos(ta), sin(ta));\n" +
                "    float along = abs(dot(uv, vec2(-dir.y, dir.x)));\n" +
                "    float rad = dot(uv, dir);\n" +
                "    float tick = (1.0 - smoothstep(0.0, 0.004, along))\n" +
                "               * step(R - 0.05, rad) * step(rad, R + 0.01);\n" +
                "    col += mix(vec3(0.4), hc, 0.5) * tick * 0.35;\n" +
                "  }\n" +
                "  gl_FragColor = vec4(col, 1.0);\n" +
                "}\n";

        private int program;
        private int aPos;
        private int uRes, uSpeed, uLive, uTime;
        private FloatBuffer quad;
        private volatile float speed, target, live;
        private long startNs;
        private int viewW = 1, viewH = 1;

        void setTarget(float mph, boolean isLive) {
            target = mph;
            live = isLive ? 1f : 0f;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.027f, 0.027f, 0.02f, 1f);
            program = link(VSH, FSH);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uRes = GLES20.glGetUniformLocation(program, "uRes");
            uSpeed = GLES20.glGetUniformLocation(program, "uSpeed");
            uLive = GLES20.glGetUniformLocation(program, "uLive");
            uTime = GLES20.glGetUniformLocation(program, "uTime");
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            ByteBuffer bb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder());
            quad = bb.asFloatBuffer();
            quad.put(v).position(0);
            startNs = System.nanoTime();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            viewW = Math.max(1, w);
            viewH = Math.max(1, h);
            GLES20.glViewport(0, 0, viewW, viewH);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            speed += (target - speed) * 0.14f;
            float t = (System.nanoTime() - startNs) / 1_000_000_000f;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uRes, viewW, viewH);
            GLES20.glUniform1f(uSpeed, speed);
            GLES20.glUniform1f(uLive, live);
            GLES20.glUniform1f(uTime, t);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
        }

        private static int link(String vsrc, String fsrc) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vsrc);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fsrc);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
