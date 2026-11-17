package com.lev1s.ikantv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lev1s.ikantv.R;
import com.lev1s.ikantv.PlayerActivity;
import com.lev1s.ikantv.data.FetchException;
import com.lev1s.ikantv.data.IkanDataSource;
import com.lev1s.ikantv.data.IkanDataSource.Section;
import com.lev1s.ikantv.data.VideoItem;

import java.util.List;

/**
 * 原生首页（Emby 风格海报墙）：顶部入口行 + 纵向板块，每板块一条横向海报轨道。
 * 数据来自站点首页 HTML 的原生解析（IkanDataSource），点海报直达现有原生播放器。
 * 遇到站点校验或解析失败时，在本页显示可用遥控器操作的重试入口。
 */
public class NativeHomeActivity extends NativeBrowseActivity {

    private static final String PLAYER_PREFS = "player";
    private static final String ACTIVE_PLAYER = "active_player";
    private static final String ACTIVE_PLAYER_TITLE = "active_player_title";
    // 后端播放器接收集/线/标题的 intent 契约待对齐。
    private static final String EXTRA_ACTIVE_EPISODE = "active_player_episode";
    private static final String EXTRA_ACTIVE_LINE = "active_player_line";
    private static final String EXTRA_ACTIVE_TITLE = "active_player_title";
    private boolean homeInitialized;
    private LinearLayout sectionsContainer;
    private LinearLayout topEntries;
    private TextView errorText;
    private PosterLoader posterLoader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBackPress = 0;
    private boolean destroyed = false;
    private View firstCard;
    private View skeletonGroup;
    private int loadGen;
    private final Runnable skeletonFailsafe = () -> {
        ++loadGen;
        removeSkeleton();
        showError("加载时间较长，点这里再试一次");
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!restoreActivePlayer(getIntent())) initHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        restoreActivePlayer(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!homeInitialized && !restoreActivePlayer(getIntent())) initHome();
    }

    private boolean restoreActivePlayer(Intent intent) {
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !(intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_HOME))) return false;
        android.content.SharedPreferences prefs = getSharedPreferences(PLAYER_PREFS, MODE_PRIVATE);
        String marker = prefs.getString(ACTIVE_PLAYER, "");
        String[] parts = marker.split("\\|", -1);
        if (parts.length != 3) return false;
        try {
            long id = Long.parseLong(parts[0]);
            int episode = Integer.parseInt(parts[1]);
            int line = Integer.parseInt(parts[2]);
            if (id <= 0 || episode < 0 || line < 0) return false;
            Intent player = new Intent(this, PlayerActivity.class);
            player.putExtra(PlayerActivity.EXTRA_URL, IkanDataSource.BASE + "/play/" + id);
            player.putExtra(EXTRA_ACTIVE_EPISODE, episode);
            player.putExtra(EXTRA_ACTIVE_LINE, line);
            player.putExtra(EXTRA_ACTIVE_TITLE, prefs.getString(ACTIVE_PLAYER_TITLE, ""));
            startActivity(player);
            // 同一次 LAUNCHER intent 只处理一次；用户返回后正常显示首页。
            setIntent(new Intent());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void initHome() {
        homeInitialized = true;
        setContentView(R.layout.activity_native_home);
        sectionsContainer = findViewById(R.id.sections_container);
        topEntries = findViewById(R.id.top_entries);
        errorText = findViewById(R.id.error_text);
        posterLoader = PosterLoader.get(this);

        buildTopEntries();
        errorText.setOnClickListener(v -> loadHome());
        loadHome();
    }

    private void buildTopEntries() {
        // 全站统一标签栏（与其他原生页同一套，含「首页」自身、当前页描边高亮）
        TabBar.attach(this, topEntries, TabBar.Tab.HOME);
    }

    private void loadHome() {
        final int gen = ++loadGen;
        errorText.setVisibility(View.GONE);
        sectionsContainer.removeAllViews();
        showSkeleton();
        new Thread(() -> {
            try {
                List<Section> sections = IkanDataSource.fetchHome();
                handler.post(() -> {
                    if (destroyed || gen != loadGen) return;
                    removeSkeleton();
                    buildSections(sections);
                });
            } catch (FetchException e) {
                handler.post(() -> {
                    if (destroyed || gen != loadGen) return;
                    removeSkeleton();
                    onLoadError(e);
                });
            }
        }).start();
    }

    /**
     * 骨架屏：与真实版式同形的占位（板块标题条 + 一排海报块，卡块与轨道卡同尺寸），
     * 数据到达即被真实内容替换。6 秒兜底自动撤除——沿用 WebView 侧教训：
     * 请求挂死时骨架不能永久蒙屏（数据回来走 removeSkeleton 正常撤）。
     */
    private void showSkeleton() {
        removeSkeleton();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setClipChildren(false);
        group.setClipToPadding(false);
        for (int s = 0; s < 2; s++) {
            View bar = new View(this);
            bar.setBackgroundResource(R.drawable.skeleton_block);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(170), dp(24));
            bp.leftMargin = dp(48);
            bp.topMargin = dp(12);
            bp.bottomMargin = dp(6);
            group.addView(bar, bp);

            LinearLayout rail = new LinearLayout(this);
            rail.setOrientation(LinearLayout.HORIZONTAL);
            rail.setClipChildren(false);
            rail.setClipToPadding(false);
            // 12dp 上下缓冲容纳 1.05 焦点缩放，卡框不会贴住板块标题或裁切。
            rail.setPadding(dp(48), dp(12), dp(48), dp(12));
            for (int i = 0; i < 7; i++) {
                View block = new View(this);
                block.setBackgroundResource(R.drawable.skeleton_block);
                // 与轨道卡同尺寸：海报 132×198 + 间距 6 + 标题区 48。
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(132), dp(252));
                cp.leftMargin = i == 0 ? 0 : dp(12);
                block.setLayoutParams(cp);
                rail.addView(block);
            }
            group.addView(rail);
        }
        skeletonGroup = group;
        sectionsContainer.addView(group);
        handler.removeCallbacks(skeletonFailsafe);
        handler.postDelayed(skeletonFailsafe, 6000);
    }

    private void removeSkeleton() {
        handler.removeCallbacks(skeletonFailsafe);
        if (skeletonGroup != null) {
            sectionsContainer.removeView(skeletonGroup);
            skeletonGroup = null;
        }
    }

    private void onLoadError(FetchException e) {
        // 失败一律应用内提示 + 点击重试，不跳转其他应用（两版完全独立）
        String msg;
        switch (e.kind) {
            case CHALLENGED:
                msg = "网站好像在做人机校验\n过一会儿点这里再试一次";
                break;
            case PARSE_EMPTY:
                msg = "页面解析失败（站点可能改版了）\n点这里再试一次";
                break;
            default:
                msg = "网络好像不太顺\n点这里再试一次";
        }
        showError(msg);
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
        errorText.setFocusable(true);
        if (interactionCount == 0) errorText.requestFocus();
    }

    private void buildSections(List<Section> sections) {
        firstCard = null;
        for (Section section : sections) {
            sectionsContainer.addView(buildSectionHeader(section));
            sectionsContainer.addView(buildRail(section));
        }
        if (firstCard != null) {
            firstCard.post(() -> {
                if (!destroyed && interactionCount == 0) firstCard.requestFocus();
            });
        }
    }

    private View buildSectionHeader(Section section) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(48);
        rp.rightMargin = dp(48);
        rp.topMargin = dp(14);
        rp.bottomMargin = dp(4);
        row.setLayoutParams(rp);

        View bar = new View(this);
        bar.setBackgroundColor(0xFFFFB300);
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(20)));

        TextView title = new TextView(this);
        title.setText(section.title);
        title.setTextColor(0xFFE8EAED);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(10);
        row.addView(title, tp);

        if (section.moreUrl != null && !section.moreUrl.isEmpty()) {
            TextView more = new TextView(this);
            more.setText("更多 »");
            more.setTextColor(getColorStateList(R.color.chip_text));
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            more.setBackgroundResource(R.drawable.chip_frame);
            more.setFocusable(true);
            more.setClickable(true);
            more.setPadding(dp(14), dp(4), dp(14), dp(4));
            final Section fs = section;
            more.setOnClickListener(v -> openSectionMore(fs));
            row.addView(more);
        }
        return row;
    }

    private void openSectionMore(Section section) {
        Intent intent = new Intent(this, NativeGridActivity.class);
        intent.putExtra(NativeGridActivity.EXTRA_TITLE, section.title);
        intent.putExtra(NativeGridActivity.EXTRA_URL, section.moreUrl);
        String kind = NativeGridActivity.KIND_HOT;
        if (section.moreUrl.contains("billboard")) {
            kind = NativeGridActivity.KIND_BILLBOARD;
        } else if (section.moreUrl.contains("/category")) {
            kind = NativeGridActivity.KIND_CATEGORY;
        }
        intent.putExtra(NativeGridActivity.EXTRA_KIND, kind);
        startActivity(intent);
    }

    private View buildRail(Section section) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        // Keep the rail within its viewport; the 48dp side padding still gives
        // edge cards room for their 1.05 focus scale.
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scroll.setLayoutParams(sp);

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        // 焦点缩放伸入这里的留白，不向板块标题、轨道视口边界外绘制。
        rail.setPadding(dp(48), dp(12), dp(48), dp(12));
        int railIndex = 0;
        for (VideoItem item : section.items) {
            PosterCardView card = new PosterCardView(this, 132);
            card.bind(item, posterLoader);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    card.getLayoutParams().width,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // 定版：页面边距 48 由轨道 padding 承担，卡间距 12 只出现在卡与卡之间
            cp.leftMargin = railIndex == 0 ? 0 : dp(12);
            card.setLayoutParams(cp);
            railIndex++;
            final VideoItem fItem = item;
            card.setOnClickListener(v -> NavHelper.openItem(this, fItem));
            rail.addView(card);
            if (firstCard == null) firstCard = card;
        }
        scroll.addView(rail);
        return scroll;
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "再按一次返回就退出爱看电视", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(skeletonFailsafe);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
