package com.lev1s.ikantv.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lev1s.ikantv.R;
import com.lev1s.ikantv.data.HistoryStore;
import com.lev1s.ikantv.data.VideoItem;

import java.util.List;

/**
 * 原生「看过」页：本地历史（HistoryStore）的海报网格，排版与焦点规则同网格页
 * （6 列、边距 48、卡间距 12、行距 16），点卡走 NavHelper 既有点播契约。
 *
 * 分包说明（2026-11-04 两版拆为独立 App）：旧版与 WebView 版同包时，这里有一座
 * 「迁移桥」用临时 WebView 读同包 localStorage 的站点历史并入本地。分包后网页版
 * 的存储在本应用沙盒之外、读不到，桥已删除；存量历史改由装机时一次性把旧包的
 * native_history 偏好文件播种进来（HistoryStore 的 migrated 标记随文件一起过来），
 * 此后历史只在本应用内生长（点播即记录，见 NavHelper.openItem）。
 */
public class NativeHistoryActivity extends NativeBrowseActivity {

    private static final int COLUMNS = 6;

    private LinearLayout container;
    private TextView emptyText;
    private TextView clearChip;
    private PosterLoader posterLoader;
    private boolean destroyed = false;
    private int cardWidthDp;
    private long returnVideoId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_native_history);

        TabBar.attach(this, findViewById(R.id.history_tabs), TabBar.Tab.HISTORY);
        container = findViewById(R.id.history_container);
        emptyText = findViewById(R.id.history_empty);
        clearChip = findViewById(R.id.history_clear);
        posterLoader = PosterLoader.get(this);

        float density = getResources().getDisplayMetrics().density;
        int screenDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        // 与网格页同一定版公式：卡宽 = (布局宽 − 边距 48×2 − 间距 12×(列数−1)) ÷ 列数
        cardWidthDp = (screenDp - 96 - (COLUMNS - 1) * 12) / COLUMNS;

        clearChip.setOnClickListener(v -> confirmClear());
        // 空态按 OK = 回首页挑片（本页由顶栏进入，finish 即回到来处），
        // 不让焦点停在无动作的文案上（UX 评审 C1）
        emptyText.setOnClickListener(v -> finish());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returnVideoId > 0) {
            render();
            returnVideoId = 0;
        }
    }

    /** 清空是不可逆操作且历史是核心资产，必须二次确认（UX 评审 C3）。
     *  焦点默认落在「取消」，防的是焦点路径路过清空 chip 时的误按连发。 */
    private void confirmClear() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage("确定要清空全部观看历史吗？清掉就找不回来了。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, which) -> {
                    HistoryStore.clear(this);
                    render();
                })
                .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
        dialog.show();
    }

    // ---------------- 渲染 ----------------

    private void render() {
        List<VideoItem> items = HistoryStore.load(this);
        container.removeAllViews();
        if (items.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            clearChip.setVisibility(View.GONE);
            emptyText.post(() -> {
                if (!destroyed) emptyText.requestFocus();
            });
            return;
        }
        emptyText.setVisibility(View.GONE);
        clearChip.setVisibility(View.VISIBLE);

        PosterCardView firstCard = null;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLUMNS == 0) {
                row = newRow();
                container.addView(row);
            }
            VideoItem item = items.get(i);
            PosterCardView card = new PosterCardView(this, cardWidthDp);
            card.bind(item, posterLoader);
            card.setOnClickListener(v -> {
                returnVideoId = item.videoId;
                NavHelper.openItem(this, item);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    card.getLayoutParams().width,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = i % COLUMNS == 0 ? 0 : dp(12);
            card.setLayoutParams(cp);
            row.addView(card);
            if (firstCard == null || (returnVideoId > 0 && item.videoId == returnVideoId)) {
                firstCard = card;
            }
        }
        // 末行补空位保持列对齐（同网格页 flushRow）
        int rem = items.size() % COLUMNS;
        if (rem != 0 && row != null) {
            for (int i = rem; i < COLUMNS; i++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        dp(cardWidthDp), 1);
                sp.leftMargin = dp(12);
                spacer.setLayoutParams(sp);
                row.addView(spacer);
            }
        }
        final PosterCardView focusTarget = firstCard;
        if (focusTarget != null) {
            focusTarget.post(() -> {
                if (!destroyed) focusTarget.requestFocus();
            });
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(12);
        row.setLayoutParams(rp);
        return row;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
