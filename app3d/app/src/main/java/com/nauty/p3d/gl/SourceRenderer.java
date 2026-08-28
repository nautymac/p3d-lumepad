package com.nauty.p3d.gl;

import android.content.Context;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

/**
 * OES 영상 텍스처를 FBO 의 한쪽 절반에 그린다.
 * 크롭(SBS 좌/우, TB 상/하, 전체)은 텍스처 좌표로 지정하고,
 * 2D->3D 시차는 프래그먼트 셰이더의 시어로 만든다 (원본 frag2dto3d.sh 수식 재현).
 */
public class SourceRenderer {

    // 화면 전체를 덮는 사각형 (NDC). 뷰포트가 그릴 영역을 한정한다.
    private static final float[] POS = {
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f,
    };

    private final int program;
    private final int aPosition, aTexCoord, uSTMatrix, uShearTop, uShearSlope, uBottomCut;
    private final FloatBuffer posBuf;
    private final float[] uv = new float[8];
    private final FloatBuffer uvBuf;

    public SourceRenderer(Context ctx) {
        program = GlUtil.program(
                GlUtil.readAsset(ctx, "p3d_src.vert"),
                GlUtil.readAsset(ctx, "p3d_src.frag"));
        aPosition   = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord   = GLES20.glGetAttribLocation(program, "aTexCoord");
        uSTMatrix   = GLES20.glGetUniformLocation(program, "uSTMatrix");
        uShearTop   = GLES20.glGetUniformLocation(program, "uShearTop");
        uShearSlope = GLES20.glGetUniformLocation(program, "uShearSlope");
        uBottomCut  = GLES20.glGetUniformLocation(program, "uBottomCut");
        posBuf = GlUtil.floats(POS);
        uvBuf  = GlUtil.floats(new float[8]);
    }

    /**
     * @param oesTex    SurfaceTexture 가 물린 OES 텍스처
     * @param stMatrix  SurfaceTexture.getTransformMatrix 결과
     * @param u0,v0,u1,v1 잘라낼 영역 (0..1). v 는 아래가 0.
     * @param shearTop   상단 시프트량. 0 이면 시어 없음 (원본 기본값 0.004)
     * @param shearSlope 아래로 갈수록 감소하는 기울기 (원본: 0.0000122 * screenHeight)
     * @param bottomCut  하단 블랙 처리 경계. 1.0 이면 비활성 (원본 0.990740741)
     */
    public void draw(int oesTex, float[] stMatrix,
                     float u0, float v0, float u1, float v1,
                     float shearTop, float shearSlope, float bottomCut) {

        uv[0] = u0; uv[1] = v0;   // 좌하
        uv[2] = u1; uv[3] = v0;   // 우하
        uv[4] = u0; uv[5] = v1;   // 좌상
        uv[6] = u1; uv[7] = v1;   // 우상
        uvBuf.put(uv).position(0);

        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GlUtil.GL_TEXTURE_EXTERNAL_OES, oesTex);

        posBuf.position(0);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, posBuf);
        GLES20.glEnableVertexAttribArray(aPosition);

        uvBuf.position(0);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, uvBuf);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0);
        GLES20.glUniform1f(uShearTop, shearTop);
        GLES20.glUniform1f(uShearSlope, shearSlope);
        GLES20.glUniform1f(uBottomCut, bottomCut);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }
}
