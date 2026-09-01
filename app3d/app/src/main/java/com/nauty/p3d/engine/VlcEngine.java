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

    /**
     * 4K급 소스인지. 그렇다면 소프트웨어 디코딩 경로를 공격적으로 튜닝한다.
     *
     * 이 기기에서 3840x1080 10bit HEVC 는 MediaCodec 이 거부해 avcodec 소프트웨어
     * 디코딩으로 떨어지고, 그러면 초당 20프레임도 못 낸다. 실측:
     * <pre>
     *   libVLC 소프트웨어 : 19.8 fps + "computer too slow -> dropping frame" 연발
     *   ExoPlayer(MediaCodec) : 27.7 fps, 드롭 경고 없음   ← 단, 이 기기엔 AC3 디코더가 없어 무음
     * </pre>
     * libVLC 3.6 의 setHWDecoderEnabled(enabled, force) 로는 이걸 못 바꾼다.
     * force 는 기기 판별이 UNKNOWN 일 때만 의미가 있고, 만들어지는 옵션은 언제나
     * ":codec=mediacodec_ndk,iomx,all" 처럼 마지막이 all 이라 avcodec 폴백이 항상 열려 있다.
     * 그래서 폴백을 막는 대신 폴백 경로를 빠르게 만든다.
     */
    private final boolean heavySource;

    private LibVLC libVlc;
    private MediaPlayer player;
    private Listener listener;
    private SurfaceTexture surfaceTexture;
    private ParcelFileDescriptor pfd;
    private boolean attached = false;
    private int lastW = 0, lastH = 0;

    /**
     * 미리 알아낸 소스 해상도. onNewVideoLayout 이 오지 않는 소스가 있어서 필요하다.
     *
     * 실제로 3840x1080 10bit HEVC 에서는 그 콜백이 한 번도 오지 않았고, 그러면
     * SurfaceTexture 버퍼가 시작할 때 잡아둔 화면 크기(2560x1504)에 머문다. VLC 는
     * 거기에 맞춰 스케일해 버리므로 그림이 눌린 채로 들어오고, 3D 파이프라인도
     * 소스 종횡비를 모르는 채 기본값 16:9 로 배치해 화면이 세로로 늘어난다.
     */
    private final int srcW, srcH;

    /** VLC 로그 상세도 (0=기본, 1=-v, 2=-vv). 디버깅용. */
    private int verbose = 0;
    public void setVerbose(int v) { verbose = v; }

    public VlcEngine()                    { this(false, 0, 0); }
    public VlcEngine(boolean heavySource) { this(heavySource, 0, 0); }

    public VlcEngine(boolean heavySource, int srcW, int srcH) {
        this.heavySource = heavySource;
        this.srcW = srcW;
        this.srcH = srcH;
    }

    @Override
    public void open(Context ctx, Uri uri, Surface surface, SurfaceTexture st, Listener l) {
        listener       = l;
        surfaceTexture = st;

        ArrayList<String> options = new ArrayList<>();

        // 인텐트 엑스트라 --ei vlcverbose 2 로 켜면 VLC 가 모듈 선택 과정을 다 찍는다.
        // MediaCodec 이 왜 거부됐는지 같은 것은 이 로그가 아니면 알 길이 없다.
        if (verbose > 0) {
            options.add("-" + new String(new char[verbose]).replace('\0', 'v'));
        }

        // 자막(SPU) 을 끈다. 이걸 켜두면 VLC 의 안드로이드 vout 이 자막 블렌딩용
        // 별도 서피스를 요구하고, 우리는 영상용 SurfaceTexture 하나만 주므로
        // "can't get Subtitles Surface" -> opaque vout 거부 -> 화면이 단색으로 나온다.
        // 3D 파이프라인에 자막을 합성하지 않으므로 끄는 게 맞다.
        options.add("--no-spu");
        options.add("--no-osd");
        options.add("--no-sub-autodetect-file");
        options.add("--audio-time-stretch");
        options.add("--avcodec-skip-frame");
        options.add("0");
        options.add("--avcodec-skip-idct");
        options.add("0");

        // 무거운 소스(4K급)에서는 소프트웨어 디코딩을 각오해야 한다.
        // libVLC 3.6 의 setHWDecoderEnabled(true, true) 로는 폴백을 막을 수 없다 —
        // 옵션 문자열이 항상 ":codec=...,all" 로 끝나서 마지막에 avcodec 이 붙는다.
        // 그러니 소프트웨어 경로 자체를 빠르게 만드는 수밖에 없다.
        options.add("--avcodec-skiploopfilter");
        options.add(heavySource ? "4" : "1");     // 4 = 디블로킹 전부 생략
        if (heavySource) {
            options.add("--avcodec-threads");
            options.add(String.valueOf(Math.min(8, Runtime.getRuntime().availableProcessors())));
            options.add("--avcodec-fast");        // 규격에 엄격하지 않은 빠른 경로 허용
            options.add("--no-avcodec-corrupted");
        }

        libVlc = new LibVLC(ctx, options);
        player = new MediaPlayer(libVlc);

        IVLCVout vout = player.getVLCVout();
        vout.setVideoSurface(surfaceTexture);

        // vout 은 시작 시점에 창 크기를 알아야 디스플레이를 초기화한다.
        // 소스 해상도를 미리 알아왔으면 그것을 쓴다 — 그래야 VLC 가 스케일하지 않고
        // 원본 그대로 넣어준다. 모르면 화면 크기로 잡아두고 콜백에서 고친다.
        int dw = srcW > 0 ? srcW : ctx.getResources().getDisplayMetrics().widthPixels;
        int dh = srcH > 0 ? srcH : ctx.getResources().getDisplayMetrics().heightPixels;
        if (dw > 0 && dh > 0) {
            surfaceTexture.setDefaultBufferSize(dw, dh);
            vout.setWindowSize(dw, dh);
            lastW = dw; lastH = dh;
            Log.i(TAG, "VLC 초기 window size = " + dw + "x" + dh
                    + (srcW > 0 ? " (소스 해상도)" : " (화면 크기 - 소스 미상)"));
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
        media.setHWDecoderEnabled(true, true);
        Log.i(TAG, "VLC 디코딩: 하드웨어 우선" + (heavySource ? " (무거운 소스 - 소프트웨어 경로 가속)" : ""));
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
        if (len <= 0) return;
        if (ms < 0) ms = 0;
        if (ms > len) ms = len;

        // setTime 은 디먹서의 타임스탬프 색인(MKV 의 Cues 등)을 쓴다.
        // setPosition(비율) 은 파일 오프셋을 추정해 그 지점부터 재동기화하므로 더 느리고
        // 부정확하다. 색인이 없는 파일에서만 비율 방식으로 물러난다.
        try {
            player.setTime(ms);
        } catch (Throwable t) {
            player.setPosition(Math.max(0f, Math.min(1f, (float) ms / (float) len)));
        }
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
