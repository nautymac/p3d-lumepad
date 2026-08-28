package com.nauty.p3d.subtitle;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT / SMI(SAMI) 자막 파서.
 *
 * 한글 자막은 UTF-8 과 EUC-KR(CP949) 이 섞여 돌아다니고, 특히 SMI 는 대부분 EUC-KR 이다.
 * BOM → UTF-8 유효성 검사 → EUC-KR 순으로 판별한다.
 */
public final class Subtitles {

    private static final String TAG = "P3D";

    private Subtitles() {}

    public static class Cue {
        public final long startMs, endMs;
        public final String text;
        Cue(long s, long e, String t) { startMs = s; endMs = e; text = t; }
    }

    /** 시간으로 자막을 찾는 트랙. 순차 재생에 맞춰 직전 인덱스부터 훑는다. */
    public static class Track {
        private final List<Cue> cues;
        private int cursor = 0;
        public final String name;

        Track(List<Cue> cues, String name) { this.cues = cues; this.name = name; }

        public int size() { return cues.size(); }

        /** 해당 시각에 표시할 자막. 없으면 null. */
        public String textAt(long ms) {
            if (cues.isEmpty()) return null;

            if (cursor >= cues.size() || cues.get(cursor).startMs > ms) cursor = 0;
            for (int i = cursor; i < cues.size(); i++) {
                Cue c = cues.get(i);
                if (c.endMs < ms) { cursor = i + 1; continue; }
                if (c.startMs <= ms) { cursor = i; return c.text; }
                return null;      // 아직 시작 전
            }
            return null;
        }
    }

    // ------------------------------------------------------------ 로드

    public static Track load(File f) {
        try {
            byte[] data = readAll(new FileInputStream(f));
            String text = decode(data);
            String lower = f.getName().toLowerCase(Locale.US);
            List<Cue> cues = lower.endsWith(".smi") || lower.endsWith(".sami")
                    ? parseSmi(text) : parseSrt(text);
            Collections.sort(cues, new Comparator<Cue>() {
                @Override public int compare(Cue a, Cue b) {
                    return Long.compare(a.startMs, b.startMs);
                }
            });
            Log.i(TAG, "자막 로드: " + f.getName() + " (" + cues.size() + "개)");
            return new Track(cues, f.getName());
        } catch (Exception e) {
            Log.e(TAG, "자막 로드 실패: " + f, e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** BOM → UTF-8 유효성 → EUC-KR 순으로 디코딩한다. */
    static String decode(byte[] d) throws Exception {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            return new String(d, 3, d.length - 3, "UTF-8");
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            return new String(d, 2, d.length - 2, "UTF-16LE");
        }
        if (isValidUtf8(d)) return new String(d, "UTF-8");
        try {
            return new String(d, "EUC-KR");
        } catch (Exception e) {
            return new String(d, "ISO-8859-1");
        }
    }

    static boolean isValidUtf8(byte[] d) {
        int i = 0;
        while (i < d.length) {
            int b = d[i] & 0xFF;
            int need;
            if (b <= 0x7F)                      { i++; continue; }
            else if ((b & 0xE0) == 0xC0)        need = 1;
            else if ((b & 0xF0) == 0xE0)        need = 2;
            else if ((b & 0xF8) == 0xF0)        need = 3;
            else return false;

            if (i + need >= d.length) return false;
            for (int k = 1; k <= need; k++) {
                if ((d[i + k] & 0xC0) != 0x80) return false;
            }
            i += need + 1;
        }
        return true;
    }

    // ------------------------------------------------------------ SRT

    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    static List<Cue> parseSrt(String text) {
        List<Cue> out = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        int i = 0;
        while (i < lines.length) {
            Matcher m = SRT_TIME.matcher(lines[i]);
            if (!m.find()) { i++; continue; }

            long start = ms(m.group(1), m.group(2), m.group(3), m.group(4));
            long end   = ms(m.group(5), m.group(6), m.group(7), m.group(8));

            StringBuilder sb = new StringBuilder();
            i++;
            while (i < lines.length && lines[i].trim().length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i].trim());
                i++;
            }
            String body = stripTags(sb.toString()).trim();
            if (body.length() > 0) out.add(new Cue(start, end, body));
        }
        return out;
    }

    private static long ms(String h, String m, String s, String frac) {
        while (frac.length() < 3) frac = frac + "0";
        return Long.parseLong(h) * 3600000L
             + Long.parseLong(m) * 60000L
             + Long.parseLong(s) * 1000L
             + Long.parseLong(frac.substring(0, 3));
    }

    // ------------------------------------------------------------ SMI

    private static final Pattern SMI_SYNC =
            Pattern.compile("<SYNC\\s+START\\s*=\\s*\"?(-?\\d+)\"?[^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * SAMI 는 종료 시각이 없다. 다음 SYNC 가 끝이고, 본문이 비면(&nbsp; 등) 자막을 지우라는 뜻이다.
     */
    static List<Cue> parseSmi(String text) {
        List<Cue> out = new ArrayList<>();
        Matcher m = SMI_SYNC.matcher(text);

        List<long[]> starts = new ArrayList<>();   // {startMs, bodyStartIndex}
        while (m.find()) {
            starts.add(new long[]{ Long.parseLong(m.group(1)), m.end() });
        }

        for (int i = 0; i < starts.size(); i++) {
            long start = starts.get(i)[0];
            int  from  = (int) starts.get(i)[1];
            int  to    = (i + 1 < starts.size()) ? (int) starts.get(i + 1)[1] : text.length();
            // 다음 SYNC 태그 자체는 제외
            if (i + 1 < starts.size()) {
                int tagStart = text.lastIndexOf('<', to - 1);
                if (tagStart > from) to = tagStart;
            }
            if (from >= to || start < 0) continue;

            String body = stripTags(text.substring(from, to)).trim();
            if (body.isEmpty()) continue;                       // 지우기 큐

            long end = (i + 1 < starts.size()) ? starts.get(i + 1)[0] : start + 5000;
            if (end <= start) end = start + 1000;
            out.add(new Cue(start, end, body));
        }
        return out;
    }

    // ------------------------------------------------------------ 공통

    static String stripTags(String s) {
        String r = s.replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n");
        r = r.replaceAll("<[^>]*>", "");
        r = r.replace("&nbsp;", " ")
             .replace("&NBSP;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&apos;", "'");
        // 줄 단위 정리
        String[] lines = r.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            String t = ln.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    /** 영상 파일 옆에서 같은 이름의 자막을 찾는다. */
    public static File findSibling(File video) {
        if (video == null) return null;
        File dir = video.getParentFile();
        if (dir == null || !dir.isDirectory()) return null;

        String base = video.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final String stem = base.toLowerCase(Locale.US);

        File[] files = dir.listFiles();
        if (files == null) return null;

        File best = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (!isSubtitle(n)) continue;
            if (!n.startsWith(stem)) continue;
            // 한국어 표시가 있으면 우선
            if (n.contains(".ko") || n.contains(".kor") || n.contains("korean")) return f;
            if (best == null) best = f;
        }
        return best;
    }

    public static boolean isSubtitle(String lowerName) {
        return lowerName.endsWith(".srt") || lowerName.endsWith(".smi")
                || lowerName.endsWith(".sami");
    }
}
