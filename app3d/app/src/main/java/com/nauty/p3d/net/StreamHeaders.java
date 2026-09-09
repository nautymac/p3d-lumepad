package com.nauty.p3d.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 유튜브에서 뽑아낸 스트림 주소는 CDN 이 요청 헤더(User-Agent 등)를 확인하는 경우가
 * 있다 — yt-dlp 가 확인해 준 헤더를 그대로 실어 보내지 않으면 ExoPlayer 의 기본
 * 요청이 막힐 수 있다.
 *
 * 호스트 단위로 저장하는 이유: HLS(m3u8)는 재생목록과 조각(segment) 파일이 같은
 * CDN 호스트의 서로 다른 URL 로 여러 번 요청된다. 정확한 URL 로 키를 잡으면
 * 재생목록만 맞고 조각 요청은 못 찾는다.
 */
final class StreamHeaders {

    private static final Map<String, Map<String, String>> BY_HOST = new ConcurrentHashMap<>();

    private StreamHeaders() { }

    static void put(String host, Map<String, String> headers) {
        if (host != null && headers != null && !headers.isEmpty()) {
            BY_HOST.put(host, headers);
        }
    }

    static Map<String, String> get(String host) {
        return host == null ? null : BY_HOST.get(host);
    }
}
