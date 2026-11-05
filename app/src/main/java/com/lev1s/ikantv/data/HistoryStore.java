package com.lev1s.ikantv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生「看过」本地历史：SharedPreferences 里存一段 JSON（org.json 是框架自带，不引库）。
 * 上限 50 条、最新在前、按 videoId 去重。NavHelper 在点播出口写入，
 * NativeHistoryActivity 读取展示。
 *
 * 一次性迁移桥（mergeMigrated 等，曾并入站点 WebView localStorage 的
 * 旧 playHistory）已随迭代二死代码清理整组删除：读取侧分包时先删，
 * 落点侧经两轮复核确认全仓零调用后删除；存量历史靠装机时播种。
 * 已装设备 prefs 里可能还留着 migrated_from_webview_v2 等标记键，
 * 无代码再读，留着无害。
 */
public final class HistoryStore {

    private static final String PREFS = "native_history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX = 50;

    private HistoryStore() { }

    /** 内部表示：比 VideoItem 多一个时间戳（记录时刻）。 */
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
