package com.leiainc.leiamediasdk.interfaces;

/**
 * 스테레오 렌더러의 출력 방식. 선언만 갖는다 (MonoVideoSurfaceRenderer 주석 참고).
 * 상수 이름과 순서가 원본과 같아야 한다.
 */
public enum RenderMode {
    STEREO,
    RAYCASTING_STEREO,
    DISPARITY,
    PASSTHRU
}
