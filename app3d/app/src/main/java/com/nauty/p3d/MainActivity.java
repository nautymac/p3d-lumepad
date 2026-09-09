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

import com.nauty.p3d.net.Dlna;
import com.nauty.p3d.net.SmbBrowser;
import com.nauty.p3d.net.SmbCredentials;
import com.nauty.p3d.net.SmbUri;

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
        btnMode = addButton(row, getString(R.string.mode_photos), new View.OnClickListener() {
            @Override public void onClick(View v) {
                kind = (kind == MediaLibrary.Kind.VIDEO)
                        ? MediaLibrary.Kind.IMAGE : MediaLibrary.Kind.VIDEO;
                openFolder = null;
                reload();
            }
        });
        addButton(row, getString(R.string.open_url), new View.OnClickListener() {
            @Override public void onClick(View v) { chooseNetworkSource(); }
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
        empty.setText(R.string.searching);
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
                        getString(on ? R.string.folder_pinned : R.string.folder_unpinned,
                                MediaLibrary.shortPath(MainActivity.this, path)),
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
        btnMode.setText(photo ? R.string.mode_videos : R.string.mode_photos);

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

        crumb.setText(photo ? R.string.crumb_photo_folders : R.string.crumb_video_folders);
        List<String> display = new ArrayList<>();
        for (MediaLibrary.Folder f : folders) display.add(f.display(this));
        show(display, getString(photo ? R.string.empty_photos : R.string.empty_videos));
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
            display.add(f == null ? it.name : it.name + "   [" + f.label(this) + "]");
        }
        crumb.setText(getString(R.string.crumb_in_folder,
                MediaLibrary.shortPath(this, openFolder), items.size()));
        show(display, getString(R.string.empty_folder));
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
                                                getString(R.string.scanned_new, paths.length),
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

    /**
     * "URL/스트리밍 열기" 를 DLNA·SMB 까지 넓힌다 (HANDOFF 1-② 참고).
     * URL 을 직접 넣는 기존 방식과, IP/포트만 넣으면 되는 DLNA·SMB 탐색을 고르게 한다.
     */
    private void chooseNetworkSource() {
        final String[] items = {
                getString(R.string.net_url), getString(R.string.net_dlna), getString(R.string.net_smb)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_url)
                .setItems(items, (d, which) -> {
                    if (which == 0) askUrl();
                    else if (which == 1) askDlnaHost();
                    else askSmbHost();
                })
                .show();
    }

    private void askUrl() {
        final EditText in = new EditText(this);
        in.setHint(R.string.url_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_url_title)
                .setView(in)
                .setPositiveButton(R.string.action_play, (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) open(Uri.parse(u), u);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------ DLNA
    //
    // 사용자가 IP/포트를 직접 넣으므로 SSDP 멀티캐스트 탐색은 건너뛴다. description.xml
    // 을 읽어 ContentDirectory 의 controlURL 을 찾고, Browse 로 목록을 받아 재생 URL
    // (평범한 http 주소)을 그대로 연다 — 재생 엔진은 손댈 것이 없다.

    private void askDlnaHost() {
        final EditText in = new EditText(this);
        in.setHint(R.string.dlna_host_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.net_dlna)
                .setMessage(R.string.dlna_common_ports)
                .setView(in)
                .setPositiveButton(R.string.action_next, (d, w) -> {
                    String hp = in.getText().toString().trim();
                    if (hp.isEmpty()) return;
                    String host = hp;
                    int port = 8200;                    // MiniDLNA 기본값
                    int c = hp.lastIndexOf(':');
                    if (c > 0) {
                        host = hp.substring(0, c);
                        try { port = Integer.parseInt(hp.substring(c + 1)); }
                        catch (NumberFormatException ignored) { }
                    }
                    startDlnaBrowse(host, port);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void startDlnaBrowse(final String host, final int port) {
        Toast.makeText(this, R.string.dlna_connecting, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String controlUrl = Dlna.findControlUrl(host, port);
                    dlnaBrowseTo(controlUrl, "0", getString(R.string.net_dlna));
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { dlnaFailed(e); }
                    });
                }
            }
        }, "dlna-connect").start();
    }

    /** id 아래 목록을 받아 대화상자로 보여준다. 폴더를 고르면 재귀적으로 더 들어간다. */
    private void dlnaBrowseTo(final String controlUrl, final String objectId, final String title) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Dlna.Item> items = Dlna.browse(controlUrl, objectId);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDlnaItems(controlUrl, title, items); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { dlnaFailed(e); }
                    });
                }
            }
        }, "dlna-browse").start();
    }

    private void dlnaFailed(Exception e) {
        Toast.makeText(this, getString(R.string.dlna_failed, String.valueOf(e.getMessage())),
                Toast.LENGTH_LONG).show();
    }

    private void showDlnaItems(final String controlUrl, String title, final List<Dlna.Item> items) {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.dlna_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Dlna.Item it = items.get(i);
            names[i] = it.container ? "📁 " + it.title : it.title;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(names, (d, which) -> {
                    Dlna.Item it = items.get(which);
                    if (it.container) dlnaBrowseTo(controlUrl, it.id, it.title);
                    else open(Uri.parse(it.url), it.title);
                })
                .show();
    }

    // ------------------------------------------------------------ SMB
    //
    // DataSource 는 SmbDataSource(엔진 쪽) 가 맡고, 여기서는 목록만 훑어 재생할 파일을
    // 고르게 한다. 계정 정보는 즐겨찾기와 같은 방식으로 SharedPreferences 에 남긴다.

    private void askSmbHost() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, dp(8));

        final EditText host = addField(form, R.string.smb_host_hint, false);
        final EditText port = addField(form, R.string.smb_port_hint, false);
        port.setText("445");
        final EditText share = addField(form, R.string.smb_share_hint, false);
        final EditText user = addField(form, R.string.smb_user_hint, false);
        final EditText pass = addField(form, R.string.smb_pass_hint, true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.net_smb)
                .setView(form)
                .setPositiveButton(R.string.action_next, (d, w) -> {
                    String h = host.getText().toString().trim();
                    if (h.isEmpty()) return;
                    int p;
                    try { p = Integer.parseInt(port.getText().toString().trim()); }
                    catch (NumberFormatException e) { p = 445; }
                    String sh = share.getText().toString().trim();
                    String u = user.getText().toString().trim();
                    String pw = pass.getText().toString();
                    if (!u.isEmpty()) SmbCredentials.save(this, h, sh, u, pw, null);
                    startSmbBrowse(h, p, sh, u, pw, "");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private EditText addField(LinearLayout parent, int hintRes, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hintRes);
        if (password) {
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(e);
        return e;
    }

    private void startSmbBrowse(final String host, final int port, final String share,
                                 final String user, final String pass, final String path) {
        Toast.makeText(this, R.string.smb_connecting, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<SmbBrowser.Entry> entries =
                            SmbBrowser.list(host, port, share, user, pass, path);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showSmbItems(host, port, share, user, pass, path, entries);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.smb_failed, String.valueOf(e.getMessage())),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "smb-browse").start();
    }

    private void showSmbItems(final String host, final int port, final String share,
                               final String user, final String pass, final String path,
                               final List<SmbBrowser.Entry> entries) {
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.smb_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            SmbBrowser.Entry e = entries.get(i);
            names[i] = e.directory ? "📁 " + e.name : e.name;
        }
        new AlertDialog.Builder(this)
                .setTitle(path.isEmpty() ? share : path)
                .setItems(names, (d, which) -> {
                    SmbBrowser.Entry e = entries.get(which);
                    String childPath = path.isEmpty() ? e.name : path + "\\" + e.name;
                    if (e.directory) {
                        startSmbBrowse(host, port, share, user, pass, childPath);
                    } else {
                        Uri uri = SmbUri.build(host, port, share, childPath, user, pass);
                        open(uri, e.name);
                    }
                })
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
