package com.nauty.p3d.leia;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.leia.core.PlatformInitArgs;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.graphics.SurfaceTextureReadyCallback;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;

import java.io.File;

/**
 * 정지 SBS 이미지를 패널에 띄우는 시험 도구.
 *
 * 2D→3D 방식을 비교할 때 필요하다. 같은 프레임을 여러 방식으로 합성해 두고
 * 번갈아 띄워 보면 눈으로 바로 갈린다 — 동영상으로는 같은 장면을 같은 조건에서
 * 비교하기 어렵다.
 *
 * 3D 파이프라인(Stereo3DView)을 거치지 않고 SBS 비트맵을 CNSDK 서피스에 직접
 * 그린다. 이미 좌우가 만들어진 이미지라 크롭·시어·레터박스가 필요 없고,
 * 그래야 "합성 방식만" 비교된다.
 *
 * 실행:
 * <pre>
 * adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaImageActivity \
 *     --es path /sdcard/Pictures/3dtest_b_depth.png
 * </pre>
 */
public class LeiaImageActivity extends Activity implements LeiaSDK.Delegate {

    private static final String TAG = "P3DLeia";

    /** CNSDK 로 넘길 프레임 크기. 패널 눈당 1920x1200 의 두 배. */
    private static final int FRAME_W = 3840;
    private static final int FRAME_H = 1200;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private InterlacedSurfaceView view;
    private LeiaSDK sdk;
    private Surface out;
    private Bitmap image;

    private volatile boolean ready = false;
    private boolean wantActive = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String path = getIntent().getStringExtra("path");
        if (path == null) {
            Toast.makeText(this, "--es path <SBS 이미지 경로> 가 필요합니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        image = BitmapFactory.decodeFile(path);
        if (image == null) {
            Toast.makeText(this, "이미지를 못 읽었습니다: " + path, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Log.i(TAG, "SBS 이미지 " + new File(path).getName()
                + "  " + image.getWidth() + "x" + image.getHeight());

        FrameLayout root = new FrameLayout(this);
        view = new InterlacedSurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        InputViewsAsset asset = new InputViewsAsset();
        asset.CreateEmptySurfaceForVideo(FRAME_W, FRAME_H, new SurfaceTextureReadyCallback() {
            @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                st.setDefaultBufferSize(FRAME_W, FRAME_H);
                out = new Surface(st);
                ui.post(new Runnable() { @Override public void run() { paint(); } });
            }
        });
        view.setViewAsset(asset);

        try (InterlacedSurfaceViewConfigAccessor c = view.getConfig()) {
            c.setSourceSize(FRAME_W, FRAME_H);
            c.setNumTiles(2, 1);
            c.setScaleType(ScaleType.FIT_CENTER);
        } catch (Throwable t) {
            Log.e(TAG, "CNSDK config 실패", t);
        }

        initSdk();
    }

    /** SBS 비트맵을 CNSDK 서피스에 한 번 그린다. */
    private void paint() {
        if (out == null || image == null) return;
        try {
            Canvas c = out.lockCanvas(null);
            c.drawColor(Color.BLACK);
            c.drawBitmap(image,
                    new Rect(0, 0, image.getWidth(), image.getHeight()),
                    new Rect(0, 0, FRAME_W, FRAME_H),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            out.unlockCanvasAndPost(c);
            Log.i(TAG, "SBS 이미지를 CNSDK 서피스에 그렸다");
        } catch (Throwable t) {
            Log.e(TAG, "그리기 실패", t);
        }
    }

    private void initSdk() {
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

    @Override public void didInitialize(LeiaSDK s) { ready = true; apply(); }
    @Override public void onFaceTrackingStarted(LeiaSDK s)    { }
    @Override public void onFaceTrackingStopped(LeiaSDK s)    { }
    @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 오류"); }

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

    @Override protected void onResume() {
        super.onResume();
        wantActive = true;
        if (view != null) view.onResume();
        if (sdk != null) { try { sdk.onResume(); } catch (Throwable ignored) { } }
        apply();
        ui.postDelayed(new Runnable() { @Override public void run() { paint(); } }, 300);
    }

    @Override protected void onPause() {
        super.onPause();
        wantActive = false;
        apply();
        if (sdk != null) { try { sdk.onPause(); } catch (Throwable ignored) { } }
        if (view != null) view.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (view != null) { try { view.releaseInputViewsAsset(); } catch (Throwable ignored) { } }
        if (out != null) { out.release(); out = null; }
        if (image != null) { image.recycle(); image = null; }
        try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
    }
}
