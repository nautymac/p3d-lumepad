package com.nauty.p3d;

import java.util.Locale;

/**
 * 입력 영상의 스테레오 배치.
 *
 * half / full 구분은 크롭이 아니라 "표시 종횡비"의 차이다.
 *   half-SBS 1920x1080 : 한쪽 눈 960x1080 이 16:9 를 가로로 압축해 담고 있음  -> 표시비 = vw/vh
 *   full-SBS 3840x1080 : 한쪽 눈 1920x1080 이 그대로 16:9                    -> 표시비 = (vw/2)/vh
 * TB 도 같은 방식으로 세로에 대해 성립한다.
 *
 */
public enum SourceFormat {
    MONO_2D (R.string.fmt_mono,     "2D"),
    SBS_HALF(R.string.fmt_sbs_half, "SBS half"),
    SBS_FULL(R.string.fmt_sbs_full, "SBS full"),
    TB_HALF (R.string.fmt_tb_half,  "TB half"),
    TB_FULL (R.string.fmt_tb_full,  "TB full");

    private final int labelRes;
    /**
     * 로그용 이름. 화면용과 나눠 둔다 — 로그는 어느 나라 말로 돌든 같아야
     * 남이 보낸 로그를 읽을 수 있고, Context 없이도 찍을 수 있어야 한다.
     */
    public final String tag;

    SourceFormat(int labelRes, String tag) { this.labelRes = labelRes; this.tag = tag; }

    /** 화면에 보일 이름. 기기 언어를 따른다. */
    public String label(android.content.Context ctx) { return ctx.getString(labelRes); }

    public boolean isSbs() { return this == SBS_HALF || this == SBS_FULL; }
    public boolean isTb()  { return this == TB_HALF  || this == TB_FULL; }

    /** 화면에 표시해야 할 한쪽 눈의 종횡비. */
    public float displayAspect(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return 16f / 9f;
        switch (this) {
            case SBS_FULL: return ((float) vw / 2f) / (float) vh;
            case TB_FULL:  return (float) vw / ((float) vh / 2f);
            default:       return (float) vw / (float) vh;   // MONO_2D, SBS_HALF, TB_HALF
        }
    }

    /** 버튼으로 순환할 순서. */
    public static final SourceFormat[] CYCLE = {
            MONO_2D, SBS_HALF, SBS_FULL, TB_HALF, TB_FULL
    };

    public SourceFormat next() {
        for (int i = 0; i < CYCLE.length; i++) {
            if (CYCLE[i] == this) return CYCLE[(i + 1) % CYCLE.length];
        }
        return MONO_2D;
    }

    /** 파일명 휴리스틱. 확정 못 하면 null. */
    public static SourceFormat fromName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.US);

        if (n.contains("fsbs") || n.contains("full-sbs") || n.contains("full_sbs")) return SBS_FULL;
        if (n.contains("ftab") || n.contains("full-ou")  || n.contains("full_ou"))  return TB_FULL;

        if (n.contains("sbs") || n.contains("side-by-side")
                || n.contains("2v3d")          // 이 기기 기본 샘플 영상 명명 규칙
                || n.contains("half3d")) {
            return SBS_HALF;
        }
        if (n.contains("tab") || n.contains("top-bottom") || n.contains("topbottom")
                || n.contains("over-under") || n.contains("_ou") || n.contains("-ou")
                || n.contains("_tb") || n.contains("-tb")) {
            return TB_HALF;
        }
        return null;
    }

    /**
     * 해상도 종횡비로 full 계열을 판별한다.
     * half 계열은 프레임 비율이 2D 와 같아서 이 방법으로는 구분할 수 없다 (파일명에 의존).
     */
    public static SourceFormat fromAspect(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return null;
        float ar = (float) vw / (float) vh;
        if (ar >= 2.6f) return SBS_FULL;   // 3840x1080 = 3.56, 32:9 등
        if (ar <= 1.05f) return TB_FULL;   // 1920x2160 = 0.89
        return null;
    }
}
