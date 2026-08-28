package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/**
 * libVLC 백엔드.
 *
 * ExoPlayer 와 달리 자체 디코더(FFmpeg)를 들고 있어서, 기기 MediaCodec 이 처리 못 하는
 * 컨테이너/코덱을 재생할 수 있다.
 *
 * 단, DTS 는 이 기기에서 결국 무음이다. VLC 가 디코딩은 하지만 오디오 출력 모듈이
 * "module not functional" 로 실패한다 (--aout=opensles_android, --stereo-mode=1,
 * setAudioOutputDevice("stereo") 전부 효과 없었고 일반 파일 볼륨만 낮아져 되돌렸다).
 * 그러니 DTS 파일에 libVLC 를 권하지 말 것.
 *
 * 렌더 경로는 동일하다: IVLCVout 에 우리 SurfaceTexture 를 물려주면
 * 프레임이 OES 텍스처로 들어오고, 그 뒤 3D 파이프라인은 엔진과 무관하게 그대로 동작한다.
 */
public class VlcEngine implements VideoEngine {

    private static final String TAG = "P3D";

    private LibVLC libVlc;
    private MediaPlayer player;
    private Listener listener;
    private SurfaceTexture surfaceTexture;
    private ParcelFileDescriptor pfd;
    private boolean attached = false;
    private int lastW = 0, lastH = 0;

    @Override
    public void open(Context ctx, Uri uri, Surface surface, SurfaceTexture st, Listener l) {
        listener       = l;
        surfaceTexture = st;

        ArrayList<String> options = new ArrayList<>();
        // 자막(SPU) 을 끈다. 이걸 켜두면 VLC 의 안드로이드 vout 이 자막 블렌딩용
        // 별도 서피스를 요구하고, 우리는 영상용 SurfaceTexture 하나만 주므로
        // "can't get Subtitles Surface" -> opaque vout 거부 -> 화면이 단색으로 나온다.
        // 3D 파이프라인에 자막을 합성하지 않으므로 끄는 게 맞다.
        options.add("--no-spu");
        options.add("--no-osd");
        options.add("--no-sub-autodetect-file");
        options.add("--audio-time-stretch");
        options.add("--avcodec-skiploopfilter");
        options.add("1");
        options.add("--avcodec-skip-frame");
        options.add("0");
        options.add("--avcodec-skip-idct");
        options.add("0");

        libVlc = new LibVLC(ctx, options);
        player = new MediaPlayer(libVlc);

        IVLCVout vout = player.getVLCVout();
        vout.setVideoSurface(surfaceTexture);

        // vout 은 시작 시점에 창 크기를 알아야 디스플레이를 초기화한다.
        // onNewVideoLayout 은 재생이 시작된 뒤에야 오므로, 그 전에 화면 크기로 한 번 잡아준다.
        int dw = ctx.getResources().getDisplayMetrics().widthPixels;
        int dh = ctx.getResources().getDisplayMetrics().heightPixels;
        if (dw > 0 && dh > 0) {
            surfaceTexture.setDefaultBufferSize(dw, dh);
            vout.setWindowSize(dw, dh);
            Log.i(TAG, "VLC 초기 window size = " + dw + "x" + dh);
        }

        vout.attachViews(new IVLCVout.OnNewVideoLayoutListener() {
            @Override
            public void onNewVideoLayout(IVLCVout v, int width, int height,
                                         int visibleWidth, int visibleHeight,
                                         int sarNum, int sarDen) {
                int w = visibleWidth  > 0 ? visibleWidth  : width;
                int h = visibleHeight > 0 ? visibleHeight : height;
                if (w <= 0 || h <= 0) return;

                // 화면비 보정(anamorphic) 이 걸린 소스면 반영한다.
                if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
                    w = (int) ((long) w * sarNum / sarDen);
                }
                Log.i(TAG, "VLC onNewVideoLayout " + width + "x" + height
                        + " visible " + visibleWidth + "x" + visibleHeight
                        + " sar " + sarNum + "/" + sarDen);

                if (w == lastW && h == lastH) return;
                lastW = w; lastH = h;

                // VLC 는 ANativeWindow 로 그리므로 버퍼 크기를 명시해 줘야
                // 원본 해상도 그대로 들어온다.
                surfaceTexture.setDefaultBufferSize(w, h);
                v.setWindowSize(w, h);

                if (listener != null) listener.onVideoSize(w, h);
            }
        });
        attached = true;

        player.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(MediaPlayer.Event e) {
                switch (e.type) {
                    case MediaPlayer.Event.EncounteredError:
                        Log.e(TAG, "VLC EncounteredError");
                        if (listener != null) listener.onError("VLC 재생 오류");
                        break;
                    case MediaPlayer.Event.Vout:
                        Log.i(TAG, "VLC Vout count = " + e.getVoutCount());
                        break;
                    case MediaPlayer.Event.Playing:
                        Log.i(TAG, "VLC Playing");
                        logAudioTracks();
                        break;
                    default:
                        break;
                }
            }
        });

        Media media = buildMedia(ctx, uri);
        if (media == null) {
            if (listener != null) listener.onError("미디어를 열 수 없습니다");
            return;
        }
        // force=false 로 둔다. 하드웨어 디코딩을 우선하되 실패하면 소프트웨어로 폴백하는데,
        // 그 폴백이야말로 libVLC 를 쓰는 이유다 (MediaCodec 이 못 하는 코덱 커버).
        // force=true 도 동작하지만 폴백이 막히므로 이득이 없다 — 화면이 단색으로 나오던 문제의
        // 원인은 디코더가 아니라 위의 window size 미설정이었다.
        media.setHWDecoderEnabled(true, false);
        player.setMedia(media);
        media.release();
    }

    /** VLC 가 실제로 잡은 오디오 트랙. ExoPlayer 와 비교해 DTS/AC3 재생 여부를 확인할 때 쓴다. */
    private void logAudioTracks() {
        if (player == null) return;
        try {
            MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
            int selected = player.getAudioTrack();
            if (tracks == null) {
                Log.i(TAG, "VLC 오디오 트랙: 없음");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (MediaPlayer.TrackDescription t : tracks) {
                sb.append("\n  id=").append(t.id).append(" ").append(t.name)
                  .append(t.id == selected ? "  [선택됨]" : "");
            }
            Log.i(TAG, "VLC 오디오 트랙 " + tracks.length + "개:" + sb);
        } catch (Throwable t) {
            Log.w(TAG, "VLC 오디오 트랙 조회 실패", t);
        }
    }

    /** content:// 는 파일 디스크립터로, 그 외(파일/네트워크)는 URI 로 연다. */
    private Media buildMedia(Context ctx, Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) return null;
                return new Media(libVlc, pfd.getFileDescriptor());
            }
            return new Media(libVlc, uri);
        } catch (Exception e) {
            Log.e(TAG, "VLC 미디어 생성 실패: " + uri, e);
            return null;
        }
    }

    @Override public void play()  { if (player != null) player.play(); }
    @Override public void pause() { if (player != null) player.pause(); }

    @Override public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public void seekTo(long ms) {
        if (player == null) return;
        long len = player.getLength();
        if (len > 0) player.setPosition(Math.max(0f, Math.min(1f, (float) ms / (float) len)));
    }

    @Override public long getPosition() { return player == null ? 0 : player.getTime(); }
    @Override public long getDuration() { return player == null ? 0 : player.getLength(); }

    @Override
    public void release() {
        listener = null;
        if (player != null) {
            player.stop();
            if (attached) {
                player.getVLCVout().detachViews();
                attached = false;
            }
            player.release();
            player = null;
        }
        if (libVlc != null) { libVlc.release(); libVlc = null; }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }
        surfaceTexture = null;
    }

    @Override public Kind kind() { return Kind.VLC; }
}
