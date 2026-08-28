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
import androidx.media3.exoplayer.ExoPlayer;

@OptIn(markerClass = UnstableApi.class)
public class ExoEngine implements VideoEngine {

    private static final String TAG = "P3D";

    private ExoPlayer player;
    private Listener listener;

    @Override
    public void open(Context ctx, Uri uri, Surface surface, SurfaceTexture st, Listener l) {
        listener = l;
        player = new ExoPlayer.Builder(ctx).build();
        player.setVideoSurface(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (listener != null) listener.onVideoSize(size.width, size.height);
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (listener != null) listener.onError(e.getErrorCodeName());
            }

            /**
             * 이 기기에는 DTS/AC3 디코더가 없다(MediaTek 오디오 디코더: MP3/GSM/RAW/G711/
             * WMA/ADPCM/APE/ALAC). 그런 트랙은 여기서 "지원 안 됨" 으로 잡히고 무음이 된다.
             * 그럴 때 libVLC 로 바꾸면 자체 디코더로 재생된다.
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
