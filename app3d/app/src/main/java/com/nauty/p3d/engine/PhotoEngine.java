package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.InputStream;

/**
 * 사진을 "한 장짜리 영상" 으로 흘려보내는 엔진.
 *
 * 이렇게 하면 3D 파이프라인을 하나도 손대지 않아도 된다. 레터박스, 화면 비 보정,
 * SBS/TB 크롭, 수렴, 위빙, Leia 신경망 변환까지 영상에 쓰던 길이 그대로 쓰인다.
 * 뷰가 아는 것은 여전히 "SurfaceTexture 로 프레임이 들어온다" 뿐이다.
 *
 * 정지 화면인데도 계속 보내는 이유가 둘 있다.
 *   · Leia 변환기는 들어온 프레임 수로 시간을 세고 RGB 를 몇 프레임 늦춰 깊이와 맞춘다.
 *     한 장만 넣으면 그 지연 구간을 못 빠져나와 화면이 비어 있다.
 *   · 우리 GL 도 "새 프레임이 있을 때만" 밖으로 내보낸다 (Stereo3DView 의 fresh 참고).
 * 다만 계속 최고 속도로 보낼 이유는 없어서, 처음 몇 초만 촘촘히 넣고 그 뒤로는
 * 유지용으로만 띄엄띄엄 보낸다.
 */
public class PhotoEngine implements VideoEngine {

    private static final String TAG = "P3D";

    /** 디코딩 상한. 눈 하나가 화면에서 1920px 이므로 SBS 전체 4096 이면 충분하다. */
    private static final int MAX_WIDTH = 4096;

    private static final long PRIME_MS   = 3000;   // 이 동안은 촘촘히
    private static final long FAST_MS    = 66;     // 약 15fps
    private static final long KEEPALIVE_MS = 400;  // 그 뒤 2.5fps

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Surface surface;
    private SurfaceTexture texture;
    private Bitmap bitmap;
    private Listener listener;
    private long startedAt;
    private boolean released;

    private final Runnable pump = new Runnable() {
        @Override public void run() {
            if (released) return;
            post();
            long since = android.os.SystemClock.elapsedRealtime() - startedAt;
            ui.postDelayed(this, since < PRIME_MS ? FAST_MS : KEEPALIVE_MS);
        }
    };

    @Override
    public void open(Context ctx, Uri uri, Surface s, SurfaceTexture st, Listener l) {
        surface  = s;
        texture  = st;
        listener = l;

        bitmap = decode(ctx, uri);
        if (bitmap == null) {
            if (l != null) l.onError("사진을 열지 못했습니다");
            return;
        }
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        Log.i(TAG, "사진 " + w + "x" + h);

        // 영상 경로의 onVideoSize 와 같은 자리다. 여기서 알린 크기로
        // half/full 판정과 레터박스가 정해진다.
        if (l != null) l.onVideoSize(w, h);

        // 이걸 안 하면 버퍼가 뷰 크기(Lume Pad 2 에서는 1x1)로 잡힌다.
        if (st != null) st.setDefaultBufferSize(w, h);

        startedAt = android.os.SystemClock.elapsedRealtime();
        ui.removeCallbacks(pump);
        ui.post(pump);
    }

    /**
     * 화면에 필요한 것보다 크게 풀지 않는다. 5120x1440 을 그대로 풀면 29MB 짜리
     * 비트맵이 되는데, 눈 하나가 어차피 1920px 로 줄어들어 보이므로 얻는 것이 없다.
     */
    private Bitmap decode(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (bounds.outWidth / o.inSampleSize > MAX_WIDTH) o.inSampleSize *= 2;
            // ARGB_8888 이어야 lockCanvas 로 그릴 때 색이 상하지 않는다.
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;

            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "사진 디코딩 실패: " + uri, t);
            return null;
        }
    }

    /** 지금 화면에 있는 사진. 시차 측정에 그대로 쓴다 — 다시 읽을 이유가 없다. */
    public Bitmap frame() { return bitmap; }

    private void post() {
        Surface s = surface;
        Bitmap b = bitmap;
        if (s == null || b == null || b.isRecycled() || !s.isValid()) return;
        Canvas c = null;
        try {
            // 하드웨어 캔버스면 GPU 가 복사한다. 3840x1080 을 초당 15번 CPU 로
            // 옮기면 그것만으로 무겁다.
            if (Build.VERSION.SDK_INT >= 23) {
                try { c = s.lockHardwareCanvas(); } catch (Throwable ignored) { }
            }
            if (c == null) c = s.lockCanvas(null);
            c.drawColor(Color.BLACK);
            c.drawBitmap(b, 0, 0, null);
        } catch (Throwable t) {
            return;                       // 서피스가 막 바뀌는 중이면 다음 차례에 다시
        } finally {
            if (c != null) {
                try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
            }
        }
    }

    // ----- 사진에는 시간이 없다. 재생 관련 호출은 조용히 받아넘긴다.

    @Override public void play()  { }
    @Override public void pause() { }
    @Override public boolean isPlaying() { return true; }
    @Override public void seekTo(long ms) { }
    @Override public long getPosition() { return 0; }
    @Override public long getDuration() { return 0; }

    @Override
    public void release() {
        released = true;
        ui.removeCallbacks(pump);
        surface = null;
        texture = null;
        Bitmap b = bitmap;
        bitmap = null;
        if (b != null && !b.isRecycled()) b.recycle();
    }

    @Override public Kind kind() { return Kind.PHOTO; }
}
