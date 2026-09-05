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

    /**
     * 패널이 3D 를 제대로 낼 준비가 되면 실행한다. 이미 준비돼 있으면 즉시.
     *
     * Lume Pad 2 는 백라이트가 3D 로 넘어가고 얼굴추적이 붙기까지 1초쯤 걸린다.
     * 그 전에 그림을 띄우면 기본 시점으로 짜다가 추적이 붙는 순간 위빙이 제자리를
     * 찾으면서 화면이 한 번 튄다. 재생을 그때까지 미루면 그 튐이 보이지 않는다.
     */
    void whenReady(Runnable r);

    /** GL 뷰를 만든 직후. 외부 출력이 필요하면 여기서 연결한다. */
    void attach(Activity a, Stereo3DView gl);

    /**
     * 3D 로 내보낼지 여부. 패널이 백라이트를 갈아야 하는 기기가 있다.
     *
     * Lume Pad 2 는 3D 모드에서 균일 백라이트를 끄고 회절 광원만 켜므로 화면이
     * 어두워진다. 2D 출력으로 볼 때까지 그 상태로 두면 손해다.
     */
    void setThreeD(boolean on);

    /**
     * 지금 소스가 2D 인지 알려준다.
     *
     * Lume Pad 2 는 2D 일 때 마지막 단계를 Leia 의 신경망 변환기로 갈아 끼운다.
     * 우리 GL 은 모노 한 장까지만 그리고, 시차는 그쪽이 만든다.
     * 우리 시어보다 확실히 낫다 — 같은 프레임으로 진짜 스테레오와 비교해 확인했다.
     */
    void setMonoSource(boolean mono);

    /**
     * 2D→3D 시차 강도. 슬라이더와 같은 눈금(1.0 = 기본).
     * 시어를 쓰는 기기는 뷰가 직접 받으므로 할 일이 없다.
     */
    void setConversionStrength(float v);


    void onResume();
    void onPause();
    void onDestroy();
}
