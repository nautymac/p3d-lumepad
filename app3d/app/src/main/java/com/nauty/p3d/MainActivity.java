package com.nauty.p3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    private final List<String> titles = new ArrayList<>();
    private final List<Uri>    uris   = new ArrayList<>();
    private ListView list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "URL/스트리밍 열기", new View.OnClickListener() {
            @Override public void onClick(View v) { askUrl(); }
        });
        addButton(row, "3D 컨트롤 센터", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Fv3dControlActivity.class));
            }
        });
        root.addView(row);

        empty = new TextView(this);
        empty.setTextColor(Color.GRAY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 48, 0, 48);
        empty.setText("영상을 찾는 중...");
        root.addView(empty);

        list = new ListView(this);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                play(uris.get(pos), titles.get(pos));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            loadVideos();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) loadVideos();
    }

    private void addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void loadVideos() {
        titles.clear();
        uris.clear();
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME};
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    titles.add(name);
                    uris.add(Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "미디어 조회 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (c != null) c.close();
        }

        // 추정된 3D 포맷을 목록에 같이 표시
        List<String> display = new ArrayList<>();
        for (String t : titles) {
            SourceFormat f = SourceFormat.fromName(t);
            display.add(f == null ? t : t + "   [" + f.label + "]");
        }

        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, display));
        empty.setVisibility(titles.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText("기기에서 영상을 찾지 못했습니다.\n위의 'URL/스트리밍 열기' 를 쓰세요.");
    }

    private void askUrl() {
        final EditText in = new EditText(this);
        in.setHint("http(s)://... .mp4 / .m3u8 / .mpd / rtsp://...");
        new AlertDialog.Builder(this)
                .setTitle("스트리밍 주소 열기")
                .setView(in)
                .setPositiveButton("재생", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) play(Uri.parse(u), u);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void play(Uri uri, String title) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(uri);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        startActivity(i);
    }
}
