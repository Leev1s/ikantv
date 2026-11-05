package com.lev1s.ikanbottv.ui;

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lev1s.ikanbottv.R;
import com.lev1s.ikanbottv.data.IkanDataSource;

/**
 * 全站统一顶栏标签（首页/搜索/电影/剧集/榜单/看过）：每个原生页面顶部同一套，
 * 当前页标签描边高亮，按 OK 直接跳页（2026-11-04 Jasen 要求：子页面也要保留
 * header，能在五个选项卡之间快速跳动）。
 *
 * 跳转语义保持任务栈扁平：首页是根——从首页出发不 finish 首页（返回键还能
 * 回到首页）；子页之间切换时 finish 自己（栈深恒定，返回永远落回首页）；
 * 点「首页」= finish 自己回到根。当前页标签点击无动作。
 */
public final class TabBar {

    public enum Tab { HOME, SEARCH, MOVIE, TV, BILLBOARD, HISTORY, NONE }

    private static final String[] MOVIE_TAGS = {"热门", "最新", "华语", "欧美", "日本",
            "动作", "喜剧", "动画", "恐怖", "悬疑"};
    private static final String[] TV_TAGS = {"热门", "国产剧", "港剧", "韩剧", "日剧",
            "美剧", "英剧", "日本动画", "综艺", "纪录片"};

    private TabBar() { }

    public static void attach(Activity act, LinearLayout container, Tab current) {
        add(act, container, "首页", Tab.HOME, current);
        add(act, container, "搜索", Tab.SEARCH, current);
        add(act, container, "电影", Tab.MOVIE, current);
        add(act, container, "剧集", Tab.TV, current);
        add(act, container, "榜单", Tab.BILLBOARD, current);
        add(act, container, "看过", Tab.HISTORY, current);
    }

    private static void add(Activity act, LinearLayout container, String label,
                            Tab tab, Tab current) {
        TextView chip = new TextView(act);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        boolean isCurrent = tab == current;
        chip.setTextColor(act.getColorStateList(
                isCurrent ? R.color.chip_text_current : R.color.chip_text));
        chip.setBackgroundResource(
                isCurrent ? R.drawable.chip_current : R.drawable.chip_frame);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setPadding(dp(act, 20), dp(act, 7), dp(act, 20), dp(act, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(act, 10);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> go(act, tab, current));
        container.addView(chip);
    }

    private static void go(Activity act, Tab tab, Tab current) {
        if (tab == current) return;
        if (tab == Tab.HOME) {
            if (!(act instanceof NativeHomeActivity)) act.finish();
            return;
        }
        Intent intent = intentFor(act, tab);
        if (intent == null) return;
        act.startActivity(intent);
        if (!(act instanceof NativeHomeActivity)) act.finish();
    }

    private static Intent intentFor(Activity act, Tab tab) {
        switch (tab) {
            case SEARCH:
                return new Intent(act, NativeSearchActivity.class);
            case HISTORY:
                return new Intent(act, NativeHistoryActivity.class);
            case BILLBOARD: {
                Intent i = new Intent(act, NativeGridActivity.class);
                i.putExtra(NativeGridActivity.EXTRA_TITLE, "榜单");
                i.putExtra(NativeGridActivity.EXTRA_KIND,
                        NativeGridActivity.KIND_BILLBOARD);
                return i;
            }
            case MOVIE:
                return gridIntent(act, "电影",
                        IkanDataSource.BASE + "/hot/index-movie-热门.html",
                        MOVIE_TAGS, "movie");
            case TV:
                return gridIntent(act, "剧集",
                        IkanDataSource.BASE + "/hot/index-tv-热门.html",
                        TV_TAGS, "tv");
            default:
                return null;
        }
    }

    private static Intent gridIntent(Activity act, String title, String url,
                                     String[] tags, String tagKind) {
        Intent intent = new Intent(act, NativeGridActivity.class);
        intent.putExtra(NativeGridActivity.EXTRA_TITLE, title);
        intent.putExtra(NativeGridActivity.EXTRA_URL, url);
        intent.putExtra(NativeGridActivity.EXTRA_KIND, NativeGridActivity.KIND_HOT);
        String[] urls = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            urls[i] = IkanDataSource.BASE + "/hot/index-" + tagKind + "-"
                    + tags[i] + ".html";
        }
        intent.putExtra(NativeGridActivity.EXTRA_TAG_LABELS, tags);
        intent.putExtra(NativeGridActivity.EXTRA_TAG_URLS, urls);
        return intent;
    }

    private static int dp(Activity act, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }
}
