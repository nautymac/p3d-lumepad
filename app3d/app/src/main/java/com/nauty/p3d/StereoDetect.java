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
    /**
     * 닮은 쪽이 반대쪽보다 이 배수만큼은 더 닮아야 그 배치로 인정한다.
     *
     * 절대 문턱만으로는 갈리지 않기 때문이다. 실측하면 진짜 2D 클립이
     * 좌우차/대비 0.221 인데 진짜 half-SBS 인 레고 배트맨은 0.292 로 오히려 더 컸다.
     * 어디에 선을 그어도 둘 중 하나는 반드시 틀린다.
     *
     * 갈라주는 것은 좌우와 상하의 비다. SBS 프레임은 좌우 절반이 시차만큼만 다른데
     * 상하 절반은 서로 다른 장면이라 차이가 몇 배로 벌어진다.
     *     블레이드 러너  좌우 0.07~0.18   상하 0.83~1.34    4~13배
     *     레고 배트맨    좌우 0.21~0.29   상하 1.09~1.33    4~6배
     *     2D 클립        좌우 0.221      상하 0.276        1.25배
     */
    private static final float SAME_MARGIN = 2.0f;

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

            // half / full 판정은 반드시 컨테이너가 적어둔 해상도로 해야 한다.
            // getFrameAtTime 이 돌려주는 비트맵은 축소돼 올 수 있다. 실제로
            // "Coraline ... 3840X1080 10BIT HEVC" 는 비트맵이 1920x1080 으로 와서
            // 종횡비가 1.78 이 되고, 그러면 full-SBS 가 half-SBS 로 잘못 잡혀
            // 좌우 눈 그림이 가로로 눌린 채 재생된다.
            int metaW = intMeta(r, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int metaH = intMeta(r, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

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

            // 비율 판정에 쓸 크기: 컨테이너 값이 있으면 그것, 없으면 비트맵 크기.
            int aspW = metaW > 0 ? metaW : frameW;
            int aspH = metaH > 0 ? metaH : frameH;

            Log.i(TAG, "스테레오 판별 표본: SBS=" + sbs + " TB=" + tb + " 2D=" + mono
                    + " (표본 " + frameW + "x" + frameH
                    + ", 원본 " + metaW + "x" + metaH + ")");

            // 해상도가 결정적이면 표본 투표를 볼 필요가 없다.
            // 3.56:1 짜리 영상은 2D 일 수 없다. 반대로 픽셀 판별은 시차가 큰 소스에서
            // 2D 라고 답하는 일이 있다 (detectImage 주석의 실측 참고).
            SourceFormat byAspect = SourceFormat.fromAspect(aspW, aspH);
            if (byAspect != null) {
                Log.i(TAG, "  해상도가 결정적이라 " + byAspect.tag);
                return byAspect;
            }

            if (sbs == 0 && tb == 0 && mono == 0) return null;   // 쓸만한 표본이 없었다

            // 과반이 아니면 바꾸지 않는다.
            //
            // 판별은 재생이 시작된 뒤에 끝나서, 결과가 나오면 배치를 갈아끼운다.
            // 그 교체가 화면에 그대로 보이기 때문에 어중간한 확신으로 바꾸면
            // 재생 2~3초쯤에 화면이 한 번 튀는 것으로 나타난다.
            //
            // 실제로 그런 파일이 있다. IMG_1609(full-SBS 홈비디오)는 표본을 다시
            // 뽑을 때마다 좌우차/대비가 0.10 ~ 0.61 사이로 널뛰어 SBS=2 2D=2 로
            // 갈리기도 하고 SBS=4 가 나오기도 한다. 이런 파일은 임시값(파일명)을
            // 그대로 두는 편이 낫다 — 틀릴 확률은 반반인데 튐은 확실히 보인다.
            int used = sbs + tb + mono;
            if (sbs * 2 <= used && tb * 2 <= used && mono * 2 <= used) {
                Log.i(TAG, "  과반이 아니라 판별을 보류한다");
                return null;
            }

            if (sbs > tb && sbs >= mono) return sbsVariant(aspW, aspH);
            if (tb > sbs && tb >= mono)  return tbVariant(aspW, aspH);
            return SourceFormat.MONO_2D;

        } catch (Throwable t) {
            Log.w(TAG, "스테레오 판별 실패: " + uri, t);
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static int intMeta(MediaMetadataRetriever r, int key) {
        try {
            String v = r.extractMetadata(key);
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
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

        Log.i(TAG, String.format(java.util.Locale.US,
                "  표본 대비 %.1f  좌우차/대비 %.3f  상하차/대비 %.3f",
                contrast, dSbs / contrast, dTb / contrast));

        boolean isSbs = dSbs < contrast * SAME_RATIO && dSbs * SAME_MARGIN < dTb;
        boolean isTb  = dTb  < contrast * SAME_RATIO && dTb  * SAME_MARGIN < dSbs;

        if (isSbs) return 1;
        if (isTb)  return 2;
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

    /**
     * 사진 한 장의 스테레오 배치를 판별한다.
     *
     * 영상보다 훨씬 확실하다. 영상은 getFrameAtTime 이 축소된 비트맵을 돌려주는
     * 바람에 full-SBS 를 half 로 잘못 잡는 일이 있어서 컨테이너 해상도를 따로
     * 봐야 했는데, 사진은 inJustDecodeBounds 로 원본 해상도를 정확히 읽을 수 있다.
     * half/full 은 그 값으로 가르고, 배치(SBS/TB/2D)만 픽셀로 판별한다.
     *
     * 판별 실패 시 null.
     */
    public static SourceFormat detectImage(Context ctx, Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            in.close();

            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;

            // 해상도가 결정적이면 픽셀은 볼 필요가 없다.
            //
            // 3.56:1 짜리 사진은 2D 일 수 없다. 그런데 픽셀 판별은 게임 스크린샷에서
            // 자주 2D 라고 답한다 — 3D Vision 계열은 영화보다 시차를 훨씬 크게 주기
            // 때문이다. 실측: Aragami 스크린샷(3344x940, 진짜 full-SBS)이 좌우차/대비
            // 0.648, 상하차/대비 1.131 로 나와 문턱(0.38)과 배수(2.0) 양쪽 다 못 넘겼다.
            // 이런 프레임에서는 해상도가 훨씬 강한 증거다.
            SourceFormat byAspect = SourceFormat.fromAspect(w, h);
            if (byAspect != null) {
                Log.i(TAG, "사진 스테레오 판별: " + w + "x" + h
                        + " — 해상도가 결정적이라 " + byAspect.tag);
                return byAspect;
            }

            // 판별에는 축소본이면 충분하다 (32x32 격자로 비교한다).
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 1;
            while (w / o.inSampleSize > 640) o.inSampleSize *= 2;
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (bmp == null) return null;

            int vote = classify(bmp);
            bmp.recycle();

            Log.i(TAG, "사진 스테레오 판별: " + w + "x" + h + "  판정 "
                    + (vote == 1 ? "SBS" : vote == 2 ? "TB" : vote == 0 ? "2D" : "보류"));

            if (vote == 1) return sbsVariant(w, h);
            if (vote == 2) return tbVariant(w, h);
            if (vote == 0) return SourceFormat.MONO_2D;

            // 대비가 모자라 픽셀로는 못 갈랐다. 해상도만으로 되는 만큼은 살린다.
            return SourceFormat.fromAspect(w, h);

        } catch (Throwable t) {
            Log.w(TAG, "사진 스테레오 판별 실패: " + uri, t);
            return null;
        }
    }
}
