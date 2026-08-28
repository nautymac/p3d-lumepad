package com.nauty.p3d.gl;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class GlUtil {
    public static final String TAG = "P3D";
    public static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    private GlUtil() {}

    public static void check(String op) {
        int e = GLES20.glGetError();
        if (e != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(op + ": glError 0x" + Integer.toHexString(e));
        }
    }

    public static String readAsset(Context ctx, String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8").replaceAll("\r\n", "\n");
        } catch (Exception e) {
            throw new RuntimeException("asset 읽기 실패: " + name, e);
        }
    }

    public static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("셰이더 컴파일 실패: " + log + "\n---\n" + src);
        }
        return s;
    }

    public static int program(String vert, String frag) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vert);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("프로그램 링크 실패: " + log);
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    public static FloatBuffer floats(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    /** libholography 가 마스크를 써 넣을 2D 텍스처. 원본 f.a() 와 동일 설정(NEAREST/CLAMP). */
    public static int createMaskTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** SurfaceTexture 용 OES 텍스처. */
    public static int createOesTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    public static void logi(String m) { Log.i(TAG, m); }
}
