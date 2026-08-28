package com.future.Holography;

/**
 * libholography.so 는 정적 JNI 네이밍(Java_com_future_Holography_Holography_*)을 쓰므로
 * 패키지/클래스 이름이 정확히 이것이어야 심볼이 해석된다. 이름을 바꾸면 UnsatisfiedLinkError.
 *
 * update() 는 "현재 바인드된 GL_TEXTURE_2D" 에 렌티큘러 인터레이싱 마스크를 써 넣는다.
 * frag3D.sh 의 Sampler1 이 이 마스크다.
 *
 * 아래 3개만 원본 3DPlayer 가 선언·사용하던 것이라 시그니처가 검증됐다.
 * 라이브러리에는 HolographyInit2 / HolographySetSize / setAngle / startAutoSwitch /
 * stopAutoSwitch / getx / gety / getdis / getCurGS / getEfficiency / sendDelt / updateJZ
 * 심볼도 존재하지만 인자 타입이 미확인이라 선언하지 않는다 (잘못 선언하면 호출 시 크래시).
 */
public class Holography {
    static { System.loadLibrary("holography"); }

    public static native int  HolographyInit(int width, int height);
    public static native void update(int x, int y);
    public static native void deinitHolography();
}
