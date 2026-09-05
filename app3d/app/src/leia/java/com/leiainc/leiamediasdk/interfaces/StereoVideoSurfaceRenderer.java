package com.leiainc.leiamediasdk.interfaces;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;

/**
 * Leia 의 스테레오(진짜 SBS) 렌더러. 선언만 갖는다.
 *
 * 2D→3D 변환기와 짝을 이룬다. 이쪽은 시차를 만들지 않고, 이미 있는 좌우 눈의
 * <b>수렴점</b>을 장면마다 다시 잡는다. 게임이나 영화의 SBS 는 큰 화면 기준으로
 * 시차가 정해져 있어서 태블릿 폭으로 줄이면 수렴이 맞지 않는데, 그걸 바로잡는다.
 */
public interface StereoVideoSurfaceRenderer {

    interface DisparityAnalysisCallback {
        void onDisparityAnalysis(Bitmap bitmap, int i);
    }

    interface SurfaceTextureCallback {
        void onSurfaceTextureReady(SurfaceTexture surfaceTexture);
    }

    float getConvergence();

    float getGain();

    RenderMode getRenderMode();

    boolean isAutoConvergence();

    void release();

    void requestRender();

    void setAutoConvergence(boolean z);

    void setConvergence(float f);

    void setDisparityAnalysisCallback(DisparityAnalysisCallback disparityAnalysisCallback);

    void setGain(float f);

    void setReconvergenceMode(ReconvergenceMode reconvergenceMode);

    void setRenderMode(RenderMode renderMode);

    void setRgbFrameDelay(int i);

    void setSingleViewMode(boolean z);
}
