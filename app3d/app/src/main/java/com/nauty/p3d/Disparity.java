package com.nauty.p3d;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import java.util.Arrays;
import java.util.Locale;

/**
 * SBS/TB 프레임의 좌·우 시차를 실제로 재서 수렴을 어디에 둬야 할지 알려준다.
 *
 * 왜 필요한가. 게임에서 뽑은 SBS 는 만들 때의 화면과 그때의 convergence 설정이
 * 그대로 굳어 있다. 그 값은 "소스 픽셀 몇 개" 로 남는데, 같은 그림을 태블릿 눈 상자에
 * 맞춰 늘리거나 줄이면 시차도 같은 비율로 변한다.
 *
 * 실제로 재보면 양이 만만치 않다. HelixMod 스크린샷 36장을 재보니 좌우 절반이
 * <b>통째로</b> 어긋나 있었다 — 1920px 눈 기준으로 20px 부터 224px(눈 폭의 11.7%)까지.
 * 24인치 모니터에서 만든 값이라 그런데, 그대로 태블릿에 올리면 두 눈이 모을 수 있는
 * 한계를 넘어 융합이 안 된다. 정작 장면 자체의 깊이 폭은 28~184px 로 그보다 훨씬 작다.
 *
 * 그래서 두 단계로 잰다.
 *   1) 전체 겹침으로 큰 오프셋을 먼저 잡는다 (d0). 이것이 "이 소스가 통째로 밀린 양".
 *   2) d0 근처에서만 블록 매칭해서 장면의 깊이 폭을 구한다.
 * 한 번에 넓게 블록 매칭을 하면 탐색 범위를 크게 잡아야 하는데, 그러면 가장자리
 * 여백이 늘어 쓸 수 있는 블록이 반으로 줄고 오정합도 늘어난다. 실제로 한 단계로
 * ±8% 만 훑었을 때는 표본 대부분이 탐색 범위 끝에 붙어 값이 전혀 못 미더웠다.
 *
 * 부호: 시차 = (우안 x − 좌안 x).
 */
public final class Disparity {

    private static final String TAG = "P3D";

    /** 비교용 축소 폭 (눈 하나 기준). */
    private static final int W = 480;
    /** 1단계 전역 탐색 범위 (눈 폭 대비). 실측 최대가 11.7% 라 넉넉히 잡는다. */
    private static final float GLOBAL = 0.22f;
    /** 2단계 국소 탐색 범위 (눈 폭 대비). 장면의 깊이 폭만 담으면 된다. */
    private static final float LOCAL = 0.05f;
    /** 블록 한 변 (축소 후 픽셀). */
    private static final int BLOCK = 16;
    /** 이보다 밋밋한 블록은 어디에 맞춰도 비슷해서 값이 못 미덥다. */
    private static final float MIN_TEXTURE = 5f;
    /**
     * 최소 SAD 가 평균 SAD 의 이 비율보다 작아야 "맞은 자리가 뚜렷하다" 고 본다.
     * 반복 무늬(벽돌, 울타리)는 어디에 놓아도 비슷해서 이 문턱에서 걸러진다.
     */
    private static final float CONFIDENCE = 0.80f;

    public static final class Result {
        /** 눈 폭 대비 비율. */
        public final float near, far, median, global;
        public final int samples;

        Result(float near, float far, float median, float global, int samples) {
            this.near = near; this.far = far; this.median = median;
            this.global = global; this.samples = samples;
        }

        /** 화면 눈 폭이 eyeW 픽셀일 때의 값들. */
        public int nearPx(int eyeW)   { return Math.round(near * eyeW); }
        public int farPx(int eyeW)    { return Math.round(far * eyeW); }
        public int medianPx(int eyeW) { return Math.round(median * eyeW); }

        /**
         * 장면의 중심을 화면 평면에 놓는 수렴 보정값 (화면 픽셀).
         *
         * 어느 쪽이 앞인지 몰라도 성립하는 규칙이라 이걸 기본으로 삼는다. 게임
         * 스크린샷에서는 앞/뒤 판정이 실제로 흔들린다 — 3D Vision 픽스가 HUD 를
         * 화면 깊이에 고정해 두는 일이 많은데, 그 HUD 가 화면 아래쪽에 몰려 있어서
         * "아래가 가깝다" 같은 상식적인 단서를 깨뜨린다. 36장 중 31장이 그 단서로는
         * 거꾸로 나왔는데, 그게 좌우가 뒤바뀐 것인지 HUD 탓인지 가릴 수가 없었다.
         *
         * 중심을 화면에 놓으면 깊이 폭의 절반은 앞, 절반은 뒤로 갈려서 어느 해석이든
         * 두 눈이 모으기 편한 범위에 들어온다. 거기서부터는 슬라이더로 취향껏 민다.
         */
        public int centerOnScreen(int eyeW) { return -medianPx(eyeW); }
    }

    private Disparity() {}

    /** 잴 수 없으면 null. 표본이 모자라거나 밋밋한 그림인 경우다. */
    public static Result measure(Bitmap frame, SourceFormat f) {
        if (frame == null || f == null || f == SourceFormat.MONO_2D) return null;

        int fw = frame.getWidth(), fh = frame.getHeight();
        if (fw < 64 || fh < 64) return null;

        // 눈 하나를 잘라내 W 폭 회색조로 줄인다.
        int ew, eh, lx, ly, rx, ry;
        if (f.isSbs()) {
            ew = fw / 2; eh = fh;
            lx = 0;  ly = 0; rx = ew; ry = 0;
        } else {
            ew = fw; eh = fh / 2;
            lx = 0; ly = 0; rx = 0; ry = eh;      // TB 는 위쪽이 좌안
        }
        int h = Math.max(16, Math.round((float) W * eh / ew));

        float[] left  = gray(frame, lx, ly, ew, eh, W, h);
        float[] right = gray(frame, rx, ry, ew, eh, W, h);
        if (left == null || right == null) return null;

        int d0  = globalShift(left, right, W, h);
        int loc = Math.max(4, Math.round(W * LOCAL));

        // 가장자리는 뺀다. 좌우는 탐색이 그림 밖으로 나가고,
        // 위아래 띠는 검은색이라 어디에 맞춰도 SAD 가 0 이라 값이 엉킨다.
        int mx = Math.abs(d0) + loc + 2;
        int my = Math.round(h * 0.10f);

        float[] found = new float[1024];
        int n = 0;

        for (int by = my; by + BLOCK <= h - my && n < found.length; by += BLOCK) {
            for (int bx = mx; bx + BLOCK <= W - mx && n < found.length; bx += BLOCK) {
                if (texture(left, W, bx, by) < MIN_TEXTURE) continue;

                float best = Float.MAX_VALUE, sum = 0;
                int bestD = d0, cnt = 0;
                for (int d = d0 - loc; d <= d0 + loc; d++) {
                    float sad = sad(left, right, W, bx, by, d);
                    if (sad < 0) continue;
                    sum += sad; cnt++;
                    if (sad < best) { best = sad; bestD = d; }
                }
                if (cnt < 3) continue;
                float mean = sum / cnt;
                if (mean <= 0 || best > mean * CONFIDENCE) continue;   // 맞은 자리가 흐릿하다

                found[n++] = (float) bestD / W;
            }
        }

        if (n < 8) {
            Log.i(TAG, "시차 측정: 쓸만한 블록이 " + n + "개뿐이라 보류 (전역 d0=" + d0 + ")");
            return null;
        }

        float[] d = Arrays.copyOf(found, n);
        Arrays.sort(d);
        Result r = new Result(pct(d, 0.10f), pct(d, 0.90f), pct(d, 0.50f),
                (float) d0 / W, n);
        Log.i(TAG, String.format(Locale.US,
                "시차 측정: 표본 %d개  전역 %.4f  근 %.4f  중앙 %.4f  원 %.4f (눈 폭 대비)",
                n, r.global, r.near, r.median, r.far));
        return r;
    }

    /**
     * 1단계. 겹치는 영역 전체를 견줘서 두 절반이 통째로 얼마나 어긋났는지 잡는다.
     * 위아래 1/8 씩은 뺀다 — 레터박스 띠와 자막·HUD 가 몰리는 자리다.
     */
    private static int globalShift(float[] a, float[] b, int w, int h) {
        int maxD = Math.round(w * GLOBAL);
        float best = Float.MAX_VALUE;
        int bestD = 0;
        for (int d = -maxD; d <= maxD; d++) {
            int m = Math.abs(d) + 2;
            if (w - 2 * m < 16) continue;
            float s = 0;
            int c = 0;
            for (int j = h / 8; j < h * 7 / 8; j++) {
                int r = j * w;
                for (int i = m; i < w - m; i++) {
                    s += Math.abs(a[r + i] - b[r + i + d]);
                    c++;
                }
            }
            if (c == 0) continue;
            s /= c;
            if (s < best) { best = s; bestD = d; }
        }
        return bestD;
    }

    private static float pct(float[] sorted, float p) {
        int i = Math.round((sorted.length - 1) * p);
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** 좌안 블록을 우안의 d 만큼 옆자리와 비교. 범위를 벗어나면 -1. */
    private static float sad(float[] a, float[] b, int w, int bx, int by, int d) {
        int sx = bx + d;
        if (sx < 0 || sx + BLOCK > w) return -1;
        float s = 0;
        for (int j = 0; j < BLOCK; j++) {
            int r = (by + j) * w;
            for (int i = 0; i < BLOCK; i++) {
                s += Math.abs(a[r + bx + i] - b[r + sx + i]);
            }
        }
        return s / (BLOCK * BLOCK);
    }

    private static float texture(float[] a, int w, int bx, int by) {
        float m = 0;
        for (int j = 0; j < BLOCK; j++)
            for (int i = 0; i < BLOCK; i++) m += a[(by + j) * w + bx + i];
        m /= BLOCK * BLOCK;
        float s = 0;
        for (int j = 0; j < BLOCK; j++)
            for (int i = 0; i < BLOCK; i++) {
                float v = a[(by + j) * w + bx + i] - m;
                s += v * v;
            }
        return (float) Math.sqrt(s / (BLOCK * BLOCK));
    }

    /**
     * 비트맵의 (x0,y0,w,h) 영역을 outW x outH 회색조로 줄인다.
     *
     * getPixel 을 25만 번 부르는 대신 잘라내기와 축소를 한 번에 시킨다.
     * 5120x1440 짜리 사진에서 눈에 띄게 차이가 난다.
     */
    private static float[] gray(Bitmap bmp, int x0, int y0, int w, int h,
                                int outW, int outH) {
        Bitmap small = null;
        try {
            Matrix m = new Matrix();
            m.setScale((float) outW / w, (float) outH / h);
            small = Bitmap.createBitmap(bmp, x0, y0, w, h, m, true);
            int sw = small.getWidth(), sh = small.getHeight();
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);

            float[] out = new float[outW * outH];
            for (int j = 0; j < outH; j++) {
                int sy = Math.min(sh - 1, j * sh / outH);
                for (int i = 0; i < outW; i++) {
                    int p = px[sy * sw + Math.min(sw - 1, i * sw / outW)];
                    out[j * outW + i] = 0.299f * ((p >> 16) & 255)
                                      + 0.587f * ((p >> 8) & 255)
                                      + 0.114f * (p & 255);
                }
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "시차 측정용 축소 실패", t);
            return null;
        } finally {
            if (small != null && small != bmp) small.recycle();
        }
    }
}
