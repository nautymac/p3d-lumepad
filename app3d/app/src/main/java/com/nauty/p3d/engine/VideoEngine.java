package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;

/**
 * 재생 엔진 추상화.
 *
 * 3D 렌더 파이프라인은 "디코딩된 프레임이 SurfaceTexture 로 들어온다"는 것만 알면 되므로,
 * 그 아래는 갈아끼울 수 있다.
 *   ExoPlayer : 기기 MediaCodec 사용. 가볍고 HLS/DASH 에 강함.
 *   libVLC    : 자체 FFmpeg 내장. MKV / DTS / AC3 등 MediaCodec 이 못 하는 것을 커버.
 */
public interface VideoEngine {

    interface Listener {
        /** 실제 영상 해상도가 확정됐을 때. 3D 크롭/종횡비 계산에 쓴다. */
        void onVideoSize(int width, int height);
        void onError(String message);
        /** 오디오 트랙은 있는데 이 기기에 디코더가 없어 무음이 되는 경우. */
        void onAudioUnsupported();
    }

    /** 엔진 종류. 설정 저장과 UI 표시에 쓴다. */
    enum Kind {
        EXO("ExoPlayer"),
        VLC("libVLC"),
        /** 사진 한 장을 정지 프레임으로 흘려보낸다 (PhotoEngine). 재생 엔진 순환에는 넣지 않는다. */
        PHOTO("사진");

        public final String label;
        Kind(String l) { label = l; }
    }

    /**
     * @param surface        ExoPlayer 처럼 Surface 를 받는 엔진용
     * @param surfaceTexture libVLC 처럼 SurfaceTexture 를 직접 받는 엔진용
     */
    void open(Context ctx, Uri uri, Surface surface, SurfaceTexture surfaceTexture, Listener l);

    void play();
    void pause();
    boolean isPlaying();

    void seekTo(long ms);
    long getPosition();
    long getDuration();

    void release();

    Kind kind();
}
