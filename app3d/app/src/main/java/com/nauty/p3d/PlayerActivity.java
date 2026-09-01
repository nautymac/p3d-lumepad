package com.nauty.p3d;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nauty.p3d.engine.ExoEngine;
import com.nauty.p3d.engine.VideoEngine;
import com.nauty.p3d.engine.VlcEngine;
import com.nauty.p3d.gl.Stereo3DView;
import com.nauty.p3d.subtitle.SubtitleBitmap;
import com.nauty.p3d.subtitle.Subtitles;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity
        implements Stereo3DView.Callback, VideoEngine.Listener {

    public static final String EXTRA_TITLE = "title";
    /** 재생 엔진 강제 지정 ("EXO" | "VLC"). 이번 재생에만 적용되고 저장되지 않는다. */
    public static final String EXTRA_ENGINE = "engine";

    private static final String TAG        = "P3D";
    private static final String PREFS      = "p3d";
    private static final String KEY_FORMAT = "fmt:";
    private static final String KEY_ENGINE    = "engine";
    private static final String KEY_SUB_SCALE = "sub_scale";
    private static final String KEY_SUB_Y     = "sub_y";
    private static final String KEY_SUB_DEPTH = "sub_depth";
    private static final String KEY_POS       = "pos:";
    private static final String KEY_ASPECT    = "aspect";

    /** 이번 재생에만 적용되는 엔진 지정 (인텐트 엑스트라). 저장하지 않는다. */
    private VideoEngine.Kind forcedKind = null;
    /**
     * 이보다 가로가 크면 소프트웨어 디코딩으로는 실시간을 못 맞춘다.
     * 이 경우 libVLC 에 디코딩 품질을 깎아서라도 속도를 내라고 알려준다.
     */
    private static final int HEAVY_SOURCE_WIDTH = 2560;

    /** 끝에서 이 시간 안쪽이면 "다 봤다" 로 보고 이어보기를 하지 않는다. */
    private static final long END_MARGIN_MS  = 30_000;
    /** 이보다 앞이면 저장할 가치가 없다. */
    private static final long MIN_SAVE_MS    = 15_000;
    private static final long SAVE_EVERY_MS  = 5_000;

    /** 재생이 시작되고 길이가 확정되면 이 지점으로 이동한다. 0 이면 없음. */
    private long pendingResumeMs = 0;
    private long lastSavedAt     = 0;

    /** 사용자가 직접 고르거나 이전에 고른 값을 불러온 경우. 자동 판별보다 우선한다. */
    private boolean manualChoice = false;
    /** 픽셀 판별이 결과를 적용했는지. 해상도 기반 보정이 덮어쓰지 않게 한다. */
    private boolean detected = false;
    private String  mediaKey;

    private Stereo3DView glView;
    private VideoEngine  engine;
    private Surface        videoSurface;
    private SurfaceTexture videoSurfaceTexture;
    private Uri pendingUri;
    private File videoFile;

    // 재생바
    private LinearLayout bottomBar;
    private Button btnPlay, btnList, btnSettings;
    private SeekBar seekBar;
    private TextView timeText;

    // 설정 패널
    private View settingsPanel;
    private Button btnSource, btnOutput, btnSwap, btnSubtitle, btnAspect, btnEngine;
    private TextView statusText, subtitleName;

    // 자막
    private Subtitles.Track subtitleTrack;
    private String lastCueText = null;
    private float subtitleScale = 1.0f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seeking = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        pendingUri = getIntent().getData();
        if (pendingUri == null) {
            Toast.makeText(this, "재생할 영상이 지정되지 않았습니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new Stereo3DView(this);
        glView.setCallback(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 재생바는 아래, 설정 패널은 오른쪽 — 서로 겹치지 않는다.
        // 둘 다 네비게이션 바 높이만큼 띄운다 (navBarHeight() 주석 참고).
        int navH = navBarHeight();

        View bar = buildBottomBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        barLp.bottomMargin = navH;
        root.addView(bar, barLp);

        settingsPanel = buildSettingsPanel();
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.setPadding(0, 0, 0, navH);
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        glView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideSystemUi();      // 네비게이션 바가 올라와 있으면 다시 내린다
                if (settingsPanel.getVisibility() == View.VISIBLE) {
                    settingsPanel.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(
                            bottomBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }
        });

        setContentView(root);

        // 디버그용 엔진 지정. 이번 재생에만 적용하고 저장하지는 않는다.
        // 저장하면 테스트로 한 번 건 값이 그 뒤 모든 재생에 따라붙는다 (실제로 겪었다).
        String forced = getIntent().getStringExtra(EXTRA_ENGINE);
        if (forced != null) {
            try {
                forcedKind = VideoEngine.Kind.valueOf(forced.toUpperCase(Locale.US));
            } catch (IllegalArgumentException ignored) { }
        }

        String name = getIntent().getStringExtra(EXTRA_TITLE);
        if (name == null) name = pendingUri.getLastPathSegment();
        mediaKey = name == null ? "" : name;

        SourceFormat saved = loadSavedFormat(mediaKey);
        if (saved != null) {
            // 전에 사용자가 직접 고른 값. 이건 무엇보다 우선한다.
            manualChoice = true;
            applySourceFormat(saved);
        } else {
            // 파일명은 참고만 한다 — 틀리게 붙어 있는 경우가 흔하다.
            // 판별이 끝날 때까지의 임시값으로만 쓰고, 결과가 나오면 픽셀 판별로 덮어쓴다.
            SourceFormat byName = SourceFormat.fromName(mediaKey);
            applySourceFormat(byName != null ? byName : SourceFormat.MONO_2D);
            startStereoDetection();
        }

        applySavedSubtitlePrefs();
        videoFile = resolveVideoFile(pendingUri);
        autoLoadSubtitle();
        refreshLabels();
    }

    /**
     * 저장된 자막 설정을 적용한다.
     * SeekBar 는 setProgress 시점에 리스너가 없어서 콜백이 안 오고,
     * 슬라이더 생성 도중엔 아직 만들어지지 않은 버튼이 있어 refreshLabels 도 못 부른다.
     * 그래서 화면 구성이 끝난 뒤 여기서 한 번에 적용한다.
     */
    private void applySavedSubtitlePrefs() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        subtitleScale = Math.max(0.4f, sp.getInt(KEY_SUB_SCALE, 100) / 100f);
        glView.setSubtitleY(sp.getInt(KEY_SUB_Y, 4) / 100f);
        glView.setSubtitleDepth(sp.getInt(KEY_SUB_DEPTH, 0));
        glView.setAspectOverride(sp.getFloat(KEY_ASPECT, 0f));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- 이어보기

    /** 이 파일을 마지막으로 본 지점. 없으면 0. */
    private long loadResumeMs() {
        if (mediaKey == null || mediaKey.isEmpty()) return 0;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_POS + mediaKey, 0);
    }

    /**
     * 재생 위치를 주기적으로 저장한다. 매 틱(150ms)마다 쓰면 낭비라 5초 간격으로만 기록한다.
     * 끝까지 본 파일은 기록을 지워서 다음에 처음부터 시작하게 한다.
     */
    private void savePositionPeriodically(long pos, long dur) {
        if (dur <= 0 || seeking) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSavedAt < SAVE_EVERY_MS) return;
        lastSavedAt = now;
        writePosition(pos, dur);
    }

    private void writePosition(long pos, long dur) {
        if (mediaKey == null || mediaKey.isEmpty() || dur <= 0) return;
        android.content.SharedPreferences.Editor e =
                getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (pos < MIN_SAVE_MS || pos >= dur - END_MARGIN_MS) {
            e.remove(KEY_POS + mediaKey);      // 초반이거나 다 봤으면 기억할 것이 없다
        } else {
            e.putLong(KEY_POS + mediaKey, pos);
        }
        e.apply();
    }

    /** 앱을 벗어나거나 닫을 때는 즉시 기록한다. */
    private void savePositionNow() {
        if (engine == null) return;
        writePosition(engine.getPosition(), engine.getDuration());
    }

    /** 현재 위치에서 상대 이동. */
    private void skip(long deltaMs) {
        if (engine == null) return;
        long dur = engine.getDuration();
        long target = engine.getPosition() + deltaMs;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur - 1000) target = Math.max(0, dur - 1000);
        engine.seekTo(target);
        lastCueText = null;                     // 자막 다시 계산
        Toast.makeText(this, (deltaMs > 0 ? "▶▶ " : "◀◀ ") + fmt(target), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 재생바

    private View buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.argb(200, 0, 0, 0));
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottomBar.setClickable(true);          // 터치가 glView 로 새지 않게

        btnList = new Button(this);
        btnList.setText("≡ 목록");
        btnList.setAllCaps(false);
        btnList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToList(); }
        });
        bottomBar.addView(btnList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 빠른 이동. 짧게 = 30초, 길게 = 5분.
        addSkipButton(bottomBar, "◀◀", -30_000L, -300_000L);

        btnPlay = new Button(this);
        btnPlay.setText("❚❚");
        btnPlay.setAllCaps(false);
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engine == null) return;
                if (engine.isPlaying()) engine.pause(); else engine.play();
                refreshLabels();
            }
        });
        bottomBar.addView(btnPlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addSkipButton(bottomBar, "▶▶", 30_000L, 300_000L);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (engine != null) engine.seekTo(s.getProgress() * 1000L);
                lastCueText = null;                 // 자막 다시 계산
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        sp.rightMargin = dp(12);
        bottomBar.addView(seekBar, sp);

        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(14f);
        timeText.setText("00:00 / 00:00");
        bottomBar.addView(timeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnSettings = new Button(this);
        btnSettings.setText("⚙ 설정");
        btnSettings.setAllCaps(false);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = settingsPanel.getVisibility() != View.VISIBLE;
                if (show) {
                    // 재생바를 가리지 않도록 패널을 그 위에서 끝낸다.
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) settingsPanel.getLayoutParams();
                    lp.bottomMargin = bottomBar.getHeight();
                    settingsPanel.setLayoutParams(lp);
                }
                settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                refreshLabels();
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(12);
        bottomBar.addView(btnSettings, gp);

        return bottomBar;
    }

    /** 빠른 이동 버튼. 짧게 누르면 short, 길게 누르면 long 만큼 이동한다. */
    private void addSkipButton(LinearLayout parent, String label,
                               final long shortMs, final long longMs) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(shortMs); }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { skip(longMs); return true; }
        });
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void backToList() {
        savePositionNow();
        if (engine != null) { engine.pause(); }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    // ---------------------------------------------------------- 설정 패널

    private View buildSettingsPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.argb(230, 16, 20, 24));
        scroll.setClickable(true);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12f);
        p.addView(statusText);

        p.addView(header("3D"));
        btnSource = panelButton(p, "소스", new View.OnClickListener() {
            @Override public void onClick(View v) {
                manualChoice = true;
                applySourceFormat(glView.getSourceFormat().next());
                refreshLabels();
            }
        });
        btnOutput = panelButton(p, "출력", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Stereo3DView.Output[] cycle = {
                        Stereo3DView.Output.THREE_D,
                        Stereo3DView.Output.TWO_D,
                        Stereo3DView.Output.SBS_DEBUG};
                Stereo3DView.Output cur = glView.getOutput();
                int i = 0;
                for (int k = 0; k < cycle.length; k++) if (cycle[k] == cur) i = k;
                glView.setOutput(cycle[(i + 1) % cycle.length]);
                refreshLabels();
            }
        });
        btnSwap = panelButton(p, "좌우반전", new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.setSwapLR(!glView.isSwapLR());
                refreshLabels();
            }
        });

        // 소스를 잘못 건드리면 그 선택이 이 파일에 저장돼 이후 자동 판별이 막힌다.
        // 되돌릴 방법이 있어야 한다.
        panelButton(p, "↺ 자동 판별로 되돌리기", new View.OnClickListener() {
            @Override public void onClick(View v) { redetect(); }
        });

        p.addView(label("깊이 (2D→3D 시차 강도)"));
        p.addView(slider(300, 100, new OnValue() {
            @Override public void set(int v) { glView.setDepth(v / 100f); refreshLabels(); }
        }));

        p.addView(label("수렴점 (perOffset)"));
        p.addView(slider(300, 150, new OnValue() {
            @Override public void set(int v) {
                glView.setPerOffset((v - 150) / 150f * 0.015f);
                refreshLabels();
            }
        }));

        p.addView(header("자막"));
        subtitleName = new TextView(this);
        subtitleName.setTextColor(Color.LTGRAY);
        subtitleName.setTextSize(11f);
        p.addView(subtitleName);

        btnSubtitle = panelButton(p, "자막 선택", new View.OnClickListener() {
            @Override public void onClick(View v) { pickSubtitle(); }
        });

        // 자막 관련 값은 저장해 둔다. 매번 다시 맞추게 하면 안 된다.
        final android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        p.addView(label("자막 크기"));
        p.addView(slider(200, sp.getInt(KEY_SUB_SCALE, 100), new OnValue() {
            @Override public void set(int v) {
                subtitleScale = Math.max(0.4f, v / 100f);
                lastCueText = null;                 // 다시 그리게
                sp.edit().putInt(KEY_SUB_SCALE, v).apply();
            }
        }));

        p.addView(label("자막 위치 (아래에서 올림)"));
        p.addView(slider(40, sp.getInt(KEY_SUB_Y, 4), new OnValue() {
            @Override public void set(int v) {
                glView.setSubtitleY(v / 100f);
                sp.edit().putInt(KEY_SUB_Y, v).apply();
            }
        }));

        p.addView(label("자막 깊이 (앞으로 튀어나옴)"));
        p.addView(slider(60, sp.getInt(KEY_SUB_DEPTH, 0), new OnValue() {
            @Override public void set(int v) {
                glView.setSubtitleDepth(v);
                sp.edit().putInt(KEY_SUB_DEPTH, v).apply();
            }
        }));

        btnAspect = panelButton(p, "화면 비", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleAspect(); }
        });

        btnEngine = panelButton(p, "엔진", new View.OnClickListener() {
            @Override public void onClick(View v) { switchEngine(); }
        });

        // 엔진 선택 버튼은 두지 않는다.
        // 이 기기에는 DTS/AC3 디코더가 없어서 ExoPlayer 로는 3D 영화 대부분이 무음이다.
        // libVLC 가 자체 디코더를 들고 있으므로 그쪽만 쓰고, ExoPlayer 는
        // libVLC 초기화가 실패했을 때의 폴백으로만 남겨둔다 (startPlayback 참고).
        // 디버깅용으로 인텐트 엑스트라 --es engine EXO|VLC 는 계속 동작한다.

        scroll.addView(p);
        return scroll;
    }

    private interface OnValue { void set(int v); }

    private SeekBar slider(int max, int initial, final OnValue cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(initial);
        s.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { cb.set(p); }
        });
        return s;
    }

    private TextView header(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#00D8FF"));
        tv.setTextSize(13f);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.LTGRAY);
        tv.setTextSize(11f);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private Button panelButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    // -------------------------------------------------------------- 자막

    /** content:// / file:// 에서 실제 파일 경로를 얻는다 (자막을 옆에서 찾기 위해). */
    private File resolveVideoFile(Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                return new File(uri.getPath());
            }
            if ("content".equals(uri.getScheme())) {
                Cursor c = getContentResolver().query(
                        uri, new String[]{MediaStore.Video.Media.DATA}, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            String path = c.getString(0);
                            if (path != null) return new File(path);
                        }
                    } finally { c.close(); }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void autoLoadSubtitle() {
        File sub = Subtitles.findSibling(videoFile);
        if (sub != null) loadSubtitle(sub);
        else updateSubtitleName();
    }

    private void loadSubtitle(File f) {
        subtitleTrack = Subtitles.load(f);
        lastCueText = null;
        glView.setSubtitleBitmap(null);
        if (subtitleTrack == null) {
            Toast.makeText(this, "자막을 읽지 못했습니다: " + f.getName(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "자막 " + subtitleTrack.name
                    + " (" + subtitleTrack.size() + "개)", Toast.LENGTH_SHORT).show();
        }
        updateSubtitleName();
    }

    private void updateSubtitleName() {
        if (subtitleName == null) return;
        subtitleName.setText(subtitleTrack == null ? "자막 없음" : subtitleTrack.name);
    }

    /** 영상 폴더 + 흔한 폴더에서 자막 파일을 모아 고르게 한다. */
    private void pickSubtitle() {
        final List<File> found = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        if (videoFile != null && videoFile.getParentFile() != null) dirs.add(videoFile.getParentFile());
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Subtitles"));

        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && Subtitles.isSubtitle(f.getName().toLowerCase(Locale.US))
                        && !found.contains(f)) {
                    found.add(f);
                }
            }
        }

        final String[] items = new String[found.size() + 1];
        items[0] = "자막 없음";
        for (int i = 0; i < found.size(); i++) items[i + 1] = found.get(i).getName();

        if (found.isEmpty()) {
            Toast.makeText(this,
                    "자막 파일을 찾지 못했습니다.\n영상과 같은 폴더나 Movies/Download 에 .srt/.smi 를 두세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("자막 선택")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        subtitleTrack = null;
                        lastCueText = null;
                        glView.setSubtitleBitmap(null);
                        updateSubtitleName();
                    } else {
                        loadSubtitle(found.get(which - 1));
                    }
                })
                .show();
    }

    // -------------------------------------------------------------- 갱신

    private void tick() {
        if (engine == null) return;

        long pos = engine.getPosition();
        long dur = engine.getDuration();

        // 이어보기: 길이가 확정된 뒤에야 이동할 수 있다.
        // libVLC 의 seek 는 내부적으로 setPosition(비율) 이라 길이를 모르면 무시된다.
        if (pendingResumeMs > 0 && dur > 0) {
            long target = pendingResumeMs;
            pendingResumeMs = 0;
            if (target < dur - END_MARGIN_MS) {
                engine.seekTo(target);
                lastCueText = null;                    // 자막 다시 계산
                Toast.makeText(this, "이어보기 " + fmt(target), Toast.LENGTH_SHORT).show();
            }
        }

        if (!seeking && dur > 0) {
            seekBar.setMax((int) (dur / 1000));
            seekBar.setProgress((int) (pos / 1000));
            timeText.setText(fmt(pos) + " / " + fmt(dur));
        }

        savePositionPeriodically(pos, dur);

        // 자막
        String cue = subtitleTrack == null ? null : subtitleTrack.textAt(pos);
        if (cue == null ? lastCueText != null : !cue.equals(lastCueText)) {
            lastCueText = cue;
            Bitmap bmp = cue == null ? null : SubtitleBitmap.render(
                    cue, glView.getWidth(), glView.getHeight(), subtitleScale);
            glView.setSubtitleBitmap(bmp);
        }
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%02d:%02d", m, s);
    }

    @SuppressLint("SetTextI18n")
    /**
     * 화면 비 선택지. 0 = 소스 해상도가 시키는 대로 (기본).
     *
     * 소스 비율 정보가 틀렸거나 (SBS 를 half/full 로 잘못 잡은 경우 등) 위아래 검은 띠가
     * 싫을 때 쓰라고 둔다. 자동 판별이 맞으면 손댈 일이 없다.
     */
    private static final float[] ASPECTS = {
            0f, 16f / 9f, 2.40f, 1.85f, 4f / 3f, Stereo3DView.ASPECT_FILL
    };

    private static String aspectLabel(float a) {
        if (a == 0f)                        return "자동";
        if (a == Stereo3DView.ASPECT_FILL)  return "꽉 채우기";
        if (Math.abs(a - 16f / 9f)  < 0.01f) return "16:9";
        if (Math.abs(a - 2.40f)     < 0.01f) return "2.40:1";
        if (Math.abs(a - 1.85f)     < 0.01f) return "1.85:1";
        if (Math.abs(a - 4f / 3f)   < 0.01f) return "4:3";
        return String.format(Locale.US, "%.2f:1", a);
    }

    private void cycleAspect() {
        float cur = glView.getAspectOverride();
        int i = 0;
        for (int k = 0; k < ASPECTS.length; k++) {
            if (ASPECTS[k] == cur) { i = k; break; }
        }
        float next = ASPECTS[(i + 1) % ASPECTS.length];
        glView.setAspectOverride(next);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(KEY_ASPECT, next).apply();
        refreshLabels();
    }

    private void refreshLabels() {
        btnPlay.setText(engine != null && engine.isPlaying() ? "❚❚" : "▶");
        if (btnSource == null || btnSwap == null) return;   // 패널 구성 전이면 건너뛴다

        btnSource.setText("소스: " + glView.getSourceFormat().label);

        String out;
        switch (glView.getOutput()) {
            case THREE_D: out = "3D 출력";   break;
            case TWO_D:   out = "2D 출력";   break;
            default:      out = "SBS 확인"; break;
        }
        btnOutput.setText("출력: " + out);
        btnSwap.setText(glView.isSwapLR() ? "좌우반전 ON" : "좌우반전 OFF");
        if (btnAspect != null) btnAspect.setText("화면 비: " + aspectLabel(glView.getAspectOverride()));
        if (btnEngine != null) btnEngine.setText("엔진: " + currentKind().label);
        updateSubtitleName();

        // 지금 소스 포맷이 어디서 왔는지 보여준다. 수동으로 잘못 고른 상태를 알아채야 하기 때문.
        String how = manualChoice ? "수동 선택 (저장됨)"
                                  : (detected ? "자동 판별" : "판별 중…");

        statusText.setText(String.format(Locale.US,
                "%s · %s · %s\n소스: %s\n깊이 %.2f · 수렴 %+.4f",
                currentKind().label, glView.getSourceFormat().label, out,
                how, glView.getDepth(), glView.getPerOffset()));
    }

    /** 이 파일에 저장된 소스 선택을 지우고 픽셀 판별을 다시 돌린다. */
    private void redetect() {
        if (mediaKey != null && !mediaKey.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().remove(KEY_FORMAT + mediaKey).apply();
        }
        manualChoice = false;
        detected = false;
        Toast.makeText(this, "저장된 선택을 지우고 다시 판별합니다…", Toast.LENGTH_SHORT).show();
        startStereoDetection();
        refreshLabels();
    }

    /**
     * 프레임을 뜯어 스테레오 배치를 판별한다. 파일이 크면 몇 초 걸리므로 백그라운드로 돌린다.
     * 사용자가 그 사이 직접 골랐으면 결과를 버린다.
     */
    private void startStereoDetection() {
        final Uri uri = pendingUri;
        new Thread(new Runnable() {
            @Override public void run() {
                final SourceFormat f = StereoDetect.detect(PlayerActivity.this, uri);
                if (f == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        // 그 사이 사용자가 직접 골랐으면 그쪽이 우선.
                        // 파일명으로 임시 적용한 값은 여기서 덮어쓴다 (이름은 틀릴 수 있다).
                        if (isFinishing() || manualChoice) return;
                        detected = true;
                        SourceFormat before = glView.getSourceFormat();
                        glView.setSourceFormat(f);   // 자동 판별이므로 저장은 하지 않는다
                        refreshLabels();
                        if (f != before) {
                            Toast.makeText(PlayerActivity.this,
                                    "3D 자동 인식: " + f.label, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "stereo-detect").start();
    }

    private void applySourceFormat(SourceFormat f) {
        glView.setSourceFormat(f);
        if (manualChoice && mediaKey != null && !mediaKey.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_FORMAT + mediaKey, f.name()).apply();
        }
    }

    private SourceFormat loadSavedFormat(String key) {
        if (key == null || key.isEmpty()) return null;
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FORMAT + key, null);
        if (v == null) return null;
        try {
            return SourceFormat.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 시스템 바를 숨기는 것만으로는 부족하다. LAYOUT_* 플래그가 없으면 뷰가 바를 뺀
     * 영역(2560x1456)에만 배치되고, 그러면 GL 표면도 1456 이 된다.
     *
     * 그런데 패널의 렌티큘러 마스크(/sdcard/3DKanKan/matrix, 2560x1600x2 바이트)는
     * 화면 전체 높이 1600 기준으로 만들어져 있다. 1456 으로 HolographyInit 을 하면
     * 마스크가 어긋나 화면 아래쪽에서 인터레이스가 끊기고, 그 경계가 가로줄로 보인다.
     * (증상: 자막 두 줄 사이에 투명한 가로선)
     *
     * LAYOUT_HIDE_NAVIGATION 과 LAYOUT_FULLSCREEN 을 넣어 레이아웃을 바 아래까지
     * 확장해야 GL 표면이 2560x1600 이 되어 마스크와 일치한다.
     */
    /**
     * 네비게이션 바가 차지하는 높이.
     *
     * hideSystemUi() 가 LAYOUT_HIDE_NAVIGATION 을 걸어서 레이아웃이 화면 끝(1600px)까지
     * 뻗는다 — 자막 이음매를 없애려면 GL 표면이 패널 전체를 덮어야 하기 때문이다.
     * 그런데 그 상태에서 네비게이션 바가 다시 올라오면 화면 맨 아래에 있는 재생바가
     * 그 밑에 깔려 보이지도, 눌리지도 않는다. 그래서 재생바만 이만큼 띄워둔다.
     */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 몰입 모드는 포커스를 잃으면 풀린다 (다이얼로그, 알림 내리기, 앱 전환 등).
     * 그대로 두면 네비게이션 바가 올라온 채로 남는다. 돌아올 때마다 다시 건다.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // -------------------------------------------------------------- 엔진

    /** 기본은 libVLC. ExoPlayer 는 이 기기에서 DTS/AC3 를 못 재생해 쓸모가 없다. */
    /**
     * 소스 가로 해상도. 컨테이너 헤더만 읽으므로 프레임 디코딩보다 훨씬 싸다.
     * 네트워크 URL 에서는 시간이 걸릴 수 있어 로컬 스킴에서만 본다.
     */
    private int[] probeVideoSize(Uri uri) {
        String s = uri.getScheme();
        if (s != null && !"content".equals(s) && !"file".equals(s)) return null;
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            int w = metaInt(r, android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int h = metaInt(r, android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            return (w > 0 && h > 0) ? new int[]{w, h} : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static int metaInt(android.media.MediaMetadataRetriever r, int key) {
        try {
            String v = r.extractMetadata(key);
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private VideoEngine.Kind currentKind() {
        if (forcedKind != null) return forcedKind;
        if (engine != null) return engine.kind();
        // 엔진 선택은 파일별로만 저장한다. 전역으로 저장하면 한 파일 때문에 바꾼 선택이
        // 다른 모든 파일에 따라붙는다.
        VideoEngine.Kind fallback = defaultKind();
        String v = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ENGINE + mediaKey, fallback.name());
        try {
            return VideoEngine.Kind.valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 기본 엔진.
     *
     * FFmpeg 오디오 확장을 넣은 뒤로 ExoPlayer 가 기본이다. 이 기기에서 libVLC 는
     * MediaCodec 조회 중 예외를 맞아 (`Exception occurred in
     * MediaCodecInfo.getCapabilitiesForType`) 하드웨어 디코더를 못 찾고 **항상**
     * 소프트웨어로 디코딩한다. 1080p 는 그래도 되지만 3840x1080 10bit HEVC 는 21fps 로
     * 무너진다. ExoPlayer 는 같은 파일을 하드웨어로 27.6fps 에 돌리고, 예전에 무음의
     * 원인이던 AC3/DTS 는 이제 FFmpeg 확장이 디코딩한다.
     *
     * 다만 ExoPlayer 가 못 여는 프로토콜이 있다. 그건 계속 libVLC 로 연다.
     */
    private VideoEngine.Kind defaultKind() {
        String s = pendingUri == null ? null : pendingUri.getScheme();
        if (s != null) {
            s = s.toLowerCase(Locale.US);
            // ExoPlayer 로는 못 여는 것들 (rtsp 모듈 미포함, smb/ftp/mms 미지원)
            if (s.startsWith("rtsp") || s.startsWith("rtmp") || s.equals("smb")
                    || s.equals("ftp") || s.equals("mms") || s.equals("udp")) {
                return VideoEngine.Kind.VLC;
            }
        }
        return VideoEngine.Kind.EXO;
    }

    /**
     * 엔진을 바꿔서 같은 지점부터 다시 연다.
     *
     * 기본은 ExoPlayer 다 ({@link #defaultKind()} 참고). libVLC 는 ExoPlayer 가 열지 못하는
     * 컨테이너나 프로토콜을 만났을 때의 수단으로 남겨둔다.
     */
    private void switchEngine() {
        VideoEngine.Kind next = currentKind() == VideoEngine.Kind.VLC
                ? VideoEngine.Kind.EXO : VideoEngine.Kind.VLC;

        long at = engine == null ? 0 : engine.getPosition();
        if (engine != null) { engine.release(); engine = null; }

        forcedKind = null;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ENGINE + mediaKey, next.name()).apply();

        startPlayback();
        if (at > 0) pendingResumeMs = at;

        Toast.makeText(this, next == VideoEngine.Kind.EXO
                        ? "ExoPlayer — 하드웨어 디코딩 + FFmpeg 오디오"
                        : "libVLC — 이 기기에선 항상 소프트웨어 디코딩 (4K는 끊김)",
                Toast.LENGTH_LONG).show();
        refreshLabels();
    }

    @Override
    public void onSurfaceReady(Surface surface, SurfaceTexture surfaceTexture) {
        videoSurface        = surface;
        videoSurfaceTexture = surfaceTexture;
        startPlayback();
    }

    private void startPlayback() {
        if (videoSurface == null || pendingUri == null || engine != null) return;

        // 큰 소스는 소프트웨어 폴백이 걸리면 재생이 무너진다. 그때는 폴백을 막고
        // MediaCodec 만 쓰게 한다. 실패하면 onError 에서 한 번 풀고 다시 연다.
        int[] size = probeVideoSize(pendingUri);
        int vw = size == null ? 0 : size[0];
        int vh = size == null ? 0 : size[1];
        boolean heavy = vw >= HEAVY_SOURCE_WIDTH;
        if (vw > 0) {
            Log.i(TAG, "소스 " + vw + "x" + vh + (heavy ? " (무거운 소스)" : ""));
            // vout 콜백(onNewVideoLayout)이 아예 오지 않는 소스가 있다. 그때는 소스 크기를
            // 모른 채 기본값 16:9 로 배치돼 화면이 눌린다. 미리 알아낸 값으로 먼저 맞춰둔다.
            onVideoSize(vw, vh);
        }

        engine = currentKind() == VideoEngine.Kind.VLC
                ? new VlcEngine(heavy, vw, vh) : new ExoEngine();
        int vlcVerbose = getIntent().getIntExtra("vlcverbose", 0);
        if (vlcVerbose > 0 && engine instanceof VlcEngine) {
            ((VlcEngine) engine).setVerbose(vlcVerbose);
        }
        try {
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            engine.play();
        } catch (Throwable t) {
            // libVLC 가 뜨지 않으면 화면이 아예 안 나오므로 ExoPlayer 로 떨어진다.
            // 다만 이 기기에서 ExoPlayer 는 DTS/AC3 를 못 해 무음일 수 있으니 그 사실을 알린다.
            // 이 폴백은 저장하지 않는다 — 저장하면 다음부터도 계속 ExoPlayer 가 된다.
            Toast.makeText(this,
                    "libVLC 시작 실패 → ExoPlayer 로 재생합니다.\n오디오 코덱에 따라 소리가 안 날 수 있습니다.",
                    Toast.LENGTH_LONG).show();
            try { engine.release(); } catch (Throwable ignored) { }
            engine = new ExoEngine();
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            engine.play();
        }
        // 이어보기 예약. 실제 이동은 길이가 확정된 뒤 tick() 에서 한다.
        pendingResumeMs = loadResumeMs();

        ui.removeCallbacks(ticker);
        ui.post(ticker);
        refreshLabels();

        // 디버그: --ei depth N 이면 2D→3D 시차 강도를 N% 로 시작한다 (슬라이더와 같은 단위).
        // 시어 램프를 측정하려면 값을 손으로 맞추지 않고 고정할 수 있어야 한다.
        int depthPct = getIntent().getIntExtra("depth", -1);
        if (depthPct >= 0) glView.setDepth(depthPct / 100f);

        // 디버그: --ei freezems N 이면 그 지점으로 이동해 정지시킨다.
        // 마스크 반응을 측정하려면 매 캡처가 같은 프레임이어야 하기 때문.
        final int freezeMs = getIntent().getIntExtra("freezems", 0);
        if (freezeMs > 0) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (engine == null) return;
                    engine.seekTo(freezeMs);
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (engine != null) engine.pause();
                            android.util.Log.i("P3D", "디버그 정지: " + freezeMs + "ms");
                        }
                    }, 1500);
                }
            }, 2500);
        }
    }

    // --------------------------------------------------- VideoEngine.Listener

    @Override
    public void onVideoSize(final int width, final int height) {
        ui.post(new Runnable() {
            @Override public void run() {
                glView.setVideoSize(width, height);
                SourceFormat byAspect = SourceFormat.fromAspect(width, height);
                if (!manualChoice && byAspect != null) {
                    if (!detected) {
                        applySourceFormat(byAspect);
                    } else {
                        // 픽셀 판별은 배치(SBS/TB/2D)만 정하게 하고, half 냐 full 이냐는
                        // 디코더가 실제로 알려준 해상도가 정하게 한다. 판별용 썸네일은
                        // 축소돼 올 수 있어서 (3840x1080 이 1920x1080 으로) full 을
                        // half 로 잘못 잡는다. 눌린 화면으로 보이는 원인이었다.
                        SourceFormat cur = glView.getSourceFormat();
                        if (byAspect == SourceFormat.SBS_FULL && cur == SourceFormat.SBS_HALF) {
                            applySourceFormat(SourceFormat.SBS_FULL);
                        } else if (byAspect == SourceFormat.TB_FULL && cur == SourceFormat.TB_HALF) {
                            applySourceFormat(SourceFormat.TB_FULL);
                        }
                    }
                }
                refreshLabels();
            }
        });
    }

    @Override
    public void onAudioUnsupported() {
        ui.post(new Runnable() {
            @Override public void run() {
                // libVLC 로 바꿔도 안 된다. 이 기기는 DTS 를 디코딩은 해도
                // 오디오 출력단에서 막혀서 결국 무음이다. 헛된 안내를 하지 않는다.
                Toast.makeText(PlayerActivity.this,
                        "재생할 수 없는 오디오 코덱입니다.\n설정에서 libVLC 로 바꿔보세요.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(PlayerActivity.this, "재생 오류: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            return;
        }
        backToList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePositionNow();
        if (engine != null) engine.pause();
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        glView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        savePositionNow();
        if (engine != null) { engine.release(); engine = null; }
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
