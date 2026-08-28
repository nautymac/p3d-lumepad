package com.nauty.p3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

/**
 * 실제 프레임을 뜯어 스테레오 배치를 판별한다.
 *
 * 파일명 휴리스틱은 한계가 뚜렷하다. 예를 들어
 * "spider.man.into.the.spider.verse.2018.3d.1080p.bluray.x264-veto.mkv" 는
 * 이름에 3d 만 있고 sbs/tab 이 없어서 2D 로 잡히고, 그러면 SBS 프레임을 통째로
 * 좌우에 복제해서 화면에 같은 그림이 두 개 나온다.
 *
 * 원리: SBS 로 인코딩된 프레임은 좌/우 절반이 시차만큼만 다르고 거의 같다.
 * 그래서 절반끼리의 차이를 프레임 자체의 대비와 비교하면 판별된다.
 * 2D 프레임은 좌/우 절반이 서로 다른 장면이라 차이가 대비와 비슷한 수준으로 크다.
 */
public final class StereoDetect {

    private static final String TAG = "P3D";

    /** 절반끼리의 차이가 대비의 이 비율보다 작으면 같은 그림으로 본다. */
    private static final float SAME_RATIO = 0.38f;
    /** 이보다 대비가 낮은 프레임(검은 화면, 로고 페이드 등)은 표본에서 제외한다. */
    private static final float MIN_CONTRAST = 8f;

    private static final int N = 32;   // 비교용 축소 해상도

    private StereoDetect() {}

    /** 판별 실패 시 null. 호출부는 그때 파일명 휴리스틱이나 2D 로 넘어가면 된다. */
    public static SourceFormat detect(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);

            long durMs = 0;
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Long.parseLong(d);
            } catch (Exception ignored) { }
            if (durMs <= 0) durMs = 60000;

            // 로고/암전 구간을 피해 본편에서 표본을 뽑는다.
            long[] at = {
                    durMs * 25 / 100, durMs * 45 / 100,
                    durMs * 62 / 100, durMs * 80 / 100
            };

            int sbs = 0, tb = 0, mono = 0;
            int frameW = 0, frameH = 0;

            for (long ms : at) {
                Bitmap bmp = r.getFrameAtTime(ms * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) continue;
                frameW = bmp.getWidth();
                frameH = bmp.getHeight();

                int vote = classify(bmp);
                bmp.recycle();
                if (vote == 1) sbs++;
                else if (vote == 2) tb++;
                else if (vote == 0) mono++;
                // vote == -1 : 대비 부족, 표본 제외
            }

            Log.i(TAG, "스테레오 판별 표본: SBS=" + sbs + " TB=" + tb + " 2D=" + mono
                    + " (" + frameW + "x" + frameH + ")");

            if (sbs == 0 && tb == 0 && mono == 0) return null;   // 쓸만한 표본이 없었다

            if (sbs > tb && sbs >= mono) return sbsVariant(frameW, frameH);
            if (tb > sbs && tb >= mono)  return tbVariant(frameW, frameH);
            return SourceFormat.MONO_2D;

        } catch (Throwable t) {
            Log.w(TAG, "스테레오 판별 실패: " + uri, t);
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** 1=SBS, 2=TB, 0=2D, -1=판단보류(대비 부족) */
    private static int classify(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 16 || h < 16) return -1;

        float[] left  = gray(bmp, 0,     0,     w / 2, h);
        float[] right = gray(bmp, w / 2, 0,     w / 2, h);
        float[] top   = gray(bmp, 0,     0,     w,     h / 2);
        float[] bot   = gray(bmp, 0,     h / 2, w,     h / 2);

        float contrast = Math.max(stddev(left), stddev(right));
        if (contrast < MIN_CONTRAST) return -1;

        float dSbs = meanAbsDiff(left, right);
        float dTb  = meanAbsDiff(top, bot);

        boolean isSbs = dSbs < contrast * SAME_RATIO;
        boolean isTb  = dTb  < contrast * SAME_RATIO;

        if (isSbs && !isTb) return 1;
        if (isTb && !isSbs) return 2;
        if (isSbs)          return dSbs <= dTb ? 1 : 2;   // 둘 다면 더 닮은 쪽
        return 0;
    }

    /** 지정 영역을 NxN 회색조로 축소. */
    private static float[] gray(Bitmap bmp, int x0, int y0, int w, int h) {
        float[] out = new float[N * N];
        for (int j = 0; j < N; j++) {
            int sy = y0 + (int) ((j + 0.5f) * h / N);
            for (int i = 0; i < N; i++) {
                int sx = x0 + (int) ((i + 0.5f) * w / N);
                int p = bmp.getPixel(
                        Math.min(sx, bmp.getWidth() - 1),
                        Math.min(sy, bmp.getHeight() - 1));
                out[j * N + i] = 0.299f * ((p >> 16) & 255)
                               + 0.587f * ((p >> 8) & 255)
                               + 0.114f * (p & 255);
            }
        }
        return out;
    }

    private static float meanAbsDiff(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    private static float stddev(float[] a) {
        float m = 0;
        for (float v : a) m += v;
        m /= a.length;
        float s = 0;
        for (float v : a) s += (v - m) * (v - m);
        return (float) Math.sqrt(s / a.length);
    }

    /** half / full 은 프레임 종횡비로 가른다. */
    private static SourceFormat sbsVariant(int w, int h) {
        if (h <= 0) return SourceFormat.SBS_HALF;
        return ((float) w / h) >= 2.6f ? SourceFormat.SBS_FULL : SourceFormat.SBS_HALF;
    }

    private static SourceFormat tbVariant(int w, int h) {
        if (h <= 0) return SourceFormat.TB_HALF;
        return ((float) w / h) <= 1.05f ? SourceFormat.TB_FULL : SourceFormat.TB_HALF;
    }
}
