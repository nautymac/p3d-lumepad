package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 유튜브(실시간 방송 포함) 링크를 ExoPlayer 가 바로 열 수 있는 주소로 바꾼다.
 *
 * 기기 안에서 yt-dlp 를 직접 돌린다 (youtubedl-android 가 파이썬과 yt-dlp 를
 * 통째로 라이브러리 안에 안고 있다) — 서버를 따로 둘 필요가 없다. 첫 호출 때
 * init() 이 그 파이썬 배포판을 앱 전용 폴더에 풀어두는데 이게 좀 걸려서, 한
 * 번 성공하면 이후로는 재사용한다.
 *
 * 영상·오디오가 이미 하나로 합쳐진 스트림만 고른다. 실시간 방송의 HLS 트랙은
 * 원래 그렇게 나오고, 일반 영상도 저화질 쪽엔 남아 있는 경우가 많다. 고화질
 * 전용 트랙(영상·오디오가 따로 나뉘어 하나로 합쳐야 하는 것)은 다루지 않는다 —
 * 그러려면 ExoPlayer 두 소스를 합치는 별도 작업이 필요하다.
 */
public final class YouTube {

    private static volatile boolean initialized = false;

    public static boolean isYoutubeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("youtube.com/") || u.contains("youtu.be/");
    }

    private static synchronized void ensureInit(Context ctx) throws Exception {
        if (initialized) return;
        YoutubeDL.getInstance().init(ctx.getApplicationContext());
        initialized = true;
    }

    /** 화질 하나. url 은 이미 다 갖춰진 주소라 고르는 순간 바로 열 수 있다. */
    public static final class Quality {
        public final String label;
        public final String url;
        Quality(String label, String url) { this.label = label; this.url = url; }
        @Override public String toString() { return label; }
    }

    public static final class Probe {
        public final String title;
        /** 맨 앞이 자동(최고화질), 그 뒤로 화질 높은 순. */
        public final List<Quality> qualities;
        Probe(String title, List<Quality> qualities) { this.title = title; this.qualities = qualities; }
    }

    /**
     * 링크 하나에 딸린 화질 목록을 전부 받아온다 — yt-dlp 를 한 번만 불러도 되도록,
     * 화질을 고른 뒤 다시 조회하지 않고 여기서 받은 주소를 바로 쓴다.
     * 파이썬 초기화 + yt-dlp 실행이 몇 초 걸릴 수 있다 — 반드시 배경 스레드에서 부른다.
     */
    public static Probe probe(Context ctx, String youtubeUrl) throws Exception {
        ensureInit(ctx);

        VideoInfo info = YoutubeDL.getInstance().getInfo(new YoutubeDLRequest(youtubeUrl));

        List<Quality> out = new ArrayList<>();
        String autoUrl = info.getManifestUrl() != null ? info.getManifestUrl() : info.getUrl();
        if (autoUrl != null) {
            out.add(new Quality("자동 (최고화질)", autoUrl));
            registerHeaders(autoUrl, info.getHttpHeaders());
        }

        List<VideoFormat> formats = info.getFormats();
        if (formats != null) {
            // 화질(세로 해상도)마다 하나씩만 — 같은 해상도에 포맷이 여럿이면 목록의
            // 나중 것으로 덮인다(대체로 더 나은 코덱 쪽이 뒤에 온다).
            TreeMap<Integer, VideoFormat> byHeight = new TreeMap<>(Collections.<Integer>reverseOrder());
            for (VideoFormat f : formats) {
                if (f.getUrl() == null || f.getHeight() <= 0) continue;
                if (isNone(f.getVcodec()) || isNone(f.getAcodec())) continue;
                byHeight.put(f.getHeight(), f);
            }
            for (Map.Entry<Integer, VideoFormat> e : byHeight.entrySet()) {
                VideoFormat f = e.getValue();
                out.add(new Quality(e.getKey() + "p", f.getUrl()));
                Map<String, String> h = f.getHttpHeaders() != null ? f.getHttpHeaders() : info.getHttpHeaders();
                registerHeaders(f.getUrl(), h);
            }
        }

        if (out.isEmpty()) {
            throw new IOException("재생 가능한 스트림 주소를 찾지 못했습니다");
        }

        String title = info.getTitle() != null ? info.getTitle() : youtubeUrl;
        return new Probe(title, out);
    }

    private static boolean isNone(String codec) {
        return codec == null || "none".equals(codec);
    }

    private static void registerHeaders(String url, Map<String, String> headers) {
        if (url != null && headers != null && !headers.isEmpty()) {
            StreamHeaders.put(Uri.parse(url).getHost(), headers);
        }
    }
}
