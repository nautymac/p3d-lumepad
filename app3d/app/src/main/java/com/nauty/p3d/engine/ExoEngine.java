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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.nauty.p3d.net.NetDataSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class ExoEngine implements VideoEngine {

    private static final String TAG = "P3D";

    private ExoPlayer player;
    private Listener listener;

    private List<TrackInfo> audioTracks = Collections.emptyList();
    private List<TrackInfo> textTracks  = Collections.emptyList();

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

        // smb:// 는 NetDataSourceFactory 가 SmbDataSource 로 돌리고, 그 밖의 스킴(http/
        // https/content/file, DLNA 가 주는 평범한 http URL 포함)은 그대로 기본 경로를 탄다
        // (HANDOFF 1-② 참고).
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(ctx).setDataSourceFactory(new NetDataSourceFactory(ctx));

        player = new ExoPlayer.Builder(ctx, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        player.setVideoSurface(surface);

        // 내장 자막은 기본으로 꺼 둔다. 그래야 사용자가 트랙 선택기에서 직접 고른
        // 경우에만 onCues 가 들어오고, 자동 선택된 트랙이 외부 .srt 와 겹치지 않는다.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());

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
                List<TrackInfo> audio = new ArrayList<>();
                List<TrackInfo> text  = new ArrayList<>();

                for (Tracks.Group g : tracks.getGroups()) {
                    int type = g.getType();
                    if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;

                    for (int i = 0; i < g.length; i++) {
                        Format f = g.getTrackFormat(i);
                        boolean ok = g.isTrackSupported(i);

                        if (type == C.TRACK_TYPE_AUDIO) {
                            if (ok) audioPlayable = true;
                            sb.append("\n  ").append(f.sampleMimeType)
                              .append(" ch=").append(f.channelCount)
                              .append(ok ? "  [재생가능]" : "  [디코더 없음]");
                            audio.add(new TrackInfo(g.getMediaTrackGroup(), i,
                                    trackLabel(f, type), ok, false));
                        } else {
                            boolean image = isImageSubtitle(f.sampleMimeType);
                            text.add(new TrackInfo(g.getMediaTrackGroup(), i,
                                    trackLabel(f, type), ok, image));
                        }
                    }
                }
                audioTracks = disambiguate(audio);
                textTracks  = disambiguate(text);

                Log.i(TAG, "ExoPlayer 오디오 트랙:" + (sb.length() == 0 ? " 없음" : sb));
                if (!audioPlayable && sb.length() > 0 && listener != null) {
                    listener.onAudioUnsupported();
                }
            }

            /**
             * 사용자가 트랙 선택기에서 내장 자막을 고른 경우에만 여기로 온다
             * (open() 에서 텍스트 트랙 타입을 기본으로 꺼 두었기 때문).
             * 이미지 자막(PGS/VOBSUB/DVB)은 selectTextTrack() 호출 전에
             * PlayerActivity 가 걸러서 아예 선택되지 않으므로, cue.text 만 보면 된다.
             */
            @Override public void onCues(CueGroup cueGroup) {
                if (listener == null) return;
                StringBuilder text = new StringBuilder();
                for (Cue c : cueGroup.cues) {
                    if (c.text == null) continue;
                    if (text.length() > 0) text.append('\n');
                    text.append(c.text);
                }
                listener.onEmbeddedCue(text.length() == 0 ? null : text.toString());
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

    // --------------------------------------------------------- 트랙 선택

    @Override public List<TrackInfo> audioTracks() { return audioTracks; }
    @Override public List<TrackInfo> textTracks()  { return textTracks; }

    @Override
    public void selectAudioTrack(TrackInfo track) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        b.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
        if (track != null) {
            b.setOverrideForType(new TrackSelectionOverride(track.group, track.indexInGroup));
        }
        player.setTrackSelectionParameters(b.build());
    }

    @Override
    public void selectTextTrack(TrackInfo track) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        b.clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (track == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
            b.setOverrideForType(new TrackSelectionOverride(track.group, track.indexInGroup));
        }
        player.setTrackSelectionParameters(b.build());
    }

    /** PGS/VOBSUB/DVB 자막은 이미지로 온다. 텍스트 자막용 경로로는 그릴 수 없다. */
    private static boolean isImageSubtitle(String mimeType) {
        return MimeTypes.APPLICATION_PGS.equals(mimeType)
                || MimeTypes.APPLICATION_VOBSUB.equals(mimeType)
                || MimeTypes.APPLICATION_DVBSUBS.equals(mimeType);
    }

    /** 트랙 선택기에 보일 이름. 이름 -> 언어 -> "오디오"/"자막" 순으로 고르고 코덱을 덧붙인다. */
    private static String trackLabel(Format f, int type) {
        StringBuilder sb = new StringBuilder();
        if (f.label != null) {
            sb.append(f.label);
        } else if (f.language != null) {
            sb.append(f.language.toUpperCase(Locale.US));
        } else {
            sb.append(type == C.TRACK_TYPE_AUDIO ? "Audio" : "Subtitle");
        }
        String codec = shortCodec(f.sampleMimeType);
        if (codec != null) sb.append(" (").append(codec).append(")");
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
            sb.append(" ").append(f.channelCount).append("ch");
        }
        return sb.toString();
    }

    /** "application/x-subrip" -> "SUBRIP" 처럼 마임타입 뒷부분만 대문자로. */
    private static String shortCodec(String mimeType) {
        if (mimeType == null) return null;
        int slash = mimeType.lastIndexOf('/');
        String tail = slash >= 0 ? mimeType.substring(slash + 1) : mimeType;
        int dash = tail.lastIndexOf('-');
        if (dash >= 0) tail = tail.substring(dash + 1);
        return tail.toUpperCase(Locale.US);
    }

    /**
     * 이름·언어·코덱·채널수가 전부 같은 트랙이 여러 개면 (제목 없는 MTV 코멘터리 트랙,
     * 같은 언어의 중복 인코딩 등) trackLabel() 이 똑같은 문자열을 만든다. 실제로는
     * 서로 다른 트랙인데 목록에서 구분이 안 되던 문제 — 겹치는 라벨에만 번호를 매긴다.
     */
    private static List<TrackInfo> disambiguate(List<TrackInfo> tracks) {
        java.util.Map<String, Integer> total = new java.util.HashMap<>();
        for (TrackInfo t : tracks) total.merge(t.label, 1, Integer::sum);

        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        List<TrackInfo> out = new ArrayList<>(tracks.size());
        for (TrackInfo t : tracks) {
            if (total.get(t.label) > 1) {
                int n = seen.merge(t.label, 1, Integer::sum);
                out.add(new TrackInfo(t.group, t.indexInGroup, t.label + "  #" + n,
                        t.supported, t.imageBased));
            } else {
                out.add(t);
            }
        }
        return out;
    }
}
