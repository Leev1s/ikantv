package com.lev1s.ikanbottv.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lev1s.ikanbottv.R;
import com.lev1s.ikanbottv.data.FetchException;
import com.lev1s.ikanbottv.data.IkanDataSource;
import com.lev1s.ikanbottv.data.IkanDataSource.GridPage;
import com.lev1s.ikanbottv.data.IkanDataSource.Section;
import com.lev1s.ikanbottv.data.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用原生列表页，四种模式：
 *   KIND_HOT / KIND_CATEGORY —— 模板 A 海报网格，跟随页内链接翻页续加
 *   KIND_BILLBOARD           —— 榜单页：多板块一次解析，逐板块成网格
 *   KIND_SEARCH              —— 模板 B 列表行（小海报 + 标题 + 简介），游标翻页
 * 点任意条目走 NavHelper 的既有播放契约。
 */
public class NativeGridActivity extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_TAG_LABELS = "tag_labels";
    public static final String EXTRA_TAG_URLS = "tag_urls";

    public static final String KIND_HOT = "hot";
    public static final String KIND_CATEGORY = "category";
    public static final String KIND_BILLBOARD = "billboard";
    public static final String KIND_SEARCH = "search";

    private static final int COLUMNS = 6;

    private TextView titleView;
    private TextView emptyView;
    private LinearLayout tagRow;
    private ScrollView scrollView;
    private LinearLayout container;
    private TextView footer;
    private LinearLayout skeleton;
    private PosterLoader posterLoader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable skeletonFailsafe = () -> hideSkeleton();

    private String kind;
    private String firstUrl;
    private String query;
    private String nextPageUrl;
    private boolean loadingPage = false;
    private boolean destroyed = false;
    private int loadGen = 0;
    private int cardWidthDp;
    private final List<PosterCardView> rowBuffer = new ArrayList<>();
    private boolean firstFocusDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_native_grid);

        titleView = findViewById(R.id.grid_title);
        emptyView = findViewById(R.id.grid_empty);
        tagRow = findViewById(R.id.tag_row);
        scrollView = findViewById(R.id.grid_scroll);
        container = findViewById(R.id.grid_container);
        footer = findViewById(R.id.footer_text);
        skeleton = findViewById(R.id.grid_skeleton);
        posterLoader = PosterLoader.get(this);

        kind = getIntent().getStringExtra(EXTRA_KIND);
        if (kind == null) kind = KIND_HOT;
        firstUrl = getIntent().getStringExtra(EXTRA_URL);
        query = getIntent().getStringExtra(EXTRA_QUERY);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null ? "" : title);
        TabBar.Tab cur = currentTab(kind, title);
        TabBar.attach(this, findViewById(R.id.grid_tabs), cur);
        // 标签页（电影/剧集/榜单）的页名与顶栏标签重复，能省则省（2026-11-04 Jasen）：
        // 页名文字隐藏；若本页又没有分类标签行，整行一并收起把高度让给内容。
        // 搜索结果（标题含关键词）与板块「更多」页（标题是板块名）保留标题。
        if (cur == TabBar.Tab.MOVIE || cur == TabBar.Tab.TV
                || cur == TabBar.Tab.BILLBOARD) {
            titleView.setVisibility(View.GONE);
            String[] tagLabels = getIntent().getStringArrayExtra(EXTRA_TAG_LABELS);
            if (tagLabels == null || tagLabels.length == 0) {
                findViewById(R.id.grid_title_row).setVisibility(View.GONE);
            }
        }

        float density = getResources().getDisplayMetrics().density;
        int screenDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        // 定版公式：卡宽 = (布局宽 − 页面边距 48×2 − 卡间距 12×(列数−1)) ÷ 列数
        cardWidthDp = (screenDp - 96 - (COLUMNS - 1) * 12) / COLUMNS;

        buildSkeleton();
        buildTags();
        footer.setOnClickListener(v -> {
            if (nextPageUrl != null && !loadingPage) loadNextPage();
            else if (!loadingPage) loadFirst();
        });
        scrollView.setOnScrollChangeListener((v, sx, sy, osx, osy) -> maybeLoadMore());

        loadFirst();
    }

    /** 本页在顶栏里对应哪个标签：搜索/榜单按 kind，电影/剧集按页名，其余（板块更多页）不高亮。 */
    private static TabBar.Tab currentTab(String kind, String title) {
        if (KIND_SEARCH.equals(kind)) return TabBar.Tab.SEARCH;
        if (KIND_BILLBOARD.equals(kind)) return TabBar.Tab.BILLBOARD;
        if ("电影".equals(title)) return TabBar.Tab.MOVIE;
        if ("剧集".equals(title)) return TabBar.Tab.TV;
        return TabBar.Tab.NONE;
    }

    private void showEmpty(String msg) {
        emptyView.setText(msg);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.post(() -> {
            if (!destroyed) emptyView.requestFocus();
        });
    }

    private void buildTags() {
        String[] labels = getIntent().getStringArrayExtra(EXTRA_TAG_LABELS);
        String[] urls = getIntent().getStringArrayExtra(EXTRA_TAG_URLS);
        if (labels == null || urls == null || labels.length != urls.length) return;
        for (int i = 0; i < labels.length; i++) {
            final String url = urls[i];
            final String label = labels[i];
            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTextColor(getColorStateList(R.color.chip_text));
            chip.setBackgroundResource(R.drawable.chip_frame);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setPadding(dp(16), dp(5), dp(16), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (url.equals(firstUrl)) return;
                firstUrl = url;
                loadFirst();
            });
            tagRow.addView(chip);
        }
    }

    /**
     * 骨架屏：2 行 × 6 列与海报卡同尺寸的灰块，首屏加载期间显示、数据到达即撤。
     * 6 秒兜底自动撤除——沿用 WebView 侧教训：请求挂死时骨架不能永久蒙屏。
     */
    private void buildSkeleton() {
        int cardH = (cardWidthDp - 10) * 3 / 2 + 59;   // 海报 2:3 + 内边距/标题区（标题整高 47+距 2+垫 10），同 PosterCardView
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(cardH));
            if (r == 0) rp.bottomMargin = dp(10);
            row.setLayoutParams(rp);
            for (int c = 0; c < COLUMNS; c++) {
                View block = new View(this);
                block.setBackgroundResource(R.drawable.skeleton_block);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        dp(cardWidthDp), LinearLayout.LayoutParams.MATCH_PARENT);
                if (c > 0) bp.leftMargin = dp(12);
                block.setLayoutParams(bp);
                row.addView(block);
            }
            skeleton.addView(row);
        }
    }

    private void showSkeleton() {
        skeleton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(skeletonFailsafe);
        handler.postDelayed(skeletonFailsafe, 6000);
    }

    private void hideSkeleton() {
        handler.removeCallbacks(skeletonFailsafe);
        skeleton.setVisibility(View.GONE);
    }

    // ---------------- 加载 ----------------

    private void loadFirst() {
        final int gen = ++loadGen;
        loadingPage = true;
        nextPageUrl = null;
        firstFocusDone = false;
        rowBuffer.clear();
        container.removeAllViews();
        emptyView.setVisibility(View.GONE);
        showSkeleton();
        footer.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                if (KIND_BILLBOARD.equals(kind)) {
                    List<Section> sections = IkanDataSource.fetchBillboard();
                    handler.post(() -> {
                        if (destroyed || gen != loadGen) return;
                        loadingPage = false;
                        hideSkeleton();
                        buildBillboard(sections);
                        int total = 0;
                        for (Section s : sections) total += s.items.size();
                        if (total == 0) showEmpty("这里暂时没有内容");
                    });
                } else {
                    GridPage page = fetchPage(firstUrl, true);
                    handler.post(() -> {
                        if (destroyed || gen != loadGen) return;
                        loadingPage = false;
                        hideSkeleton();
                        nextPageUrl = page.nextPageUrl;
                        appendItems(page.items);
                        if (page.items.isEmpty()) {
                            showEmpty(KIND_SEARCH.equals(kind)
                                    ? "没有搜到「" + query + "」，换个片名试试（建议用中文全名）"
                                    : "这里暂时没有内容");
                        } else {
                            updateFooter();
                        }
                    });
                }
            } catch (FetchException e) {
                handler.post(() -> {
                    if (destroyed || gen != loadGen) return;
                    loadingPage = false;
                    hideSkeleton();
                    onLoadError(e, true);
                });
            }
        }).start();
    }

    private void loadNextPage() {
        if (nextPageUrl == null || loadingPage) return;
        final int gen = loadGen;
        final String url = nextPageUrl;
        loadingPage = true;
        footer.setText("加载中…");
        footer.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                GridPage page = fetchPage(url, false);
                handler.post(() -> {
                    if (destroyed || gen != loadGen) return;
                    loadingPage = false;
                    nextPageUrl = page.nextPageUrl;
                    appendItems(page.items);
                    updateFooter();
                });
            } catch (FetchException e) {
                handler.post(() -> {
                    if (destroyed || gen != loadGen) return;
                    loadingPage = false;
                    footer.setText("这一页没加载出来，点这里重试");
                    footer.setVisibility(View.VISIBLE);
                    footer.setFocusable(true);
                    footer.requestFocus();
                });
            }
        }).start();
    }

    private GridPage fetchPage(String url, boolean first) throws FetchException {
        if (KIND_SEARCH.equals(kind)) {
            return first ? IkanDataSource.fetchSearch(query)
                    : IkanDataSource.fetchSearchNext(url);
        }
        return IkanDataSource.fetchGrid(url);
    }

    private void onLoadError(FetchException e, boolean firstPage) {
        switch (e.kind) {
            case CHALLENGED:
                NavHelper.degradeToSite(this, "网站好像在做人机校验");
                break;
            case PARSE_EMPTY:
                if (KIND_SEARCH.equals(kind)) {
                    // 搜不到就是搜不到：居中空态 + 焦点落点，别留只剩标题的黑页
                    showEmpty("没有搜到「" + query + "」，换个片名试试（建议用中文全名）");
                } else {
                    NavHelper.degradeToSite(this, "页面解析失败（站点可能改版了）");
                }
                break;
            default:
                footer.setText("网络好像不太顺，点这里重试");
                footer.setVisibility(View.VISIBLE);
                footer.setFocusable(true);
                footer.requestFocus();
        }
    }

    private void updateFooter() {
        if (nextPageUrl == null) {
            footer.setText("— 没有更多了 —");
            footer.setVisibility(View.VISIBLE);
        } else {
            footer.setVisibility(View.GONE);
        }
    }

    private void maybeLoadMore() {
        if (nextPageUrl == null || loadingPage) return;
        View child = scrollView.getChildAt(0);
        if (child == null) return;
        int diff = child.getBottom() - (scrollView.getHeight() + scrollView.getScrollY());
        if (diff < dp(500)) loadNextPage();
    }

    // ---------------- 渲染 ----------------

    private void appendItems(List<VideoItem> items) {
        if (KIND_SEARCH.equals(kind)) {
            for (VideoItem item : items) container.addView(buildSearchRow(item));
            focusFirst();
            return;
        }
        for (VideoItem item : items) {
            PosterCardView card = new PosterCardView(this, cardWidthDp);
            card.bind(item, posterLoader);
            final VideoItem fItem = item;
            card.setOnClickListener(v -> NavHelper.openItem(this, fItem));
            rowBuffer.add(card);
            if (rowBuffer.size() == COLUMNS) flushRow();
        }
        if (!rowBuffer.isEmpty()) flushRow();
        focusFirst();
    }

    private void flushRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(10);
        row.setLayoutParams(rp);
        int colIndex = 0;
        for (PosterCardView card : rowBuffer) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    card.getLayoutParams().width,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // 定版：页面边距 48 由容器 padding 承担，卡间距 12 只出现在卡与卡之间
            cp.leftMargin = colIndex == 0 ? 0 : dp(12);
            card.setLayoutParams(cp);
            row.addView(card);
            colIndex++;
        }
        // 末行不足整行时补空位，保持列对齐
        for (int i = rowBuffer.size(); i < COLUMNS; i++) {
            View spacer = new View(this);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    dp(cardWidthDp), 1);
            sp.leftMargin = i == 0 ? 0 : dp(12);
            spacer.setLayoutParams(sp);
            row.addView(spacer);
        }
        container.addView(row);
        rowBuffer.clear();
    }

    private View buildSearchRow(VideoItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.card_frame);
        row.setPadding(dp(10), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        FrameLayout thumbWrap = new FrameLayout(this);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundResource(R.drawable.poster_ph);
        thumbWrap.addView(poster, new FrameLayout.LayoutParams(dp(60), dp(90)));
        if (item.posterCandidates == null || item.posterCandidates.isEmpty()) {
            // 无海报条目：缩略位补标题首字，与海报卡的占位规则一致，别留空框
            TextView letter = new TextView(this);
            letter.setText(item.title == null || item.title.isEmpty()
                    ? "" : item.title.substring(0, 1));
            letter.setTextColor(0xFF9AA0A6);
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            letter.setGravity(Gravity.CENTER);
            thumbWrap.addView(letter, new FrameLayout.LayoutParams(
                    dp(60), dp(90)));
        }
        row.addView(thumbWrap);
        posterLoader.load(poster, item.posterCandidates);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(16);
        texts.setLayoutParams(tp);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(0xFFE8EAED);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(title);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(item.subtitle);
            sub.setTextColor(0xFF9AA0A6);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sub.setMaxLines(2);
            LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sp2.topMargin = dp(4);
            sub.setLayoutParams(sp2);
            texts.addView(sub);
        }
        row.addView(texts);
        row.setOnClickListener(v -> NavHelper.openItem(this, item));
        row.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f)
                        .setDuration(120).start());
        return row;
    }

    private void buildBillboard(List<Section> sections) {
        for (Section section : sections) {
            TextView header = new TextView(this);
            header.setText(section.title);
            header.setTextColor(0xFFE8EAED);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(14);
            hp.bottomMargin = dp(6);
            header.setLayoutParams(hp);
            container.addView(header);
            appendItems(section.items);
        }
    }

    private void focusFirst() {
        if (firstFocusDone) return;
        firstFocusDone = true;
        container.post(() -> {
            if (destroyed) return;
            View f = findFirstFocusable(container);
            if (f != null) f.requestFocus();
        });
    }

    private View findFirstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE
                && !(v instanceof FrameLayout) && v != container) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findFirstFocusable(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
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
