package com.nauty.p3d.leia;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.leia.core.PlatformInitArgs;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.graphics.SurfaceTextureReadyCallback;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;
import com.nauty.p3d.SourceFormat;
import com.nauty.p3d.gl.Stereo3DView;

/**
 * Lume Pad 2 포팅 1단계 — CNSDK 경로만 검증하는 최소 스파이크.
 *
 * 경로: ExoPlayer -> Stereo3DView(OES -> FBO 좌/우 절반) -> CNSDK 서피스 -> 위빙.
 * 인터레이스 단계만 CNSDK 로 넘기고 나머지(레터박스, 시어, 자막)는 그대로 쓴다.
 *
 * ProMa 와 근본적으로 다른 점: 패널이 렌티큘러가 아니라 8방향 회절 백라이트라
 * 얼굴 위치에 따라 어느 방향이 어느 눈인지 계속 다시 계산된다. 그래서 우리가 셰이더로
 * 고정 패턴을 짜 넣을 수 없고 위빙을 CNSDK 에 맡겨야 한다.
 *
 * 실행:
 * <pre>
 * adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaSpikeActivity \
 *     --es uri content://media/external/video/media/1000520816 --ei w 3840 --ei h 2160
 * </pre>
 */
@OptIn(markerClass = UnstableApi.class)
public class LeiaSpikeActivity extends Activity implements LeiaSDK.Delegate {

    private static final String TAG = "P3DLeia";

    /**
     * 눈당 권장 해상도. 패널이 보고하는 View Resolution 이 1920x1200 이라
     * SBS 프레임은 그 두 배인 3840x1200 이 기준이다. 소스가 다르면 인텐트로 덮어쓴다.
     */
    private int frameW = 3840;
    private int frameH = 1200;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LeiaSDK sdk;
    private InterlacedSurfaceView leiaView;
    private ExoPlayer player;
    private Surface videoSurface;
    private Surface sbsOut;
    private Stereo3DView stereo;
    private SourceFormat fmt = SourceFormat.SBS_FULL;
    private boolean detectFormat = false;
    private Uri mediaUri;

    /** SDK 초기화가 끝났는지. createSDK 직후에는 아직 false 다 (비동기). */
    private volatile boolean sdkReady = false;
    private boolean wantActive = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String u = getIntent().getStringExtra("uri");
        if (u == null) {
            Toast.makeText(this, "--es uri <content://...> 가 필요합니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mediaUri = Uri.parse(u);
        frameW = getIntent().getIntExtra("w", frameW);
        frameH = getIntent().getIntExtra("h", frameH);
        String fs = getIntent().getStringExtra("fmt");
        if (fs != null) {
            try { fmt = SourceFormat.valueOf(fs); } catch (IllegalArgumentException ignored) { }
        } else {
            detectFormat = true;    // 지정이 없으면 픽셀로 판별한다 (아래 startDetection)
        }
        Log.i(TAG, "소스 " + mediaUri + "  프레임 " + frameW + "x" + frameH);

        FrameLayout root = new FrameLayout(this);

        // 우리 3D 파이프라인. 화면에는 CNSDK 뷰가 나가므로 이쪽은 보이지 않아도 되지만,
        // GL 컨텍스트를 얻으려면 계층에 들어 있어야 한다. 1x1 로 깔아 둔다.
        // 마스크는 쓰지 않는다 — 이 패널에는 맞지 않고 위빙은 CNSDK 가 한다.
        stereo = new Stereo3DView(this);
        stereo.setSourceFormat(fmt);
        stereo.setCallback(new Stereo3DView.Callback() {
            @Override public void onSurfaceReady(Surface s, SurfaceTexture st) {
                ui.post(new Runnable() {
                    @Override public void run() { startPlayback(s); }
                });
            }
        });
        root.addView(stereo, new FrameLayout.LayoutParams(1, 1));

        leiaView = new InterlacedSurfaceView(this);
        root.addView(leiaView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // CNSDK 가 내주는 서피스에는 디코더가 아니라 **우리가 만든 SBS** 를 넣는다.
        // 그래야 레터박스·시어·자막이 다 적용된 그림이 위빙된다.
        InputViewsAsset asset = new InputViewsAsset();
        asset.CreateEmptySurfaceForVideo(frameW, frameH, new SurfaceTextureReadyCallback() {
            @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                st.setDefaultBufferSize(frameW, frameH);
                Surface s = new Surface(st);
                sbsOut = s;
                stereo.setExternalSbsTarget(s, frameW, frameH);
                Log.i(TAG, "CNSDK 서피스를 3D 파이프라인 출력으로 연결");
            }
        });
        leiaView.setViewAsset(asset);

        // 기하. setSourceSize 는 "두 눈이 담긴 전체 프레임" 크기이고 numTiles 가 그것을 가른다.
        // 여기 값을 다른 곳에서 또 덮어쓰면 안 된다 — 프레임에 눈이 몇 개 담기는지
        // 아는 쪽(여기)이 소유해야 한다.
        try (InterlacedSurfaceViewConfigAccessor c = leiaView.getConfig()) {
            c.setSourceSize(frameW, frameH);
            c.setNumTiles(2, 1);                 // 좌우 SBS
            c.setScaleType(ScaleType.FIT_CENTER);
        } catch (Throwable t) {
            Log.e(TAG, "config 설정 실패", t);
        }

        initSdk();
        if (detectFormat) startDetection();
    }

    /**
     * 파일명은 믿을 수 없다. 실제로 "IMG_2110_SBS.mp4" 는 이름만 SBS 고 half 였다 —
     * full 로 해석하면 좌우가 눌린다. ProMa 에서 코랄라인이 같은 방식으로 틀렸었다.
     * 그래서 프레임을 뜯어 픽셀로 판별한다.
     */
    private void startDetection() {
        new Thread(new Runnable() {
            @Override public void run() {
                final SourceFormat d = com.nauty.p3d.StereoDetect.detect(LeiaSpikeActivity.this, mediaUri);
                if (d == null) { Log.i(TAG, "스테레오 판별 실패 - 기본값 유지"); return; }
                ui.post(new Runnable() {
                    @Override public void run() {
                        fmt = d;
                        if (stereo != null) stereo.setSourceFormat(d);
                        Log.i(TAG, "픽셀 판별 결과: " + d);
                        Toast.makeText(LeiaSpikeActivity.this, "소스: " + d.tag, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /**
     * CNSDK 초기화.
     *
     * platform 에 app 만 넣으면 createSDK 가 null 을 돌려준다 — activity 와 context 까지
     * 채워야 한다. 그리고 돌아온 직후에는 아직 초기화가 안 끝났으므로
     * didInitialize 콜백을 기다려야 한다 (여기서 백라이트를 켠다).
     */
    private void initSdk() {
        try {
            LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
            args.platform = new PlatformInitArgs();
            args.platform.app = getApplication();
            args.platform.activity = this;
            args.platform.context = this;
            args.enableFaceTracking = true;
            args.delegate = this;
            sdk = LeiaSDK.createSDK(args);
            Log.i(TAG, "createSDK -> " + (sdk == null ? "null (실패)" : "ok, 초기화 대기"));
        } catch (Throwable t) {
            Log.e(TAG, "createSDK 예외", t);
        }
    }

    private void startPlayback(Surface s) {
        videoSurface = s;
        player = new ExoPlayer.Builder(this).build();
        // 소스 해상도를 알려줘야 눈당 상자에 레터박스를 옳게 친다.
        // (--ei w/h 는 CNSDK 로 내보낼 프레임 크기지 소스 크기가 아니다)
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize v) {
                Log.i(TAG, "소스 해상도 " + v.width + "x" + v.height);
                if (stereo != null) stereo.setVideoSize(v.width, v.height);
            }
        });
        player.setVideoSurface(s);
        player.setMediaItem(MediaItem.fromUri(mediaUri));
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_ALL);
        player.prepare();
        player.setPlayWhenReady(true);
        Log.i(TAG, "ExoPlayer 를 3D 파이프라인 입력면에 물렸다");
    }

    // ------------------------------------------------------------ LeiaSDK.Delegate

    @Override
    public void didInitialize(LeiaSDK s) {
        sdkReady = true;
        Log.i(TAG, "didInitialize — 백라이트/추적 적용");
        applyActive();
    }

    @Override public void onFaceTrackingStarted(LeiaSDK s)    { Log.i(TAG, "얼굴추적 시작"); }
    @Override public void onFaceTrackingStopped(LeiaSDK s)    { Log.i(TAG, "얼굴추적 정지"); }
    @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 치명적 오류"); }

    /**
     * 백라이트와 카메라는 시스템 공용 자원이다. 화면에 떠 있고 3D 일 때만 잡는다.
     * 안 그러면 다음 앱이 3D 로 남은 패널을 물려받는다.
     */
    private void applyActive() {
        if (sdk == null || !sdkReady) return;
        try {
            sdk.enableFaceTracking(wantActive);
            sdk.enableBacklight(wantActive);
            Log.i(TAG, "백라이트 " + (wantActive ? "3D" : "2D"));
        } catch (Throwable t) {
            Log.e(TAG, "백라이트/추적 전환 실패", t);
        }
    }

    // ------------------------------------------------------------ 생명주기

    @Override
    protected void onResume() {
        super.onResume();
        wantActive = true;
        if (stereo != null) stereo.onResume();       // 숨겨둔 GLSurfaceView 도 직접 깨워야 한다
        if (leiaView != null) leiaView.onResume();
        if (sdk != null) { try { sdk.onResume(); } catch (Throwable ignored) { } }
        if (player != null) player.setPlayWhenReady(true);
        applyActive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wantActive = false;
        applyActive();
        if (player != null) player.setPlayWhenReady(false);
        if (sdk != null) { try { sdk.onPause(); } catch (Throwable ignored) { } }
        if (leiaView != null) leiaView.onPause();
        if (stereo != null) stereo.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (videoSurface != null) { videoSurface.release(); videoSurface = null; }
        if (leiaView != null) {
            try { leiaView.releaseInputViewsAsset(); } catch (Throwable ignored) { }
        }
        try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
    }
}
