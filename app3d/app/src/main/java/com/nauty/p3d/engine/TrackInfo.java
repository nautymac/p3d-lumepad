package com.nauty.p3d.engine;

import androidx.media3.common.TrackGroup;

/**
 * 트랙 선택기(오디오/자막)에 보여줄 한 줄.
 *
 * ExoPlayer 의 {@code TrackSelectionOverride} 는 {@link TrackGroup} 과 그 안의
 * 인덱스로 트랙을 가리키므로, 고르기 화면에서 다시 override 를 만들 수 있게
 * 그 둘을 그대로 들고 있는다.
 */
public final class TrackInfo {

    public final TrackGroup group;
    public final int indexInGroup;
    /** 화면에 보일 이름 (언어/이름 + 코덱 + 채널수 등). */
    public final String label;
    /** 이 기기 디코더로 실제 재생 가능한지. */
    public final boolean supported;
    /**
     * PGS/VOBSUB/DVB 같은 이미지 자막.
     * 지금은 좌/우 눈에 비트맵을 넣는 경로가 텍스트 전용이라 고르면 안내만 한다
     * (HANDOFF 1-① 참고).
     */
    public final boolean imageBased;

    public TrackInfo(TrackGroup group, int indexInGroup, String label,
                      boolean supported, boolean imageBased) {
        this.group = group;
        this.indexInGroup = indexInGroup;
        this.label = label;
        this.supported = supported;
        this.imageBased = imageBased;
    }

    @Override public String toString() { return label; }
}
