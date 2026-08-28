uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = (uSTMatrix * aTexCoord).xy;
}
