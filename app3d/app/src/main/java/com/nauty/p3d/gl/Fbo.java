package com.nauty.p3d.gl;

import android.opengl.GLES20;

/** 좌/우 뷰를 나란히(SBS) 담는 오프스크린 타깃. */
public class Fbo {
    private int fbo, tex, rbo;
    public int width, height;

    public Fbo(int w, int h) {
        width = w; height = h;
        int[] a = new int[1];

        GLES20.glGenTextures(1, a, 0);  tex = a[0];
        GLES20.glGenFramebuffers(1, a, 0); fbo = a[0];
        GLES20.glGenRenderbuffers(1, a, 0); rbo = a[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, null);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rbo);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, rbo);

        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO 불완전: 0x" + Integer.toHexString(st));
        }
    }

    public void bind()   { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo); }
    public void unbind() { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); }
    public int  texture(){ return tex; }

    public void release() {
        GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        GLES20.glDeleteRenderbuffers(1, new int[]{rbo}, 0);
        GLES20.glDeleteTextures(1, new int[]{tex}, 0);
        fbo = tex = rbo = 0;
    }
}
