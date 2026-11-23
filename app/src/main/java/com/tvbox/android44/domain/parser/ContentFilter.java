package com.tvbox.android44.domain.parser;

import com.tvbox.android44.domain.model.Movie;

/**
 * 内容过滤：只按「分类」过滤，不看片名。
 *
 * <p>过滤发生在分类层：命中关键词的分类不进入分类栏、不参与列表拉取；
 * 影片层面只看它所属的 typeName，片名与备注一律不参与判断，
 * 以免误伤「欲望都市」「裸婚时代」「激情燃烧的岁月」这类正常作品。
 * 过滤可整体开关，关掉后分类与影片全部照常显示。
 *
 * <p>关键词来源：对 lziapi / ffzyapi / suoniapi / hongniuzy2 / 360zyzz / mujiziyuan
 * 六个常用 MacCMS 采集源的 type_name 做实测枚举，覆盖其全部成人向分类变体。
 */
public final class ContentFilter {

    /**
     * 类型字段关键词（typeName 命中即过滤；也用于首页分类名过滤）。
     * 顺序无关，contains 匹配，短词已足够覆盖长变体（如"伦理"覆盖"日本伦理/西方伦理/韩国伦理"）。
     */
    private static final String[] TYPE_KEYWORDS = {
            // 成人向 —— 实测分类名变体
            "伦理", "三级", "两性", "擦边", "写真", "福利", "成人", "情色",
            "艳照", "裸聊", "色情", "禁片",
            // 内容质量过滤 —— 原版已有
            "解说", "资讯", "新闻", "广告", "宣传", "预告花絮", "演员"
    };

    private ContentFilter() {
    }

    /** 运行时开关。默认开启；由 SettingsRepository.contentFilterEnabled() 在启动与切换时同步。 */
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 兼容旧调用点。name / remarks 参数保留但不再参与判断——过滤已收敛到分类层，
     * 只看影片所属分类（typeName），片名与备注不参与任何过滤。
     */
    public static boolean isBlocked(String name, String typeName, String remarks) {
        return isBlockedCategory(typeName);
    }

    /**
     * 分类是否应被隐藏。分类栏构建、分类下拉都走这里。
     */
    public static boolean isBlockedCategory(String categoryName) {
        if (!enabled || categoryName == null) {
            return false;
        }
        for (String k : TYPE_KEYWORDS) {
            if (categoryName.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 影片是否属于被过滤的分类。只看 typeName，不看片名与备注。 */
    public static boolean isBlocked(Movie movie) {
        if (movie == null || !enabled) {
            return false;
        }
        return isBlockedCategory(movie.typeName);
    }
}
