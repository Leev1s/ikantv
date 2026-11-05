package com.lev1s.ikantv.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.lev1s.ikantv.PlayerActivity;
import com.lev1s.ikantv.data.HistoryStore;
import com.lev1s.ikantv.data.VideoItem;

/** 原生浏览层共用的跳转：点播入口。本应用与网页版完全独立，这里没有任何跨应用调用。 */
public final class NavHelper {

    private NavHelper() { }

    /**
     * 点开一个条目：
     * - 有 videoId → 按 MainActivity 既有契约（PlayerActivity.EXTRA_URL = 完整 /play/ URL）
     *   拉起原生播放器，播放链路与 WebView 侧完全一致；
     * - 只有 searchQuery（榜单里站内无直达的条目）→ 打开原生搜索结果页。
     */
    public static void openItem(Activity from, VideoItem item) {
        if (item == null) return;
        if (item.videoId > 0) {
            // 点播出口顺手记一条原生「看过」（去重前移、≤50 条，见 HistoryStore）
            HistoryStore.record(from, item);
            Intent intent = new Intent(from, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_URL, item.playUrl());
            from.startActivity(intent);
        } else if (item.searchQuery != null && !item.searchQuery.isEmpty()) {
            Intent intent = new Intent(from, NativeGridActivity.class);
            intent.putExtra(NativeGridActivity.EXTRA_KIND, NativeGridActivity.KIND_SEARCH);
            intent.putExtra(NativeGridActivity.EXTRA_QUERY, item.searchQuery);
            intent.putExtra(NativeGridActivity.EXTRA_TITLE, "搜索：" + item.searchQuery);
            from.startActivity(intent);
        } else {
            Toast.makeText(from, "这个条目暂时打不开", Toast.LENGTH_SHORT).show();
        }
    }
}
