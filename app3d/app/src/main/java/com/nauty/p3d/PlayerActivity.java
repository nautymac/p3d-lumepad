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
    /** 재생 엔진 강제 지정 ("EXO" | "VLC"). 지정하면 저장된 선택을 덮어쓴다. */
    public static final String EXTRA_ENGINE = "engine";

    private static final String PREFS      = "p3d";
    private static final String KEY_FORMAT = "fmt:";
    private static final String KEY_ENGINE    = "engine";
    private static final String KEY_SUB_SCALE = "sub_scale";
    private static final String KEY_SUB_Y     = "sub_y";
    private static final String KEY_SUB_DEPTH = "sub_depth";

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
    private Button btnSource, btnOutput, btnSwap, btnEngine, btnSubtitle;
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
        root.addView(buildBottomBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        settingsPanel = buildSettingsPanel();
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        glView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (settingsPanel.getVisibility() == View.VISIBLE) {
                    settingsPanel.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(
                            bottomBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }
        });

        setContentView(root);

        String forced = getIntent().getStringExtra(EXTRA_ENGINE);
        if (forced != null) {
            try {
                VideoEngine.Kind k = VideoEngine.Kind.valueOf(forced.toUpperCase(Locale.US));
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString(KEY_ENGINE, k.name()).apply();
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
        glView.setSubtitleDepth(sp.getInt(KEY_SUB_DEPTH, 14));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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

    private void backToList() {
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
        p.addView(slider(60, sp.getInt(KEY_SUB_DEPTH, 14), new OnValue() {
            @Override public void set(int v) {
                glView.setSubtitleDepth(v);
                sp.edit().putInt(KEY_SUB_DEPTH, v).apply();
            }
        }));

        p.addView(header("재생 엔진"));
        btnEngine = panelButton(p, "엔진", new View.OnClickListener() {
            @Override public void onClick(View v) { switchEngine(); }
        });

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

        if (!seeking && dur > 0) {
            seekBar.setMax((int) (dur / 1000));
            seekBar.setProgress((int) (pos / 1000));
            timeText.setText(fmt(pos) + " / " + fmt(dur));
        }

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
    private void refreshLabels() {
        btnPlay.setText(engine != null && engine.isPlaying() ? "❚❚" : "▶");
        if (btnSource == null || btnEngine == null) return;

        btnSource.setText("소스: " + glView.getSourceFormat().label);

        String out;
        switch (glView.getOutput()) {
            case THREE_D: out = "3D 출력";   break;
            case TWO_D:   out = "2D 출력";   break;
            default:      out = "SBS 확인"; break;
        }
        btnOutput.setText("출력: " + out);
        btnSwap.setText(glView.isSwapLR() ? "좌우반전 ON" : "좌우반전 OFF");
        btnEngine.setText("엔진: " + currentKind().label);
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

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // -------------------------------------------------------------- 엔진

    private VideoEngine.Kind currentKind() {
        if (engine != null) return engine.kind();
        String v = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ENGINE, VideoEngine.Kind.EXO.name());
        try {
            return VideoEngine.Kind.valueOf(v);
        } catch (IllegalArgumentException e) {
            return VideoEngine.Kind.EXO;
        }
    }

    private void switchEngine() {
        VideoEngine.Kind next = currentKind() == VideoEngine.Kind.EXO
                ? VideoEngine.Kind.VLC : VideoEngine.Kind.EXO;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_ENGINE, next.name()).apply();

        long pos = engine == null ? 0 : engine.getPosition();
        if (engine != null) { engine.release(); engine = null; }

        startPlayback();
        if (engine != null && pos > 0) engine.seekTo(pos);
        lastCueText = null;

        Toast.makeText(this, next.label + " 로 전환", Toast.LENGTH_SHORT).show();
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

        engine = currentKind() == VideoEngine.Kind.VLC ? new VlcEngine() : new ExoEngine();
        try {
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            engine.play();
        } catch (Throwable t) {
            Toast.makeText(this, engine.kind().label + " 시작 실패 → ExoPlayer 로 대체",
                    Toast.LENGTH_LONG).show();
            try { engine.release(); } catch (Throwable ignored) { }
            engine = new ExoEngine();
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            engine.play();
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_ENGINE, VideoEngine.Kind.EXO.name()).apply();
        }
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        refreshLabels();
    }

    // --------------------------------------------------- VideoEngine.Listener

    @Override
    public void onVideoSize(final int width, final int height) {
        ui.post(new Runnable() {
            @Override public void run() {
                glView.setVideoSize(width, height);
                if (!manualChoice && !detected) {
                    SourceFormat byAspect = SourceFormat.fromAspect(width, height);
                    if (byAspect != null) applySourceFormat(byAspect);
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
                        "이 기기에 없는 오디오 코덱입니다 (DTS 등).\n이 파일은 소리 없이 재생됩니다.",
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
        if (engine != null) { engine.release(); engine = null; }
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
