package com.tvbox.android44.common;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;

/**
 * 三档字号：小 1.15 / 中 1.30 / 大 1.50。
 *
 * <p>面向老年用户，默认「中」档即为大字，不做 1.0 基准。
 * 字号只影响浏览界面（首页、搜索、收藏、历史、播放页）；设置页与节目介绍页
 * 保持系统原始字号，由 {@link #wrapImmutable(Context)} 显式跳过缩放。
 *
 * <p>API 17+ 使用 createConfigurationContext，API 16 使用独立资源上下文。
 */
public final class FontScale {

    /** 小：仍是放大档，低于原始字号老人读不动。 */
    public static final float SCALE_SMALL = 1.15f;
    /** 中：默认档。 */
    public static final float SCALE_NORMAL = 1.30f;
    /** 大：视力更差时使用。 */
    public static final float SCALE_LARGE = 1.50f;

    private FontScale() {
    }

    /** 当前档位倍率。 */
    public static float current() {
        String f = TvBoxApp.get().settings().fontScale();
        if (SettingsRepository.FONT_SMALL.equals(f)) {
            return SCALE_SMALL;
        }
        if (SettingsRepository.FONT_LARGE.equals(f)
                || SettingsRepository.FONT_XLARGE.equals(f)) {
            return SCALE_LARGE;
        }
        return SCALE_NORMAL;
    }

    /** 可缩放界面（列表 / 卡片 / 播放页）使用。中档同样要放大，不能短路返回 base。 */
    public static Context wrap(Context base) {
        return withScale(base, current());
    }

    /**
     * 不缩放界面（设置页 / 节目介绍页）使用。
     *
     * <p>这两页的排版按原始字号调好，跟着放大后会挤掉按钮和简介区，
     * 因此这里固定回落到 1.0，不受设置影响。
     */
    public static Context wrapImmutable(Context base) {
        float current = base.getResources().getConfiguration().fontScale;
        if (Math.abs(current - 1.0f) < 0.001f) {
            return base;
        }
        return withScale(base, 1.0f);
    }

    /** API 16 lacks createConfigurationContext; use isolated resources there. */
    public static Context withScale(final Context base, float scale) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = scale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return base.createConfigurationContext(cfg);
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.setTo(base.getResources().getDisplayMetrics());
        metrics.scaledDensity = metrics.density * scale;
        final android.content.res.Resources resources = new android.content.res.Resources(
                base.getAssets(), metrics, cfg);
        return new android.content.ContextWrapper(base) {
            private android.content.res.Resources.Theme theme;
            private android.view.LayoutInflater inflater;
            @Override public android.content.res.Resources getResources() { return resources; }
            @Override public android.content.res.AssetManager getAssets() { return resources.getAssets(); }
            @Override public android.content.res.Resources.Theme getTheme() {
                if (theme == null) {
                    theme = resources.newTheme();
                    theme.setTo(base.getTheme());
                }
                return theme;
            }
            @Override public void setTheme(int resourceId) { getTheme().applyStyle(resourceId, true); }
            @Override public Object getSystemService(String name) {
                if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                    if (inflater == null) inflater = android.view.LayoutInflater.from(base).cloneInContext(this);
                    return inflater;
                }
                return super.getSystemService(name);
            }
        };
    }
}
