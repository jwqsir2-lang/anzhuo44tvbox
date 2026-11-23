package com.tvbox.android44.feature.favorite;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.TvDialogs;
import com.tvbox.android44.common.ui.AspectImageView;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.common.ui.PosterEntry;
import com.tvbox.android44.common.ui.PosterGridAdapter;
import com.tvbox.android44.data.local.FavoriteStore;
import com.tvbox.android44.feature.detail.DetailActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏页：收藏过的影片网格墙。
 *
 * <p>点击条目 → 打开详情页；详情页会按 movieId 自动找到历史进度续播。
 * 长按不处理，取消收藏在详情页的收藏按钮里做。
 */
public class FavoriteFragment extends Fragment implements PageFocusState.Owner {

    private static final int COLUMNS = 8;

    private StateLayout state;
    private RecyclerView grid;
    private TextView metaView;
    private PosterGridAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_favorite, container, false);
        state = root.findViewById(R.id.favorite_state);
        grid = root.findViewById(R.id.favorite_grid);
        metaView = root.findViewById(R.id.favorite_meta);

        grid.setLayoutManager(new GridLayoutManager(getActivity(), COLUMNS));
        int spacing = (int) (getResources().getDisplayMetrics().density * 10);
        grid.addItemDecoration(new GridSpacingDecoration(spacing));

        adapter = new PosterGridAdapter(new PosterGridAdapter.OnPosterClick() {
            @Override
            public void onPosterClick(PosterEntry entry, int position) {
                if (entry == null || !(entry.payload instanceof FavoriteStore.Item)
                        || getActivity() == null) {
                    return;
                }
                FavoriteStore.Item it = (FavoriteStore.Item) entry.payload;
                DetailActivity.startForResult(requireActivity(), it.apiLineId, it.movieId);
            }
        }, AspectImageView.RATIO_POSTER, R.layout.item_poster);
        grid.setAdapter(adapter);

        root.findViewById(R.id.favorite_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TvDialogs.confirm(getActivity(), getString(R.string.nav_favorite),
                        "确定清空全部收藏吗？",
                        new TvDialogs.ConfirmListener() {
                            @Override
                            public void onConfirm() {
                                TvBoxApp.get().favorites().clear();
                                render();
                            }
                        });
            }
        });

        render();
        return root;
    }

    private void render() {
        if (state == null || adapter == null) return;
        List<FavoriteStore.Item> favorites = TvBoxApp.get().favorites().all();
        if (favorites.isEmpty()) {
            adapter.setEntries(new ArrayList<PosterEntry>());
            metaView.setText("");
            state.showEmpty(getString(R.string.favorite_empty));
            return;
        }
        List<PosterEntry> entries = new ArrayList<PosterEntry>();
        for (FavoriteStore.Item it : favorites) {
            if (it == null || it.movieName == null || it.movieName.isEmpty()) continue;
            entries.add(new PosterEntry(it.key(), it.movieName,
                    it.remarks == null ? "" : it.remarks, it.posterUrl, it));
        }
        adapter.setEntries(entries);
        adapter.notifyDataSetChanged();
        metaView.setText("共 " + entries.size() + " 部");
        if (entries.isEmpty()) {
            state.showEmpty(getString(R.string.favorite_empty));
        } else {
            state.showContent();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            render();
        }
    }

    @Override
    public void restorePageFocus(Bundle bookmarked) {
        if (grid != null && bookmarked != null) {
            PageFocusState.restore(grid, bookmarked);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        state = null;
        grid = null;
        metaView = null;
        adapter = null;
    }
}
