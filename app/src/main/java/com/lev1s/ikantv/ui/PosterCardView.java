package com.lev1s.ikantv.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.animation.ValueAnimator;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lev1s.ikantv.R;
import com.lev1s.ikantv.data.VideoItem;

/**
 * Emby 风格海报卡：海报（2:3）+ 下方标题，海报墙与网格页共用。
 * 焦点态 = card_frame 琥珀描边 + 轻微放大（原生绘制，不再靠 CSS）。
 * 无海报的条目（首页口碑榜文本条）在海报区显示大号名次数字。
 */
public class PosterCardView extends LinearLayout {

    private final ImageView posterView;
    private final TextView rankBadge;
    private final TextView bigRankView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator focusAnimator;
    private float focusAlpha;
    private final Runnable startMarquee = this::startTitleMarquee;

    private void startTitleMarquee() { titleView.setSelected(true); }

    /** @param widthDp 卡片总宽（dp），海报高按 2:3 推导 */
    public PosterCardView(Context context, int widthDp) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(true);
        setClickable(true);
        setWillNotDraw(false);
        focusPaint.setColor(0xFFFFB300);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(dp(2));
        setClipChildren(false);
        setClipToPadding(false);

        int posterW = dp(widthDp);
        int posterH = posterW * 3 / 2;

        FrameLayout wrap = new FrameLayout(context);
        // 海报区裁剪只把溢出收进矩形：方正定版（2026-11-04 Jasen 定——海报页面
        // 不要任何圆角，此前 8dp 圆角裁剪废止）；作用于整个海报区（位图、占位底、
        // 首字/名次面），与 card_frame 的方角框成套
        wrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
            }
        });
        wrap.setClipToOutline(true);
        posterView = new ImageView(context);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterView.setBackgroundResource(R.drawable.poster_ph);
        wrap.addView(posterView, new FrameLayout.LayoutParams(posterW, posterH));

        bigRankView = new TextView(context);
        bigRankView.setTextColor(0xFFFFB300);
        bigRankView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        bigRankView.setGravity(Gravity.CENTER);
        bigRankView.setVisibility(GONE);
        wrap.addView(bigRankView, new FrameLayout.LayoutParams(posterW, posterH));

        rankBadge = new TextView(context);
        rankBadge.setBackgroundResource(R.drawable.rank_badge);
        rankBadge.setTextColor(0xFF14161A);
        rankBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rankBadge.setGravity(Gravity.CENTER);
        rankBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
        rankBadge.setVisibility(GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(6);
        bp.topMargin = dp(6);
        wrap.addView(rankBadge, bp);
        addView(wrap);

        titleView = new TextView(context);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(24));
        tp.topMargin = dp(6);
        addView(titleView, tp);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(0xFFB3B3B3);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(20));
        sub.topMargin = dp(4);
        addView(subtitleView, sub);

        setLayoutParams(new LayoutParams(dp(widthDp), LayoutParams.WRAP_CONTENT));

        setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f)
                    .scaleY(hasFocus ? 1.08f : 1.0f)
                    .setDuration(200).start();
            removeCallbacks(startMarquee);
            titleView.setSelected(false);
            titleView.setEllipsize(hasFocus ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            titleView.setMarqueeRepeatLimit(-1);
            if (hasFocus) postDelayed(startMarquee, 800);
            if (focusAnimator != null) focusAnimator.cancel();
            focusAnimator = ValueAnimator.ofFloat(focusAlpha, hasFocus ? 1f : 0f);
            focusAnimator.setDuration(200);
            focusAnimator.addUpdateListener(a -> {
                focusAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            focusAnimator.start();
            // 放大时压住邻卡靠抬 Z 轴，绝不能用 bringToFront()：它会把卡片
            // 在父 LinearLayout 里挪到末位——真机实测曾把历史页/榜单/首页
            // 轨道的首卡「瞬移」到行尾（顺序错乱，2026-11-04 验收抓到）
            v.setTranslationZ(hasFocus ? 8f : 0f);
        });
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        focusPaint.setAlpha((int) (focusAlpha * 255));
        float inset = dp(4);
        canvas.drawRect(-inset, -inset, getWidth() + inset, getHeight() + inset, focusPaint);
    }

    public void bind(VideoItem item, PosterLoader loader) {
        titleView.setText(item.title);
        boolean hasPoster = item.posterCandidates != null && !item.posterCandidates.isEmpty();
        if (item.rank > 0) {
            rankBadge.setText(String.valueOf(item.rank));
            rankBadge.setVisibility(VISIBLE);
        } else {
            rankBadge.setVisibility(GONE);
        }
        if (hasPoster) {
            bigRankView.setVisibility(GONE);
            // 候选全是死链时回调亮首字，别留黑框（真机见过整块发黑的「海报」）
            loader.load(posterView, item.posterCandidates, () -> {
                bigRankView.setText(item.title.isEmpty() ? ""
                        : item.title.substring(0, 1));
                bigRankView.setVisibility(VISIBLE);
            });
        } else {
            // 无海报（榜单文本条）：海报区显示大号名次，没名次就显示标题首字
            posterView.setImageDrawable(null);
            posterView.setTag(null);
            bigRankView.setText(item.rank > 0 ? String.valueOf(item.rank)
                    : (item.title.isEmpty() ? "" : item.title.substring(0, 1)));
            bigRankView.setVisibility(VISIBLE);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

}
