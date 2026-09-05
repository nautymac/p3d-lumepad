package com.nauty.p3d.leia;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.leia.core.PlatformInitArgs;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.graphics.SurfaceTextureReadyCallback;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;
import com.leiainc.leiamediasdk.LeiaMediaSDK;
import com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer;

/**
 * Leia 자신의 2D→3D 엔진이 우리 프로세스에서 도는지 확인하는 시험 도구.
 *
 * 체인이 셋이라 한 번에 플레이어에 넣으면 어디서 깨졌는지 알 수 없다. 여기서는
 * 최소한으로만 잇는다:
 *
 *   MediaPlayer ─▶ Leia 렌더러 입력면 ─▶ (신경망 시차 + forward mapping)
 *               ─▶ 2타일 다시점 ─▶ CNSDK 입력면 ─▶ 위빙 ─▶ 화면
 *
 * 우리 GL(레터박스·화면비·자막)은 일부러 빼뒀다. 엔진이 붙는지부터 본다.
 *
 * 실행:
 *   adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaMlSpikeActivity \
 *       --es path /sdcard/Movies/test_2d.mp4
 */
public class LeiaMlSpikeActivity extends Activity implements LeiaSDK.Delegate {

    private static final String TAG = "P3DLeiaMl";

    /** CNSDK 로 넘길 다시점 프레임 크기. 눈당 1920x1200 짜리 2타일. */
    private static final int OUT_W = 3840;
    private static final int OUT_H = 1200;

    /** Leia 렌더러에 넣을 모노 프레임 크기 = 타일 하나. */
    private static final int IN_W = 1920;
    private static final int IN_H = 1200;

    private InterlacedSurfaceView   view;
    private LeiaSDK                 sdk;
    private MonoVideoSurfaceRenderer ml;
    private MediaPlayer             mp;
    private Surface                 cnsdkSurface;
    private String                  path;
    /** --es image 로 주면 동영상 대신 이 SBS 그림의 **왼쪽 눈**을 넣는다. */
    private String                  imagePath;
    private final Handler           ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        path = getIntent().getStringExtra("path");
        imagePath = getIntent().getStringExtra("image");
        if (path == null) path = "/sdcard/Movies/test_2d.mp4";

        FrameLayout root = new FrameLayout(this);
        view = new InterlacedSurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // CNSDK 쪽은 지금 플레이어와 똑같이 잡는다. Leia 렌더러의 기본 타일 배치가
        // 2x1 이라 우리가 SBS 를 넘길 때와 설정이 같다.
        InputViewsAsset asset = new InputViewsAsset();
        asset.CreateEmptySurfaceForVideo(OUT_W, OUT_H, new SurfaceTextureReadyCallback() {
            @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                st.setDefaultBufferSize(OUT_W, OUT_H);
                cnsdkSurface = new Surface(st);
                Log.i(TAG, "CNSDK 입력면 준비 " + OUT_W + "x" + OUT_H);
                startMl();
            }
        });
        view.setViewAsset(asset);

        try (InterlacedSurfaceViewConfigAccessor c = view.getConfig()) {
            c.setSourceSize(OUT_W, OUT_H);
            c.setNumTiles(2, 1);
            c.setScaleType(ScaleType.FIT_CENTER);
        } catch (Throwable t) {
            Log.e(TAG, "CNSDK config 실패", t);
        }

        try {
            LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
            args.platform = new PlatformInitArgs();
            args.platform.app      = getApplication();
            args.platform.activity = this;
            args.platform.context  = this;
            args.enableFaceTracking = true;
            args.delegate = this;
            sdk = LeiaSDK.createSDK(args);
        } catch (Throwable t) {
            Log.e(TAG, "createSDK 예외", t);
        }
    }

    /**
     * Leia 렌더러를 만들고 그 입력면에 디코더를 물린다.
     *
     * 생성자가 렌더링 스레드 초기화까지 블록하므로 UI 스레드에서 부르면 안 된다.
     */
    private void startMl() {
        new Thread(new Runnable() { @Override public void run() {
            LeiaMediaSDK media = LeiaMediaSDK.getInstance(LeiaMlSpikeActivity.this);
            if (media == null) { toast("미디어 서비스를 열 수 없다"); return; }

            // ADSP_LIBRARY_PATH 때문에 서비스 컨텍스트여야 한다 (LeiaMediaSDK 주석 참고).
            Context svc = LeiaMediaSDK.serviceContext(LeiaMlSpikeActivity.this);
            if (svc == null) { toast("서비스 컨텍스트 실패"); return; }

            long t0 = System.currentTimeMillis();
            ml = media.createMonoVideoSurfaceRenderer(svc, cnsdkSurface,
                    new MonoVideoSurfaceRenderer.SurfaceTextureCallback() {
                        @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                            // 기본값이 1000x1000 이라 그대로 두면 정사각으로 늘어난다.
                            st.setDefaultBufferSize(IN_W, IN_H);
                            Log.i(TAG, "Leia 렌더러 입력면 준비 " + IN_W + "x" + IN_H);
                            Surface in = new Surface(st);
                            if (imagePath != null) feedStill(in); else play(in);
                        }
                    });
            if (ml == null) { toast("Leia 2D→3D 엔진 생성 실패 — 로그 확인"); return; }
            Log.i(TAG, "Leia 2D→3D 엔진 생성 " + (System.currentTimeMillis() - t0) + "ms");
        }}, "leia-ml-init").start();
    }

    /**
     * 정지 그림을 영상처럼 계속 밀어 넣는다.
     *
     * 정답이 있는 비교를 하려면 정지 화면이어야 한다. 같은 장면의 진짜 SBS 와
     * 나란히 놓고 볼 수 있기 때문이다. Leia 렌더러는 onFrameAvailable 로 도니
     * 같은 프레임을 반복해서 넣어도 정상으로 돈다.
     *
     * 소스가 16:9 이고 타일 하나는 16:10 이라, 늘어나지 않게 여기서 레터박스를 준다.
     * (정식으로 넣을 때는 우리 GL 이 하던 일이다)
     */
    private void feedStill(final Surface into) {
        Bitmap full = BitmapFactory.decodeFile(imagePath);
        if (full == null) { toast("그림을 읽을 수 없다: " + imagePath); return; }
        final Bitmap left = Bitmap.createBitmap(full, 0, 0, full.getWidth() / 2, full.getHeight());
        full.recycle();

        float srcAsp = (float) left.getWidth() / left.getHeight();
        int dw, dh;
        if ((float) IN_W / IN_H > srcAsp) { dh = IN_H; dw = Math.round(IN_H * srcAsp); }
        else                              { dw = IN_W; dh = Math.round(IN_W / srcAsp); }
        final Rect dst = new Rect((IN_W - dw) / 2, (IN_H - dh) / 2,
                                  (IN_W - dw) / 2 + dw, (IN_H - dh) / 2 + dh);
        Log.i(TAG, "정지 그림 " + left.getWidth() + "x" + left.getHeight()
                + " -> " + dst.width() + "x" + dst.height() + " (레터박스)");

        ui.post(new Runnable() { @Override public void run() {
            try {
                Canvas c = into.lockCanvas(null);
                c.drawColor(Color.BLACK);
                c.drawBitmap(left, null, dst, null);
                into.unlockCanvasAndPost(c);
            } catch (Throwable t) {
                Log.e(TAG, "그림 밀어넣기 실패", t);
                return;
            }
            ui.postDelayed(this, 33);
        }});
    }

    private void play(Surface into) {
        try {
            mp = new MediaPlayer();
            mp.setDataSource(this, Uri.parse("file://" + path));
            mp.setSurface(into);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            Log.i(TAG, "재생 시작 " + path);
        } catch (Throwable t) {
            Log.e(TAG, "재생 실패", t);
            toast("재생 실패: " + t);
        }
    }

    private void toast(final String m) {
        Log.e(TAG, m);
        runOnUiThread(new Runnable() { @Override public void run() {
            Toast.makeText(LeiaMlSpikeActivity.this, m, Toast.LENGTH_LONG).show();
        }});
    }

    // --- LeiaSDK.Delegate ---
    @Override public void didInitialize(LeiaSDK s) {
        try { s.enableFaceTracking(true); s.enableBacklight(true); } catch (Throwable ignored) { }
    }
    @Override public void onFaceTrackingStarted(LeiaSDK s)    { Log.i(TAG, "얼굴추적 시작"); }
    @Override public void onFaceTrackingStopped(LeiaSDK s)    { }
    @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 오류"); }

    @Override protected void onResume() {
        super.onResume();
        if (view != null) view.onResume();
        if (sdk != null) { try { sdk.onResume(); } catch (Throwable ignored) { } }
    }

    @Override protected void onPause() {
        if (sdk != null) { try { sdk.enableBacklight(false); sdk.onPause(); } catch (Throwable ignored) { } }
        if (view != null) view.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } }
        if (ml != null) { try { ml.release(); } catch (Throwable ignored) { } }
        try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
