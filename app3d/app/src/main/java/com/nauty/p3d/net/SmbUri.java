package com.nauty.p3d.net;

import android.net.Uri;

import java.util.List;

/**
 * {@code smb://[user[:pass]@]host[:port]/share/path/to/file} 를 분해·조립한다.
 *
 * smbj 는 공유 안의 경로를 "\\" 로 구분한다 (URL 의 "/" 가 아니다). 그래서 URI 의
 * 경로 조각을 다시 "\\" 로 이어 붙인다 (HANDOFF 1-② 참고).
 */
public final class SmbUri {

    public final String host;
    public final int port;
    public final String share;
    /** smbj 식 "\\" 구분자. 공유 루트면 빈 문자열. */
    public final String path;
    /** URI 에 없으면 null — 그러면 SmbCredentials 에 저장된 값을 찾아야 한다. */
    public final String user;
    public final String pass;

    private SmbUri(String host, int port, String share, String path, String user, String pass) {
        this.host = host;
        this.port = port;
        this.share = share;
        this.path = path;
        this.user = user;
        this.pass = pass;
    }

    public boolean hasCredentials() { return user != null && !user.isEmpty(); }

    public static SmbUri parse(Uri uri) {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 445;

        String user = null, pass = null;
        String info = uri.getUserInfo();
        if (info != null) {
            int c = info.indexOf(':');
            user = c >= 0 ? info.substring(0, c) : info;
            pass = c >= 0 ? info.substring(c + 1) : "";
        }

        List<String> seg = uri.getPathSegments();
        String share = seg.isEmpty() ? "" : seg.get(0);
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < seg.size(); i++) {
            if (path.length() > 0) path.append('\\');
            path.append(seg.get(i));
        }
        return new SmbUri(host, port, share, path.toString(), user, pass);
    }

    /** 탐색기·재생에 쓸 URI 를 만든다. path 는 smbj 식 "\\" 구분자로 넘긴다. */
    public static Uri build(String host, int port, String share, String path,
                             String user, String pass) {
        StringBuilder sb = new StringBuilder("smb://");
        if (user != null && !user.isEmpty()) {
            sb.append(Uri.encode(user));
            if (pass != null && !pass.isEmpty()) sb.append(':').append(Uri.encode(pass));
            sb.append('@');
        }
        sb.append(host);
        if (port > 0 && port != 445) sb.append(':').append(port);
        sb.append('/').append(Uri.encode(share));
        if (path != null && !path.isEmpty()) {
            for (String part : path.split("\\\\")) {
                if (!part.isEmpty()) sb.append('/').append(Uri.encode(part));
            }
        }
        return Uri.parse(sb.toString());
    }
}
