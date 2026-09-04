package com.nauty.p3d.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * 지금 GL 컨텍스트에 남의 {@link Surface} 를 하나 더 붙여 두고, 프레임마다 갈아끼우는 도구.
 *
 * Lume Pad 2 에서 필요하다. 그 패널은 위빙을 Leia CNSDK 가 해야 하는데, CNSDK 는 자기
 * 서피스를 내주고 "여기에 SBS 를 그려 넣어라" 고 한다. 우리 3D 파이프라인은
 * {@code GLSurfaceView} 안에서 도는 상태라, 같은 컨텍스트에 두 번째 EGL 서피스를 만들어
 * 그릴 때만 그쪽으로 전환하는 방식이 제일 덜 침습적이다.
 *
 * 함정: {@code GLSurfaceView} 는 컨텍스트를 <b>EGL10</b> 으로 만드는데 우리는 윈도우
 * 서피스를 <b>EGL14</b> 로 만들게 된다. 윈도우 서피스는 컨텍스트와 같은 config 에서만
 * 동작하므로 config 를 새로 고르면 안 되고, 현재 컨텍스트의 {@code EGL_CONFIG_ID} 로
 * 되찾아 와야 한다.
 */
public class ExternalGlTarget {

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private EGLSurface savedDraw = EGL14.EGL_NO_SURFACE;
    private EGLSurface savedRead = EGL14.EGL_NO_SURFACE;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;

    private final Surface target;
    public final int width, height;

    private ExternalGlTarget(Surface target, int w, int h) {
        this.target = target;
        this.width  = w;
        this.height = h;
    }

    /** GL 스레드에서 부를 것. 실패하면 null 을 돌려주고 호출부는 외부 출력을 포기하면 된다. */
    public static ExternalGlTarget create(Surface target, int w, int h) {
        ExternalGlTarget t = new ExternalGlTarget(target, w, h);
        try {
            t.display = EGL14.eglGetCurrentDisplay();
            t.context = EGL14.eglGetCurrentContext();
            if (t.display == EGL14.EGL_NO_DISPLAY || t.context == EGL14.EGL_NO_CONTEXT) {
                GlUtil.logi("외부 타깃: 현재 EGL 컨텍스트가 없다");
                return null;
            }

            EGLConfig cfg = configOfCurrentContext(t.display, t.context);
            if (cfg == null) {
                GlUtil.logi("외부 타깃: 컨텍스트의 EGLConfig 를 못 찾았다");
                return null;
            }

            int[] attrs = { EGL14.EGL_NONE };
            t.surface = EGL14.eglCreateWindowSurface(t.display, cfg, target, attrs, 0);
            if (t.surface == null || t.surface == EGL14.EGL_NO_SURFACE) {
                GlUtil.logi("외부 타깃: eglCreateWindowSurface 실패 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
                return null;
            }
            GlUtil.logi("외부 타깃 준비 " + w + "x" + h);
            return t;
        } catch (Throwable e) {
            GlUtil.logi("외부 타깃 생성 예외: " + e);
            return null;
        }
    }

    /**
     * 컨텍스트를 만들 때 쓴 config 를 id 로 되찾는다.
     * 속성으로 새로 고르면 미묘하게 다른 config 가 잡혀 윈도우 서피스가 만들어지지 않는다.
     */
    private static EGLConfig configOfCurrentContext(EGLDisplay dpy, EGLContext ctx) {
        int[] id = new int[1];
        if (!EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, id, 0)) return null;

        int[] attrs = { EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, found, 0) || found[0] < 1) {
            return null;
        }
        return configs[0];
    }

    /** 이 타깃으로 전환. 성공하면 true. */
    public boolean makeCurrent() {
        savedDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        savedRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        return EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    /** 그린 것을 내보내고 원래 서피스로 돌아온다. */
    public void swapAndRestore() {
        EGL14.eglSwapBuffers(display, surface);
        EGL14.eglMakeCurrent(display, savedDraw, savedRead, context);
    }

    public boolean isFor(Surface s) { return target == s; }

    public void release() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface);
            surface = EGL14.EGL_NO_SURFACE;
        }
    }
}
