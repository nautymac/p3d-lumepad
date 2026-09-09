package com.nauty.p3d.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * SMB 로그인 정보를 호스트 단위로 기억해 둔다. {@link com.nauty.p3d.MediaLibrary} 의
 * 즐겨찾기가 이미 SharedPreferences 를 쓰고 있어 같은 방식을 따른다.
 *
 * 공유 이름은 키에 넣지 않는다 — 계정을 물어보는 시점(host 를 고른 직후)엔 아직
 * 어느 공유를 열지 모르고, 보통 한 호스트는 한 계정으로 붙으므로 host 단위로
 * 기억해야 다음에 다시 입력하지 않아도 된다 (사용자 요청).
 *
 * 평문 SharedPreferences 다. 걸리면 EncryptedSharedPreferences 로 바꾸면 되고,
 * 저장 형식(문자열 세 개)은 그대로 옮겨 쓸 수 있다 (HANDOFF 1-② 참고).
 */
public final class SmbCredentials {

    private static final String PREFS = "p3d_smb";

    public static final class Entry {
        public final String user, pass, domain;
        public Entry(String user, String pass, String domain) {
            this.user = user; this.pass = pass; this.domain = domain;
        }
    }

    private SmbCredentials() {}

    private static String key(String host) {
        return host == null ? "" : host.toLowerCase(Locale.US);
    }

    public static void save(Context ctx, String host, String user, String pass, String domain) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(host) + ":u", user == null ? "" : user)
                .putString(key(host) + ":p", pass == null ? "" : pass)
                .putString(key(host) + ":d", domain == null ? "" : domain)
                .apply();
    }

    /** 없으면 null. */
    public static Entry find(Context ctx, String host) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = sp.getString(key(host) + ":u", null);
        if (u == null) return null;
        String p = sp.getString(key(host) + ":p", "");
        String d = sp.getString(key(host) + ":d", "");
        return new Entry(u, p, d.isEmpty() ? null : d);
    }
}
