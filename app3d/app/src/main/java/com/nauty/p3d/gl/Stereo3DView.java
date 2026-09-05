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
    /** 화면 비 강제값 (0 = 소스 그대로). 레터박스가 거슬리거나 소스 비율이 틀린 경우용. */
    public static final float ASPECT_FILL = -1f;

    /**
     * 2D→3D 시어 램프에서 시차가 0 이 되는 세로 위치 (0=위, 1=아래).
     * 0.5 면 화면 중앙이 스크린 평면이 되고 위아래가 각각 뒤/앞으로 갈린다.
     * 원본 3DPlayer 의 상수쌍은 0.128 이었다 — 사실상 맨 위 기준이라
     * 아래쪽만 시차가 몰렸다.
     */
    private static final float SHEAR_PIVOT = 0.5f;

    private volatile int          videoW       = 16;
    private volatile int          videoH       = 9;
    private volatile float        aspectOverride = 0f;

    /**
     * SBS 를 우리 화면 대신 남의 서피스로 내보낸다 (Lume Pad 2 의 CNSDK).
     *
     * 그 패널은 8방향 회절 + 얼굴추적이라 위빙을 CNSDK 가 해야 한다. 우리는 SBS 까지만
     * 만들어 넘기고 인터레이스 단계를 건너뛴다. null 이면 지금까지대로 우리가 인터레이스한다.
     */
    private volatile Surface extTarget = null;
    private volatile int     extW = 0, extH = 0;

    /**
     * 렌티큘러 마스크를 쓸지. ProMa 는 true(기본), Lume Pad 2 는 false —
     * 그 기기에는 libholography 가 만드는 마스크가 맞지 않고 쓸 일도 없다.
     */
    private volatile boolean useHolography = true;

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

    /**
     * 한쪽 눈 그림의 종횡비를 강제한다.
     * 0 이면 소스 해상도에서 계산한 값을 쓰고 (기본), {@link #ASPECT_FILL} 이면
     * 화면 비율에 맞춰 늘려 레터박스를 없앤다.
     */
    public void setAspectOverride(float a) { aspectOverride = a; requestRender(); }
    public float getAspectOverride()       { return aspectOverride; }

    /**
     * SBS 출력을 이 서피스로 보낸다. 크기는 <b>두 눈이 담긴 전체 프레임</b> 기준이다
     * (눈당 1920x1200 이면 3840x1200). GL 표면이 만들어지기 전에 불러도 된다.
     */
    public void setExternalSbsTarget(Surface s, int w, int h) {
        extTarget = s; extW = w; extH = h;
        requestRender();
    }

    /** 렌티큘러 마스크 사용 여부. GL 표면이 만들어지기 전에 정해야 한다. */
    public void setUseHolography(boolean v) { useHolography = v; }

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
        /** SBS 를 남의 서피스로 내보낼 때 쓰는 두 번째 EGL 서피스 (Lume Pad 2). */
        private ExternalGlTarget external;
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

            // 외부로 내보낼 때는 FBO 가 곧 내보낼 프레임이므로 그 크기로 만든다.
            // 우리가 인터레이스할 때는 지금까지대로 화면 크기.
            int fw = extTarget != null && extW > 0 ? extW : w;
            int fh = extTarget != null && extH > 0 ? extH : h;
            if (fbo != null) fbo.release();
            fbo = new Fbo(fw, fh);
            GlUtil.logi("surface " + w + "x" + h + ", FBO " + fw + "x" + fh);

            if (!useHolography) return;   // Lume Pad 2 등: 마스크를 쓰지 않는다

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
            GlUtil.logi("HolographyInit 완료");
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
            if (extTarget != null) {
                // FBO 크기는 외부 타깃을 따라야 한다.
                //
                // onSurfaceChanged 에서만 만들면 순서에 걸린다: 우리 GLSurfaceView 는
                // 1x1 로 깔려 있어 표면이 먼저 만들어지는 경우가 있고, 그때는 아직
                // 외부 타깃이 없어 FBO 가 1x1 로 잡힌 뒤 다시 만들어지지 않는다
                // (뷰 크기가 안 변하니 onSurfaceChanged 가 다시 오지 않는다).
                // 그러면 1x1 을 화면 전체로 늘려 뿌리게 된다.
                if (fbo == null || fbo.width != extW || fbo.height != extH) {
                    if (fbo != null) fbo.release();
                    fbo = new Fbo(extW, extH);
                    GlUtil.logi("FBO 를 외부 타깃 크기로 재생성 " + extW + "x" + extH);
                }
                // 외부(CNSDK)로 SBS 를 넘긴다. 인터레이스는 저쪽이 한다.
                renderSbsToFbo();
                drawToExternal();
            } else if (out == Output.TWO_D) {
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

        /** FBO 에 만들어 둔 SBS 를 외부 서피스(CNSDK)로 내보낸다. */
        private void drawToExternal() {
            Surface s = extTarget;
            if (s == null) return;

            if (external != null && !external.isFor(s)) {
                external.release();
                external = null;
            }
            if (external == null) {
                external = ExternalGlTarget.create(s, extW, extH);
                if (external == null) { extTarget = null; return; }   // 한 번 실패하면 포기
            }

            if (!external.makeCurrent()) {
                GlUtil.logi("외부 타깃 makeCurrent 실패");
                return;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, external.width, external.height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            blit.draw(fbo.texture(), 0f, 0f, 1f, 1f);
            external.swapAndRestore();
        }

        /**
         * 눈 하나가 화면에서 갖는 종횡비.
         *
         * ProMa 는 FBO 의 반쪽(1280x1600)이 화면 전체(2560x1600)로 늘어나므로 화면 비와 같고,
         * CNSDK 로 넘길 때는 반쪽(1920x1200)이 그대로 눈 상자가 되므로 반쪽 비와 같다.
         * 이 값이 있어야 어느 쪽이든 레터박스를 같은 식으로 계산할 수 있다.
         */
        private float eyeDisplayAspect() {
            if (extTarget != null && extH > 0) return (extW / 2f) / extH;
            return (float) surfW / (float) surfH;
        }

        /**
         * 눈 하나가 화면에서 갖는 가로 픽셀 수.
         * 시어 세기와 자막 크기가 "화면에서 얼마나" 를 기준으로 정해지므로 필요하다.
         */
        private float eyeDisplayWidth() {
            if (extTarget != null && extW > 0) return extW / 2f;
            return surfW;
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

            // 레터박스 계산. 눈 하나가 화면에서 갖는 상자(eyeDisplayAspect)에 소스 비율을
            // 맞춰 넣고, 그 결과를 FBO 반쪽 픽셀로 환산한다.
            //
            // 환산이 필요한 이유: ProMa 는 FBO 반쪽(1280x1600)이 화면 전체(2560x1600)로
            // 늘어나는 아나모픽이라 폭이 절반으로 눌려 있고, CNSDK 로 넘길 때는
            // 반쪽(1920x1200)이 그대로 눈 상자라 눌림이 없다. 두 경우를 같은 식으로 다룬다.
            int   halfW    = fbo.width / 2;
            int   boxH     = fbo.height;
            float eyeAsp   = eyeDisplayAspect();
            float boxDispW = boxH * eyeAsp;          // 눈 상자를 화면 단위로 본 폭

            float as = sourceAspect(f);
            float dispW, dispH;
            if (eyeAsp > as) {                        // 상자가 소스보다 옆으로 넓다 -> 좌우 여백
                dispH = boxH;
                dispW = boxH * as;
            } else {                                  // 상자가 더 좁다 -> 위아래 여백
                dispW = boxDispW;
                dispH = boxDispW / as;
            }
            int dw  = Math.max(1, Math.round(dispW * halfW / boxDispW));   // 화면 단위 -> FBO 픽셀
            int dh  = Math.max(1, Math.round(dispH));
            int dy  = (boxH - dh) / 2;
            int dxL = (halfW - dw) / 2;
            int dxR = halfW + dxL;

            float shearTop   = 0f;
            float shearSlope = 0f;
            if (f == SourceFormat.MONO_2D) {
                // 원본 frag2dto3d.sh: x += 0.004 - y * screenHeight * 0.0000122
                // 원본에서 screenHeight 유니폼에 들어간 값이 실제로는 가로 해상도였다.
                //
                // 기울기는 원본 그대로 두고, 램프가 0 이 되는 높이(SHEAR_PIVOT)만 옮겼다.
                // 원본 상수쌍의 영점은 0.004/0.031232 = v 0.128 — 화면 최상단 근처다.
                // 그러면 시차가 아래로 갈수록 한 방향으로만 커져서
                //   · 위쪽은 슬라이더를 움직여도 거의 변하지 않고
                //   · 화면 아래에서 한쪽 눈이 depth=1 에 약 70px, 최대치에선 200px 넘게 밀린다
                // 융합 한계를 넘으면 3D 가 아니라 그냥 찌그러져 보인다.
                //
                // 영점을 세로 중앙에 두면 위는 뒤로, 아래는 앞으로 갈리면서
                // 화면 전체가 슬라이더에 반응하고 한쪽 눈의 최대 이동량도 절반이 된다.
                //
                // 기울기가 음수인 이유: 원본 상수를 그대로 옮겼더니 깊이 순서가 거꾸로였다.
                // 진짜 SBS 영상의 좌우 시차를 블록 매칭으로 재보면 (눈당 1920px 기준)
                //     위 +2px  ->  아래 -8px      지면이 앞, 하늘이 뒤   ← 실제
                // 인데 우리 출력은
                //     위 -4px  ->  아래 +4px      하늘이 앞, 지면이 뒤   ← 반대였다
                // 셰이더가 t.x += (shearTop - v*shearSlope) 로 샘플링 위치를 옮기므로
                // 양수는 그 눈의 그림을 왼쪽으로 민다. v=1 이 화면 아래이니 원래 식은
                // 위를 앞으로 보내고 있었다. 뒤집어 재측정하니 위 +8 -> 아래 -8 로
                // 실제와 방향이 맞았고, 실기에서도 눈에 띄게 자연스러워졌다.
                shearSlope = -0.0000122f * eyeDisplayWidth() * depth;
                shearTop   = shearSlope * SHEAR_PIVOT;
            }

            // 시어를 두 눈에 절반씩 반대로 나눈다.
            //
            // 원본 3DPlayer 는 좌안을 그대로 두고 우안에만 시어를 걸었다. 그러면 시차는
            // 맞지만 **융합된 상(cyclopean image)이 두 눈 위치의 평균**이라, 곧은 세로선이
            // s(v)/2 만큼 기울어져 보인다 — 시차 강도를 올릴수록 기둥이 옆으로 누워 보이는
            // 원인이 이것이다 (depth=3 이면 화면 높이에 걸쳐 120px 쯤 기운다).
            //
            // 좌안 -s(v)/2, 우안 +s(v)/2 로 나누면
            //   · 시차(우-좌)는 s(v) 로 그대로 — 입체감은 동일하고
            //   · 평균은 0 이라 세로선이 곧게 선다
            //   · 눈당 표본 이동량도 절반이라 가장자리 뭉개짐이 준다
            float halfTop   = shearTop   * 0.5f;
            float halfSlope = shearSlope * 0.5f;

            GLES20.glViewport(dxL, dy, dw, dh);
            src.draw(oesTex, stMatrix, uvL[0], uvL[1], uvL[2], uvL[3],
                    -halfTop, -halfSlope, bottomCut);

            // 우안: 2D 소스면 반대 방향으로 나머지 절반
            GLES20.glViewport(dxR, dy, dw, dh);
            src.draw(oesTex, stMatrix, uvR[0], uvR[1], uvR[2], uvR[3],
                    halfTop, halfSlope, bottomCut);

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

            float eyeW = eyeDisplayWidth();
            float onScreenW = Math.min(subs.width(), eyeW * 0.92f);
            float k = onScreenW / (float) subs.width();
            // 화면 단위 폭을 FBO 반쪽 픽셀로 환산한다. ProMa 는 반쪽이 화면 전체로
            // 늘어나므로 1/2 이 되고, CNSDK 로 넘길 때는 반쪽이 곧 눈 상자라 1 이다.
            int w = Math.max(1, Math.round(onScreenW * halfW / eyeW));
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
            float o = aspectOverride;
            if (o == ASPECT_FILL) return (float) surfW / (float) surfH;  // 레터박스 없이 꽉 채움
            if (o > 0f)           return o;
            return f.displayAspect(videoW, videoH);
        }
    }
}
