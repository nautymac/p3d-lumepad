package com.nauty.p3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 3D 컨트롤 센터 — 아무 앱이나 3DFV 화이트리스트에 등록해 패널을 3D 로 쓴다.
 *
 * 등록 경로가 두 가지고 동작이 다르다.
 *   파일 화이트리스트  : 왼쪽에 3DFV 오버레이가 떠서 모드·깊이를 직접 고를 수 있다 (Chrome 방식).
 *                        단 3DFV 가 파일을 다시 읽어야 반영된다.
 *   브로드캐스트       : 즉시 반영되지만 오버레이 없이 고정 모드로만 적용된다.
 *
 * 기본은 파일 방식이다. 오버레이가 있어야 쓸 만하기 때문.
 */
public class Fv3dControlActivity extends Activity {

    private static final int REQ_PERM = 2;

    private final List<String> labels   = new ArrayList<>();
    private final List<String> packages = new ArrayList<>();
    private ListView list;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView help = new TextView(this);
        help.setTextColor(Color.LTGRAY);
        help.setTextSize(12f);
        help.setText("앱을 골라 3D 소스 포맷을 지정하면, 그 앱이 가로모드로 최상단일 때\n"
                + "화면 왼쪽에 3DFV 오버레이(›)가 떠서 3D 를 켜고 깊이를 조절할 수 있습니다.\n"
                + "자체적으로 3D 를 렌더하는 앱(이 플레이어 포함)은 등록하지 마세요.");
        root.addView(help);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#00D8FF"));
        status.setTextSize(12f);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "서비스 확인", v -> {
            Fv3d.ping(this);
            Toast.makeText(this, "PING 전송됨", Toast.LENGTH_SHORT).show();
        });
        addButton(row, "기본값 복원", v -> confirmReset());
        root.addView(row);

        list = new ListView(this);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                pickMode(labels.get(pos), packages.get(pos));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) refresh();
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    // ------------------------------------------------------------ 목록

    private void refresh() {
        if (!Fv3dWhitelist.configDirExists()) {
            status.setText("3DFV 설정 폴더를 찾을 수 없습니다: " + Fv3dWhitelist.DIR);
        } else {
            status.setText("등록된 액티비티 " + Fv3dWhitelist.read().size() + "개");
        }
        loadApps();
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(main, 0));

        final PackageManager fpm = pm;
        Collections.sort(found, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(fpm).toString().compareToIgnoreCase(b.loadLabel(fpm).toString());
            }
        });

        Map<String, int[]> reg = Fv3dWhitelist.read();

        labels.clear();
        packages.clear();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;      // 우리 앱은 등록 대상이 아니다

            // 이 패키지의 액티비티가 하나라도 등록돼 있으면 표시한다
            String mark = "";
            for (String act : activitiesOf(pkg)) {
                int[] wt = reg.get(act);
                if (wt != null) { mark = "   ✓ " + Fv3dWhitelist.modeLabel(wt[1]); break; }
            }
            labels.add(ri.loadLabel(pm) + mark + "\n" + pkg);
            packages.add(pkg);
        }
    }

    /** 그 패키지의 모든 액티비티. 스트리밍 앱은 런처가 아니라 별도 액티비티에서 그린다. */
    private List<String> activitiesOf(String pkg) {
        List<String> out = new ArrayList<>();
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo ai : pi.activities) out.add(ai.name);
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------ 등록

    private void pickMode(final String label, final String pkg) {
        final List<String> acts = activitiesOf(pkg);
        if (acts.isEmpty()) {
            Toast.makeText(this, "액티비티를 찾을 수 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] options = {
                Fv3dWhitelist.modeLabel(0),
                Fv3dWhitelist.modeLabel(1),
                Fv3dWhitelist.modeLabel(2),
                Fv3dWhitelist.modeLabel(3),
                "등록 해제",
        };
        new AlertDialog.Builder(this)
                .setTitle(label.split("\n")[0])
                .setMessage("액티비티 " + acts.size() + "개를 등록합니다.\n"
                        + "스트리밍·게임 앱은 재생 화면이 런처와 다른 액티비티라\n"
                        + "전부 등록해야 확실합니다.")
                .setItems(options, (d, which) -> {
                    boolean ok;
                    if (which == 4) {
                        ok = Fv3dWhitelist.remove(acts);
                    } else {
                        ok = Fv3dWhitelist.put(acts, Fv3dWhitelist.WINDOW_TYPE_DEFAULT, which);
                    }
                    if (!ok) {
                        Toast.makeText(this,
                                "화이트리스트 파일을 쓰지 못했습니다.\n저장소 권한을 확인하세요.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    refresh();
                    showApplyGuide(which == 4);
                })
                .show();
    }

    /** 파일을 바꿔도 3DFV 가 다시 읽어야 반영된다. 서비스를 재시작해 즉시 적용한다. */
    private void showApplyGuide(boolean removed) {
        new AlertDialog.Builder(this)
                .setTitle(removed ? "등록 해제됨" : "등록됨")
                .setMessage("3DFV 는 화이트리스트를 서비스 시작 때만 읽습니다.\n"
                        + "지금 적용하려면 3DFV 를 재시작해야 합니다.\n\n"
                        + "적용 후 대상 앱을 가로모드 전체화면으로 실행하면\n"
                        + "화면 왼쪽에 오버레이(›)가 나타납니다.")
                .setPositiveButton("지금 적용", (d, w) -> {
                    Fv3d.restartService(Fv3dControlActivity.this);
                    Toast.makeText(this, "3DFV 재시작 중… 몇 초 뒤 적용됩니다", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("기본값 복원")
                .setMessage("추가한 등록을 모두 지우고 기기 기본 화이트리스트로 되돌립니다.\n"
                        + "(" + Fv3dWhitelist.USER + " 삭제)")
                .setPositiveButton("복원", (d, w) -> {
                    boolean ok = Fv3dWhitelist.resetToVendor();
                    Toast.makeText(this, ok ? "복원했습니다. 재부팅 후 반영됩니다."
                                            : "삭제하지 못했습니다", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show();
    }
}
