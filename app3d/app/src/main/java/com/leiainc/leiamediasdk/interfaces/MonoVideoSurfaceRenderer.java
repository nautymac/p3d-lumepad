package com.leiainc.leiamediasdk.interfaces;

import android.graphics.SurfaceTexture;

/**
 * Leia 의 모노 영상 → 다시점 렌더러. **선언만** 우리가 갖는다.
 *
 * 구현체는 기기에 깔린 com.leiainc.media.service APK 안에 있고, 우리는 그것을
 * DexClassLoader 로 우리 프로세스에 올려 쓴다 (LeiaMediaSDK 주석 참고).
 * 그때 클래스 해석이 부모 우선이라, 구현체가 참조하는 이 인터페이스는 서비스 APK 안의
 * 것이 아니라 **여기 이 선언**으로 연결된다. 그래서 이름과 시그니처가 한 글자도 달라선 안 된다.
 *
 * 서비스 APK 를 디컴파일해 그대로 옮겨 적은 것이고, 바이너리는 하나도 가져오지 않았다.
 */
public interface MonoVideoSurfaceRenderer {

    interface SurfaceTextureCallback {
        void onSurfaceTextureReady(SurfaceTexture surfaceTexture);
    }

    ReconvergenceMode getCurrentReconvergenceMode();

    boolean isAutoConvergence();

    void release();

    void requestRender();

    void setAutoConvergence(boolean z);

    void setConvergence(float f);

    void setGainMultiplier(float f);

    void setPerspective(float f);

    void setReconvergenceMode(ReconvergenceMode reconvergenceMode);

    void setRgbFrameDelay(int i);

    void setSingleViewMode(boolean z);
}
