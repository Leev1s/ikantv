package com.lev1s.ikantv.ui;

import android.app.Activity;
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
import com.lev1s.ikantv.data.FetchException;
import com.lev1s.ikantv.data.IkanDataSource;
import com.lev1s.ikantv.data.IkanDataSource.Section;
import com.lev1s.ikantv.data.VideoItem;

import java.util.List;

/**
 * 原生首页（Emby 风格海报墙）：顶部入口行 + 纵向板块，每板块一条横向海报轨道。
 * 数据来自站点首页 HTML 的原生解析（IkanDataSource），点海报直达现有原生播放器。
 * 撞 Cloudflare 校验/解析失效时自动降级到 WebView 原站模式（MainActivity）。
 */
public class NativeHomeActivity extends Activity {

    private LinearLayout sectionsContainer;
    private LinearLayout topEntries;
    private TextView errorText;
    private PosterLoader posterLoader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBackPress = 0;
    private boolean destroyed = false;
    private View firstCard;
    private View skeletonGroup;
    private final Runnable skeletonFailsafe = () -> removeSkeleton();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        errorText.setVisibility(View.GONE);
        sectionsContainer.removeAllViews();
        showSkeleton();
        new Thread(() -> {
            try {
                List<Section> sections = IkanDataSource.fetchHome();
                handler.post(() -> {
                    if (destroyed) return;
                    removeSkeleton();
                    buildSections(sections);
                });
            } catch (FetchException e) {
                handler.post(() -> {
                    if (destroyed) return;
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
            rail.setPadding(dp(48), dp(4), dp(48), dp(8));
            for (int i = 0; i < 7; i++) {
                View block = new View(this);
                block.setBackgroundResource(R.drawable.skeleton_block);
                // 与轨道卡同尺寸：宽 132；高 = 海报 122×3/2 + 内边距 10 + 标题区 6+44
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(132), dp(243));
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
        switch (e.kind) {
            case CHALLENGED:
                NavHelper.degradeToSite(this, "网站好像在做人机校验");
                break;
            case PARSE_EMPTY:
                NavHelper.degradeToSite(this, "页面解析失败（站点可能改版了）");
                break;
            default:
                errorText.setText("网络好像不太顺\n点这里再试一次");
                errorText.setVisibility(View.VISIBLE);
                errorText.setFocusable(true);
                errorText.requestFocus();
        }
    }

    private void buildSections(List<Section> sections) {
        firstCard = null;
        for (Section section : sections) {
            sectionsContainer.addView(buildSectionHeader(section));
            sectionsContainer.addView(buildRail(section));
        }
        if (firstCard != null) {
            firstCard.post(() -> {
                if (!destroyed) firstCard.requestFocus();
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
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scroll.setLayoutParams(sp);

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(dp(48), dp(4), dp(48), dp(8));
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                || event.getKeyCode() == KeyEvent.KEYCODE_GUIDE)) {
            // 菜单键 = 切换到网页版 App（分包后原站模式是独立应用，跨包打开）
            NavHelper.openWebViewApp(this, "已切换到网页版「爱看电视」");
            return true;
        }
        return super.dispatchKeyEvent(event);
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
