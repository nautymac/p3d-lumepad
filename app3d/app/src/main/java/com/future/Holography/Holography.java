package com.future.Holography;

/**
 * libholography.so 는 정적 JNI 네이밍(Java_com_future_Holography_Holography_*)을 쓰므로
 * 패키지/클래스 이름이 정확히 이것이어야 심볼이 해석된다. 이름을 바꾸면 UnsatisfiedLinkError.
 *
 * update() 는 "현재 바인드된 GL_TEXTURE_2D" 에 렌티큘러 인터레이싱 마스크를 써 넣는다.
 * frag3D.sh 의 Sampler1 이 이 마스크다.
 *
 * 인자 (x, y) 는 시점 좌표처럼 보이지만 실제로는 무시된다. 측정으로 확인했다 —
 * 자세한 내용은 ../../../../../../FINDINGS.md 의 "아이트래킹" 절 참고.
 * 기본 앱들(3DPlayer, Sight3D)도 전부 update(0, 0) 만 쓴다.
 *
 * 라이브러리에는 startAutoSwitch / getx / gety / getdis / setAngle / sendDelt 심볼도 있지만
 * 값이 항상 0 이라 쓸모가 없다. 선언하지 않는다.
 */
public class Holography {
    static { System.loadLibrary("holography"); }

    public static native int  HolographyInit(int width, int height);
    public static native void update(int x, int y);
    public static native void deinitHolography();
}
