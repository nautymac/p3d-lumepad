package com.nauty.p3d.subtitle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** 자막 문자열을 GL 텍스처로 올릴 비트맵으로 그린다. 흰 글자 + 검은 외곽선. */
public final class SubtitleBitmap {

    private SubtitleBitmap() {}

    public static Bitmap render(String text, int screenW, int screenH, float scale) {
        if (text == null || text.trim().isEmpty() || screenW <= 0 || screenH <= 0) return null;

        float textSize = Math.max(18f, screenH * 0.042f * scale);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        int maxW = Math.max(64, (int) (screenW * 0.9f));

        // 레이아웃은 반드시 하나만 만들어 두 번 그린다.
        // 외곽선용으로 STROKE 페인트에 별도 StaticLayout 을 만들면, 선 두께가 글자 폭 측정에
        // 반영돼 줄바꿈 위치와 줄 높이가 달라진다. 그러면 둘째 줄부터 외곽선과 채움이 어긋나
        // 글자를 가로지르는 선처럼 보인다.
        StaticLayout layout = layout(text, paint, maxW);

        int pad = (int) (textSize * 0.28f);
        int h = layout.getHeight() + pad * 2;
        if (h <= 0) return null;

        Bitmap bmp = Bitmap.createBitmap(maxW, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.translate(0, pad);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, textSize * 0.13f));
        paint.setColor(Color.BLACK);
        layout.draw(c);                      // 외곽선

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        layout.draw(c);                      // 채움 — 같은 레이아웃이라 정확히 겹친다

        return bmp;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(String text, TextPaint p, int width) {
        return new StaticLayout(text, p, width, Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false);
    }
}
