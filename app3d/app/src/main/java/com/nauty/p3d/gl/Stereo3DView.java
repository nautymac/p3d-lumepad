package com.nauty.p3d.gl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Surface;

import com.future.Holography.Holography;
import com.nauty.p3d.SourceFormat;

import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 영상 -> SBS 합성 -> 렌티큘러 인터레이스 출력.
 *
 * 3D 경로 (원본 3DPlayer 와 동일 구조):
 *   OES 텍스처 -> FBO 좌/우 절반 -> frag3D.sh 인터레이스 -> 화면
 * 소스가 2D 인 경우 좌안은 원본, 우안은 시어를 먹여 인공 시차를 만든다.
 */
public class Stereo3DView extends GLSurfaceView {

    public enum Output { THREE_D, TWO_D, SBS_DEBUG }

    public interface Callback {
        /**
         * 영상 입력면 준비 완료. 재생 엔진을 여기에 물리면 된다.
         * ExoPlayer 는 Surface 를, libVLC 는 SurfaceTexture 를 받으므로 둘 다 넘긴다.
         */
        void onSurfaceReady(Surface surface, SurfaceTexture surfaceTexture);
    }

    // --- GL 스레드와 공유되는 상태 ---
    private volatile SourceFormat sourceFormat = SourceFormat.MONO_2D;
    private volatile Output       output       = Output.THREE_D;
    private volatile boolean      swapLR       = false;
    private volatile float        depth        = 1.0f;    // 2D->3D 시어 배율
    private volatile float        perOffset    = 0.0f;    // 수렴점 (+-0.015)
    private volatile float        bottomCut    = 1.0f;    // 1.0 = 비활성
    private volatile int          videoW       = 16;
    private volatile int          videoH       = 9;

    /** 자막을 화면 앞쪽으로 띄우는 시차(화면 px). 클수록 앞으로 나온다. */
    private volatile float subtitleDepth = 0f;

    /** 자막의 화면 하단으로부터의 위치 (높이 비율). 클수록 위로 올라온다. */
    private volatile float subtitleY = 0.04f;

    private final Object subLock = new Object();
    private Bitmap  pendingSub;      // subLock 으로 보호
    private boolean subDirty;        // subLock 으로 보호

    private Callback callback;

    /**
     * libholography 는 프로세스 전역 상태다 (정적 JNI + 네이티브 전역 버퍼).
     * 초기화 여부를 Renderer 인스턴스 필드로 추적하면, 액티비티가 재생성될 때
     * 새 인스턴스가 "초기화 안 됨" 으로 보고 deinit 없이 HolographyInit 을 다시 부른다.
     * 그러면 네이티브가 마스크를 전부 0 으로 읽어와 인터레이스가 사라진다
     * (증상: 목록으로 나갔다 다른 파일을 열면 3D 가 안 됨. 앱을 완전히 죽여야 복구).
     * 그래서 프로세스 단위로 추적한다.
     */
    private static boolean sHolographyInited = false;

    public Stereo3DView(Context c) { this(c, null); }

    public Stereo3DView(Context c, AttributeSet a) {
        super(c, a);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(new Renderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /**
     * 안전망. 프레임 통지가 어떤 이유로든 유실돼도 주기적으로 렌더를 깨워
     * updateTexImage() 가 호출되게 한다 — 영구 정지를 원천 차단한다.
     * 300ms 주기라 유휴 시 비용은 무시할 수준.
     */
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final Runnable watchdogTick = new Runnable() {
        @Override public void run() {
            requestRender();
            watchdog.postDelayed(this, 300);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        watchdog.removeCallbacks(watchdogTick);
        watchdog.postDelayed(watchdogTick, 300);
    }

    @Override
    public void onPause() {
        watchdog.removeCallbacks(watchdogTick);
        super.onPause();
    }

    public void setCallback(Callback cb)        { callback = cb; }
    public void setSourceFormat(SourceFormat f) { sourceFormat = f; requestRender(); }
    public void setOutput(Output o)             { output = o;       requestRender(); }
    public void setSwapLR(boolean s)            { swapLR = s;       requestRender(); }
    public void setDepth(float d)               { depth = d;        requestRender(); }
    public void setBottomCut(float c)           { bottomCut = c;    requestRender(); }

    public void setPerOffset(float p) {
        perOffset = Math.max(-0.015f, Math.min(0.015f, p));
        requestRender();
    }

    public void setVideoSize(int w, int h) {
        if (w > 0 && h > 0) { videoW = w; videoH = h; requestRender(); }
    }

    /**
     * 표시할 자막 이미지. null 이면 지운다.
     * 넘긴 비트맵의 소유권은 뷰가 가져가며, GL 스레드에서 업로드 후 recycle 한다.
     */
    public void setSubtitleBitmap(Bitmap b) {
        synchronized (subLock) {
            if (pendingSub != null && pendingSub != b && !pendingSub.isRecycled()) {
                pendingSub.recycle();          // 아직 업로드 못 한 이전 것은 버린다
            }
            pendingSub = b;
            subDirty = true;
        }
        requestRender();
    }

    public void setSubtitleDepth(float px) { subtitleDepth = px; requestRender(); }

    /** 하단 여백 비율 (0 = 바닥, 0.3 = 화면 높이의 30% 위). */
    public void setSubtitleY(float frac) {
        subtitleY = Math.max(0f, Math.min(0.45f, frac));
        requestRender();
    }
    public float getSubtitleY() { return subtitleY; }
    public float getSubtitleDepth() { return subtitleDepth; }

    public SourceFormat getSourceFormat() { return sourceFormat; }
    public Output  getOutput()    { return output; }
    public boolean isSwapLR()     { return swapLR; }
    public float   getDepth()     { return depth; }
    public float   getPerOffset() { return perOffset; }

    // -------------------------------------------------------------------

    private class Renderer implements GLSurfaceView.Renderer,
                                      SurfaceTexture.OnFrameAvailableListener {

        private int oesTex;
        private SurfaceTexture surfaceTexture;
        private final float[] stMatrix = new float[16];
        private SourceRenderer src;
        private InterlaceRenderer interlace;
        private BlitRenderer blit;
        private SubtitleRenderer subs;
        private Fbo fbo;
        private int surfW, surfH;

        /**
         * 도착했지만 아직 소비하지 않은 프레임 수.
         *
         * 불린 플래그로 하면 신호가 유실된다: onDrawFrame 이 플래그를 읽고 지우는 사이에
         * onFrameAvailable 이 다시 들어오면 그 프레임 통지가 사라지고, 그러면
         * updateTexImage() 가 호출되지 않아 디코더가 버퍼를 돌려받지 못해 재생이 멈춘다.
         * (증상: 수 초 재생 후 화면 정지)
         */
        private final AtomicInteger pendingFrames = new AtomicInteger(0);

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glDisable(GLES20.GL_BLEND);

            oesTex = GlUtil.createOesTexture();
            surfaceTexture = new SurfaceTexture(oesTex);
            surfaceTexture.setOnFrameAvailableListener(this);

            src       = new SourceRenderer(getContext());
            interlace = new InterlaceRenderer(getContext());
            blit      = new BlitRenderer(getContext());
            subs      = new SubtitleRenderer();

            final Surface s = new Surface(surfaceTexture);
            final SurfaceTexture st = surfaceTexture;
            if (callback != null) {
                post(new Runnable() {
                    @Override public void run() { callback.onSurfaceReady(s, st); }
                });
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            surfW = w;
            surfH = h;
            if (fbo != null) fbo.release();
            fbo = new Fbo(w, h);

            // 반드시 deinit -> init 순서. 이전 액티비티가 남긴 상태가 있으면 먼저 정리한다.
            synchronized (Stereo3DView.class) {
                if (sHolographyInited) {
                    Holography.deinitHolography();
                    sHolographyInited = false;
                    GlUtil.logi("이전 Holography 상태 해제");
                }
                Holography.HolographyInit(w, h);
                sHolographyInited = true;
            }
            GlUtil.logi("surface " + w + "x" + h + ", HolographyInit 완료");
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            pendingFrames.incrementAndGet();
            requestRender();
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // 밀린 프레임을 전부 소비한다. 소비하지 않으면 그 버퍼가 디코더로 반납되지 않는다.
            int n = pendingFrames.getAndSet(0);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    surfaceTexture.updateTexImage();   // 남은 게 없으면 no-op, 블로킹 없음
                }
                surfaceTexture.getTransformMatrix(stMatrix);
            }

            // 새 자막이 있으면 텍스처로 올린다.
            Bitmap newSub = null;
            boolean subChanged = false;
            synchronized (subLock) {
                if (subDirty) {
                    newSub = pendingSub;
                    pendingSub = null;
                    subDirty = false;
                    subChanged = true;
                }
            }
            if (subChanged) {
                subs.upload(newSub);
                if (newSub != null && !newSub.isRecycled()) newSub.recycle();
            }

            Output out = output;
            if (out == Output.TWO_D) {
                drawMono();
            } else {
                renderSbsToFbo();

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, surfW, surfH);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

                if (out == Output.SBS_DEBUG) {
                    blit.draw(fbo.texture(), 0f, 0f, 1f, 1f);
                } else {
                    interlace.draw(fbo.texture(), perOffset);
                }
            }

            // 그리는 동안 새 프레임이 들어왔으면 다시 요청한다.
            // (requestRender 는 누적되지 않으므로 여기서 확실히 이어준다)
            if (pendingFrames.get() > 0) requestRender();
        }

        /** 좌/우 뷰를 FBO 의 양쪽 절반에 그린다. */
        private void renderSbsToFbo() {
            fbo.bind();
            GLES20.glViewport(0, 0, fbo.width, fbo.height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            SourceFormat f = sourceFormat;
            float[] uvL = uvFor(f, true);
            float[] uvR = uvFor(f, false);

            // 화면상 종횡비가 맞도록 letterbox 계산.
            // 3D 출력에서 FBO 절반(W/2 x H)이 화면 전체(W x H)로 늘어나므로
            // 절반 안에서의 폭은 화면 기준 폭의 1/2 이어야 한다.
            float as = sourceAspect(f);
            float sw, sh;
            if ((float) surfW / (float) surfH > as) {
                sh = surfH;
                sw = surfH * as;
            } else {
                sw = surfW;
                sh = surfW / as;
            }
            int dw    = Math.max(1, (int) (sw / 2f));
            int dh    = Math.max(1, (int) sh);
            int halfW = fbo.width / 2;
            int dy    = (fbo.height - dh) / 2;
            int dxL   = (halfW - dw) / 2;
            int dxR   = halfW + dxL;

            float shearTop   = 0f;
            float shearSlope = 0f;
            if (f == SourceFormat.MONO_2D) {
                // 원본 frag2dto3d.sh: x += 0.004 - y * screenHeight * 0.0000122
                // 원본에서 screenHeight 유니폼에 들어간 값이 실제로는 가로 해상도였다.
                shearTop   = 0.004f * depth;
                shearSlope = 0.0000122f * surfW * depth;
            }

            // 좌안: 원본 그대로
            GLES20.glViewport(dxL, dy, dw, dh);
            src.draw(oesTex, stMatrix, uvL[0], uvL[1], uvL[2], uvL[3], 0f, 0f, bottomCut);

            // 우안: 2D 소스면 시어 적용
            GLES20.glViewport(dxR, dy, dw, dh);
            src.draw(oesTex, stMatrix, uvR[0], uvR[1], uvR[2], uvR[3], shearTop, shearSlope, bottomCut);

            // 자막은 좌/우 뷰에 각각 그리되 서로 반대로 밀어 화면 앞쪽에 뜨게 한다.
            // (음의 시차: 좌안은 오른쪽으로, 우안은 왼쪽으로)
            float shiftHalf = subtitleDepth / 2f;   // 절반은 가로로 2배 늘어나므로 절반만 민다
            drawSubtitleInHalf(0,     halfW,  shiftHalf);
            drawSubtitleInHalf(halfW, halfW, -shiftHalf);

            fbo.unbind();
        }

        /** FBO 한쪽 절반의 하단 중앙에 자막을 배치한다. */
        private void drawSubtitleInHalf(int originX, int halfW, float shiftHalfPx) {
            if (subs == null || !subs.hasSubtitle()) return;

            float onScreenW = Math.min(subs.width(), surfW * 0.92f);
            float k = onScreenW / (float) subs.width();
            int w = Math.max(1, (int) (onScreenW / 2f));      // 절반 안에서는 가로 1/2
            int h = Math.max(1, (int) (subs.height() * k));
            int y = (int) (fbo.height * subtitleY);
            int x = originX + (halfW - w) / 2 + (int) shiftHalfPx;

            GLES20.glViewport(x, y, w, h);
            subs.draw();
        }

        /** 2D 출력: 한쪽 뷰만 화면 전체에. */
        private void drawMono() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, surfW, surfH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            SourceFormat f = sourceFormat;
            float[] uv = uvFor(f, true);
            float as = sourceAspect(f);
            float sw, sh;
            if ((float) surfW / (float) surfH > as) {
                sh = surfH;
                sw = surfH * as;
            } else {
                sw = surfW;
                sh = surfW / as;
            }
            GLES20.glViewport((int) ((surfW - sw) / 2f), (int) ((surfH - sh) / 2f),
                              Math.max(1, (int) sw), Math.max(1, (int) sh));
            src.draw(oesTex, stMatrix, uv[0], uv[1], uv[2], uv[3], 0f, 0f, bottomCut);

            // 2D 출력에서는 자막도 한 번만, 시차 없이.
            if (subs != null && subs.hasSubtitle()) {
                float onScreenW = Math.min(subs.width(), surfW * 0.92f);
                float k = onScreenW / (float) subs.width();
                int w = Math.max(1, (int) onScreenW);
                int h = Math.max(1, (int) (subs.height() * k));
                GLES20.glViewport((surfW - w) / 2, (int) (surfH * subtitleY), w, h);
                subs.draw();
            }
        }

        /** 크롭 영역 (u0,v0,u1,v1). v 는 아래가 0. half/full 은 크롭이 같고 종횡비만 다르다. */
        private float[] uvFor(SourceFormat f, boolean leftEye) {
            boolean left = swapLR ? !leftEye : leftEye;
            if (f.isSbs()) {
                return left ? new float[]{0f, 0f, 0.5f, 1f}
                            : new float[]{0.5f, 0f, 1f, 1f};
            }
            if (f.isTb()) {
                // 관례상 위쪽이 좌안. v 는 아래가 0 이므로 위쪽 = [0.5, 1]
                return left ? new float[]{0f, 0.5f, 1f, 1f}
                            : new float[]{0f, 0f, 1f, 0.5f};
            }
            return new float[]{0f, 0f, 1f, 1f};
        }

        private float sourceAspect(SourceFormat f) {
            return f.displayAspect(videoW, videoH);
        }
    }
}
