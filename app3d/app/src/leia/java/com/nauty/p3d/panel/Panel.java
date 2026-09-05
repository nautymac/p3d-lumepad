package com.nauty.p3d.panel;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.View;

import com.leia.core.PlatformInitArgs;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.graphics.SurfaceTextureReadyCallback;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;
import com.leiainc.leiamediasdk.LeiaMediaSDK;
import com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer;
import com.leiainc.leiamediasdk.interfaces.StereoVideoSurfaceRenderer;
import com.nauty.p3d.gl.Stereo3DView;

/**
 * Lume Pad 2 용.
 *
 * 화면까지 가는 길이 세 토막이다.
 *
 * <pre>
 * 우리 GL ──▶ Leia 엔진 ──▶ CNSDK ──▶ 패널
 *  레터박스     시차 생성      8방향 위빙
 *  화면비       또는 수렴보정   얼굴추적 조향
 *  자막
 * </pre>
 *
 * 가운데 엔진은 소스에 따라 갈린다.
 *   2D 소스 : MonoVideoSurfaceRenderer   — 신경망으로 시차를 만든다
 *   3D 소스 : StereoVideoSurfaceRenderer — 있는 좌우 눈의 수렴점을 장면마다 다시 잡는다
 *
 * 둘 다 기기에 깔린 Leia Media Service 의 것이고, 출력이 2타일이라 CNSDK 설정은 하나로 족하다.
 * 자세한 배경과 함정은 저장소 루트의 {@code LUMEPAD2-PORT.md} 참고.
 */
public final class Panel {

    public static PanelBackend create() { return new Leia(); }

    private Panel() {}

    private static final class Leia implements PanelBackend, LeiaSDK.Delegate {

        private static final String TAG = "P3DLeia";

        /**
         * CNSDK 로 넘길 프레임 크기.
         * 패널이 보고하는 View Resolution 이 눈당 1920x1200 이라 그 두 배로 잡는다.
         * 더 올리면 패널이 표현하지 못하는 픽셀에 대역폭만 쓰는 셈이다.
         */
        private static final int FRAME_W = 3840;
        private static final int FRAME_H = 1200;

        /** 얼굴추적이 끝내 시작되지 않아도 재생은 시작돼야 한다. */
        private static final long READY_TIMEOUT_MS = 2000;
        /** 추적이 붙고도 첫 얼굴 좌표가 오기까지 조금 더 걸린다. */
        private static final long READY_SETTLE_MS  = 250;

        /**
         * 3D 소스를 Leia 의 수렴 보정기에 태울지.
         *
         * 지금은 끈다. 우리 SBS 를 그 엔진에 넣어봤더니 실기에서 3D 가 제대로 서지
         * 않았다 — 자동 수렴을 꺼도 마찬가지였으니, 수렴 계산이 아니라 우리가 넣는
         * SBS 를 그쪽이 기대하는 모양으로 주지 못하고 있는 것이다.
         *
         * 2D 쪽(MonoVideoSurfaceRenderer)은 확실히 좋아졌으므로 그것만 쓰고,
         * 3D 소스는 지금까지대로 우리 GL 이 CNSDK 로 바로 보낸다.
         *
         * 남겨두는 이유: 게임 SBS 처럼 큰 화면 기준으로 시차가 잡힌 소스는 태블릿
         * 폭으로 줄이면 수렴이 맞지 않는다. 그걸 풀 실마리가 이쪽에 있다.
         * 다시 볼 때는 엔진이 입력 텍스처를 어떻게 나누고 뒤집는지부터 확인해야 한다
         * (StereoVideoRenderer, stereo_fragment, TextureShape).
         */
        private static final boolean USE_STEREO_ENGINE = false;

        /** Leia 의 2D→3D 기본 게인. 슬라이더 1.0 이 이 값이 되도록 맞춘다. */
        private static final float BASE_GAIN = 0.3f;

        private InterlacedSurfaceView view;
        private LeiaSDK     sdk;
        private Surface     out;          // CNSDK 입력면 = 엔진들의 출력
        private Activity    activity;
        private Stereo3DView gl;

        private volatile boolean ready      = false;   // didInitialize 는 비동기로 온다
        private boolean          wantActive = false;
        private boolean          wantThreeD = true;    // 플레이어의 출력 설정(3D/2D)

        // 재생 시작을 패널이 자리잡을 때까지 붙잡아 두기 위한 것들.
        private final Handler main = new Handler(Looper.getMainLooper());
        private Runnable         pendingReady;
        private volatile boolean tracking = false;

        // --- Leia 엔진 ---
        //
        // 소스에 따라 둘 중 하나만 살아 있다. 둘 다 출력이 CNSDK 입력면이라, 한 서피스에
        // EGL 윈도우 표면을 둘이 만들 수 없어서 겹칠 수가 없다 (EGL_BAD_ALLOC 0x3003).
        // 만드는 데 1초쯤 걸리고 생성자가 블록하므로 백그라운드에서 만든다.
        private volatile Object  engine;         // MonoVideoSurfaceRenderer 또는 Stereo...
        private volatile boolean builtMono;      // 지금 살아 있는 엔진의 종류
        private volatile boolean wantMono;       // 소스가 요구하는 종류
        private volatile boolean known    = false;  // 소스 종류가 정해졌는지
        private volatile boolean building = false;
        private Surface engineIn;
        private volatile float   strength = 1.0f;

        @Override
        public View outputView(Activity a) {
            view = new InterlacedSurfaceView(a);
            return view;
        }

        @Override
        public void attach(Activity a, final Stereo3DView gl) {
            this.activity = a;
            this.gl       = gl;

            // CNSDK 가 내주는 서피스가 최종 출력이다. 엔진이 그리로 그리고, 엔진이 없는
            // 동안에는 우리 GL 이 직접 그린다 (예전 경로 — 수렴 보정만 빠진 그림).
            InputViewsAsset asset = new InputViewsAsset();
            asset.CreateEmptySurfaceForVideo(FRAME_W, FRAME_H, new SurfaceTextureReadyCallback() {
                @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                    st.setDefaultBufferSize(FRAME_W, FRAME_H);
                    out = new Surface(st);
                    if (known) {
                        switchEngine();
                    } else {
                        gl.setExternalSbsTarget(out, FRAME_W, FRAME_H);
                        Log.i(TAG, "CNSDK 서피스에 직접 연결 (소스 판별 대기)");
                    }
                }
            });
            view.setViewAsset(asset);

            // setSourceSize 는 두 눈이 담긴 전체 프레임 크기이고 numTiles 가 그것을 가른다.
            // 엔진 출력도 2타일이라 어느 경로든 이 설정 하나로 맞는다.
            try (InterlacedSurfaceViewConfigAccessor c = view.getConfig()) {
                c.setSourceSize(FRAME_W, FRAME_H);
                c.setNumTiles(2, 1);
                c.setScaleType(ScaleType.FIT_CENTER);
            } catch (Throwable t) {
                Log.e(TAG, "CNSDK config 설정 실패", t);
            }

            try {
                LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
                args.platform = new PlatformInitArgs();
                args.platform.app      = a.getApplication();
                args.platform.activity = a;    // 빠지면 createSDK 가 null 을 준다
                args.platform.context  = a;    // 마찬가지
                args.enableFaceTracking = true;
                args.delegate = this;
                sdk = LeiaSDK.createSDK(args);
                Log.i(TAG, "createSDK -> " + (sdk == null ? "null (실패)" : "ok, 초기화 대기"));
            } catch (Throwable t) {
                Log.e(TAG, "createSDK 예외", t);
            }
        }

        @Override public boolean useHolography() { return false; }

        @Override public void whenReady(Runnable r) {
            if (tracking) { r.run(); return; }
            pendingReady = r;
            main.postDelayed(fireReady, READY_TIMEOUT_MS);
        }

        private final Runnable fireReady = new Runnable() {
            @Override public void run() {
                Runnable r = pendingReady;
                pendingReady = null;
                if (r != null) r.run();
            }
        };

        /**
         * 2D 출력이면 백라이트를 2D 로 되돌리고 엔진도 평면으로 돌린다.
         *
         * 밝기 때문이다. 3D 모드에서는 균일 광원이 완전히 꺼지고(mode3d_ratio_2d=0.0)
         * 회절 광원만 남아 같은 시스템 밝기에서도 화면이 눈에 띄게 어둡다.
         * 얼굴추적 카메라도 볼 필요가 없으니 같이 내린다.
         */
        @Override public void setThreeD(boolean on) {
            if (wantThreeD == on) return;
            wantThreeD = on;
            applySingleView();
            apply();
        }

        /**
         * 소스가 2D 인지 알려준다. 이 값이 어느 엔진을 쓸지 정한다.
         *
         * 판별이 끝나기 전의 임시 추측으로는 부르지 않는다 — 그것만 보고 1초짜리 엔진을
         * 만들기 시작하면 곧 뒤집힐 때 출력면을 두고 다투게 된다.
         */
        @Override public void setMonoSource(boolean m) {
            boolean first = !known;
            known = true;
            if (!first && wantMono == m) return;
            wantMono = m;
            if (out != null) switchEngine();
        }

        @Override public void setConversionStrength(float v) {
            strength = v;
            applyStrength();
        }

        // --- 엔진 교체 ---

        /**
         * 원하는 종류의 엔진 하나만 살아 있게 만든다.
         *
         * 출력면(CNSDK)은 하나뿐인데 그것을 쥘 수 있는 것도 하나뿐이라, 반드시
         * 놓고 나서 잡아야 한다. 순서가 어긋나면 뒤에 오는 쪽이 EGL 표면을 못 만든다.
         */
        private void switchEngine() {
            if (building) return;          // 만드는 중이면 끝나고 다시 본다
            final boolean m = wantMono;
            if (!m && !USE_STEREO_ENGINE) {   // 3D 는 엔진을 거치지 않는다
                releaseEngineAndRestore();
                return;
            }
            if (engine != null && builtMono == m) return;
            building = true;

            new Thread(new Runnable() { @Override public void run() {
                releaseEngine();
                if (gl != null) gl.detachExternalTarget();   // 우리 GL 이 쥐고 있으면 놓는다

                LeiaMediaSDK media = LeiaMediaSDK.getInstance(activity);
                Context svc = media == null ? null : LeiaMediaSDK.serviceContext(activity);
                if (svc == null) {
                    Log.e(TAG, "Leia 엔진을 쓸 수 없다 — CNSDK 로 직접 간다");
                    building = false;
                    restoreDirect();
                    return;
                }

                long t0 = System.currentTimeMillis();
                Object e = m ? createMono(media, svc) : createStereo(media, svc);
                building = false;

                if (e == null) { restoreDirect(); return; }
                engine    = e;
                builtMono = m;
                Log.i(TAG, (m ? "2D→3D 변환기" : "SBS 수렴 보정기") + " 준비 "
                        + (System.currentTimeMillis() - t0) + "ms");

                applyStrength();
                applySingleView();

                // 만드는 사이에 소스 종류가 바뀌었을 수 있다.
                if (wantMono != m) switchEngine();
            }}, "leia-engine").start();
        }

        private Object createMono(LeiaMediaSDK media, Context svc) {
            return media.createMonoVideoSurfaceRenderer(svc, out,
                    new MonoVideoSurfaceRenderer.SurfaceTextureCallback() {
                        @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                            // 기본값이 1000x1000 이라 두면 정사각으로 눌린다.
                            // 2D 는 눈 하나 크기의 모노 한 장을 넣는다.
                            st.setDefaultBufferSize(FRAME_W / 2, FRAME_H);
                            engineIn = new Surface(st);
                            if (gl != null) gl.setExternalMonoTarget(engineIn, FRAME_W / 2, FRAME_H);
                        }
                    });
        }

        private Object createStereo(LeiaMediaSDK media, Context svc) {
            return media.createStereoVideoSurfaceRenderer(svc, out,
                    new StereoVideoSurfaceRenderer.SurfaceTextureCallback() {
                        @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                            // 3D 는 좌우가 담긴 SBS 한 장을 그대로 넣는다.
                            st.setDefaultBufferSize(FRAME_W, FRAME_H);
                            engineIn = new Surface(st);
                            if (gl != null) gl.setExternalSbsTarget(engineIn, FRAME_W, FRAME_H);
                        }
                    });
        }

        /** 엔진을 놓고 CNSDK 로 직결한다. GL 스레드를 기다려야 하므로 별도 스레드에서. */
        private void releaseEngineAndRestore() {
            if (engine == null) { restoreDirect(); return; }
            building = true;
            new Thread(new Runnable() { @Override public void run() {
                releaseEngine();
                if (gl != null) gl.detachExternalTarget();
                building = false;
                restoreDirect();
                Log.i(TAG, "3D 소스 — CNSDK 로 직결");
            }}, "leia-engine-off").start();
        }

        private void releaseEngine() {
            Object e = engine;
            engine = null;
            if (e instanceof MonoVideoSurfaceRenderer) {
                try { ((MonoVideoSurfaceRenderer) e).release(); } catch (Throwable ignored) { }
            } else if (e instanceof StereoVideoSurfaceRenderer) {
                try { ((StereoVideoSurfaceRenderer) e).release(); } catch (Throwable ignored) { }
            }
            if (engineIn != null) { engineIn.release(); engineIn = null; }
        }

        /** 엔진을 못 쓸 때의 길. 수렴 보정만 빠지고 그림은 나온다. */
        private void restoreDirect() {
            if (gl != null && out != null) gl.setExternalSbsTarget(out, FRAME_W, FRAME_H);
        }

        /**
         * 깊이 슬라이더를 엔진에 전달한다.
         * 2D 는 만들어낼 시차의 세기이고, 3D 는 있는 시차를 얼마나 살릴지다.
         */
        private void applyStrength() {
            Object e = engine;
            try {
                if (e instanceof MonoVideoSurfaceRenderer) {
                    MonoVideoSurfaceRenderer r = (MonoVideoSurfaceRenderer) e;
                    r.setGainMultiplier(BASE_GAIN * strength);
                    // 자동 수렴은 Leia 기본값 그대로 켜둔다. 한때 화면이 좌우로 흔들리는
                    // 원인으로 의심해 꺼봤지만 아니었다 — 그 영상이 좌우 비교 화면이라
                    // 2D→3D 신경망에 최악의 입력이었던 것이다.
                } else if (e instanceof StereoVideoSurfaceRenderer) {
                    StereoVideoSurfaceRenderer s = (StereoVideoSurfaceRenderer) e;
                    s.setGain(strength);
                    s.setAutoConvergence(true);   // 장면마다 수렴점을 다시 잡는다
                }
            } catch (Throwable ignored) { }
        }

        /** 2D 출력이면 엔진이 좌우를 같게 낸다 — 위빙을 거쳐도 평면으로 보인다. */
        private void applySingleView() {
            Object e = engine;
            try {
                if (e instanceof MonoVideoSurfaceRenderer) {
                    ((MonoVideoSurfaceRenderer) e).setSingleViewMode(!wantThreeD);
                } else if (e instanceof StereoVideoSurfaceRenderer) {
                    ((StereoVideoSurfaceRenderer) e).setSingleViewMode(!wantThreeD);
                }
            } catch (Throwable ignored) { }
        }

        // --- LeiaSDK.Delegate ---

        @Override public void didInitialize(LeiaSDK s) {
            // 이전 세션이 백라이트를 3D 로 남긴 채 끊겼을 수 있다.
            //
            // 정상 종료 경로(onPause)는 2D 로 되돌리지만 강제 종료나 비정상 종료는
            // 그걸 건너뛴다. 그렇게 남은 상태에서 이어받으면 위빙이 어긋난 채로
            // 시작되고, 그때 증상이 잔상·크로스토크로 나타난다. 켜기 전에 한 번
            // 확실히 내려서 상태를 초기화한다.
            try { s.enableBacklight(false); } catch (Throwable ignored) { }
            ready = true;
            apply();
        }

        @Override public void onFaceTrackingStarted(LeiaSDK s) {
            Log.i(TAG, "얼굴추적 시작");
            tracking = true;
            main.removeCallbacks(fireReady);
            main.postDelayed(fireReady, READY_SETTLE_MS);
        }

        @Override public void onFaceTrackingStopped(LeiaSDK s)    { Log.i(TAG, "얼굴추적 정지"); }
        @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 오류"); }

        /**
         * 백라이트와 카메라는 시스템 공용이다. 앞에 있을 때만 잡는다 —
         * 안 그러면 다음 앱이 3D 로 남은 패널을 물려받는다.
         */
        private void apply() {
            if (sdk == null || !ready) return;
            try {
                boolean on = wantActive && wantThreeD;
                sdk.enableFaceTracking(on);
                sdk.enableBacklight(on);
                Log.i(TAG, "백라이트 " + (on ? "3D" : "2D"));
            } catch (Throwable t) {
                Log.e(TAG, "백라이트 전환 실패", t);
            }
        }

        @Override
        public void onResume() {
            wantActive = true;
            if (view != null) view.onResume();
            if (sdk != null) { try { sdk.onResume(); } catch (Throwable ignored) { } }
            apply();
        }

        @Override
        public void onPause() {
            wantActive = false;
            apply();
            if (sdk != null) { try { sdk.onPause(); } catch (Throwable ignored) { } }
            if (view != null) view.onPause();
        }

        @Override
        public void onDestroy() {
            releaseEngine();
            if (view != null) {
                try { view.releaseInputViewsAsset(); } catch (Throwable ignored) { }
            }
            if (out != null) { out.release(); out = null; }
            try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
        }
    }
}
