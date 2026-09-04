package com.nauty.p3d.panel;

import android.app.Activity;
import android.view.View;

import com.nauty.p3d.gl.Stereo3DView;

/**
 * 패널마다 다른 "마지막 한 단계" 를 가린다.
 *
 * 우리 파이프라인은 어느 기기에서든 SBS 까지 똑같이 만든다. 그것을 화면에 어떻게
 * 내보내느냐만 갈린다.
 *
 * <pre>
 * ProMa P10   렌티큘러. 고정 마스크라 우리가 직접 인터레이스해서 우리 GLSurfaceView 에 그린다.
 * Lume Pad 2  8방향 회절 + 얼굴추적. 고정 패턴이 불가능해 위빙을 CNSDK 에 넘긴다.
 * </pre>
 *
 * 구현은 제품 플레이버가 고른다 ({@code Panel.create()}). CNSDK 는 재배포할 수 없어서
 * proma 빌드에는 들어가지 않아야 하므로, 이 심이 없으면 소스가 갈리지 않는다.
 */
public interface PanelBackend {

    /**
     * 3D 출력을 담당하는 뷰. null 이면 {@link Stereo3DView} 자체가 화면에 나간다(ProMa).
     * null 이 아니면 그 뷰가 화면을 차지하고 Stereo3DView 는 숨겨진다(Lume Pad 2).
     */
    View outputView(Activity a);

    /** GL 뷰를 만든 직후. 외부 출력이 필요하면 여기서 연결한다. */
    void attach(Activity a, Stereo3DView gl);

    /** 렌티큘러 마스크(libholography)를 쓰는 패널인지. */
    boolean useHolography();

    void onResume();
    void onPause();
    void onDestroy();
}
