package com.lev1s.ikanbottv.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.lev1s.ikanbottv.PlayerActivity;
import com.lev1s.ikanbottv.data.HistoryStore;
import com.lev1s.ikanbottv.data.VideoItem;

/** 原生浏览层共用的跳转：点播契约与降级出口。 */
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

    /**
     * 浏览层降级：撞上 Cloudflare 校验或解析失效时，切换到独立的网页版 App
     * （「爱看电视」，包名 com.lev1s.ikanbottv——两版已分包，MainActivity 在
     * 那个应用里），保证用户永远有得看；网页版未安装时只提示、不崩。
     */
    public static void degradeToSite(Activity from, String reason) {
        openWebViewApp(from, reason + "，已切换到网页版「爱看电视」");
    }

    /** 跨应用打开网页版「爱看电视」（分包后的原站模式兜底出口）。 */
    public static void openWebViewApp(Activity from, String toastText) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.lev1s.ikanbottv",
                    "com.lev1s.ikanbottv.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            from.startActivity(intent);
            Toast.makeText(from, toastText, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(from, "网页版「爱看电视」未安装，无法切换",
                    Toast.LENGTH_LONG).show();
        }
    }
}
