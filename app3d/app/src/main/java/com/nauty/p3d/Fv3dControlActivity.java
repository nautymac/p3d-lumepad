package com.nauty.p3d;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
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

/**
 * 3D 컨트롤 센터 — 설치된 앱을 3DFV 화이트리스트에 등록/해제한다.
 *
 * 기존 3개 앱에 없던 기능. YouTube 는 기본 화이트리스트에 SBS-half 로 고정돼 있어서
 * 상하(TB) 영상을 틀면 깨지는데, 여기서 즉시 바꿀 수 있다.
 */
public class Fv3dControlActivity extends Activity {

    private final List<String> labels    = new ArrayList<>();
    private final List<String> activities = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView help = new TextView(this);
        help.setTextColor(Color.LTGRAY);
        help.setTextSize(12f);
        help.setText("앱을 선택해 3D 소스 포맷을 지정하면, 그 앱이 화면 최상단(가로모드)일 때\n"
                + "3DFV 가 패널을 3D 로 전환합니다. 자체 3D 렌더를 하는 앱은 등록하지 마세요.");
        root.addView(help);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "서비스 확인(PING)", v -> {
            Fv3d.ping(this);
            Toast.makeText(this, "PING 전송됨", Toast.LENGTH_SHORT).show();
        });
        addButton(row, "3D 끄기", v -> {
            Fv3d.close(this);
            Toast.makeText(this, "3D 서비스 종료 요청", Toast.LENGTH_SHORT).show();
        });
        root.addView(row);

        ListView list = new ListView(this);
        loadApps();
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                pickFormat(labels.get(pos), activities.get(pos));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);

        List<ResolveInfo> sorted = new ArrayList<>(found);
        final PackageManager fpm = pm;
        Collections.sort(sorted, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(fpm).toString()
                        .compareToIgnoreCase(b.loadLabel(fpm).toString());
            }
        });

        labels.clear();
        activities.clear();
        for (ResolveInfo ri : sorted) {
            String cls = ri.activityInfo.name;
            labels.add(ri.loadLabel(pm) + "\n" + cls);
            activities.add(cls);
        }
    }

    private void pickFormat(final String label, final String activityClass) {
        final String[] options = {
                Fv3d.SRC_LABELS[0], Fv3d.SRC_LABELS[1],
                Fv3d.SRC_LABELS[2], Fv3d.SRC_LABELS[3], "등록 해제"
        };
        new AlertDialog.Builder(this)
                .setTitle(label.split("\n")[0])
                .setItems(options, (d, which) -> {
                    int type = (which == 4) ? Fv3d.SRC_UNREGISTER : which;
                    Fv3d.register(Fv3dControlActivity.this, activityClass, type);
                    Toast.makeText(Fv3dControlActivity.this,
                            (which == 4 ? "해제됨: " : options[which] + " 로 등록: ") + activityClass,
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }
}
