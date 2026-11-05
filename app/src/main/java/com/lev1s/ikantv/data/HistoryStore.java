package com.lev1s.ikantv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生「看过」本地历史：SharedPreferences 里存一段 JSON（org.json 是框架自带，不引库）。
 * 上限 50 条、最新在前、按 videoId 去重。NavHelper 在点播出口写入，
 * NativeHistoryActivity 读取展示。
 *
 * 本文件后半段的 mergeMigrated / isMigrated / markMigrated /
 * noteNullMigrationRead 是一次性迁移桥（曾并入站点 WebView localStorage
 * 里的旧 playHistory）的落点：桥的读取侧已在分包时删除，这组 API 全仓
 * 已无调用方，存量历史现靠装机时播种；仅因已装设备的 prefs 里可能还
 * 留着迁移标记键而暂存，勿据此以为迁移机制还在运行。
 *
 * 迁移标记用 v2 键的历史原因：v1 逻辑有个实测踩到的缺陷——首次打开
 * 历史页之前只要有过任何一次原生点播，就会误标「已迁移」而永不真正
 * 读旧数据（2026-11-04 真机验收发现）。v2 不认 v1 标记。
 */
public final class HistoryStore {

    private static final String PREFS = "native_history";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_MIGRATED = "migrated_from_webview_v2";
    private static final String KEY_NULL_READS = "migration_null_reads";
    private static final int MAX = 50;

    private HistoryStore() { }

    /** 内部表示：比 VideoItem 多一个时间戳，迁移合并时按它排序。 */
    private static class Entry {
        long videoId;
        String title;
        List<String> posters;
        long ts;
    }

    /** 点播时记一条：同一片重复点播去重并前移。数据量 ≤50 条，主线程同步写无压力。 */
    public static void record(Context ctx, VideoItem item) {
        if (item == null || item.videoId <= 0) return;
        List<Entry> entries = read(ctx);
        Entry e = new Entry();
        e.videoId = item.videoId;
        e.title = item.title == null ? "" : item.title;
        e.posters = item.posterCandidates == null
                ? new ArrayList<String>() : new ArrayList<>(item.posterCandidates);
        e.ts = System.currentTimeMillis();
        entries.removeIf(o -> o.videoId == e.videoId);
        entries.add(0, e);
        write(ctx, trim(entries));
    }

    /** 按存储顺序（最新在前）读出可展示的条目。 */
    public static List<VideoItem> load(Context ctx) {
        List<VideoItem> out = new ArrayList<>();
        for (Entry e : read(ctx)) {
            VideoItem item = new VideoItem();
            item.videoId = e.videoId;
            item.title = e.title;
            item.posterCandidates = e.posters;
            out.add(item);
        }
        return out;
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_ITEMS).apply();
    }

    /**
     * （迁移桥落点，已无调用方，见类注释）
     * 并入迁移桥读来的站点旧历史：本地已有同 videoId 的记录优先（更新、带海报），
     * 其余按旧记录的时间戳合并排序后截断。返回新并入的条数。
     */
    public static int mergeMigrated(Context ctx, List<VideoItem> items,
                                    Map<Long, Long> timestamps) {
        List<Entry> entries = read(ctx);
        Set<Long> existing = new HashSet<>();
        for (Entry e : entries) existing.add(e.videoId);
        int added = 0;
        for (VideoItem item : items) {
            if (item == null || item.videoId <= 0 || existing.contains(item.videoId)) continue;
            Entry e = new Entry();
            e.videoId = item.videoId;
            e.title = item.title == null ? "" : item.title;
            e.posters = item.posterCandidates == null
                    ? new ArrayList<String>() : new ArrayList<>(item.posterCandidates);
            Long ts = timestamps.get(item.videoId);
            e.ts = ts == null ? 0 : ts;
            entries.add(e);
            existing.add(item.videoId);
            added++;
        }
        if (added == 0) return 0;
        entries.sort((a, b) -> Long.compare(b.ts, a.ts));
        write(ctx, trim(entries));
        return added;
    }

    public static boolean isMigrated(Context ctx) {
        return prefs(ctx).getBoolean(KEY_MIGRATED, false);
    }

    public static void markMigrated(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    /**
     * （迁移桥落点，已无调用方，见类注释）
     * 记一次「迁移桥读到 null」（key 不存在或临时 WebView 没看见主存储）。
     * 一次读空不可信：累计 3 次都读空才返回 true，调用方据此放弃并标记完成，
     * 免得原生-only 用户每次打开历史页都白跑一遍桥。
     */
    public static boolean noteNullMigrationRead(Context ctx) {
        int n = prefs(ctx).getInt(KEY_NULL_READS, 0) + 1;
        prefs(ctx).edit().putInt(KEY_NULL_READS, n).apply();
        return n >= 3;
    }

    // ---------------- 序列化 ----------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<Entry> read(Context ctx) {
        List<Entry> out = new ArrayList<>();
        String json = prefs(ctx).getString(KEY_ITEMS, null);
        if (json == null) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.videoId = o.optLong("id");
                if (e.videoId <= 0) continue;
                e.title = o.optString("t");
                e.ts = o.optLong("ts");
                e.posters = new ArrayList<>();
                JSONArray ps = o.optJSONArray("p");
                if (ps != null) {
                    for (int j = 0; j < ps.length(); j++) e.posters.add(ps.optString(j));
                }
                out.add(e);
            }
        } catch (Exception ignored) {
            // 存储损坏就当空历史，不让它挡住看电视
        }
        return out;
    }

    private static void write(Context ctx, List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.videoId);
                o.put("t", e.title);
                o.put("ts", e.ts);
                JSONArray ps = new JSONArray();
                for (String p : e.posters) ps.put(p);
                o.put("p", ps);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static List<Entry> trim(List<Entry> entries) {
        return entries.size() > MAX
                ? new ArrayList<>(entries.subList(0, MAX)) : entries;
    }
}
