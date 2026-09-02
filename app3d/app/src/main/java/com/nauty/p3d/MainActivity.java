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

    /**
     * 돌아올 때마다 다시 읽는다. 다른 앱으로 영상을 받아온 직후에도 목록에 나와야 한다.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        loadVideos();
        scanUnindexed();
    }

    /**
     * MediaStore 가 모르는 영상 파일을 찾아 미디어 스캐너에 넘긴다.
     *
     * 목록은 MediaStore 를 조회해서 만드는데, 파일을 만든 앱이 스캔을 요청하지 않으면
     * 그 파일은 색인되지 않아 목록에 나오지 않는다. 실제로 Download/Seal/ 의 두 mkv 중
     * 하나만 색인돼 있었다 — 받아온 영상이 안 보이던 이유가 이것이다.
     *
     * 색인만 시켜주면 그 뒤로는 평소 경로(content://)로 열리므로, 자막 탐색이나
     * 이어보기 키 같은 나머지 동작은 손댈 필요가 없다.
     */
    private void scanUnindexed() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> missing = new ArrayList<>();
                try {
                    java.util.Set<String> known = new java.util.HashSet<>();
                    Cursor c = getContentResolver().query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.Video.Media.DATA}, null, null, null);
                    if (c != null) {
                        while (c.moveToNext()) {
                            String d = c.getString(0);
                            if (d != null) known.add(d);
                        }
                        c.close();
                    }
                    collect(android.os.Environment.getExternalStorageDirectory(),
                            0, known, missing);
                } catch (Throwable t) {
                    return;   // 색인은 보조 기능이다. 실패해도 조용히 넘어간다
                }
                if (missing.isEmpty()) return;

                final String[] paths = missing.toArray(new String[0]);
                final int[] done = {0};
                android.media.MediaScannerConnection.scanFile(
                        MainActivity.this, paths, null,
                        new android.media.MediaScannerConnection.OnScanCompletedListener() {
                            @Override public void onScanCompleted(String path, Uri uri) {
                                synchronized (done) {
                                    if (++done[0] < paths.length) return;
                                }
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        loadVideos();
                                        Toast.makeText(MainActivity.this,
                                                "새 영상 " + paths.length + "개를 목록에 넣었습니다",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
            }
        }).start();
    }

    /** 영상 확장자를 가진 파일 중 MediaStore 에 없는 것을 모은다. */
    private void collect(java.io.File dir, int depth,
                         java.util.Set<String> known, List<String> out) {
        if (dir == null || depth > 5 || out.size() > 500) return;
        // Android/ 밑은 앱 전용 데이터라 볼 이유가 없고, .nomedia 는 사용자가 숨긴 것이다.
        if (depth > 0 && ("Android".equals(dir.getName())
                || new java.io.File(dir, ".nomedia").exists())) return;

        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) {
                collect(f, depth + 1, known, out);
            } else if (isVideo(f.getName()) && !known.contains(f.getAbsolutePath())) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".webm",
            ".wmv", ".flv", ".m4v", ".mpg", ".mpeg", ".3gp", ".divx", ".rmvb", ".vob"
    };

    private static boolean isVideo(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        for (String e : VIDEO_EXT) if (n.endsWith(e)) return true;
        return false;
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
