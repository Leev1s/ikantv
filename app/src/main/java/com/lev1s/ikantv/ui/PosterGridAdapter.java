package com.lev1s.ikantv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.lev1s.ikantv.R;
import com.lev1s.ikantv.data.VideoItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Six-column poster rows for paged movie and category screens. */
public final class PosterGridAdapter extends RecyclerView.Adapter<PosterGridAdapter.Holder> {

    public interface OnItemClickListener {
        void onItemClick(VideoItem item);
    }

    public interface OnItemFocusListener {
        void onItemFocused(long stableId);
    }

    private static final Object FOCUS_ROUTE_PAYLOAD = new Object();

    private final Context context;
    private final int cardWidthDp;
    private final PosterLoader posterLoader;
    private final OnItemClickListener clickListener;
    private final OnItemFocusListener focusListener;
    private final List<VideoItem> items = new ArrayList<>();
    private final Set<Long> knownIds = new HashSet<>();

    public PosterGridAdapter(Context context, int cardWidthDp, PosterLoader posterLoader,
                             OnItemClickListener clickListener,
                             OnItemFocusListener focusListener) {
        this.context = context;
        this.cardWidthDp = cardWidthDp;
        this.posterLoader = posterLoader;
        this.clickListener = clickListener;
        this.focusListener = focusListener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return stableId(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public VideoItem getItem(int position) {
        return items.get(position);
    }

    public int positionOfId(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (stableId(items.get(i)) == id) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public void append(List<VideoItem> more) {
        if (more == null || more.isEmpty()) return;
        int start = items.size();
        List<VideoItem> unique = new ArrayList<>(more.size());
        for (VideoItem item : more) {
            if (item != null && knownIds.add(stableId(item))) unique.add(item);
        }
        if (unique.isEmpty()) return;
        items.addAll(unique);
        if (start > 0) {
            int previousLastRow = ((start - 1) / 6) * 6;
            notifyItemRangeChanged(previousLastRow, start - previousLastRow,
                    FOCUS_ROUTE_PAYLOAD);
        }
        notifyItemRangeInserted(start, unique.size());
    }

    public void clear() {
        int oldCount = items.size();
        if (oldCount == 0) return;
        items.clear();
        knownIds.clear();
        notifyItemRangeRemoved(0, oldCount);
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        FrameLayout slot = new FrameLayout(context);
        slot.setClipChildren(false);
        slot.setClipToPadding(false);
        slot.setFocusable(false);
        PosterCardView card = new PosterCardView(context, cardWidthDp);
        card.setId(View.generateViewId());
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(cardWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        slot.addView(card, cardParams);
        return new Holder(slot, card);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        bind(holder, position);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
        if (payloads.contains(FOCUS_ROUTE_PAYLOAD)) {
            holder.card.setNextFocusDownId(isInLastRow(position)
                    ? R.id.footer_text : View.NO_ID);
            return;
        }
        bind(holder, position);
    }

    @Override
    public void onViewRecycled(Holder holder) {
        holder.card.clearPoster(posterLoader);
        holder.item = null;
        super.onViewRecycled(holder);
    }

    private void bind(Holder holder, int position) {
        VideoItem item = items.get(position);
        holder.item = item;
        holder.card.bind(item, posterLoader);
        holder.card.setPosterFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && holder.item != null) {
                focusListener.onItemFocused(stableId(holder.item));
            }
        });
        holder.card.setNextFocusDownId(isInLastRow(position)
                ? R.id.footer_text : View.NO_ID);
        holder.card.setOnClickListener(v -> {
            if (holder.item != null) clickListener.onItemClick(holder.item);
        });
    }

    private boolean isInLastRow(int position) {
        return !items.isEmpty() && position >= ((items.size() - 1) / 6) * 6;
    }

    /** Video IDs are canonical; the content-derived fallback covers exceptional entries. */
    public static long stableId(VideoItem item) {
        if (item.videoId > 0) return item.videoId;

        StringBuilder key = new StringBuilder();
        key.append(item.searchQuery == null ? "" : item.searchQuery).append('\n')
                .append(item.title == null ? "" : item.title).append('\n')
                .append(item.rank).append('\n');
        if (item.posterCandidates != null && !item.posterCandidates.isEmpty()) {
            key.append(item.posterCandidates.get(0));
        }
        long hash = 0xcbf29ce484222325L;
        for (byte value : key.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xff;
            hash *= 0x100000001b3L;
        }
        long id = hash | Long.MIN_VALUE;
        return id == RecyclerView.NO_ID ? Long.MIN_VALUE : id;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final PosterCardView card;
        VideoItem item;

        Holder(ViewGroup slot, PosterCardView card) {
            super(slot);
            this.card = card;
        }
    }
}
