package com.nauty.p3d.gl;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.FloatBuffer;

/**
 * 자막 비트맵을 알파 블렌딩으로 그린다.
 *
 * 자막은 화면 위에 2D 로 얹으면 안 된다 — 렌티큘러 패널은 픽셀 열마다 좌/우 눈으로 갈리므로
 * 2D 오버레이는 눈마다 다른 조각을 보게 되어 겹쳐 보인다. 좌/우 뷰에 각각 그려서
 * 인터레이스를 함께 태워야 선명하고, 좌우 시프트로 깊이도 줄 수 있다.
 */
public class SubtitleRenderer {

    private static final String VS =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position = aPosition; vTex = aTexCoord; }\n";

    private static final String FS =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final float[] POS = {-1f, -1f, 0f,  1f, -1f, 0f,  -1f, 1f, 0f,  1f, 1f, 0f};
    // 비트맵은 위가 0 이므로 v 를 뒤집어 준다.
    private static final float[] UV  = { 0f, 1f,       1f, 1f,        0f, 0f,      1f, 0f};

    private final int program, aPosition, aTexCoord, sTex;
    private final FloatBuffer posBuf, uvBuf;

    private int texId = 0;
    private int texW = 0, texH = 0;

    public SubtitleRenderer() {
        program   = GlUtil.program(VS, FS);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        sTex      = GLES20.glGetUniformLocation(program, "sTex");
        posBuf = GlUtil.floats(POS);
        uvBuf  = GlUtil.floats(UV);
    }

    /** GL 스레드에서 호출. 비트맵을 텍스처로 올린다. null 이면 자막을 지운다. */
    public void upload(Bitmap bmp) {
        if (bmp == null) {
            clear();
            return;
        }
        if (texId == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        texW = bmp.getWidth();
        texH = bmp.getHeight();
    }

    public void clear() {
        if (texId != 0) {
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            texId = 0;
        }
        texW = texH = 0;
    }

    public boolean hasSubtitle() { return texId != 0 && texW > 0 && texH > 0; }
    public int width()  { return texW; }
    public int height() { return texH; }

    /** 현재 뷰포트를 가득 채워 그린다. 배치는 호출부가 glViewport 로 정한다. */
    public void draw() {
        if (!hasSubtitle()) return;

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(sTex, 0);

        posBuf.position(0);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, posBuf);
        GLES20.glEnableVertexAttribArray(aPosition);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, uvBuf);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glDisable(GLES20.GL_BLEND);
    }
}
