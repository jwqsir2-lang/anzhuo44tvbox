package com.tvbox.android44.feature.douban;

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
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.ui.AspectImageView;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.common.ui.PosterEntry;
import com.tvbox.android44.common.ui.PosterGridAdapter;
import com.tvbox.android44.data.remote.DoubanClient;
import com.tvbox.android44.data.repository.DoubanRepository;
import com.tvbox.android44.domain.model.DoubanHotItem;
import com.tvbox.android44.feature.main.MainActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 豆瓣热播独立页。
 *
 * <p>布局对应设计稿：顶部大标题 + 统计信息，下方 2 行 6 列海报网格。
 * 点击任一条目 → 携带片名跳到搜索页，在当前视频源里找可播放资源。
 *
 * <p>失败时展示错误态与重试入口，不再静默隐藏。
 */
public class DoubanFragment extends Fragment implements PageFocusState.Owner {

    private static final int COLUMNS = 6;

    private StateLayout state;
    private RecyclerView grid;
    private TextView metaView;
    private PosterGridAdapter adapter;
    private DoubanRepository.RequestHandle handle;
    private List<DoubanHotItem> lastItems = new ArrayList<DoubanHotItem>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_douban, container, false);
        state = root.findViewById(R.id.douban_state);
        grid = root.findViewById(R.id.douban_grid);
        metaView = root.findViewById(R.id.douban_page_meta);

        grid.setLayoutManager(new GridLayoutManager(getActivity(), COLUMNS));
        int spacing = (int) (getResources().getDisplayMetrics().density * 10);
        grid.addItemDecoration(new GridSpacingDecoration(spacing));

        adapter = new PosterGridAdapter(new PosterGridAdapter.OnPosterClick() {
            @Override
            public void onPosterClick(PosterEntry entry, int position) {
                if (entry == null || getActivity() == null) return;
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).switchToSearchWithQuery(entry.title);
                }
            }
        }, AspectImageView.RATIO_POSTER, R.layout.item_poster);
        grid.setAdapter(adapter);

        load();
        return root;
    }

    private void load() {
        if (state == null) return;
        state.showLoading(getString(R.string.douban_loading));
        handle = TvBoxApp.get().douban().hot(DoubanClient.CATEGORY_MOVIE, 1,
                new DoubanRepository.Callback() {
                    @Override
                    public void onResult(Result<List<DoubanHotItem>> result) {
                        if (state == null || getView() == null) return;
                        List<DoubanHotItem> items = result == null ? null : result.data();
                        if (items == null || items.isEmpty()) {
                            String reason = getString(R.string.douban_empty);
                            if (result != null && result.isFailure()) {
                                Result.Failure f = result.asFailure();
                                if (f != null && f.userMessage != null) reason = f.userMessage;
                            }
                            state.showError(reason, new StateLayout.OnRetryListener() {
                                @Override
                                public void onRetry() {
                                    load();
                                }
                            });
                            return;
                        }
                        lastItems = items;
                        render(items);
                        state.showContent();
                    }
                });
    }

    private void render(List<DoubanHotItem> items) {
        List<PosterEntry> entries = new ArrayList<PosterEntry>();
        for (DoubanHotItem it : items) {
            if (it == null || it.title == null || it.title.isEmpty()) continue;
            String sub = it.rating > 0
                    ? getString(R.string.douban_rating, it.rating)
                    : (it.year == null ? "" : it.year);
            entries.add(new PosterEntry("douban-" + it.id, it.title, sub, it.posterUrl, it));
        }
        adapter.setEntries(entries);
        adapter.notifyDataSetChanged();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        metaView.setText(getString(R.string.douban_page_meta, entries.size(), today));
    }

    @Override
    public void restorePageFocus(Bundle bookmarked) {
        if (grid != null && bookmarked != null) {
            PageFocusState.restore(grid, bookmarked);
        }
    }

    @Override
    public void onDestroyView() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
        super.onDestroyView();
        state = null;
        grid = null;
        metaView = null;
        adapter = null;
    }
}
