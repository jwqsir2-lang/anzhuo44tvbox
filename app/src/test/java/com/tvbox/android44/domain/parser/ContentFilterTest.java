package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.Movie;

import org.junit.Test;

/** 内容过滤：只按分类过滤；片名/备注一律不参与判断。 */
public class ContentFilterTest {

    private static Movie movie(String name, String typeName, String remarks) {
        Movie m = new Movie("1", "api", "源", name);
        m.typeName = typeName;
        m.remarks = remarks;
        return m;
    }

    @Test
    public void categoryKeywords_blocked() {
        assertTrue(ContentFilter.isBlocked(movie("任意", "伦理片", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "电影解说", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "新闻资讯", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "预告花絮", "")));
    }

    /** 实测来源：suoniapi / hongniuzy2 / 360zyzz / lziapi 的成人向分类名变体。 */
    @Test
    public void adultCategoryVariants_blocked() {
        assertTrue(ContentFilter.isBlocked(movie("任意", "港台三级", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "韩国伦理", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "西方伦理", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "日本伦理", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "两性课堂", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "写真热舞", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "擦边短剧", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "伦理片", "")));
    }


    @Test
    public void nameAndRemarks_neverFiltered() {
        // 片名/备注不再参与过滤：含关键词字面的正常作品必须放行
        assertFalse(ContentFilter.isBlocked(movie("某某电影解说版", "动作片", "")));
        assertFalse(ContentFilter.isBlocked(movie("正常名", "剧情片", "福利合集")));
        assertFalse(ContentFilter.isBlocked(movie("某某演员资料大全", "剧情片", "")));
        assertFalse(ContentFilter.isBlocked(movie("演员请就位", "综艺", "更新至20240815期")));
        assertFalse(ContentFilter.isBlocked(movie("流浪地球2", "科幻片", "HD")));
        assertFalse(ContentFilter.isBlocked(movie("新闻女王", "港剧", "更新至20集")));
        assertFalse(ContentFilter.isBlocked(movie("庆余年（第二季）", "古装", "")));
        // 单字关键词已移除，这些含“欲/裸/激情”的正常片名不得被误伤
        assertFalse(ContentFilter.isBlocked(movie("欲望都市", "美剧", "全6季")));
        assertFalse(ContentFilter.isBlocked(movie("裸婚时代", "国产剧", "全30集")));
        assertFalse(ContentFilter.isBlocked(movie("激情燃烧的岁月", "国产剧", "全22集")));
        assertFalse(ContentFilter.isBlocked(movie("色戒", "剧情片", "HD")));
    }

    @Test
    public void categoryName_hiddenFromTabs() {
        assertTrue(ContentFilter.isBlockedCategory("伦理片"));
        assertTrue(ContentFilter.isBlockedCategory("港台三级"));
        assertTrue(ContentFilter.isBlockedCategory("两性课堂"));
        assertTrue(ContentFilter.isBlockedCategory("擦边短剧"));
        assertTrue(ContentFilter.isBlockedCategory("写真热舞"));
        assertFalse(ContentFilter.isBlockedCategory("动作片"));
        assertFalse(ContentFilter.isBlockedCategory("国产剧"));
        assertFalse(ContentFilter.isBlockedCategory(null));
    }

    @Test
    public void switchOff_disablesAllFiltering() {
        ContentFilter.setEnabled(false);
        try {
            assertFalse(ContentFilter.isBlocked(movie("任意", "伦理片", "")));
            assertFalse(ContentFilter.isBlocked(movie("某某电影解说", "电影解说", "")));
        } finally {
            ContentFilter.setEnabled(true);
        }
        assertTrue(ContentFilter.isBlocked(movie("任意", "伦理片", "")));
    }

    @Test
    public void nullMovie_notBlocked() {
        assertFalse(ContentFilter.isBlocked((Movie) null));
        assertFalse(ContentFilter.isBlocked(null, null, null));
    }
}
