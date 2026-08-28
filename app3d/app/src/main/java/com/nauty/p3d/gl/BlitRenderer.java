package com.nauty.p3d.gl;

import android.content.Context;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

/** 2D 출력 모드용: 일반 2D 텍스처를 그대로 화면에 그린다. */
public class BlitRenderer {
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

    private static final float[] POS = {-1f,-1f,0f,  1f,-1f,0f,  -1f,1f,0f,  1f,1f,0f};

    private final int program, aPosition, aTexCoord, sTex;
    private final FloatBuffer posBuf, uvBuf;
    private final float[] uv = new float[8];

    public BlitRenderer(Context ctx) {
        program   = GlUtil.program(VS, FS);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        sTex      = GLES20.glGetUniformLocation(program, "sTex");
        posBuf = GlUtil.floats(POS);
        uvBuf  = GlUtil.floats(new float[8]);
    }

    public void draw(int tex, float u0, float v0, float u1, float v1) {
        uv[0]=u0; uv[1]=v0;  uv[2]=u1; uv[3]=v0;
        uv[4]=u0; uv[5]=v1;  uv[6]=u1; uv[7]=v1;
        uvBuf.put(uv).position(0);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
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
    }
}
