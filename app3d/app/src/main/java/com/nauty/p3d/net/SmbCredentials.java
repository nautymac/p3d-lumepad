package com.nauty.p3d.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * SMB 로그인 정보를 host+공유 이름으로 기억해 둔다. {@link com.nauty.p3d.MediaLibrary} 의
 * 즐겨찾기가 이미 SharedPreferences 를 쓰고 있어 같은 방식을 따른다.
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

    private static String key(String host, String share) {
        return (host == null ? "" : host.toLowerCase(Locale.US)) + "|" + (share == null ? "" : share);
    }

    public static void save(Context ctx, String host, String share,
                             String user, String pass, String domain) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(host, share) + ":u", user == null ? "" : user)
                .putString(key(host, share) + ":p", pass == null ? "" : pass)
                .putString(key(host, share) + ":d", domain == null ? "" : domain)
                .apply();
    }

    /** 없으면 null. */
    public static Entry find(Context ctx, String host, String share) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = sp.getString(key(host, share) + ":u", null);
        if (u == null) return null;
        String p = sp.getString(key(host, share) + ":p", "");
        String d = sp.getString(key(host, share) + ":d", "");
        return new Entry(u, p, d.isEmpty() ? null : d);
    }
}
