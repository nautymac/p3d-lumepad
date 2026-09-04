package com.nauty.p3d.panel;

import android.app.Activity;
import android.graphics.SurfaceTexture;
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
        private boolean wantActive = false;

        @Override
        public View outputView(Activity a) {
            view = new InterlacedSurfaceView(a);
            return view;
        }

        @Override
        public void attach(Activity a, final Stereo3DView gl) {
            // CNSDK 가 내주는 서피스에 **우리가 만든 SBS** 를 넣는다.
            // 디코더를 바로 물리면 레터박스·시어·자막이 빠진 그림이 위빙된다.
            InputViewsAsset asset = new InputViewsAsset();
            asset.CreateEmptySurfaceForVideo(FRAME_W, FRAME_H, new SurfaceTextureReadyCallback() {
                @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                    st.setDefaultBufferSize(FRAME_W, FRAME_H);
                    out = new Surface(st);
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

        // --- LeiaSDK.Delegate ---

        @Override public void didInitialize(LeiaSDK s) {
            ready = true;
            apply();
        }
        @Override public void onFaceTrackingStarted(LeiaSDK s)    { Log.i(TAG, "얼굴추적 시작"); }
        @Override public void onFaceTrackingStopped(LeiaSDK s)    { Log.i(TAG, "얼굴추적 정지"); }
        @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 오류"); }

        /**
         * 백라이트와 카메라는 시스템 공용이다. 앞에 있을 때만 잡는다 —
         * 안 그러면 다음 앱이 3D 로 남은 패널을 물려받는다.
         */
        private void apply() {
            if (sdk == null || !ready) return;
            try {
                sdk.enableFaceTracking(wantActive);
                sdk.enableBacklight(wantActive);
                Log.i(TAG, "백라이트 " + (wantActive ? "3D" : "2D"));
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
            if (out != null) { out.release(); out = null; }
            try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
        }
    }
}
