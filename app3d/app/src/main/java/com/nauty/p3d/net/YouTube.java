package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;

import com.fasterxml.jackson.databind.JsonNode;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * getInfo() 가 감싼 VideoInfo/VideoFormat 매퍼는 language·subtitles 필드를 노출하지
 * 않아서, 여기서는 --dump-json 결과를 직접 원시 JSON(JsonNode)으로 읽는다.
 *
 * 실시간 방송의 HLS 트랙은 영상·오디오가 이미 하나로 합쳐져 나오지만, 일반
 * 영상은 요즘 유튜브가 화질별로 영상·오디오를 따로 나눠 준다 — 합쳐진 트랙은
 * 360p 하나 정도만 남는 경우가 흔하다. 그래서 합쳐진 트랙뿐 아니라, 화질별
 * 영상 전용 트랙을 오디오 전용 트랙과 짝지어서도 화질 목록에 넣는다. 이렇게
 * 짝지은 화질은 Quality.audioUrl 에 오디오 주소가 따로 담기고, ExoEngine 이
 * "merge://" 스킴을 보고 두 소스를 합쳐서 연다.
 *
 * 더빙된 영상처럼 오디오 언어가 여러 개면 화질 목록에 언어를 같이 표기해
 * 고를 수 있게 한다 — 언어가 하나뿐이면(대부분의 경우) 평소와 똑같다.
 */
public final class YouTube {

    private static volatile boolean initialized = false;

    public static boolean isYoutubeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("youtube.com/") || u.contains("youtu.be/");
    }

    /**
     * 라이브러리에 들어 있는 yt-dlp 는 이 라이브러리가 배포될 때 버전에 그대로
     * 고정돼 있다. 유튜브는 스크래핑 방어를 자주 바꿔서, 몇 달만 지나도 번들
     * 버전은 요청은 되지만 403 으로 거부되는 스트림 주소를 뽑아 온다(실기에서
     * 겪었다 — 요청 헤더까지 그대로 실어 보내도 막혔다. 방어가 헤더가 아니라
     * yt-dlp 버전이 아는 서명 방식 자체를 보는 것으로 보인다).
     * init() 직후 최신판으로 자체 업데이트를 한 번 시도한다 — 실패해도(오프라인 등)
     * 번들 버전으로 계속 시도할 수 있게 무시한다.
     */
    private static synchronized void ensureInit(Context ctx) throws Exception {
        if (initialized) return;
        YoutubeDL.getInstance().init(ctx.getApplicationContext());
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.getApplicationContext(),
                    YoutubeDL.UpdateChannel._STABLE);
        } catch (Exception ignored) { }
        initialized = true;
    }

    /**
     * 화질 하나. audioUrl 이 null 이면 url 하나로 이미 다 갖춰진 스트림(영상+오디오
     * 합쳐짐)이고, null 이 아니면 url(영상)과 audioUrl(오디오)을 따로 열어 합쳐야 한다.
     */
    public static final class Quality {
        public final String label;
        public final String url;
        public final String audioUrl;
        Quality(String label, String url, String audioUrl) {
            this.label = label; this.url = url; this.audioUrl = audioUrl;
        }
        @Override public String toString() { return label; }

        /** ExoEngine 이 열 수 있는 주소로 바꾼다 — 합쳐야 하면 merge:// 로 감싼다. */
        public Uri playUri() {
            if (audioUrl == null) return Uri.parse(url);
            return new Uri.Builder().scheme("merge").authority("yt")
                    .appendQueryParameter("v", url)
                    .appendQueryParameter("a", audioUrl)
                    .build();
        }
    }

    /** 자막(캡션) 하나. 고른 것만 downloadCaption() 으로 내려받는다 — 목록 자체는 공짜다. */
    public static final class Caption {
        public final String code;
        public final String label;
        public final String url;
        Caption(String code, String label, String url) {
            this.code = code; this.label = label; this.url = url;
        }
        @Override public String toString() { return label; }
    }

    public static final class Probe {
        public final String title;
        /** 맨 앞이 자동(최고화질), 그 뒤로 화질(및 언어) 별. */
        public final List<Quality> qualities;
        /** 기기 언어 → 영어 → 그 밖 순으로 정렬. 실제 업로드 자막이 자동 생성보다 앞선다. */
        public final List<Caption> captions;
        Probe(String title, List<Quality> qualities, List<Caption> captions) {
            this.title = title; this.qualities = qualities; this.captions = captions;
        }
    }

    /**
     * 링크 하나에 딸린 화질(+언어) 목록과 고를 수 있는 자막 목록을 받아온다 — yt-dlp 를
     * 한 번만 불러도 되도록, 화질을 고른 뒤 다시 조회하지 않고 여기서 받은 주소를
     * 바로 쓴다. 자막은 목록만 만들고 실제로 받지는 않는다 — 사용자가 고른 것만
     * downloadCaption() 으로 따로 받는다. 파이썬 초기화 + yt-dlp 실행이 몇 초 걸릴
     * 수 있다 — 반드시 배경 스레드에서 부른다.
     */
    public static Probe probe(Context ctx, String youtubeUrl) throws Exception {
        ensureInit(ctx);

        YoutubeDLRequest request = new YoutubeDLRequest(youtubeUrl);
        request.addOption("--dump-json");
        YoutubeDLResponse resp = YoutubeDL.getInstance().execute(request, null, null);
        JsonNode root = YoutubeDL.getInstance().getObjectMapper().readTree(resp.getOut());

        String title = root.path("title").asText(youtubeUrl);
        List<Quality> qualities = buildQualities(root);
        List<Caption> captions = listCaptions(root);

        return new Probe(title, qualities, captions);
    }

    // ------------------------------------------------------------ 화질/오디오

    private static List<Quality> buildQualities(JsonNode root) {
        // 화질(세로 해상도)마다 하나씩 — 같은 해상도에 후보가 여럿이면 비트레이트가
        // 더 높은 쪽을 남긴다. 오디오는 언어별로 가장 좋은 것 하나씩 남긴다.
        TreeMap<Integer, JsonNode> combinedByHeight = new TreeMap<>(Collections.<Integer>reverseOrder());
        TreeMap<Integer, JsonNode> videoOnlyByHeight = new TreeMap<>(Collections.<Integer>reverseOrder());
        // LinkedHashMap 이라 순서가 유지된다 — 유튜브는 원본 언어를 보통 먼저 나열한다.
        LinkedHashMap<String, JsonNode> audioByLang = new LinkedHashMap<>();

        JsonNode formats = root.path("formats");
        if (formats.isArray()) {
            for (JsonNode f : formats) {
                String url = text(f, "url");
                if (url == null) continue;
                boolean hasVideo = !isNone(text(f, "vcodec"));
                boolean hasAudio = !isNone(text(f, "acodec"));
                int height = f.path("height").asInt(0);

                // HLS/DASH 매니페스트(m3u8 등)는 그 안에 이미 필요한 트랙이 다 있다 —
                // acodec 표기가 부정확해 "영상 전용"으로 잘못 걸리면, 관계없는 오디오
                // 전용 파일과 억지로 합쳐져 재생이 깨진다(실기에서 DVR 방송 다시보기로
                // 겪었다). 매니페스트면 acodec 표기와 무관하게 무조건 완결된 트랙으로 본다.
                boolean isManifest = text(f, "manifest_url") != null
                        || "m3u8_native".equals(text(f, "protocol"))
                        || "m3u8".equals(text(f, "protocol"));

                if ((hasVideo && hasAudio || isManifest && hasVideo) && height > 0) {
                    keepBest(combinedByHeight, height, f);
                } else if (hasVideo && height > 0) {
                    keepBest(videoOnlyByHeight, height, f);
                } else if (hasAudio) {
                    String lang = text(f, "language");
                    String key = lang == null ? "" : lang;
                    JsonNode cur = audioByLang.get(key);
                    if (cur == null || bitrate(f) > bitrate(cur)) audioByLang.put(key, f);
                }
            }
        }

        List<Quality> out = new ArrayList<>();
        TreeMap<Integer, Quality> defaultByHeight = new TreeMap<>(Collections.<Integer>reverseOrder());

        for (Map.Entry<Integer, JsonNode> e : combinedByHeight.entrySet()) {
            JsonNode f = e.getValue();
            Quality q = new Quality(e.getKey() + "p", text(f, "url"), null);
            defaultByHeight.put(e.getKey(), q);
            registerHeaders(text(f, "url"), headersOf(f, root));
        }

        if (!audioByLang.isEmpty()) {
            boolean multiLang = audioByLang.size() > 1;
            boolean first = true;
            for (Map.Entry<String, JsonNode> langEntry : audioByLang.entrySet()) {
                JsonNode audio = langEntry.getValue();
                String audioUrl = text(audio, "url");
                registerHeaders(audioUrl, headersOf(audio, root));
                String langName = multiLang ? languageName(langEntry.getKey()) : null;

                for (Map.Entry<Integer, JsonNode> e : videoOnlyByHeight.entrySet()) {
                    int height = e.getKey();
                    if (first && defaultByHeight.containsKey(height)) continue; // 이미 합쳐진 트랙이 있다
                    JsonNode f = e.getValue();
                    String videoUrl = text(f, "url");
                    registerHeaders(videoUrl, headersOf(f, root));
                    String label = langName == null ? height + "p" : height + "p · " + langName;
                    Quality q = new Quality(label, videoUrl, audioUrl);
                    if (first) defaultByHeight.put(height, q);
                    else out.add(q);   // 다른 언어는 "자동" 계산에 넣지 않고 목록에만 추가
                }
                first = false;
            }
        }

        List<Quality> all = new ArrayList<>();
        if (!defaultByHeight.isEmpty()) {
            Quality top = defaultByHeight.firstEntry().getValue();
            all.add(new Quality("자동 (최고화질)", top.url, top.audioUrl));
        }
        all.addAll(defaultByHeight.values());
        all.addAll(out);
        return all;
    }

    private static void keepBest(TreeMap<Integer, JsonNode> byHeight, int height, JsonNode f) {
        JsonNode cur = byHeight.get(height);
        if (cur == null || bitrate(f) > bitrate(cur)) byHeight.put(height, f);
    }

    private static double bitrate(JsonNode f) {
        return Math.max(f.path("tbr").asDouble(0), f.path("abr").asDouble(0));
    }

    private static boolean isNone(String codec) {
        return codec == null || "none".equals(codec);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** "ko" -> "한국어" 처럼, 기기 언어 기준으로 사람이 읽을 이름을 만든다. 실패하면 코드 그대로. */
    private static String languageName(String code) {
        if (code.isEmpty()) return "?";
        try {
            String tag = code.split("-")[0];
            String name = new Locale(tag).getDisplayLanguage();
            return name.isEmpty() ? code : name;
        } catch (Exception e) {
            return code;
        }
    }

    /**
     * 포맷마다 http_headers 가 따로 있는 일은 드물다 — 보통 정보 최상위(root)에
     * 이 영상 전체에 쓸 헤더가 한 번만 들어 있다. 이걸 안 실어 보내면 구글비디오
     * CDN 이 403 으로 막는다(실기에서 겪었다 — yt-dlp 가 확인해 준 요청과 다르다고
     * 보는 것 같다). 포맷 쪽에 있으면 그걸 우선하고, 없으면 root 걸 쓴다.
     */
    private static Map<String, String> headersOf(JsonNode f, JsonNode root) {
        JsonNode h = f.get("http_headers");
        if (h == null || !h.isObject()) h = root.get("http_headers");
        if (h == null || !h.isObject()) return null;
        Map<String, String> out = new java.util.HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = h.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    private static void registerHeaders(String url, Map<String, String> headers) {
        if (url != null && headers != null && !headers.isEmpty()) {
            StreamHeaders.put(Uri.parse(url).getHost(), headers);
        }
    }

    // ------------------------------------------------------------ 자막

    private static final class RawCaption {
        final String code, label, url;
        RawCaption(String code, String label, String url) {
            this.code = code; this.label = label; this.url = url;
        }
    }

    /**
     * 고를 수 있는 자막 목록을 만든다. 기기 언어와 영어로만 한정한다 — 특히
     * automatic_captions 는 유튜브가 원본 자동 자막 하나를 구글 번역으로 100개
     * 넘는 언어에 다 돌려서 그대로 다 들어 있어서, 걸러내지 않으면 "압카즈어",
     * "아파르어" 처럼 쓸 일 없는 항목이 목록 대부분을 채운다.
     *
     * 실제 사람이 올린 자막(subtitles)을 먼저 넣고, 자동 생성 자막
     * (automatic_captions)은 같은 언어의 실제 자막이 없을 때만 "(자동 생성)"
     * 표시를 붙여 넣는다 — 겹쳐서 목록만 늘어지는 것을 막는다.
     *
     * 언어 키는 지역/변형 접미사를 뗀 기본 태그로 묶는다("en-orig", "en-US" 는
     * 전부 "en") — 더빙된 영상은 automatic_captions 에 같은 언어가 "en" 과
     * "en-orig" 처럼 두 번 들어 있어서, 접미사까지 그대로 키로 쓰면 같은 언어가
     * 목록에 두 번 뜬다.
     *
     * 정렬은 기기 언어 → 영어 순.
     */
    private static List<Caption> listCaptions(JsonNode root) {
        final String deviceLang = Locale.getDefault().getLanguage();
        java.util.Set<String> allowed = new java.util.HashSet<>();
        allowed.add(deviceLang);
        allowed.add("en");

        java.util.Set<String> seen = new java.util.HashSet<>();   // 이미 목록에 넣은 기본 언어 태그
        List<RawCaption> raw = new ArrayList<>();
        collectCaptions(root.path("subtitles"), false, raw, seen, allowed);
        collectCaptions(root.path("automatic_captions"), true, raw, seen, allowed);

        Collections.sort(raw, (a, b) -> rank(a.code, deviceLang) - rank(b.code, deviceLang));

        List<Caption> out = new ArrayList<>(raw.size());
        for (RawCaption r : raw) out.add(new Caption(r.code, r.label, r.url));
        return out;
    }

    private static int rank(String code, String deviceLang) {
        String tag = code.split("-")[0].toLowerCase(Locale.US);
        if (tag.equals(deviceLang)) return 0;
        if (tag.equals("en")) return 1;
        return 2;
    }

    /** allowed 가 null 이면 다 받는다(실제 업로드 자막) — 아니면 그 안에 있는 언어만(자동 생성). */
    private static void collectCaptions(JsonNode langs, boolean auto, List<RawCaption> out,
                                         java.util.Set<String> seen, java.util.Set<String> allowed) {
        if (langs == null || !langs.isObject()) return;
        Iterator<String> names = langs.fieldNames();
        while (names.hasNext()) {
            String rawCode = names.next();
            String base = rawCode.split("-")[0].toLowerCase(Locale.US);
            if (allowed != null && !allowed.contains(base)) continue;
            if (!seen.add(base)) continue;   // 같은 언어를 다른 변형으로 이미 넣었다
            String url = vttUrlIn(langs.get(rawCode));
            if (url == null) { seen.remove(base); continue; }
            String label = languageName(base) + (auto ? " (자동 생성)" : "");
            out.add(new RawCaption(base, label, url));
        }
    }

    private static String vttUrlIn(JsonNode formats) {
        if (formats == null || !formats.isArray()) return null;
        for (JsonNode f : formats) {
            if ("vtt".equals(text(f, "ext"))) return text(f, "url");
        }
        return null;
    }

    /**
     * 고른 자막 하나를 내려받아 .srt 파일로 저장한다. 실패하면 null — 호출한 쪽이
     * 계속 재생하되 자막만 없이 진행하면 된다.
     *
     * WebVTT 는 타임스탬프에 "."을 쓰는데 Subtitles.parseSrt() 의 정규식이 ","와
     * "." 을 이미 다 받아들이고, 안 쓰는 줄(WEBVTT 헤더, 큐 식별자, style 블록)은
     * 타임스탬프가 아니라서 자연히 건너뛰므로 형식 변환 없이 그대로 .srt 로
     * 저장해도 파싱된다.
     */
    public static File downloadCaption(Context ctx, Caption c) {
        try {
            String text = httpGet(c.url);
            if (text == null || text.trim().isEmpty()) return null;

            File f = File.createTempFile("yt_sub_", ".srt", ctx.getCacheDir());
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(text);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
