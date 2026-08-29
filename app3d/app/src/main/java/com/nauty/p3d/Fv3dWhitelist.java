package com.nauty.p3d;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 3DFV 화이트리스트 파일 조작.
 *
 * 브로드캐스트로 등록하면 Service3D 가 mIsCustomActivity=true 로 처리해서
 * **왼쪽 오버레이(FloatView)가 뜨지 않고** 고정 모드로만 적용된다.
 * 파일 화이트리스트로 등록해야 Chrome 처럼 오버레이가 떠서
 * 모드(SBS-half/full/상하)와 깊이를 직접 고를 수 있다.
 *
 * Service3D.getFVWhiteList() 는 점 없는 파일을 먼저 찾는다:
 *   /sdcard/K3DX/config/white_list2.config     ← 우리가 쓰는 파일 (우선)
 *   /sdcard/K3DX/config/.white_list2.config    ← 없을 때만 벤더 파일
 * 덕분에 벤더 파일을 건드리지 않아도 되고, 우리 파일을 지우면 원상복구된다.
 *
 * 형식: <windowType><sourceType>@<액티비티 전체 클래스명>
 *   windowType 0=at 1=sv 2=at|sv 3=first layer
 *   sourceType 0=SBS half 1=SBS full 2=상하 3=SBS fullx2
 */
public final class Fv3dWhitelist {

    private static final String TAG = "P3D";

    public static final String DIR    = "/sdcard/K3DX/config";
    public static final String USER   = DIR + "/white_list2.config";
    public static final String VENDOR = DIR + "/.white_list2.config";

    /** 대부분의 앱은 SurfaceView 로 그리므로 1 이 무난하다. */
    public static final int WINDOW_TYPE_DEFAULT = 1;

    private Fv3dWhitelist() {}

    public static boolean configDirExists() {
        return new File(DIR).isDirectory();
    }

    /** 등록된 액티비티 -> {windowType, sourceType} */
    public static Map<String, int[]> read() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (String line : lines()) {
            String t = line.trim();
            if (t.length() > 3 && t.charAt(2) == '@'
                    && Character.isDigit(t.charAt(0)) && Character.isDigit(t.charAt(1))) {
                out.put(t.substring(3), new int[]{t.charAt(0) - '0', t.charAt(1) - '0'});
            }
        }
        return out;
    }

    public static boolean isRegistered(String activity) {
        return read().containsKey(activity);
    }

    /** 등록/변경. 이미 있으면 모드만 바꾼다. */
    public static boolean put(List<String> activities, int windowType, int sourceType) {
        List<String> ls = new ArrayList<>(lines());
        for (String act : activities) {
            String entry = "" + windowType + sourceType + "@" + act;
            boolean replaced = false;
            for (int i = 0; i < ls.size(); i++) {
                if (matches(ls.get(i), act)) {
                    ls.set(i, entry);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) ls.add(entry);
        }
        return write(ls);
    }

    public static boolean remove(List<String> activities) {
        List<String> ls = new ArrayList<>(lines());
        for (String act : activities) {
            for (int i = ls.size() - 1; i >= 0; i--) {
                if (matches(ls.get(i), act)) ls.remove(i);
            }
        }
        return write(ls);
    }

    private static boolean matches(String line, String activity) {
        String t = line.trim();
        return t.length() > 3 && t.charAt(2) == '@' && t.substring(3).equals(activity);
    }

    /** 우리 파일이 있으면 그것, 없으면 벤더 파일을 씨앗으로 쓴다. */
    private static List<String> lines() {
        List<String> out = new ArrayList<>();
        File f = new File(USER);
        if (!f.isFile()) f = new File(VENDOR);
        if (!f.isFile()) return out;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String s;
            while ((s = br.readLine()) != null) out.add(s);
        } catch (Exception e) {
            Log.w(TAG, "화이트리스트 읽기 실패: " + f, e);
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean write(List<String> ls) {
        FileWriter w = null;
        try {
            w = new FileWriter(USER, false);
            for (String s : ls) {
                w.write(s);
                w.write("\n");
            }
            w.flush();
            Log.i(TAG, "화이트리스트 저장: " + USER + " (" + ls.size() + "줄)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "화이트리스트 쓰기 실패: " + USER, e);
            return false;
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    /** 우리 파일을 지워 벤더 기본값으로 되돌린다. */
    public static boolean resetToVendor() {
        File f = new File(USER);
        return !f.exists() || f.delete();
    }

    public static String modeLabel(int sourceType) {
        switch (sourceType) {
            case 0:  return "좌우 SBS (half)";
            case 1:  return "좌우 SBS (full)";
            case 2:  return "상하 (TB)";
            case 3:  return "좌우 SBS (full x2)";
            default: return "sourceType " + sourceType;
        }
    }

    public static String describe(int[] wt) {
        return String.format(Locale.US, "%s · windowType %d", modeLabel(wt[1]), wt[0]);
    }
}
