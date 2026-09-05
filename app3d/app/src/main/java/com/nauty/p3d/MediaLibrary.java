package com.nauty.p3d;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기기의 영상·사진을 <b>실제 폴더</b> 단위로 다룬다.
 *
 * 왜 폴더인가. 게임 스크린샷만 1883장이 연도 폴더로 나뉘어 있어서 한 줄로 늘어놓으면
 * 찾을 수가 없다. 넘겨 보는 것도 폴더 안에서 도는 편이 낫다.
 *
 * 왜 MediaStore 의 BUCKET_DISPLAY_NAME 을 안 쓰는가. 그건 <b>맨 끝 폴더 이름</b>일 뿐이라
 * 서로 다른 위치의 같은 이름이 한 덩어리로 합쳐진다. 대신 DATA 컬럼(실제 경로)의
 * 부모 디렉터리로 묶는다 — 경로가 다르면 다른 폴더로 남는다.
 *
 * 왜 파일 경로로 바로 열지 않는가. targetSdk 24 이상에서는 file:// 를 인텐트에 실으면
 * StrictMode 가 FileUriExposedException 을 던진다. 그래서 묶는 것만 경로로 하고,
 * 여는 것은 지금까지대로 content:// 로 한다.
 */
public final class MediaLibrary {

    public enum Kind { VIDEO, IMAGE }

    private static final String PREFS    = "p3d";
    private static final String KEY_FAVS = "fav_folders";

    public static final class Item {
        public final Uri uri;
        public final String name;
        public final String folder;      // 실제 부모 경로

        Item(Uri uri, String name, String folder) {
            this.uri = uri; this.name = name; this.folder = folder;
        }
    }

    public static final class Folder {
        public final String path;
        public final int count;
        public final boolean favorite;

        Folder(String path, int count, boolean favorite) {
            this.path = path; this.count = count; this.favorite = favorite;
        }

        /** 목록에 보일 이름. 내부 저장소 앞부분은 다들 같으니 떼고 보여준다. */
        public String display(Context ctx) {
            return (favorite ? "★ " : "") + shortPath(ctx, path) + "   (" + count + ")";
        }
    }

    private MediaLibrary() {}

    // ------------------------------------------------------------- 조회

    private static Uri content(Kind k) {
        return k == Kind.VIDEO
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /**
     * 정렬.
     *
     * 사진은 이름 순이어야 한다 — 한 게임의 여러 장(_1, _2, …)이 붙어 있어야
     * 넘겨 보기 좋다. 영상은 최근에 받은 것이 위로 와야 해서 날짜 역순이다.
     */
    private static String order(Kind k) {
        return k == Kind.VIDEO
                ? MediaStore.Video.Media.DATE_ADDED + " DESC"
                : MediaStore.Images.Media.DISPLAY_NAME + " ASC";
    }

    private static String[] cols(Kind k) {
        return k == Kind.VIDEO
                ? new String[]{MediaStore.Video.Media._ID,
                               MediaStore.Video.Media.DISPLAY_NAME,
                               MediaStore.Video.Media.DATA}
                : new String[]{MediaStore.Images.Media._ID,
                               MediaStore.Images.Media.DISPLAY_NAME,
                               MediaStore.Images.Media.DATA};
    }

    /** 이 종류의 파일이 하나라도 든 폴더들. 즐겨찾기가 위로 온다. */
    public static List<Folder> folders(Context ctx, Kind kind) {
        Set<String> favs = favorites(ctx);
        Map<String, Integer> counts = new LinkedHashMap<>();

        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(content(kind), cols(kind), null, null, order(kind));
            if (c != null) {
                while (c.moveToNext()) {
                    String dir = parentOf(c.getString(2));
                    if (dir == null) continue;
                    Integer n = counts.get(dir);
                    counts.put(dir, n == null ? 1 : n + 1);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }

        List<Folder> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(new Folder(e.getKey(), e.getValue(), favs.contains(e.getKey())));
        }
        Collections.sort(out, new Comparator<Folder>() {
            @Override public int compare(Folder a, Folder b) {
                if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
                return a.path.compareToIgnoreCase(b.path);
            }
        });
        return out;
    }

    /** 한 폴더의 파일. folder 가 null 이면 전체. */
    public static List<Item> list(Context ctx, Kind kind, String folder) {
        List<Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(content(kind), cols(kind), null, null, order(kind));
            if (c != null) {
                while (c.moveToNext()) {
                    String data = c.getString(2);
                    String dir  = parentOf(data);
                    // 하위 폴더까지 딸려 오면 안 된다. 부모가 정확히 같은 것만.
                    if (folder != null && !folder.equals(dir)) continue;
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    if (name == null) name = data == null
                            ? String.valueOf(id) : new File(data).getName();
                    out.add(new Item(
                            Uri.withAppendedPath(content(kind), String.valueOf(id)), name, dir));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 목록에서 이 URI 의 자리. 없으면 -1. */
    public static int indexOf(List<Item> items, Uri uri) {
        if (uri == null || items == null) return -1;
        String s = uri.toString();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uri.toString().equals(s)) return i;
        }
        return -1;
    }

    /** 이 파일이 든 폴더. 뷰어가 인텐트로 열렸을 때 목록을 되찾는 데 쓴다. */
    public static String folderOf(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    uri, new String[]{MediaStore.Images.Media.DATA}, null, null, null);
            if (c != null && c.moveToFirst()) return parentOf(c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // --------------------------------------------------------- 즐겨찾기

    /**
     * 자주 여는 폴더를 위로 고정한다.
     *
     * 폴더가 수십 개가 되면 매번 훑어 내려가야 하는데, 실제로 오가는 폴더는 몇 개뿐이다.
     * 목록에서 길게 눌러 켜고 끈다.
     */
    public static Set<String> favorites(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> s = sp.getStringSet(KEY_FAVS, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    public static boolean toggleFavorite(Context ctx, String folder) {
        Set<String> s = favorites(ctx);
        boolean on = !s.contains(folder);
        if (on) s.add(folder); else s.remove(folder);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_FAVS, s).apply();
        return on;
    }

    // ------------------------------------------------------------- 잡다

    private static String parentOf(String data) {
        if (data == null || data.isEmpty()) return null;
        int i = data.lastIndexOf('/');
        return i <= 0 ? null : data.substring(0, i);
    }

    /**
     * 목록에 보일 짧은 경로.
     *   /storage/emulated/0/Download/x  ->  Download/x
     *   /storage/249C-9B8D/Download/x   ->  SD/Download/x
     *
     * 이 기기는 영상이 내장과 SD 양쪽에 흩어져 있다. 앞부분이 다 같은 글자라
     * 그대로 두면 정작 구별되는 뒷부분이 화면 밖으로 밀린다.
     */
    public static String shortPath(Context ctx, String path) {
        if (path == null) return "";
        try {
            String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            if (path.equals(root)) return ctx.getString(R.string.internal_storage);
            if (path.startsWith(root + "/")) return path.substring(root.length() + 1);
        } catch (Throwable ignored) { }

        if (path.startsWith("/storage/")) {
            int i = path.indexOf('/', "/storage/".length());
            if (i < 0) return "SD";
            return "SD/" + path.substring(i + 1);
        }
        return path;
    }

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".webm",
            ".wmv", ".flv", ".m4v", ".mpg", ".mpeg", ".3gp", ".divx", ".rmvb", ".vob"
    };

    private static final String[] PHOTO_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif", ".jps", ".mpo"
    };

    public static boolean isVideoName(String name) { return hasExt(name, VIDEO_EXT); }
    public static boolean isPhotoName(String name) { return hasExt(name, PHOTO_EXT); }

    private static boolean hasExt(String name, String[] exts) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        for (String e : exts) if (n.endsWith(e)) return true;
        return false;
    }
}
