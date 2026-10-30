package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.media3.common.Effect;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.SingleFrameGlShaderProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/*
 * সব ইফেক্ট/ফিল্টার এক ফাইলে:
 *  ShortFx            – ইফেক্ট তালিকা, রঙ-ম্যাট্রিক্স
 *  FxShader           – Glitch / Ultra / ফিল্টারের GLSL শেডার
 *  FxEffect           – এডিট প্রিভিউ + এক্সপোর্টের Media3 effect
 *  FxSurfaceProcessor – ক্যামেরার লাইভ ইফেক্ট (CameraX + OpenGL)
 *  GlitchIconDrawable / UltraIconDrawable – চিপের আইকন
 */

/** ক্যামেরা/এডিটের ইফেক্ট তালিকা। 0 None, 1 HD, 2 4K (এ দুটো রেকর্ডের কোয়ালিটি), 3.. শেডার ইফেক্ট। */
final class ShortFx {
    /** শুধু এডিট স্ক্রিনের "4K Ultra" চিপ: আসল sharpen + উজ্জ্বল রঙ */
    static final int ULTRA = 12;
    static final String[] NAMES = {"No Fx", "HD", "4K", "iPhone", "Glitch", "Black & white", "Vintage", "Cinematic", "Soft glow", "Warm", "Cool", "Beauty"};

    private ShortFx() { }

    /** ক্যামেরার চিপ নম্বর → শেডার মোড (4K চিপ = Ultra HD ইফেক্ট) */
    static int modeFor(int chip) { return chip == 2 ? ULTRA : chip >= 3 ? chip : 0; }

    static boolean glitch(int idx) { return idx == 4; }
    static boolean sharp(int idx) { return idx == ULTRA; }

    /** Android-স্টাইল 4x5 রঙ-ম্যাট্রিক্স (শুধু গুণক); null = কোনো রঙ-বদল নেই */
    private static float[] rows(int idx) {
        switch (idx) {
            case 3:  // iPhone
                return new float[]{1.12f, 0.06f, 0.00f, 0, 0,
                                   0.02f, 1.06f, 0.02f, 0, 0,
                                   0.00f, 0.04f, 0.92f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 5:  // Black & white
                return new float[]{0.299f, 0.587f, 0.114f, 0, 0,
                                   0.299f, 0.587f, 0.114f, 0, 0,
                                   0.299f, 0.587f, 0.114f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 6:  // Vintage
                return new float[]{0.60f, 0.45f, 0.12f, 0, 0,
                                   0.35f, 0.62f, 0.10f, 0, 0,
                                   0.25f, 0.40f, 0.30f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 7:  // Cinematic
                return new float[]{1.12f, 0.00f, -0.04f, 0, 0,
                                   -0.02f, 1.00f, 0.06f, 0, 0,
                                   -0.06f, 0.10f, 1.12f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 8:  // Soft glow
                return new float[]{1.02f, 0.10f, 0.10f, 0, 0,
                                   0.10f, 1.02f, 0.10f, 0, 0,
                                   0.10f, 0.10f, 1.02f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 9:  // Warm
                return new float[]{1.15f, 0, 0, 0, 0,
                                   0, 1.02f, 0, 0, 0,
                                   0, 0, 0.85f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 10: // Cool
                return new float[]{0.86f, 0, 0, 0, 0,
                                   0, 1.00f, 0, 0, 0,
                                   0, 0, 1.15f, 0, 0,
                                   0, 0, 0, 1, 0};
            case 11: // Beauty
                return new float[]{1.08f, 0.04f, 0.02f, 0, 0,
                                   0.02f, 1.04f, 0.02f, 0, 0,
                                   0.02f, 0.04f, 1.00f, 0, 0,
                                   0, 0, 0, 1, 0};
            default:
                return null;   // None / HD / 4K / Glitch: রঙ অপরিবর্তিত
        }
    }

    /** শেডারের uColor (mat4, column-major) */
    static float[] color16(int idx) {
        float[] m = {1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1};
        float[] r = rows(idx);
        if (r == null) return m;
        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++) m[col * 4 + row] = r[row * 5 + col];
        return m;
    }

    /** Media3 effect (এডিট প্রিভিউ ও এক্সপোর্ট); None/HD/4K হলে null */
    @OptIn(markerClass = UnstableApi.class)
    static Effect gl(int idx) {
        return idx >= 3 ? new FxEffect(idx) : null;
    }
}

/**
 * সব ইফেক্টের একটাই GLSL শেডার (ক্যামেরার লাইভ প্রিভিউ/রেকর্ড, এডিট প্রিভিউ আর এক্সপোর্টে হুবহু একই):
 *  - Glitch: ফ্রেমের আড়াআড়ি ফালি সরে যাওয়া, লাল/নীল চ্যানেল আলাদা হওয়া, স্ক্যান-লাইন (সময় ধরে ঝলকে ঝলকে)
 *  - 4K Ultra: আসল তীক্ষ্ণতা বাড়ানো (sharpen কনভোলিউশন) + উজ্জ্বল রঙ
 *  - বাকি ফিল্টার: uColor রঙ-ম্যাট্রিক্স
 */
final class FxShader {
    private FxShader() { }

    static final String VS =
            "attribute vec4 aFramePosition;\n"
          + "varying vec2 vOut;\n"
          + "void main() {\n"
          + "  gl_Position = aFramePosition;\n"
          + "  vOut = aFramePosition.xy * 0.5 + 0.5;\n"
          + "}\n";

    /** oes = true হলে ক্যামেরার external texture, নইলে Media3-র সাধারণ 2D texture */
    static String fs(boolean oes) {
        return (oes ? "#extension GL_OES_EGL_image_external : require\n" : "")
          + "precision highp float;\n"
          + "uniform " + (oes ? "samplerExternalOES" : "sampler2D") + " uTexSampler;\n"
          + "uniform mat4 uTexMatrix;\n"
          + "uniform mat4 uColor;\n"
          + "uniform float uTime;\n"
          + "uniform float uGlitch;\n"
          + "uniform float uSharp;\n"
          + "uniform vec2 uTexel;\n"
          + "varying vec2 vOut;\n"
          + "float rnd(float x) { return fract(sin(x * 12.9898) * 43758.5453); }\n"
          + "vec3 samp(vec2 o) {\n"
          + "  vec2 t = (uTexMatrix * vec4(clamp(o, 0.0, 1.0), 0.0, 1.0)).xy;\n"
          + "  return texture2D(uTexSampler, t).rgb;\n"
          + "}\n"
          + "void main() {\n"
          + "  vec2 o = vOut;\n"
          + "  float sp = 0.0;\n"
          + "  float t = floor(uTime * 8.0);\n"
          + "  float on = step(0.50, rnd(t));\n"
          + "  if (uGlitch > 0.5) {\n"
          + "    float inBand = 0.0;\n"
          + "    for (int i = 0; i < 3; i++) {\n"
          + "      float s = t * 5.0 + float(i) * 11.0;\n"
          + "      float y0 = rnd(s) * 0.92;\n"
          + "      float h = 0.015 + rnd(s + 1.0) * 0.07;\n"
          + "      float hit = step(y0, o.y) * step(o.y, y0 + h) * step(0.35, rnd(s + 2.0)) * on;\n"
          + "      o.x += (rnd(s + 3.0) - 0.5) * 0.16 * hit;\n"
          + "      inBand = max(inBand, hit);\n"
          + "    }\n"
          + "    sp = 0.002 + 0.004 * on + 0.010 * inBand;\n"
          + "  }\n"
          + "  vec3 c;\n"
          + "  c.r = samp(vec2(o.x + sp, o.y)).r;\n"
          + "  c.g = samp(o).g;\n"
          + "  c.b = samp(vec2(o.x - sp, o.y)).b;\n"
          + "  if (uGlitch > 0.5) c *= 0.97 + 0.03 * sin(vOut.y * 1000.0);\n"
          + "  if (uSharp > 0.5) {\n"
          + "    vec2 r = uTexel * 1.5;\n"
          + "    vec3 n = samp(o + vec2(r.x, 0.0)) + samp(o - vec2(r.x, 0.0)) + samp(o + vec2(0.0, r.y)) + samp(o - vec2(0.0, r.y));\n"
          + "    c = c + (c - n * 0.25) * 1.8;\n"
          + "    c = clamp(c, 0.0, 1.0);\n"
          + "    c = mix(c, c * c * (3.0 - 2.0 * c), 0.45);\n"
          + "    float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
          + "    c = mix(vec3(l), c, 1.35);\n"
          + "  }\n"
          + "  c = clamp((uColor * vec4(c, 1.0)).rgb, 0.0, 1.0);\n"
          + "  gl_FragColor = vec4(c, 1.0);\n"
          + "}\n";
    }
}

/** FxShader-এর Media3 সংস্করণ: এডিট স্ক্রিনের প্রিভিউ (ExoPlayer) আর এক্সপোর্ট (Transformer) দুটোতেই চলে। */
@OptIn(markerClass = UnstableApi.class)
final class FxEffect implements GlEffect {
    private final int mode;

    FxEffect(int mode) { this.mode = mode; }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        try {
            return new Program(mode, useHdr);
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    private static final class Program extends SingleFrameGlShaderProgram {
        private final GlProgram gl;
        private final float[] color;
        private final float glitch, sharp;
        private final float[] identity = {1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1};
        private float tw = 1f, th = 1f;

        Program(int mode, boolean useHdr) throws GlUtil.GlException {
            super(useHdr);
            color = ShortFx.color16(mode);
            glitch = ShortFx.glitch(mode) ? 1f : 0f;
            sharp = ShortFx.sharp(mode) ? 1f : 0f;
            gl = new GlProgram(FxShader.VS, FxShader.fs(false));
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        }

        @Override
        public androidx.media3.common.util.Size configure(int inputWidth, int inputHeight) {
            tw = 1f / Math.max(1, inputWidth);
            th = 1f / Math.max(1, inputHeight);
            return new androidx.media3.common.util.Size(inputWidth, inputHeight);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatsUniform("uTexMatrix", identity);
                gl.setFloatsUniform("uColor", color);
                gl.setFloatUniform("uTime", (presentationTimeUs % 100_000_000L) / 1_000_000f);
                gl.setFloatUniform("uGlitch", glitch);
                gl.setFloatUniform("uSharp", sharp);
                gl.setFloatsUniform("uTexel", new float[]{tw, th});
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e, presentationTimeUs);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try { gl.delete(); } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
        }
    }
}

/**
 * ক্যামেরার লাইভ ইফেক্ট (CameraX SurfaceProcessor + OpenGL ES): ক্যামেরার ফ্রেম FxShader দিয়ে প্রসেস হয়ে
 * প্রিভিউ আর রেকর্ডিং — দুই জায়গাতেই যায়। অর্থাৎ ক্যামেরায় যা দেখেন, রেকর্ডও ঠিক তা-ই।
 */
final class FxSurfaceProcessor implements SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "FxSurfaceProcessor";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final class Out { EGLSurface es; int w, h; final float[] m = new float[16]; }

    private final HandlerThread thread = new HandlerThread("FxGL");
    private final Handler gh;
    private final Executor executor;
    private final Map<SurfaceOutput, Out> outs = new HashMap<>();
    private final float[] stMat = new float[16];
    private final FloatBuffer quad;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface pbuf = EGL14.EGL_NO_SURFACE;
    private int prog, texId, locPos, locTexMatrix, locColor, locTime, locGlitch, locSharp, locTexel, locSampler;
    private SurfaceTexture st;
    private boolean ready = false;
    private volatile int mode = 0;

    FxSurfaceProcessor() {
        thread.start();
        gh = new Handler(thread.getLooper());
        executor = r -> gh.post(r);
        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        gh.post(this::initGl);
    }

    Executor getExecutor() { return executor; }

    void setMode(int m) { mode = m; }

    // ------------------------------------------------------------ GL শুরু

    private void initGl() {
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            EGL14.eglInitialize(dpy, v, 0, v, 1);
            int[] a = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] cs = new EGLConfig[1];
            int[] n = new int[1];
            EGL14.eglChooseConfig(dpy, a, 0, cs, 0, 1, n, 0);
            cfg = cs[0];
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);

            prog = link(FxShader.VS, FxShader.fs(true));
            locPos = GLES20.glGetAttribLocation(prog, "aFramePosition");
            locTexMatrix = GLES20.glGetUniformLocation(prog, "uTexMatrix");
            locColor = GLES20.glGetUniformLocation(prog, "uColor");
            locTime = GLES20.glGetUniformLocation(prog, "uTime");
            locGlitch = GLES20.glGetUniformLocation(prog, "uGlitch");
            locSharp = GLES20.glGetUniformLocation(prog, "uSharp");
            locTexel = GLES20.glGetUniformLocation(prog, "uTexel");
            locSampler = GLES20.glGetUniformLocation(prog, "uTexSampler");

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            ready = true;
        } catch (Throwable e) {
            Log.e(TAG, "GL init failed", e);
        }
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    // ------------------------------------------------------------ SurfaceProcessor (CameraX এই executor-এই ডাকে)

    @Override
    public void onInputSurface(SurfaceRequest request) {
        if (!ready) { request.willNotProvideSurface(); return; }
        android.util.Size sz = request.getResolution();
        final SurfaceTexture t = new SurfaceTexture(texId);
        t.setDefaultBufferSize(sz.getWidth(), sz.getHeight());
        final Surface s = new Surface(t);
        st = t;
        t.setOnFrameAvailableListener(this, gh);
        request.provideSurface(s, executor, r -> {
            t.setOnFrameAvailableListener(null);
            if (st == t) st = null;
            t.release();
            s.release();
        });
    }

    @Override
    public void onOutputSurface(final SurfaceOutput output) {
        if (!ready) { output.close(); return; }
        Surface s = output.getSurface(executor, event -> {
            Out o = outs.remove(output);
            if (o != null) {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                EGL14.eglDestroySurface(dpy, o.es);
            }
            output.close();
        });
        Out o = new Out();
        o.es = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[]{EGL14.EGL_NONE}, 0);
        android.util.Size sz = output.getSize();
        o.w = sz.getWidth();
        o.h = sz.getHeight();
        outs.put(output, o);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture t) {
        if (!ready || t != st) return;
        try {
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
            t.updateTexImage();
        } catch (Exception e) { return; }
        t.getTransformMatrix(stMat);
        long ts = t.getTimestamp();
        int m = mode;
        float tsec = (float) ((ts / 1_000_000L) % 100_000L) / 1000f;
        for (Map.Entry<SurfaceOutput, Out> e : outs.entrySet()) {
            Out o = e.getValue();
            EGL14.eglMakeCurrent(dpy, o.es, o.es, ctx);
            GLES20.glViewport(0, 0, o.w, o.h);
            e.getKey().updateTransformMatrix(o.m, stMat);
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glUniform1i(locSampler, 0);
            GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, o.m, 0);
            GLES20.glUniformMatrix4fv(locColor, 1, false, ShortFx.color16(m), 0);
            GLES20.glUniform1f(locTime, tsec);
            GLES20.glUniform1f(locGlitch, ShortFx.glitch(m) ? 1f : 0f);
            GLES20.glUniform1f(locSharp, ShortFx.sharp(m) ? 1f : 0f);
            GLES20.glUniform2f(locTexel, 1f / o.w, 1f / o.h);
            quad.position(0);
            GLES20.glEnableVertexAttribArray(locPos);
            GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 8, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(locPos);
            EGLExt.eglPresentationTimeANDROID(dpy, o.es, ts);
            EGL14.eglSwapBuffers(dpy, o.es);
        }
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
    }

    void release() {
        gh.post(() -> {
            try {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
                for (Out o : outs.values()) EGL14.eglDestroySurface(dpy, o.es);
                outs.clear();
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(dpy, pbuf);
                EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            } catch (Throwable ignored) { }
            thread.quitSafely();
        });
    }
}

/**
 * Glitch চিপের আইকন: কালো গোল, সাদা কিনারা, ভাঙা সায়ান/ম্যাজেন্টা/সাদা রিং আর
 * মাঝে রঙ-আলাদা হওয়া "GLITCH" লেখা। বাছা থাকলে চারপাশে ধূসর হ্যালো।
 */
final class GlitchIconDrawable extends Drawable {
    private static final int CYAN = 0xFF00E5FF, MAGENTA = 0xFFFF2DAA;
    private final boolean on;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    GlitchIconDrawable(boolean on, float density) { this.on = on; this.d = density; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float R = Math.min(b.width(), b.height()) / 2f;
        if (on) { p.setStyle(Paint.Style.FILL); p.setColor(0x66999999); c.drawCircle(cx, cy, R, p); }
        float rr = on ? R * 0.78f : R * 0.92f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? Color.WHITE : 0xFF8A8A8A);
        c.drawCircle(cx, cy, rr, p);
        p.setColor(Color.BLACK);
        c.drawCircle(cx, cy, rr - d * 2.5f, p);

        // ভাঙা গ্লিচ রিং: তিন রঙ, আলাদা আলাদা অংশ, সামান্য সরানো
        float ringR = rr * 0.62f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d * 2.2f);
        p.setStrokeCap(Paint.Cap.BUTT);
        RectF ring = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
        arcs(c, ring, -d * 1.3f, CYAN, new float[]{15, 125, 190, 150});
        arcs(c, ring, d * 1.3f, MAGENTA, new float[]{-5, 115, 175, 135});
        arcs(c, ring, 0f, Color.WHITE, new float[]{25, 105, 205, 115});

        // লেখার পেছনে রিং কেটে দেওয়া কালো ফালি
        float ts = rr * 0.30f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.BLACK);
        c.drawRect(cx - ringR - d * 3, cy - ts * 0.62f, cx + ringR + d * 3, cy + ts * 0.62f, p);

        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(ts);
        p.setTextAlign(Paint.Align.CENTER);
        float ty = cy + ts * 0.35f;
        p.setColor(CYAN);    c.drawText("GLITCH", cx - d * 1.1f, ty, p);
        p.setColor(MAGENTA); c.drawText("GLITCH", cx + d * 1.1f, ty, p);
        p.setColor(Color.WHITE); c.drawText("GLITCH", cx, ty, p);
    }

    private void arcs(Canvas c, RectF r, float dx, int color, float[] a) {
        p.setColor(color);
        c.save();
        c.translate(dx, 0);
        c.drawArc(r, a[0], a[1], false, p);
        c.drawArc(r, a[2], a[3], false, p);
        c.restore();
    }

    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

/** 4K চিপের আইকন: কালো গোল, সাদা কিনারা, ভেতরে হলুদ বর্ডারের বক্সে বড় "4K" আর নিচে ছোট "ULTRA"। */
final class UltraIconDrawable extends Drawable {
    private static final int GOLD = 0xFFFFB300;
    private final boolean on;
    private final float d;
    private final String big;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    UltraIconDrawable(String big, boolean on, float density) { this.big = big; this.on = on; this.d = density; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float R = Math.min(b.width(), b.height()) / 2f;
        if (on) { p.setStyle(Paint.Style.FILL); p.setColor(0x66999999); c.drawCircle(cx, cy, R, p); }
        float rr = on ? R * 0.78f : R * 0.92f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? Color.WHITE : 0xFF8A8A8A);
        c.drawCircle(cx, cy, rr, p);
        p.setColor(Color.BLACK);
        c.drawCircle(cx, cy, rr - d * 2.5f, p);

        // হলুদ বর্ডারের বক্স
        float bw = rr * 1.05f, bh = rr * 1.05f;
        RectF box = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d * 2f);
        p.setColor(GOLD);
        c.drawRoundRect(box, d * 5, d * 5, p);

        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(GOLD);
        p.setTextSize(rr * 0.62f);
        c.drawText(big, cx, cy + rr * 0.10f, p);
        p.setTextSize(rr * 0.17f);
        c.drawText("ULTRAHD", cx, cy + rr * 0.40f, p);
    }

    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

/** "No Fx" (মূল ক্যামেরা) চিপের আইকন: সাদা গোল + কালো Shorts লোগো (বাছা থাকলে ধূসর হ্যালো) */
final class ShortsIconDrawable extends Drawable {
    private static final int RED = 0xFF111111;   // কালো Shorts আইকন, সাদা বৃত্তে
    private final boolean on;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    ShortsIconDrawable(boolean on, float density) { this.on = on; this.d = density; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float R = Math.min(b.width(), b.height()) / 2f;
        if (on) { p.setStyle(Paint.Style.FILL); p.setColor(0x66999999); c.drawCircle(cx, cy, R, p); }
        float rr = on ? R * 0.78f : R * 0.92f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? Color.WHITE : 0xFFE6E6E6);
        c.drawCircle(cx, cy, rr, p);

        float h = rr * 1.05f, w = rr * 0.55f;
        RectF body = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        p.setColor(RED);
        c.drawRoundRect(body, w * 0.5f, w * 0.5f, p);
        // Shorts লোগোর দুই কোণের খাঁজ
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(d * 2.4f);
        p.setColor(on ? Color.WHITE : 0xFFE6E6E6);
        c.drawLine(cx + w * 0.50f, cy - h * 0.26f, cx + w * 0.10f, cy - h * 0.50f, p);
        c.drawLine(cx - w * 0.50f, cy + h * 0.26f, cx - w * 0.10f, cy + h * 0.50f, p);
        // সাদা প্লে ত্রিভুজ
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        Path t = new Path();
        t.moveTo(cx - w * 0.16f, cy - w * 0.24f);
        t.lineTo(cx - w * 0.16f, cy + w * 0.24f);
        t.lineTo(cx + w * 0.26f, cy);
        t.close();
        c.drawPath(t, p);
    }

    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

/** CameraX 1.3-এ CameraEffect.Builder নেই, তাই সাবক্লাস: প্রিভিউ + ভিডিও রেকর্ড দুটোতেই ইফেক্ট বসে */
final class FxCameraEffect extends CameraEffect {
    FxCameraEffect(FxSurfaceProcessor proc) {
        super(CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE, proc.getExecutor(), proc, t -> { });
    }
}
