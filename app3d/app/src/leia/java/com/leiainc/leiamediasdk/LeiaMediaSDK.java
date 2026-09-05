package com.leiainc.leiamediasdk;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import android.view.Surface;

import com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer;

import java.lang.reflect.Constructor;

import dalvik.system.DexClassLoader;

/**
 * Leia 의 2D→3D 엔진을 우리 프로세스에 불러오는 로더.
 *
 * LeiaPlayer 가 쓰는 방식을 그대로 따랐다. 핵심은 이게 IPC 가 아니라는 것이다 —
 * 기기에 깔린 com.leiainc.media.service APK 의 sourceDir 를 DexClassLoader 로 열어
 * 구현 클래스와 네이티브 라이브러리를 **우리 프로세스 안에서** 돌린다.
 *
 * 그래서 우리가 재배포하는 것이 하나도 없다. 모델 .dlc 7개(약 330MB)도, SNPE 런타임도,
 * Hexagon 스켈도 전부 서비스 APK 에 그대로 있고 우리는 참조만 한다. 우리가 가진 것은
 * 인터페이스 선언 두 개뿐이다.
 *
 * 클래스 이름이 우리 패키지가 아니라 com.leiainc.leiamediasdk 인 이유가 있다.
 * DexClassLoader 의 부모가 우리 앱 로더라 클래스 해석이 부모 우선이고, 서비스 APK 의
 * 모델 코드가 LeiaMediaSDK.getAppWrapper() 를 부른다. 그 호출이 여기로 와야 한다.
 *
 * 동작 조건: Build.MODEL 이 LPD-20W(Lume Pad 2) 또는 K68 이어야 한다. 구현체가
 * 생성자에서 직접 확인하고 아니면 예외를 던진다. ProMa 에는 이 소스가 들어가지 않는다.
 */
public final class LeiaMediaSDK {

    private static final String TAG = "P3DLeiaMl";

    /** 서비스 APK 의 패키지. 모델도 셰이더도 전부 여기서 나온다. */
    public static final String SERVICE_PACKAGE = "com.leiainc.media.service";

    private static AppWrapper   appWrapper;
    private static LeiaMediaSDK instance;

    private final DexClassLoader loader;

    /**
     * SNPE 에 넘길 Application.
     *
     * 모델을 만드는 쪽이 new SNPE.NeuralNetworkBuilder(LeiaMediaSDK.getAppWrapper()) 로
     * 부르는데, 거기서 보는 것은 ApplicationInfo 다 — 네이티브 라이브러리를 어디서 찾을지.
     * 우리 앱이 아니라 서비스 APK 를 가리켜야 한다.
     */
    public static class AppWrapper extends Application {
        private final ApplicationInfo info;

        AppWrapper(ApplicationInfo info) { this.info = info; }

        @Override public ApplicationInfo getApplicationInfo() { return info; }
    }

    private LeiaMediaSDK(Context ctx) throws Exception {
        ApplicationInfo info = ctx.getPackageManager()
                .getPackageInfo(SERVICE_PACKAGE, 0).applicationInfo;
        appWrapper = new AppWrapper(info);
        loader = new DexClassLoader(info.sourceDir, "", info.nativeLibraryDir, ctx.getClassLoader());
        Log.i(TAG, "미디어 서비스 로드: " + info.sourceDir);
    }

    public static AppWrapper getAppWrapper() { return appWrapper; }

    /** 서비스가 없으면 null. 그때는 우리 시어로 돌아가면 된다. */
    public static synchronized LeiaMediaSDK getInstance(Context ctx) {
        if (instance == null) {
            try {
                instance = new LeiaMediaSDK(ctx);
            } catch (Throwable t) {
                Log.e(TAG, "미디어 서비스를 열 수 없다", t);
                return null;
            }
        }
        return instance;
    }

    /**
     * 모노 영상 → 다시점 렌더러를 만든다.
     *
     * ctx 는 반드시 서비스 패키지의 컨텍스트여야 한다 (serviceContext 참고).
     * 구현체가 생성자에서 ctx.getApplicationInfo().nativeLibraryDir 로
     * ADSP_LIBRARY_PATH 를 잡기 때문이다 — 우리 앱을 가리키면 Hexagon 스켈을 못 찾는다.
     *
     * out 에는 hTiles x vTiles 로 타일된 다시점 그림이 그려진다. Lume Pad 2 기본값이
     * 2x1 이라, 지금 우리가 CNSDK 에 넘기는 SBS 와 배치가 같다.
     *
     * 이 호출은 렌더링 스레드가 초기화될 때까지 블록한다. UI 스레드에서 부르지 말 것.
     */
    public MonoVideoSurfaceRenderer createMonoVideoSurfaceRenderer(
            Context ctx, Surface out, MonoVideoSurfaceRenderer.SurfaceTextureCallback cb) {
        try {
            Constructor<?> c = loader
                    .loadClass("com.leiainc.androidsdk.video.mono.MonoVideoSurfaceRendererImpl")
                    .getDeclaredConstructor(Context.class, Surface.class,
                            MonoVideoSurfaceRenderer.SurfaceTextureCallback.class);
            c.setAccessible(true);
            return (MonoVideoSurfaceRenderer) c.newInstance(ctx, out, cb);
        } catch (Throwable t) {
            Log.e(TAG, "MonoVideoSurfaceRendererImpl 생성 실패", t);
            return null;
        }
    }

    /**
     * 서비스 패키지의 컨텍스트.
     *
     * 셰이더와 모델은 구현체가 알아서 getResourcesForApplication(서비스) 로 읽으니
     * 아무 컨텍스트나 되지만, ADSP_LIBRARY_PATH 만큼은 이게 있어야 맞는다.
     */
    public static Context serviceContext(Context ctx) {
        try {
            return ctx.createPackageContext(SERVICE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            Log.e(TAG, "서비스 컨텍스트를 만들 수 없다", t);
            return null;
        }
    }
}
