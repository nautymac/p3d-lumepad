package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

@OptIn(markerClass = UnstableApi.class)
public class ExoEngine implements VideoEngine {

    private static final String TAG = "P3D";

    private ExoPlayer player;
    private Listener listener;

    @Override
    public void open(Context ctx, Uri uri, Surface surface, SurfaceTexture st, Listener l) {
        listener = l;

        // FFmpeg 오디오 확장을 우선한다.
        //
        // 이 기기의 MediaCodec 에는 AC3/E-AC3/DTS/TrueHD 디코더가 없다 (MTK 오디오 디코더는
        // MP3/GSM/RAW/G711/WMA/ADPCM/APE/ALAC 뿐). 그래서 3D 영화 대부분이 ExoPlayer 에서
        // 무음이었고, 그 때문에 4K HEVC 처럼 MediaCodec 이 꼭 필요한 소스에서도
        // ExoPlayer 를 못 썼다. libffmpegJNI 가 있으면 그 코덱들을 소프트웨어로 디코딩한다.
        //
        // PREFER 로 두는 이유: 기기 디코더가 있는 코덱(AAC 등)까지 FFmpeg 이 가져가면
        // 손해지만, 실제로 확장 렌더러는 자기가 지원하는 포맷만 받는다.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(ctx)
                .setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        Log.i(TAG, "FFmpeg 오디오 확장: "
                + (FfmpegLibrary.isAvailable()
                        ? "사용 가능 (" + FfmpegLibrary.getVersion() + ")" : "없음"));

        player = new ExoPlayer.Builder(ctx, renderers).build();
        player.setVideoSurface(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (listener != null) listener.onVideoSize(size.width, size.height);
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (listener != null) listener.onError(e.getErrorCodeName());
            }

            /**
             * 기기 MediaCodec 에 없는 코덱(AC3/E-AC3/DTS/TrueHD)은 FFmpeg 확장이 받는다.
             * 그래도 재생 불가로 남는 트랙이 있으면 여기서 잡힌다.
             */
            @Override public void onTracksChanged(Tracks tracks) {
                boolean audioPlayable = false;
                StringBuilder sb = new StringBuilder();
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                    for (int i = 0; i < g.length; i++) {
                        Format f = g.getTrackFormat(i);
                        boolean ok = g.isTrackSupported(i);
                        if (ok) audioPlayable = true;
                        sb.append("\n  ").append(f.sampleMimeType)
                          .append(" ch=").append(f.channelCount)
                          .append(ok ? "  [재생가능]" : "  [디코더 없음]");
                    }
                }
                Log.i(TAG, "ExoPlayer 오디오 트랙:" + (sb.length() == 0 ? " 없음" : sb));
                if (!audioPlayable && sb.length() > 0 && listener != null) {
                    listener.onAudioUnsupported();
                }
            }
        });
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
    }

    @Override public void play()  { if (player != null) player.setPlayWhenReady(true); }
    @Override public void pause() { if (player != null) player.setPlayWhenReady(false); }

    @Override public boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    @Override public void seekTo(long ms) { if (player != null) player.seekTo(ms); }

    @Override public long getPosition() { return player == null ? 0 : player.getCurrentPosition(); }
    @Override public long getDuration() { return player == null ? 0 : player.getDuration(); }

    @Override
    public void release() {
        if (player != null) { player.release(); player = null; }
        listener = null;
    }

    @Override public Kind kind() { return Kind.EXO; }
}
