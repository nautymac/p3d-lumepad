#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTexture;
// 2D->3D 시어. 원본 frag2dto3d.sh 수식:
//   tmp.x += 0.004 - (vTex.y * screenHeight) * 0.0000122
// screenHeight 를 uShearSlope 에 흡수시켜 파라미터 2개로 정리.
uniform float uShearTop;     // 화면 상단에서의 이동량 (기본 0.004), 0 이면 시어 없음
uniform float uShearSlope;   // 아래로 갈수록 감소하는 기울기 (기본 0.0000122 * srcHeightPx)
uniform float uBottomCut;    // 하단 잘라내기 경계 (원본 0.990740741, 1.0 이면 비활성)
void main() {
    vec2 t = vTex;
    t.x += uShearTop - vTex.y * uShearSlope;
    gl_FragColor = texture2D(sTexture, t);
    if (vTex.y > uBottomCut) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    }
}
