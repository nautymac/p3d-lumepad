package com.nauty.p3d.gl;

import android.content.Context;
import android.opengl.GLES20;

import com.future.Holography.Holography;

import java.nio.FloatBuffer;

/**
 * SBS 이미지(FBO) + libholography 마스크 -> 렌티큘러 인터레이스 출력.
 * 원본 assets/vertex3D.sh + frag3D.sh 를 그대로 사용한다.
 * 마스크 생성 수식이 패널의 슬랜티드 렌티큘러 피치에 맞춰져 있어서,
 * 이 두 셰이더만은 직접 다시 쓰지 않고 원본을 재사용하는 편이 안전하다.
 */
public class InterlaceRenderer {

    private static final float[] POS = {
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f,
    };
    private static final float[] UV = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    private final int program;
    private final int aPosition, aTextureCoord, sampler0, sampler1, uPerOffset;
    private final FloatBuffer posBuf, uvBuf;
    private final int maskTex;

    public InterlaceRenderer(Context ctx) {
        program = GlUtil.program(
                GlUtil.readAsset(ctx, "vertex3D.sh"),
                GlUtil.readAsset(ctx, "frag3D.sh"));
        aPosition     = GLES20.glGetAttribLocation(program, "aPosition");
        aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
        sampler0      = GLES20.glGetUniformLocation(program, "Sampler0");
        sampler1      = GLES20.glGetUniformLocation(program, "Sampler1");
        uPerOffset    = GLES20.glGetUniformLocation(program, "perOffset");
        posBuf = GlUtil.floats(POS);
        uvBuf  = GlUtil.floats(UV);
        maskTex = GlUtil.createMaskTexture();
    }

    /**
     * @param sbsTex   좌/우 뷰가 나란히 담긴 텍스처
     * @param perOffset 깊이(수렴) 오프셋. 원본은 ±0.015 로 제한.
     */
    public void draw(int sbsTex, float perOffset) {
        GLES20.glUseProgram(program);

        // 마스크 갱신: update() 는 "현재 바인드된 GL_TEXTURE_2D" 에 써 넣는다.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
        Holography.update(0, 0);
        GLES20.glUniform1i(sampler1, 1);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sbsTex);
        GLES20.glUniform1i(sampler0, 0);

        GLES20.glUniform1f(uPerOffset, perOffset);

        posBuf.position(0);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, posBuf);
        GLES20.glEnableVertexAttribArray(aPosition);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 8, uvBuf);
        GLES20.glEnableVertexAttribArray(aTextureCoord);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTextureCoord);
    }
}
