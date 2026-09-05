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

/**
 * 목록 화면. 폴더 -> 파일 두 단계다.
 *
 * 한 줄로 늘어놓던 것을 폴더로 나눈 이유는 단순하다. 사진이 1883장이고 연도 폴더로
 * 정리돼 있는데 평평한 목록으로는 찾을 수가 없었다. 영상도 같은 방식으로 묶는다.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    private final List<String> titles = new ArrayList<>();
    private final List<Uri>    uris   = new ArrayList<>();
    private List<MediaLibrary.Folder> folders = new ArrayList<>();

    private ListView list;
    private TextView empty, crumb;
    private Button   btnMode;

    /** 영상 목록이냐 사진 목록이냐. 목록만 갈릴 뿐 여는 화면은 같다. */
    private MediaLibrary.Kind kind = MediaLibrary.Kind.VIDEO;

    /** null 이면 폴더 목록, 아니면 그 폴더의 파일 목록. */
    private String openFolder = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnMode = addButton(row, "사진 보기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                kind = (kind == MediaLibrary.Kind.VIDEO)
                        ? MediaLibrary.Kind.IMAGE : MediaLibrary.Kind.VIDEO;
                openFolder = null;
                reload();
            }
        });
        addButton(row, "URL/스트리밍 열기", new View.OnClickListener() {
            @Override public void onClick(View v) { askUrl(); }
        });
        addButton(row, "3D 컨트롤 센터", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Fv3dControlActivity.class));
            }
        });
        root.addView(row);

        crumb = new TextView(this);
        crumb.setTextColor(Color.parseColor("#00D8FF"));
        crumb.setTextSize(13f);
        crumb.setPadding(0, dp(8), 0, dp(4));
        root.addView(crumb);

        empty = new TextView(this);
        empty.setTextColor(Color.GRAY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 48, 0, 48);
        empty.setText("찾는 중...");
        root.addView(empty);

        list = new ListView(this);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (openFolder == null) {
                    if (pos >= 0 && pos < folders.size()) {
                        openFolder = folders.get(pos).path;
                        reload();
                    }
                } else if (pos >= 0 && pos < uris.size()) {
                    open(uris.get(pos), titles.get(pos));
                }
            }
        });
        // 자주 여는 폴더는 위로 고정한다. 길게 눌러 켜고 끈다.
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (openFolder != null || pos < 0 || pos >= folders.size()) return false;
                String path = folders.get(pos).path;
                boolean on = MediaLibrary.toggleFavorite(MainActivity.this, path);
                Toast.makeText(MainActivity.this,
                        MediaLibrary.shortPath(path) + (on ? " 고정" : " 고정 해제"),
                        Toast.LENGTH_SHORT).show();
                reload();
                return true;
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
            reload();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) reload();
    }

    /** 폴더 목록에서 뒤로 = 앱 종료, 파일 목록에서 뒤로 = 폴더 목록. */
    @Override
    public void onBackPressed() {
        if (openFolder != null) {
            openFolder = null;
            reload();
            return;
        }
        super.onBackPressed();
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
        reload();
        scanUnindexed();
    }

    private void reload() {
        boolean photo = (kind == MediaLibrary.Kind.IMAGE);
        btnMode.setText(photo ? "영상 보기" : "사진 보기");

        if (openFolder == null) loadFolders(photo);
        else                    loadFiles(photo);
    }

    private void loadFolders(boolean photo) {
        folders = MediaLibrary.folders(this, kind);
        titles.clear();
        uris.clear();

        // 폴더가 하나뿐이면 한 단계를 아낄 이유가 있다. 눌러 들어갈 곳이 하나뿐이다.
        if (folders.size() == 1) {
            openFolder = folders.get(0).path;
            loadFiles(photo);
            return;
        }

        crumb.setText((photo ? "사진" : "영상") + " 폴더  ·  길게 눌러 위로 고정");
        List<String> display = new ArrayList<>();
        for (MediaLibrary.Folder f : folders) display.add(f.display());
        show(display, photo ? "기기에서 사진을 찾지 못했습니다."
                            : "기기에서 영상을 찾지 못했습니다.\n위의 URL/스트리밍 열기 를 쓰세요.");
    }

    private void loadFiles(boolean photo) {
        titles.clear();
        uris.clear();
        List<MediaLibrary.Item> items = MediaLibrary.list(this, kind, openFolder);
        List<String> display = new ArrayList<>();
        for (MediaLibrary.Item it : items) {
            titles.add(it.name);
            uris.add(it.uri);
            // 영상은 이름으로 3D 를 추측해 같이 보여준다. 사진은 그럴 필요가 없다 —
            // 원본 해상도를 정확히 읽을 수 있어서 뷰어가 열면서 바로 판별한다.
            SourceFormat f = photo ? null : SourceFormat.fromName(it.name);
            display.add(f == null ? it.name : it.name + "   [" + f.label + "]");
        }
        crumb.setText("◀ " + MediaLibrary.shortPath(openFolder)
                + "   (" + items.size() + ")");
        show(display, "이 폴더가 비었습니다.");
    }

    /**
     * MediaStore 가 모르는 영상·사진 파일을 찾아 미디어 스캐너에 넘긴다.
     *
     * 목록은 MediaStore 를 조회해서 만드는데, 파일을 만든 앱이 스캔을 요청하지 않으면
     * 그 파일은 색인되지 않아 목록에 나오지 않는다. 실제로 Download/Seal/ 의 두 mkv 중
     * 하나만 색인돼 있었다 — 받아온 영상이 안 보이던 이유가 이것이다. adb 로 밀어 넣은
     * 스크린샷 폴더도 같은 이유로 통째로 안 보일 수 있다.
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
                    collectKnown(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Video.Media.DATA, known);
                    collectKnown(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Images.Media.DATA, known);
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
                                        reload();
                                        Toast.makeText(MainActivity.this,
                                                "새 파일 " + paths.length + "개를 목록에 넣었습니다",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
            }
        }).start();
    }

    private void collectKnown(Uri content, String dataCol, java.util.Set<String> out) {
        Cursor c = getContentResolver().query(content, new String[]{dataCol}, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String d = c.getString(0);
                if (d != null) out.add(d);
            }
        } finally { c.close(); }
    }

    /** 영상·사진 확장자를 가진 파일 중 MediaStore 에 없는 것을 모은다. */
    private void collect(java.io.File dir, int depth,
                         java.util.Set<String> known, List<String> out) {
        // 스크린샷 폴더가 1883장이라 상한을 넉넉히 잡는다. 한 번 색인되면 다시 걸리지 않는다.
        if (dir == null || depth > 6 || out.size() > 4000) return;
        // Android/ 밑은 앱 전용 데이터라 볼 이유가 없고, .nomedia 는 사용자가 숨긴 것이다.
        if (depth > 0 && ("Android".equals(dir.getName())
                || new java.io.File(dir, ".nomedia").exists())) return;

        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) {
                collect(f, depth + 1, known, out);
            } else if (!known.contains(f.getAbsolutePath())
                    && (MediaLibrary.isVideoName(f.getName())
                        || MediaLibrary.isPhotoName(f.getName()))) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void show(List<String> display, String emptyText) {
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, display));
        empty.setVisibility(display.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(emptyText);
    }

    private void askUrl() {
        final EditText in = new EditText(this);
        in.setHint("http(s)://... .mp4 / .m3u8 / .mpd / rtsp://...");
        new AlertDialog.Builder(this)
                .setTitle("스트리밍 주소 열기")
                .setView(in)
                .setPositiveButton("재생", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) open(Uri.parse(u), u);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void open(Uri uri, String title) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(uri);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        if (kind == MediaLibrary.Kind.IMAGE) {
            i.putExtra(PlayerActivity.EXTRA_PHOTO, true);
            // 이전/다음은 지금 보고 있는 폴더 안에서만 돈다.
            i.putExtra(PlayerActivity.EXTRA_FOLDER, openFolder);
        }
        startActivity(i);
    }
}
