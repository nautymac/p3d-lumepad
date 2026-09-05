package com.leiainc.leiamediasdk.interfaces;

/**
 * 재수렴 방식. MonoVideoSurfaceRenderer 와 같은 이유로 선언만 갖는다.
 * 상수 이름과 **순서**까지 원본과 같아야 한다 — 구현체가 ordinal 로 다룬다.
 */
public enum ReconvergenceMode {
    noZoom(0, 0),
    zoomXAndY(1, 1);

    public final int zoomX;
    public final int zoomY;

    ReconvergenceMode(int zoomX, int zoomY) {
        this.zoomX = zoomX;
        this.zoomY = zoomY;
    }

    public static ReconvergenceMode defaultMode() {
        return zoomXAndY;
    }

    public ReconvergenceMode next() {
        ReconvergenceMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
