package com.tvbox.android44.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏夹：按影片（来源 + 影片 ID）去重，最近收藏的排在前面。
 *
 * <p>存的是"怎么找到这部片"的最小集合，而不是播放状态——
 * 播放进度由 WatchHistoryStore 负责，两者互相独立。
 * 点收藏项时按 movieId 回源拉详情，再用历史记录里的进度续播。
 */
public class FavoriteStore {

    /** 一条收藏。 */
    public static class Item {
        public String movieId;
        public String apiLineId;
        public String apiLineName;
        public String movieName;
        public String posterUrl;
        public String typeName;
        public String remarks;
        public long savedAt;

        public String key() {
            return apiLineId + "|" + movieId;
        }
    }

    private static final String PREFS = "favorites";
    private static final String KEY_ITEMS = "items";
    private static final int LIMIT = 200;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Map<String, Item> cache = new LinkedHashMap<String, Item>();

    public FavoriteStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private synchronized void load() {
        String json = prefs.getString(KEY_ITEMS, null);
        cache.clear();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            List<Item> list = gson.fromJson(json, new TypeToken<List<Item>>() { }.getType());
            if (list != null) {
                for (Item it : list) {
                    if (it != null && it.movieId != null) {
                        cache.put(it.key(), it);
                    }
                }
            }
        } catch (Exception ignored) {
            // 数据损坏时清空，不影响启动
        }
    }

    /** 最近收藏在前。 */
    public synchronized List<Item> all() {
        List<Item> list = new ArrayList<Item>(cache.values());
        java.util.Collections.sort(list, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.savedAt, a.savedAt);
            }
        });
        return list;
    }

    public synchronized boolean isFavorite(String apiLineId, String movieId) {
        return cache.containsKey(apiLineId + "|" + movieId);
    }

    /** 收藏；已存在则更新时间戳并移到最前。返回 true 表示当前处于收藏状态。 */
    public synchronized boolean toggle(Item item) {
        if (item == null || item.movieId == null) {
            return false;
        }
        String key = item.key();
        if (cache.containsKey(key)) {
            cache.remove(key);
            persist();
            return false;
        }
        item.savedAt = System.currentTimeMillis();
        cache.put(key, item);
        // 超出上限时丢掉最早的
        while (cache.size() > LIMIT) {
            String oldest = null;
            long ts = Long.MAX_VALUE;
            for (Item it : cache.values()) {
                if (it.savedAt < ts) {
                    ts = it.savedAt;
                    oldest = it.key();
                }
            }
            if (oldest == null) break;
            cache.remove(oldest);
        }
        persist();
        return true;
    }

    public synchronized void clear() {
        cache.clear();
        persist();
    }

    private void persist() {
        try {
            prefs.edit().putString(KEY_ITEMS, gson.toJson(new ArrayList<Item>(cache.values())))
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
