package com.nauty.p3d.leia;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * 백라이트 비율을 서비스에 직접 묻고 바꾸는 시험 도구.
 *
 * 3D 모드는 균일 광원을 끄고(mode3d_ratio_2d = 0.0) 회절 광원만 1.2배로 쓴다.
 * 그 1.2 를 올리면 3D 화면이 밝아지는지가 물음이다.
 *
 * adb 로 Settings 에 직접 써봤을 때는 커널 로그가 꿈쩍도 하지 않았는데, 정식 API 를
 * 뜯어보니 setBacklightRatios 도 같은 Settings 키에 쓸 뿐이었다. 다만 <b>읽는</b> 쪽은
 * 서비스에게 묻는다. 그래서 여기서는 읽기를 먼저 한다 — 서비스가 들고 있는 값이
 * Settings 와 다르면 "설정은 써지지만 서비스에 닿지 않는다" 가 확인되는 것이고,
 * 같다면 그 커널 로그가 이 비율과 무관한 값이었다는 뜻이다.
 *
 * com.leia.android.lights 는 /system/framework 의 시스템 공유 라이브러리다
 * (매니페스트에 uses-library 로 선언돼 있다). 컴파일 시점에는 없으므로 리플렉션으로 부른다.
 *
 * 실행:
 *   adb shell appops set com.nauty.p3d WRITE_SETTINGS allow      (쓰려면 한 번 필요)
 *   adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaLightsActivity          # 읽기만
 *   adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaLightsActivity --ef r3d 1.6
 */
public class LeiaLightsActivity extends Activity {

    private static final String TAG = "P3DLights";

    private static final String[] MANAGERS = {
            "com.leia.android.lights.LeiaLightsManagerV8",
            "com.leia.android.lights.LeiaLightsManagerV7",
            "com.leia.android.lights.LeiaLightsManagerV6",
            "com.leia.android.lights.LeiaLightsManagerV5",
            "com.leia.android.lights.LeiaLightsManagerV4",
            "com.leia.android.lights.LeiaLightsManager",
    };

    private final StringBuilder report = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);

        say("쓰기 권한(WRITE_SETTINGS): " + Settings.System.canWrite(this));
        say("Settings 값  mode3d_ratio_2d=" + sysFloat("backlight_mode3d_ratio_2d")
                + "  mode3d_ratio_3d=" + sysFloat("backlight_mode3d_ratio_3d"));

        for (String cls : MANAGERS) {
            if (probe(cls)) break;
        }
        tv.setText(report.toString());
    }

    /** 이 버전으로 서비스에 닿으면 true. */
    private boolean probe(String cls) {
        try {
            Class<?> mgr  = Class.forName(cls);
            Class<?> mode = Class.forName(cls + "$BacklightMode");
            Object   m3d  = Enum.valueOf(asEnum(mode), "MODE_3D");
            Object   inst = mgr.getConstructor(android.content.Context.class).newInstance(this);

            Method get = mgr.getMethod("getBacklightRatios", mode);
            float[] before = (float[]) get.invoke(inst, m3d);
            if (before == null) { say(cls + " -> 서비스 응답 없음"); return false; }

            say(cls);
            say("  서비스가 들고 있는 값  2d=" + before[0] + "  3d=" + before[1]);

            float r3d = getIntent().getFloatExtra("r3d", -1f);
            if (r3d > 0) {
                Method set = mgr.getMethod("setBacklightRatios", mode, float.class, float.class);
                set.invoke(inst, m3d, before[0], r3d);
                float[] after = (float[]) get.invoke(inst, m3d);
                say("  " + before[1] + " -> " + r3d + " 요청");
                say("  요청 후 서비스 값     2d=" + after[0] + "  3d=" + after[1]);
                say("  요청 후 Settings 값   " + sysFloat("backlight_mode3d_ratio_3d"));
            }
            return true;
        } catch (Throwable t) {
            say(cls + " 실패: " + t);
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<Enum> asEnum(Class<?> c) { return (Class<Enum>) c; }

    private String sysFloat(String key) {
        try {
            return String.valueOf(Settings.System.getFloat(getContentResolver(), key));
        } catch (Throwable t) {
            return "(없음)";
        }
    }

    private void say(String s) {
        Log.i(TAG, s);
        report.append(s).append('\n');
    }
}
