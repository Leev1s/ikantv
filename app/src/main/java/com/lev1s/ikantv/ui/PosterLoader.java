package com.lev1s.ikantv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.target.ViewTarget;
import com.lev1s.ikantv.R;
import com.lev1s.ikantv.data.HttpFetcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 海报加载入口：Glide 负责请求生命周期、解码与有限缓存；本类保留站点要求的
 * 请求头、候选 URL 顺序回退，以及候选耗尽时给卡片亮首字的回调。
 */
public final class PosterLoader {

    // 海报卡在 720p 六列布局中的显示宽约 180px；240×360 留少量清晰度余量，
    // 并避免把原始大图长期解码进 32 位设备的位图缓存。
    private static final int REQUEST_WIDTH_PX = 240;
    private static final int REQUEST_HEIGHT_PX = 360;
    private static final String REFERER = "https://www.ikanbot.com/";
    private static final int REQUEST_STATE_TAG = R.id.poster_request_state;
    private static final LazyHeaders HEADERS = new LazyHeaders.Builder()
            .addHeader("User-Agent", HttpFetcher.DESKTOP_UA)
            .addHeader("Referer", REFERER)
            .build();

    private static PosterLoader instance;

    public static synchronized PosterLoader get(Context ctx) {
        if (instance == null) instance = new PosterLoader();
        return instance;
    }

    private final RequestListener<Drawable> requestListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(GlideException e, Object model,
                                    Target<Drawable> target, boolean isFirstResource) {
            ImageView view = imageViewFor(target);
            LoadState state = currentState(view);
            if (state == null || !isCurrentCandidate(state, model)) return true;

            final int next = state.candidateIndex + 1;
            view.post(() -> {
                if (currentState(view) != state) return;
                if (next < state.candidates.size()) {
                    state.candidateIndex = next;
                    startCandidate(view, state);
                } else if (state.onExhausted != null) {
                    state.onExhausted.run();
                }
            });
            // The placeholder remains visible until the next candidate succeeds. In
            // particular, do not start or clear a Glide request inside this callback.
            return true;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model,
                                       Target<Drawable> target, DataSource dataSource,
                                       boolean isFirstResource) {
            ImageView view = imageViewFor(target);
            LoadState state = currentState(view);
            // Return true for stale work so Glide does not put it into a recycled view.
            return state == null || !isCurrentCandidate(state, model);
        }
    };

    private PosterLoader() { }

    /** The candidate list's order is the fallback priority. */
    public void load(ImageView view, List<String> candidates) {
        load(view, candidates, null);
    }

    /** Cancels the view's old request and discards its generation marker. */
    public void clear(ImageView view) {
        view.setTag(REQUEST_STATE_TAG, null);
        Glide.with(view).clear(view);
        view.setImageDrawable(null);
    }

    /** Runs on the main thread after every candidate has failed. */
    public void load(ImageView view, List<String> candidates, Runnable onExhausted) {
        if (candidates == null || candidates.isEmpty()) {
            clear(view);
            return;
        }

        List<String> urls = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) urls.add(candidate);
        }
        if (urls.isEmpty()) {
            clear(view);
            return;
        }
        LoadState state = new LoadState(urls, onExhausted);
        view.setTag(REQUEST_STATE_TAG, state);
        startCandidate(view, state);
    }

    private void startCandidate(ImageView view, LoadState state) {
        if (currentState(view) != state || state.candidateIndex >= state.candidates.size()) return;
        String url = state.candidates.get(state.candidateIndex);
        Glide.with(view)
                .load(new GlideUrl(url, HEADERS))
                .override(REQUEST_WIDTH_PX, REQUEST_HEIGHT_PX)
                .centerCrop()
                .placeholder(R.drawable.poster_ph)
                .error(R.drawable.poster_ph)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .listener(requestListener)
                .into(view);
    }

    private static ImageView imageViewFor(Target<Drawable> target) {
        if (target instanceof ViewTarget) {
            android.view.View view = ((ViewTarget<?, ?>) target).getView();
            if (view instanceof ImageView) return (ImageView) view;
        }
        return null;
    }

    private static LoadState currentState(ImageView view) {
        if (view == null) return null;
        Object value = view.getTag(REQUEST_STATE_TAG);
        return value instanceof LoadState ? (LoadState) value : null;
    }

    private static boolean isCurrentCandidate(LoadState state, Object model) {
        if (state.candidateIndex >= state.candidates.size()) return false;
        String current = state.candidates.get(state.candidateIndex);
        String loaded = model instanceof GlideUrl
                ? ((GlideUrl) model).toStringUrl() : String.valueOf(model);
        return current.equals(loaded);
    }

    private static final class LoadState {
        final List<String> candidates;
        final Runnable onExhausted;
        int candidateIndex;

        LoadState(List<String> candidates, Runnable onExhausted) {
            this.candidates = candidates;
            this.onExhausted = onExhausted;
        }
    }
}
