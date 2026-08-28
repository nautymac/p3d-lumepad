package com.nauty.p3d;

import android.content.Context;
import android.content.Intent;

/**
 * 3DFV(com.wztech.service3d/.Service3D) 제어.
 *
 * Service3D.onBind() 는 null 을 반환하므로 바인딩은 불가능하고, 브로드캐스트가 공개 API 다.
 * 서비스는 "최상단 액티비티가 화이트리스트에 있을 때만" 패널을 3D 로 전환한다.
 * 아래 request 브로드캐스트로 임의의 액티비티를 런타임 화이트리스트에 넣을 수 있다.
 * (기기에서 동작 확인: 수신 시 logcat 에 debug_Service3D "CustomActivity intent")
 *
 * 주의 — 우리 플레이어는 스스로 인터레이스를 렌더하므로 등록하면 안 된다.
 *        등록 대상은 YouTube 처럼 남이 만든 앱이다.
 */
public final class Fv3d {

    private static final String SVC = "com.wztech.service3d.Service3D";

    public static final String ACTION_REQUEST  = SVC + ".request";
    public static final String ACTION_RESPONSE = SVC + ".response";
    public static final String ACTION_PING     = SVC + ".PING";
    public static final String ACTION_PONG     = SVC + ".PONG";
    public static final String ACTION_CLOSE    = "com.wztech.service.close_self";

    /** Service3D 내부 상수와 동일 */
    public static final int SRC_SBS_HALF    = 0;
    public static final int SRC_SBS_FULL    = 1;
    public static final int SRC_TOP_BOTTOM  = 2;
    public static final int SRC_SBS_FULLX2  = 3;
    public static final int SRC_UNREGISTER  = -1;

    public static final String[] SRC_LABELS = {
            "좌우 SBS (half)", "좌우 SBS (full)", "상하 TB", "좌우 SBS (full x2)"
    };

    private Fv3d() {}

    /**
     * 액티비티를 3D 화이트리스트에 등록/해제한다.
     * @param activityClass 패키지 접두어 없는 액티비티 전체 클래스명
     *                      예: com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
     * @param sourceType    SRC_* 중 하나. SRC_UNREGISTER 면 해제.
     */
    public static void register(Context ctx, String activityClass, int sourceType) {
        Intent i = new Intent(ACTION_REQUEST);
        i.putExtra("ActivityName", activityClass);
        i.putExtra("SourceType", sourceType);
        ctx.sendBroadcast(i);
    }

    /** 서비스 생존 확인. 응답은 ACTION_PONG 브로드캐스트로 온다. */
    public static void ping(Context ctx) {
        ctx.sendBroadcast(new Intent(ACTION_PING));
    }

    /** 3D 서비스 종료 (2D 로 되돌림). */
    public static void close(Context ctx) {
        ctx.sendBroadcast(new Intent(ACTION_CLOSE));
    }

    /** 기본 화이트리스트 파일 경로. 형식: <windowType><sourceType>@<액티비티클래스명> */
    public static final String WHITELIST_PATH = "/sdcard/K3DX/config/.white_list2.config";
}
