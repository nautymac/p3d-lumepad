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
import com.nauty.p3d.gl.Stereo3DView;

/**
 * Lume Pad 2 (Leia CNSDK) 용.
 *
 * 우리는 SBS 까지만 만들고, 8방향 배분과 얼굴추적 조향은 CNSDK 에 맡긴다.
 * 자세한 배경과 함정은 저장소 루트의 {@code LUMEPAD2-PORT.md} 참고.
 */
public final class Panel {

    public static PanelBackend create() { return new Leia(); }

    private Panel() {}

    private static final class Leia implements PanelBackend, LeiaSDK.Delegate {

        private static final String TAG = "P3DLeia";

        /**
         * CNSDK 로 넘길 SBS 프레임 크기.
         * 패널이 보고하는 View Resolution 이 눈당 1920x1200 이라 그 두 배로 잡는다.
         * 더 올리면 패널이 표현하지 못하는 픽셀에 대역폭만 쓰는 셈이다.
         */
        private static final int FRAME_W = 3840;
        private static final int FRAME_H = 1200;

        private InterlacedSurfaceView view;
        private LeiaSDK sdk;
        private Surface out;

        private volatile boolean ready  = false;   // didInitialize 는 비동기로 온다

        // --- 2D 소스용 Leia 신경망 변환기 ---
        //
        // 기기에 깔린 Leia Media Service 의 엔진을 우리 프로세스에 올려 쓴다
        // (LeiaMediaSDK 주석 참고). 모노 한 장을 넣으면 시차를 만들어 2타일로 내주는데,
        // 그 배치가 우리가 CNSDK 에 넘기던 SBS 와 같아서 CNSDK 설정은 건드릴 게 없다.
        //
        // 만드는 데 1초쯤 걸리고 생성자가 블록하므로, 2D 소스를 처음 만났을 때
        // 백그라운드에서 한 번만 만들고 계속 갖고 있는다.
        private Surface                  mlIn;
        private Activity                 activity;
        private Stereo3DView             gl;
        private MonoVideoSurfaceRenderer ml;
        private volatile boolean         mlStarting = false;
        private volatile boolean         mono       = false;
        private volatile float           strength   = 1.0f;

        /** Leia 기본값. 슬라이더 1.0 이 이 값이 되도록 맞춘다. */
        private static final float BASE_GAIN = 0.3f;

        // 재생 시작을 패널이 자리잡을 때까지 붙잡아 두기 위한 것들.
        private final Handler main = new Handler(Looper.getMainLooper());
        private Runnable pendingReady;
        private boolean  tracking = false;

        /** 얼굴추적이 끝내 시작되지 않아도 재생은 시작돼야 한다. */
        private static final long READY_TIMEOUT_MS = 2000;
        /** 추적이 붙고도 첫 얼굴 좌표가 오기까지 조금 더 걸린다. */
        private static final long READY_SETTLE_MS  = 250;
        private boolean wantActive = false;
        private boolean wantThreeD = true;    // 플레이어의 출력 설정(3D/2D)

        @Override
        public View outputView(Activity a) {
            view = new InterlacedSurfaceView(a);
            return view;
        }

        @Override
        public void attach(Activity a, final Stereo3DView gl) {
            this.activity = a;
            this.gl       = gl;
            // CNSDK 가 내주는 서피스에 **우리가 만든 SBS** 를 넣는다.
            // 디코더를 바로 물리면 레터박스·시어·자막이 빠진 그림이 위빙된다.
            InputViewsAsset asset = new InputViewsAsset();
            asset.CreateEmptySurfaceForVideo(FRAME_W, FRAME_H, new SurfaceTextureReadyCallback() {
                @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                    st.setDefaultBufferSize(FRAME_W, FRAME_H);
                    out = new Surface(st);
                    // 2D 로 이미 정해져 있으면 이 면은 Leia 변환기가 가져가야 한다.
                    // 우리가 먼저 잡으면 그쪽이 EGL 표면을 만들지 못한다.
                    if (mono) { ensureMl(); return; }
                    gl.setExternalSbsTarget(out, FRAME_W, FRAME_H);
                    Log.i(TAG, "CNSDK 서피스를 3D 파이프라인 출력으로 연결");
                }
            });
            view.setViewAsset(asset);

            // setSourceSize 는 두 눈이 담긴 전체 프레임 크기이고 numTiles 가 그것을 가른다.
            // 이 값의 소유권은 여기 하나뿐이어야 한다 (다른 곳에서 덮어쓰면 어긋난다).
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

        /**
         * 2D 소스면 Leia 변환기를 끼우고, 3D 소스면 지금까지대로 우리 SBS 를 바로 넘긴다.
         */
        @Override public void setMonoSource(boolean m) {
            if (mono == m) return;
            mono = m;
            if (m) ensureMl();
            else {
                // 3D 로 돌아간다. 변환기가 쥐고 있는 출력면을 돌려받아야 한다.
                if (ml != null) {
                    try { ml.release(); } catch (Throwable ignored) { }
                    ml = null;
                    if (mlIn != null) { mlIn.release(); mlIn = null; }
                }
                if (gl != null && out != null) gl.setExternalSbsTarget(out, FRAME_W, FRAME_H);
            }
        }

        @Override public void setConversionStrength(float v) {
            strength = v;
            MonoVideoSurfaceRenderer r = ml;
            if (r != null) {
                try { r.setGainMultiplier(BASE_GAIN * v); } catch (Throwable ignored) { }
            }
        }

        /**
         * 변환기를 한 번만 만든다.
         *
         * 출력은 CNSDK 입력면이고, 입력은 그쪽이 내주는 면이다. 그 면이 준비되면
         * 우리 GL 의 출력을 SBS 에서 모노로 바꿔 그리로 돌린다.
         *
         * ADSP_LIBRARY_PATH 때문에 서비스 컨텍스트를 넘겨야 하고, 생성자가
         * 렌더링 스레드 초기화까지 블록하므로 UI 스레드에서 부르면 안 된다.
         */
        private void ensureMl() {
            if (ml != null) {
                if (gl != null && mlIn != null) gl.setExternalMonoTarget(mlIn, FRAME_W / 2, FRAME_H);
                return;
            }
            if (mlStarting || out == null || activity == null) return;
            mlStarting = true;
            new Thread(new Runnable() { @Override public void run() {
                LeiaMediaSDK media = LeiaMediaSDK.getInstance(activity);
                Context svc = media == null ? null : LeiaMediaSDK.serviceContext(activity);
                if (svc == null) {
                    Log.e(TAG, "Leia 변환기를 쓸 수 없다 — 시어로 간다");
                    mlStarting = false;
                    return;
                }
                // 우리 GL 이 출력면을 쥐고 있으면 먼저 놓아야 한다.
                if (gl != null) gl.detachExternalTarget();

                long t0 = System.currentTimeMillis();
                MonoVideoSurfaceRenderer r = media.createMonoVideoSurfaceRenderer(svc, out,
                        new MonoVideoSurfaceRenderer.SurfaceTextureCallback() {
                            @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                                // 기본값이 1000x1000 이라 두면 정사각으로 눌린다.
                                st.setDefaultBufferSize(FRAME_W / 2, FRAME_H);
                                mlIn = new Surface(st);
                                if (mono && gl != null) {
                                    gl.setExternalMonoTarget(mlIn, FRAME_W / 2, FRAME_H);
                                    Log.i(TAG, "2D→3D 를 Leia 변환기로 넘겼다");
                                }
                            }
                        });
                mlStarting = false;
                if (r == null) { Log.e(TAG, "Leia 변환기 생성 실패 — 시어로 간다"); return; }
                ml = r;
                Log.i(TAG, "Leia 변환기 준비 " + (System.currentTimeMillis() - t0) + "ms");
                try {
                    r.setGainMultiplier(BASE_GAIN * strength);
                    r.setAutoConvergence(true);
                } catch (Throwable ignored) { }
            }}, "leia-ml").start();
        }

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
         * 2D 출력이면 백라이트를 2D 로 되돌린다.
         *
         * 밝기 때문이다. 3D 모드에서는 균일 광원이 완전히 꺼지고(mode3d_ratio_2d=0.0)
         * 회절 광원만 남아 같은 시스템 밝기에서도 화면이 눈에 띄게 어둡다.
         * 얼굴추적 카메라도 볼 필요가 없으니 같이 내린다.
         */
        @Override public void setThreeD(boolean on) {
            if (wantThreeD == on) return;
            wantThreeD = on;
            apply();
        }

        // --- LeiaSDK.Delegate ---

        @Override public void didInitialize(LeiaSDK s) {
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
            if (view != null) {
                try { view.releaseInputViewsAsset(); } catch (Throwable ignored) { }
            }
            if (ml != null) { try { ml.release(); } catch (Throwable ignored) { } ml = null; }
            if (mlIn != null) { mlIn.release(); mlIn = null; }
            if (out != null) { out.release(); out = null; }
            try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
        }
    }
}
