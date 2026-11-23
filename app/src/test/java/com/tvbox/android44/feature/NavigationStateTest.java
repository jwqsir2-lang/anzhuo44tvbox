package com.tvbox.android44.feature;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.ui.*;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.local.HistoryStore;
import com.tvbox.android44.data.repository.*;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.AiRecommendItem;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import androidx.fragment.app.Fragment;

import com.tvbox.android44.feature.home.HomeFragment;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.feature.search.SearchFragment;
import com.tvbox.android44.feature.player.PlayerActivity;
import com.tvbox.android44.feature.settings.ConfigHttpServer;
import com.tvbox.android44.feature.settings.LoopbackPhoneSearchFragment;
import com.tvbox.android44.testutil.TestSettings;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19}, application = NavigationStateTest.LocalApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationStateTest {
    public static class LocalApplication extends TvBoxApp {
        private TestSettings testSettings;
        private MovieRepository testMovies;
        private MultiSourceSearch testSearch;
        private volatile List<WatchHistoryItem> historyItems = Collections.emptyList();
        private HistoryStore testHistory;
        void configure(String url) {
            configure(new ApiLine("local", "测试源", url, false));
        }
        void configure(ApiLine... sources) {
            testSettings = new TestSettings(sources);
            testMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            testSearch = new MultiSourceSearch(executors().network(), testMovies, testSettings);
        }
        @Override public SettingsRepository settings() { return testSettings == null ? super.settings() : testSettings; }
        @Override public MovieRepository movies() { return testMovies == null ? super.movies() : testMovies; }
        @Override public MultiSourceSearch search() { return testSearch == null ? super.search() : testSearch; }
        @Override public synchronized HistoryStore history() {
            if (testHistory == null) {
                testHistory = new HistoryStore(this) {
                    @Override public synchronized List<WatchHistoryItem> load() {
                        return new ArrayList<>(historyItems);
                    }
                };
            }
            return testHistory;
        }
    }
    private MockWebServer server;
    private ActivityController<MainActivity> controller;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        ((LocalApplication) RuntimeEnvironment.getApplication()).configure(server.url("/api/").toString());
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("{\"page\":1,\"pagecount\":1,\"total\":2,\"class\":["
                        + "{\"type_id\":1,\"type_pid\":0,\"type_name\":\"电影\"},"
                        + "{\"type_id\":2,\"type_pid\":1,\"type_name\":\"动作\"}],\"list\":["
                        + "{\"vod_id\":100,\"type_id\":2,\"vod_name\":\"影片一\"},"
                        + "{\"vod_id\":101,\"type_id\":2,\"vod_name\":\"影片二\"}]}" );
            }
        });
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().postResume().visible();
        // 默认首页已改为 DOUBAN（豆瓣热播），本组用例针对「影视」页，需显式切过去。
        controller.get().selectTab(MainActivity.Tab.HOME, false);
        controller.get().getSupportFragmentManager().executePendingTransactions();
    }
    @After public void tearDown() throws Exception {
        controller.pause().stop().destroy(); TvBoxApp.get().executors().shutdown(); server.shutdown();
    }
    /**
     * HOME 页（影视）自己的视图根。
     * 注意：Activity.findViewById 会命中被 hide 的 DOUBAN 视图里同名的 state_* 控件
     * （两者都 inflate 了 view_state.xml），所以 home_* 系列一律从这个根上找。
     */
    private View homeRoot() {
        HomeFragment home = (HomeFragment) controller.get()
                .getSupportFragmentManager().findFragmentByTag("HOME");
        assertNotNull("HOME Fragment 应已创建", home);
        assertNotNull("HOME 视图应已 inflate", home.getView());
        return home.getView();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            new java.util.concurrent.CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("UI did not reach expected state", condition.getAsBoolean());
    }
    @Test public void homeUsesSourceContentAndSavesCategorySelection() throws Exception {
        RecyclerView tabs = homeRoot().findViewById(R.id.home_tabs);
        await(() -> tabs.getAdapter().getItemCount() == 2);
        tabs.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(100, 1073741824));
        tabs.layout(0, 0, 1200, 100);
        tabs.findViewHolderForAdapterPosition(1).itemView.performClick();
        HomeFragment home = (HomeFragment) controller.get().getSupportFragmentManager().findFragmentByTag("HOME");
        Bundle saved = new Bundle(); home.onSaveInstanceState(saved);
        assertEquals("1", saved.getString("tab")); assertEquals("local", saved.getString("api"));
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        for (int i = 1; i < server.getRequestCount(); i++) {
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS).getPath().startsWith("/api/"));
        }
    }

    @Test public void homeSkipsFailedAndEmptySourcesAndUsesBackupForCategories() throws Exception {
        List<String> pages = Collections.synchronizedList(new ArrayList<String>());
        List<String> categories = Collections.synchronizedList(new ArrayList<String>());
        restartWithSources(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("list".equals(request.getRequestUrl().queryParameter("ac"))) {
                    categories.add(path);
                    // A late category response from the failed source must not appear with the backup.
                    return "/working/".equals(path)
                            ? new MockResponse().setBody("{\"class\":[{\"type_id\":7,\"type_name\":\"备用分类\"}]}")
                            : new MockResponse().setBodyDelay(300, TimeUnit.MILLISECONDS)
                                    .setBody("{\"class\":[{\"type_id\":9,\"type_name\":\"旧分类\"}]}");
                }
                // 只记录本轮关心的三个源；/api/ 是 setUp 默认源，restartWithSources 之前的
                // 残留请求可能晚到，不参与断言。
                if ("/primary/".equals(path) || "/empty/".equals(path) || "/working/".equals(path)) {
                    pages.add(path);
                }
                if ("/primary/".equals(path)) return new MockResponse().setResponseCode(504);
                if ("/empty/".equals(path)) return new MockResponse().setBody("{\"list\":[]}");
                if ("/working/".equals(path)) return homeMovies();
                return new MockResponse().setResponseCode(503);
            }
        }, "primary", "empty", "working");

        RecyclerView grid = homeRoot().findViewById(R.id.home_grid);
        RecyclerView tabs = homeRoot().findViewById(R.id.home_tabs);
        ChipAdapter chips = (ChipAdapter) tabs.getAdapter();
        await(() -> grid.getAdapter().getItemCount() == 1 && chips.indexOf("7") >= 0);
        assertEquals(Arrays.asList("/primary/", "/empty/", "/working/"), new ArrayList<>(pages));
        assertTrue(categories.contains("/working/"));
        assertEquals(-1, chips.indexOf("9"));
        Movie shown = (Movie) ((PosterGridAdapter) grid.getAdapter()).entries().get(0).payload;
        assertEquals("working", shown.apiLineId);
        assertEquals("primary", TvBoxApp.get().settings().currentApi().id);
        android.widget.TextView status = homeRoot().findViewById(R.id.home_status);
        assertTrue(status.getText().toString().contains("working"));
        assertFalse(status.isFocusable());
    }

    @Test public void homeExhaustsBackupsOnceAndRetryPrefersTheDefaultAgain() throws Exception {
        AtomicBoolean primaryRecovered = new AtomicBoolean();
        List<String> pages = Collections.synchronizedList(new ArrayList<String>());
        restartWithSources(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                // /api/ 是 setUp 默认源的残留请求，可能晚到，不参与断言
                if (("/primary/".equals(path) || "/backup/".equals(path))
                        && "detail".equals(request.getRequestUrl().queryParameter("ac"))) {
                    pages.add(path);
                }
                if ("/primary/".equals(path) && primaryRecovered.get()) return homeMovies();
                return new MockResponse().setResponseCode(503);
            }
        }, "primary", "backup");

        // 关键：Activity 根视图下同时挂着 DOUBAN 与 HOME 两套 StateLayout，
        // view_state.xml 里的 state_retry 在两个布局里同名，Activity.findViewById 会命中最先
        // 挂上去的那份（这里是被 hide 的 DOUBAN）。必须从 HOME 自己的视图里找。
        HomeFragment home = (HomeFragment) controller.get()
                .getSupportFragmentManager().findFragmentByTag("HOME");
        assertNotNull("HOME Fragment 应已创建", home);
        assertNotNull("HOME 视图应已 inflate", home.getView());
        final View homeView = home.getView();

        await(() -> {
            View r = homeView.findViewById(R.id.state_retry);
            return r != null && r.getVisibility() == View.VISIBLE;
        });
        assertEquals(Arrays.asList("/primary/", "/backup/"), new ArrayList<>(pages));

        primaryRecovered.set(true);
        homeView.findViewById(R.id.state_retry).performClick();
        final RecyclerView grid = homeView.findViewById(R.id.home_grid);
        await(() -> grid.getAdapter() != null && grid.getAdapter().getItemCount() == 1);
        assertEquals(Arrays.asList("/primary/", "/backup/", "/primary/"), new ArrayList<>(pages));
        Movie shown = (Movie) ((PosterGridAdapter) grid.getAdapter()).entries().get(0).payload;
        assertEquals("primary", shown.apiLineId);
    }

    /**
     * 首页不再提供「继续观看」区域：历史续播统一走「历史」页。
     * 这里断言该区块已从首页布局中彻底移除，且刷新历史不会让首页崩掉。
     */
    @Test public void homeNoLongerRendersContinueWatchingSection() throws Exception {
        MainActivity activity = controller.get();
        assertNull("首页布局不应再包含继续观看区块",
                activity.findViewById(R.id.home_continue_section));
        assertNull("首页布局不应再包含继续观看列表",
                activity.findViewById(R.id.home_continue_grid));

        List<WatchHistoryItem> items = new ArrayList<>();
        items.add(historyItem("finished", 99000));
        for (int i = 0; i < 7; i++) items.add(historyItem("item-" + i, 30000));
        ((LocalApplication) RuntimeEnvironment.getApplication()).historyItems = items;
        HomeFragment home = (HomeFragment) activity.getSupportFragmentManager().findFragmentByTag("HOME");
        // 历史变更回调仍会被触发，但首页不再渲染该区域，不得抛异常。
        home.onHistoryChanged();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(activity.findViewById(R.id.home_continue_section));
    }

    private static WatchHistoryItem historyItem(String id, long position) {
        WatchHistoryItem item = new WatchHistoryItem();
        item.apiLineId = "old-source";
        item.movieId = id;
        item.movieName = id;
        item.lineId = "old-source|line";
        item.episodeIndex = 2;
        item.duration = 100000;
        item.position = position;
        return item;
    }

    @Test public void phoneSearchSubmitsKeywordToTvAndLeavingSearchStopsTheSession() throws Exception {
        MainActivity activity = controller.get();
        SearchFragment search = new LoopbackPhoneSearchFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(R.id.content, search, "SEARCH").hide(search).commitNow();
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        ConfigHttpServer session = openLoopbackSearch(search);
        String url = "http://127.0.0.1:" + session.port() + "/" + session.token();
        Future<Integer> response = TvBoxApp.get().executors().network().submit(() -> {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try {
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    stream.write("query=%E5%BD%B1%E7%89%87%E4%B8%80".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        });
        await(response::isDone);
        assertEquals(200, (int) response.get());
        EditText input = activity.findViewById(R.id.search_input);
        RecyclerView results = activity.findViewById(R.id.search_grid);
        await(() -> !session.isRunning() && results.getAdapter().getItemCount() == 2
                && org.robolectric.util.ReflectionHelpers.getField(search, "phoneSearchSession") == null);
        assertEquals("影片一", input.getText().toString());
        assertEquals(input.length(), input.getSelectionStart());

        ConfigHttpServer reopened = openLoopbackSearch(search);
        activity.selectTab(MainActivity.Tab.HOME, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(search.isHidden());
        assertFalse(reopened.isRunning());
    }

    private static ConfigHttpServer openLoopbackSearch(SearchFragment search) {
        assertTrue("Search fragment must be resumed before opening a session", search.isResumed());
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(search, "openPhoneSearchAt",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class, "127.0.0.1"));
        ConfigHttpServer session = org.robolectric.util.ReflectionHelpers.getField(search, "phoneSearchSession");
        assertNotNull(session);
        assertTrue(session.isRunning());
        return session;
    }

    private void restartWithSources(Dispatcher dispatcher, String... names) {
        controller.pause().stop().destroy();
        ApiLine[] sources = new ApiLine[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            sources[i] = new ApiLine(name, name, server.url("/" + name + "/").toString(), false);
        }
        server.setDispatcher(dispatcher);
        ((LocalApplication) RuntimeEnvironment.getApplication()).configure(sources);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().postResume().visible();
        // 默认首页是 DOUBAN，本组用例针对「影视」页，需显式切过去并推动一次事务，
        // 让 HomeFragment 的 onCreateView/reload 真正跑起来（否则 state_retry 永不可见）。
        MainActivity rebuilt = controller.get();
        rebuilt.selectTab(MainActivity.Tab.HOME, false);
        rebuilt.getSupportFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static MockResponse homeMovies() {
        return new MockResponse().setBody("{\"page\":1,\"pagecount\":1,\"total\":1,"
                + "\"list\":[{\"vod_id\":300,\"type_id\":7,\"vod_name\":\"测试影片\"}]}");
    }
    @Test public void presetQueryBeforeViewCreationRunsAndQueryIsSaved() throws Exception {
        SearchFragment search = new SearchFragment();
        search.presetQuery("影片一");
        controller.get().getSupportFragmentManager().beginTransaction().replace(R.id.content, search, "test-search").commitNow();
        RecyclerView grid = search.getView().findViewById(R.id.search_grid);
        await(() -> grid.getAdapter().getItemCount() == 2);
        assertEquals("影片一", ((EditText) search.getView().findViewById(R.id.search_input)).getText().toString());
        Bundle saved = new Bundle(); search.onSaveInstanceState(saved);
        assertEquals("影片一", saved.getString("query"));
    }
    @Test public void bookmarkTracksStableCardAfterMetadataChangesAndReordering() throws Exception {
        RecyclerView grid = homeRoot().findViewById(R.id.home_grid);
        await(() -> grid.getAdapter().getItemCount() == 2);
        grid.setItemAnimator(null);
        grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(800, 1073741824));
        grid.layout(0, 0, 1200, 800);
        android.view.View card = grid.findViewHolderForAdapterPosition(1).itemView;
        assertTrue("API 19 cards must be explicitly keyboard-focusable", card.isFocusable());
        assertTrue(card.requestFocus());
        Bundle bookmark = PageFocusState.capture(controller.get().findViewById(R.id.content));
        assertEquals(R.id.home_grid, bookmark.getInt("list"));
        assertEquals(1, bookmark.getInt("position"));
        PosterGridAdapter adapter = (PosterGridAdapter) grid.getAdapter();
        List<PosterEntry> entries = new ArrayList<>(adapter.entries());
        PosterEntry selected = entries.get(1);
        entries.set(1, new PosterEntry(selected.key, "更新的主源元数据", "", "", selected.payload));
        adapter.updateEntries(entries);
        grid.layout(0, 0, 1200, 800);
        assertEquals(bookmark.getLong("item"), adapter.getItemId(1));
        Collections.reverse(entries); adapter.setEntries(entries);
        grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(800, 1073741824));
        grid.layout(0, 0, 1200, 800);
        assertTrue(PageFocusState.restore(controller.get().findViewById(R.id.content), bookmark));
        await(() -> grid.findFocus() != null && grid.findContainingItemView(grid.findFocus()) != null
                && grid.getChildAdapterPosition(grid.findContainingItemView(grid.findFocus())) == 0);
    }
    @Test public void hiddenLiveTabsResolveToSourceHome() {
        controller.get().selectTab(MainActivity.Tab.LIVE_TV, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        assertFalse(controller.get().getSupportFragmentManager().findFragmentByTag("HOME").isHidden());
        assertNull(controller.get().getSupportFragmentManager().findFragmentByTag("LIVE_TV"));
    }

    @Test public void activityRecreationRestoresSelectedSourceCategory() throws Exception {
        RecyclerView tabs = homeRoot().findViewById(R.id.home_tabs);
        await(() -> tabs.getAdapter().getItemCount() == 2);
        tabs.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(100, 1073741824));
        tabs.layout(0, 0, 1200, 100);
        tabs.findViewHolderForAdapterPosition(1).itemView.performClick();
        Bundle saved = new Bundle(); controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        RecyclerView restored = homeRoot().findViewById(R.id.home_tabs);
        await(() -> restored.getAdapter().getItemCount() == 2);
        ChipAdapter chips = (ChipAdapter) restored.getAdapter();
        assertTrue(chips.chips().get(chips.indexOf("1")).selected);
        assertFalse(chips.chips().get(chips.indexOf("all")).selected);
    }

    @Test public void fontSettingAppliesToMainPageAfterRecreation() {
        TvBoxApp.get().settings().setFontScale(SettingsRepository.FONT_LARGE);
        Bundle saved = new Bundle(); controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        assertEquals(com.tvbox.android44.common.FontScale.SCALE_LARGE,
                controller.get().getResources().getConfiguration().fontScale, 0.001f);
    }

    @Test @Config(sdk = {16, 19, 23, 28})
    public void settingsOnlyKeepsQrConfigurationAndManagementEntries() {
        controller.get().selectTab(MainActivity.Tab.SETTINGS, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        android.view.View settings = controller.get().getSupportFragmentManager()
                .findFragmentByTag("SETTINGS").getView();
        assertNotNull(settings);
        for (String removed : new String[]{"settings_api_add", "settings_ai_model"}) {
            int id = controller.get().getResources().getIdentifier(removed, "id", controller.get().getPackageName());
            assertTrue(id == 0 || settings.findViewById(id) == null);
        }
        assertTrue(settings.findViewById(R.id.settings_api_manage).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_api_qr).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_ai_qr).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_ai_key).isFocusable());
    }

    @Test @Config(sdk = {16, 19, 23, 28})
    public void settingsRemoteFocusFollowsVisibleRowsAndDoesNotJumpByGeometry() {
        controller.get().selectTab(MainActivity.Tab.SETTINGS, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        android.view.View settings = controller.get().getSupportFragmentManager()
                .findFragmentByTag("SETTINGS").getView();
        assertNotNull(settings);

        assertTrue(settings.findViewById(R.id.settings_font).requestFocus());
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.nav_settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_font, settings);
        // 上下换行统一落在行首：IPTV 行首就是地址入口
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_iptv, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_api, settings);
        // 地址 / 管理 / 二维码 同一排：左右切换
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_api_manage, settings);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_api_qr, settings);
        direction(KeyEvent.KEYCODE_DPAD_LEFT, R.id.settings_api_manage, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_provider, settings);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_api, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_provider, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_key, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_qr, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_auto_line, settings);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_stats, settings);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_stats_clear, settings);
        // 最后一行的行首是开关，更新按钮在其右侧
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_check_toggle, settings);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_auto_line, settings);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_stats, settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_check_toggle, settings);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_update_action, settings);
        // 最后一行再按 DOWN 没有下一行，焦点保持不动
        View before = settings.findViewById(R.id.settings_update_action);
        controller.get().dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        controller.get().dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue(before.hasFocus());
    }

    /** 直播源地址行必须能用遥控器选到，并左右进入「测试」按钮。 */
    @Test @Config(sdk = {16, 19, 23, 28})
    public void settingsIptvRowIsReachableByRemote() {
        controller.get().selectTab(MainActivity.Tab.SETTINGS, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        android.view.View settings = controller.get().getSupportFragmentManager()
                .findFragmentByTag("SETTINGS").getView();
        assertNotNull(settings);

        assertTrue(settings.findViewById(R.id.settings_iptv).requestFocus());
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_iptv_test, settings);
        direction(KeyEvent.KEYCODE_DPAD_LEFT, R.id.settings_iptv, settings);
    }

    private void direction(int code, int target) {
        direction(code, target, controller.get().getWindow().getDecorView());
    }

    /** 设置页控件挂在 Fragment 视图树上，Activity.findViewById 找不到，需指定查找根。 */
    private void direction(int code, int target, View root) {
        View focused = controller.get().getWindow().getDecorView().findFocus();
        assertTrue("D-pad " + code + " from " + focused + " to " + root.findViewById(target),
                controller.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
        controller.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        assertTrue(root.findViewById(target).hasFocus());
    }

    @Test public void focusedSearchEditorReceivesDigitsAndShortcutsDoNotRepeat() {
        MainActivity activity = controller.get();
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        EditText input = activity.findViewById(R.id.search_input);
        assertTrue(input.requestFocus());
        KeyEvent[] typing = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents("2012".toCharArray());
        assertNotNull(typing);
        for (KeyEvent event : typing) {
            activity.dispatchKeyEvent(event);
        }
        activity.getSupportFragmentManager().executePendingTransactions();
        assertFalse(activity.getSupportFragmentManager().findFragmentByTag("SEARCH").isHidden());
        assertNull(activity.getSupportFragmentManager().findFragmentByTag("HISTORY"));
        assertTrue(input.hasFocus());
        assertTrue(activity.findViewById(R.id.search_button).requestFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6)));
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.findViewById(R.id.settings_api).requestFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6, 1)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_6)));
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.findViewById(R.id.settings_api).hasFocus());
    }

    @Test public void searchOpenedFromRecommendationReturnsToItsCard() throws Exception {
        MainActivity activity = controller.get();
        activity.selectTab(MainActivity.Tab.RECOMMEND, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        RecyclerView list = activity.findViewById(R.id.recommend_list);
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(list.getAdapter(), "setItems",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(List.class,
                        Collections.singletonList(new AiRecommendItem("Title", "Title", "Reason"))));
        ((com.tvbox.android44.common.StateLayout) activity.findViewById(R.id.recommend_state)).showContent();
        list.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1000, 500);
        View card = list.findViewHolderForAdapterPosition(0).itemView;
        assertTrue(card.requestFocus());
        card.performClick();
        assertEquals("Title", ((EditText) activity.findViewById(R.id.search_input)).getText().toString());
        activity.onBackPressed();
        activity.getSupportFragmentManager().executePendingTransactions();
        await(() -> card.hasFocus());
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        activity.onBackPressed();
        activity.getSupportFragmentManager().executePendingTransactions();
        // 返回键落点是首页（默认 Tab = DOUBAN），不是「影视」页
        Fragment homeTab = activity.getSupportFragmentManager().findFragmentByTag("DOUBAN");
        assertNotNull("返回后应回到默认首页", homeTab);
        assertFalse("返回后默认首页必须可见", homeTab.isHidden());
    }
}
