package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;

import java.util.List;

/**
 * 재생 엔진 추상화.
 *
 * 3D 렌더 파이프라인은 "디코딩된 프레임이 SurfaceTexture 로 들어온다"는 것만 알면 되므로,
 * 그 아래는 갈아끼울 수 있다.
 *   ExoPlayer   : 기기 MediaCodec 으로 하드웨어 디코딩. 영상은 전부 이쪽이다.
 *   PhotoEngine : 사진 한 장을 정지 프레임으로 흘려보낸다.
 *
 * 한때 libVLC 도 있었지만 걷어냈다. 이 기기에서 libVLC 는 MediaCodec 조회 중 예외를
 * 맞아 (Exception occurred in MediaCodecInfo.getCapabilitiesForType) 하드웨어 디코더를
 * 못 찾고 <b>항상</b> 소프트웨어로 디코딩한다. 3840x1080 10bit HEVC 가 21fps 로
 * 무너지는데 ExoPlayer 는 같은 파일을 27.6fps 로 돌린다. 무음의 원인이던 AC3/DTS 는
 * FFmpeg 오디오 확장이 맡으면서 libVLC 를 남겨둘 이유가 없어졌다.
 * APK 에서 43MB(libvlc.so)가 빠지는 것은 덤이다.
 */
public interface VideoEngine {

    interface Listener {
        /** 실제 영상 해상도가 확정됐을 때. 3D 크롭/종횡비 계산에 쓴다. */
        void onVideoSize(int width, int height);
        void onError(String message);
        /** 오디오 트랙은 있는데 이 기기에 디코더가 없어 무음이 되는 경우. */
        void onAudioUnsupported();
        /**
         * 선택된 내장 자막 트랙의 지금 자막. null 이면 지울 자막이 없다는 뜻이다.
         * 텍스트 자막만 온다 — 이미지 자막(PGS/VOBSUB)은 선택 자체를 막아 여기로 오지 않는다.
         */
        void onEmbeddedCue(String text);
    }

    /** 엔진 종류. 설정 저장과 UI 표시에 쓴다. */
    enum Kind {
        EXO(com.nauty.p3d.R.string.engine_exo),
        /** 사진 한 장을 정지 프레임으로 흘려보낸다 (PhotoEngine). */
        PHOTO(com.nauty.p3d.R.string.photo_label);

        /** 화면에 보일 이름. 기기 언어를 따른다 (ExoPlayer 는 어느 말이든 같다). */
        public final int labelRes;
        Kind(int labelRes) { this.labelRes = labelRes; }
    }

    /**
     * @param surface        ExoPlayer 처럼 Surface 를 받는 엔진용
     * @param surfaceTexture SurfaceTexture 를 직접 받는 엔진용
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

    // --------------------------------------------------------- 트랙 선택
    //
    // mkv/mp4 컨테이너 안에 여러 오디오·자막 트랙이 들어 있을 수 있다. 사진에는
    // 트랙이라는 것이 없으니 PhotoEngine 은 전부 빈 목록/빈 구현으로 둔다.

    /** 컨테이너 안의 오디오 트랙. 아직 안 열렸거나 없으면 빈 목록. */
    List<TrackInfo> audioTracks();

    /** 컨테이너 안의 자막 트랙. 아직 안 열렸거나 없으면 빈 목록. */
    List<TrackInfo> textTracks();

    /** null 이면 기기가 고르는 기본값으로 되돌린다. */
    void selectAudioTrack(TrackInfo track);

    /**
     * null 이면 내장 자막을 끈다.
     * 외부 .srt/.smi 를 쓸 때도 반드시 null 로 꺼야 한다 — 안 그러면 내장 자막이
     * {@link Listener#onEmbeddedCue} 로 계속 들어와 외부 자막과 겹쳐 보인다.
     */
    void selectTextTrack(TrackInfo track);
}
