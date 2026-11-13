/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.api.WSession;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;
import com.igalia.wolvic.ui.views.CustomRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Root manga panel. It replaces the browser window as the landing surface and
 * owns the bottom manga tab bar. Pages are plain Android views inside one
 * UIWidget, so the tab bar and content always share the same VR surface and
 * placement.
 *
 * The panel is placed exactly where Wolvic puts its front window: translation
 * values are WORLD UNITS (1 metre = 1 / WORLD_DPI_RATIO = 360 units), not
 * metres. Using a metre-sized value here puts the panel inside the near clip
 * plane and makes it invisible.
 */
public class MangaLibraryWidget extends UIWidget {

    private static final String TAG = "MangaHome";
    private static final String SOURCE_PREFS = "manga_sources";
    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "webm", "avi"};
    private static final int SPAN_COUNT = 4;
    private static final int MAX_LOCAL_ROWS = 60;

    private enum MangaPage {
        LIBRARY("书架"),
        LOCAL("本地"),
        SMB("SMB"),
        WEBDAV("网盘"),
        EXTENSIONS("扩展"),
        SETTINGS("设置"),
        BROWSER("浏览器");

        final String label;

        MangaPage(String label) {
            this.label = label;
        }
    }

    private TextView mTitle;
    private FrameLayout mPageContainer;
    private LinearLayout mTabBar;
    private LinearLayout mLibraryPage;
    private TextView mEmptyText;
    private CustomRecyclerView mGrid;
    private MangaCoverAdapter mAdapter;

    private final Map<MangaPage, View> mPageViews = new EnumMap<>(MangaPage.class);
    private final Map<MangaPage, LinearLayout> mPageContents = new EnumMap<>(MangaPage.class);
    private final Map<MangaPage, Button> mTabButtons = new EnumMap<>(MangaPage.class);

    private MangaPage mCurrentPage = MangaPage.LIBRARY;
    private File mRoot;
    private File mLocalDir;
    private boolean mPermissionRequested;

    public MangaLibraryWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        LayoutInflater.from(getContext()).inflate(R.layout.manga_library, this, true);

        mTitle = findViewById(R.id.libraryTitle);
        mPageContainer = findViewById(R.id.pageContainer);
        mTabBar = findViewById(R.id.tabBar);
        mLibraryPage = findViewById(R.id.libraryPage);
        mEmptyText = findViewById(R.id.emptyText);
        mGrid = findViewById(R.id.mangaGrid);

        mGrid.setLayoutManager(new GridLayoutManager(getContext(), SPAN_COUNT));
        mAdapter = new MangaCoverAdapter(getContext(), this::openReader);
        mGrid.setAdapter(mAdapter);

        mRoot = new File(Environment.getExternalStorageDirectory(), "Manga");
        mLocalDir = Environment.getExternalStorageDirectory();

        mPageViews.put(MangaPage.LIBRARY, mLibraryPage);
        for (MangaPage page : MangaPage.values()) {
            if (page == MangaPage.LIBRARY || page == MangaPage.BROWSER) {
                continue;
            }
            View pageView = createPage(page);
            pageView.setVisibility(GONE);
            mPageContainer.addView(pageView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            mPageViews.put(page, pageView);
        }

        buildTabs();
        switchPage(MangaPage.LIBRARY);
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();

        aPlacement.width = windowWidth + mBorderWidth * 2;
        aPlacement.height = windowHeight + mBorderWidth * 2;
        aPlacement.worldWidth = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT;
        aPlacement.translationY = WidgetPlacement.unitFromMeters(context, R.dimen.window_world_y);
        aPlacement.translationZ = WidgetPlacement.getWindowWorldZMeters(context);
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.parentHandle = -1;
        aPlacement.cylinder = false;
        aPlacement.visible = false;

        Log.i(TAG, "placement width=" + aPlacement.width + " height=" + aPlacement.height
                + " worldWidth=" + aPlacement.worldWidth
                + " y=" + aPlacement.translationY + " z=" + aPlacement.translationZ);
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        refreshCurrentPage();
        super.show(aShowFlags);
        Log.i(TAG, "show page=" + mCurrentPage.name() + " visible=" + isVisible());
    }

    @Override
    public void onResume() {
        // initializeWidgets() runs in onCreate and queues addWidget before the
        // native VR layer is ready. Re-push the placement once the runtime is up
        // so the panel cannot be left at the world origin (near clip plane).
        updateWidget();
        if (hasStoragePermission()) {
            refreshCurrentPage();
        }
        Log.i(TAG, "onResume page=" + mCurrentPage.name());
    }

    @Override
    protected void onDismiss() {
        // Root manga view: the default implementation removes the widget and
        // leaves the hidden browser window behind.
    }

    // ---------------------------------------------------------------- pages

    private View createPage(MangaPage page) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mPageContents.put(page, content);
        fillPage(page, content);
        return scroll;
    }

    private void refreshPage(MangaPage page) {
        LinearLayout content = mPageContents.get(page);
        if (content == null) {
            return;
        }
        content.removeAllViews();
        fillPage(page, content);
    }

    private void fillPage(MangaPage page, LinearLayout content) {
        switch (page) {
            case LOCAL:
                fillLocalPage(content);
                break;
            case SMB:
            case WEBDAV:
                fillConfigPage(page, content);
                break;
            case EXTENSIONS:
                fillExtensionsPage(content);
                break;
            case SETTINGS:
                fillSettingsPage(content);
                break;
            default:
                break;
        }
    }

    private void refreshCurrentPage() {
        if (mCurrentPage == MangaPage.LIBRARY) {
            scanLibrary();
        } else if (mCurrentPage == MangaPage.LOCAL
                || mCurrentPage == MangaPage.SETTINGS
                || mCurrentPage == MangaPage.EXTENSIONS) {
            refreshPage(mCurrentPage);
        }
    }

    private void switchPage(MangaPage page) {
        mCurrentPage = page;
        for (Map.Entry<MangaPage, View> entry : mPageViews.entrySet()) {
            entry.getValue().setVisibility(entry.getKey() == page ? VISIBLE : GONE);
        }
        mTitle.setText("漫画 · " + page.label);
        updateTabs();

        if (page == MangaPage.LIBRARY) {
            scanLibrary();
        } else if (page == MangaPage.LOCAL
                || page == MangaPage.SETTINGS
                || page == MangaPage.EXTENSIONS) {
            refreshPage(page);
        }
        Log.i(TAG, "switchPage " + page.name());
    }

    private void buildTabs() {
        for (MangaPage page : MangaPage.values()) {
            Button button = new Button(getContext());
            button.setText(page.label);
            button.setAllCaps(false);
            button.setTextSize(18f);
            button.setTextColor(0xFFFFFFFF);
            button.setMinWidth(dp(120));
            button.setMinHeight(dp(56));
            button.setOnClickListener(v -> {
                if (page == MangaPage.BROWSER) {
                    showBrowser();
                } else {
                    switchPage(page);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(5), 0, dp(5), 0);
            mTabBar.addView(button, lp);
            mTabButtons.put(page, button);
        }
        updateTabs();
    }

    private void updateTabs() {
        for (Map.Entry<MangaPage, Button> entry : mTabButtons.entrySet()) {
            boolean active = entry.getKey() == mCurrentPage;
            entry.getValue().setBackgroundColor(active ? 0xFF3D5AFE : 0xFF2A2A33);
            entry.getValue().setAlpha(active ? 1.0f : 0.85f);
        }
    }

    // ------------------------------------------------------------ bookshelf

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || mWidgetManager.isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void requestStoragePermission() {
        if (mPermissionRequested || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return;
        }
        mPermissionRequested = true;
        mWidgetManager.requestPermission(
                mRoot.getAbsolutePath(),
                Manifest.permission.READ_EXTERNAL_STORAGE,
                WidgetManagerDelegate.OriginatorType.APPLICATION,
                new WSession.PermissionDelegate.Callback() {
                    @Override
                    public void grant() {
                        mPermissionRequested = false;
                        Log.i(TAG, "storage permission granted");
                        refreshCurrentPage();
                    }

                    @Override
                    public void reject() {
                        mPermissionRequested = false;
                        Log.w(TAG, "storage permission rejected");
                        if (mCurrentPage == MangaPage.LIBRARY) {
                            showEmptyState("需要存储权限才能读取 /sdcard/Manga。请在系统设置中允许后重新进入。 ");
                        }
                    }
                });
    }

    private void scanLibrary() {
        if (!hasStoragePermission()) {
            showEmptyState("需要存储权限才能读取 /sdcard/Manga。 ");
            requestStoragePermission();
            return;
        }

        List<MangaCoverAdapter.MangaItem> items = new ArrayList<>();
        File[] dirs = null;
        try {
            if (mRoot != null && mRoot.isDirectory()) {
                dirs = mRoot.listFiles(File::isDirectory);
            }
        } catch (Throwable ignored) {
        }

        if (dirs != null) {
            Arrays.sort(dirs, (a, b) ->
                    MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
            for (File dir : dirs) {
                if (dir == null || !dir.isDirectory()) {
                    continue;
                }
                File cover = findFirstImage(dir);
                boolean hasVideo = containsVideo(dir);
                items.add(new MangaCoverAdapter.MangaItem(dir, dir.getName(), cover, hasVideo));
            }
        }

        mAdapter.update(items);
        if (items.isEmpty()) {
            showEmptyState("未找到漫画。请将漫画文件夹放入 /sdcard/Manga 后重试。 ");
        } else {
            mEmptyText.setVisibility(GONE);
            mGrid.setVisibility(VISIBLE);
        }
        Log.i(TAG, "scanLibrary items=" + items.size());
    }

    private void showEmptyState(String message) {
        mAdapter.update(new ArrayList<>());
        mEmptyText.setText(message.trim());
        mEmptyText.setVisibility(VISIBLE);
        mGrid.setVisibility(GONE);
    }

    private void openReader(File folder) {
        if (folder == null) {
            return;
        }
        hide(KEEP_WIDGET);
        MangaReaderWidget reader = new MangaReaderWidget(getContext());
        reader.setOnBackListener(() -> show(REQUEST_FOCUS));
        reader.setFolder(folder);
        reader.show(REQUEST_FOCUS);
        Log.i(TAG, "openReader " + folder.getAbsolutePath());
    }

    // --------------------------------------------------------------- local

    private void fillLocalPage(LinearLayout content) {
        addInfo(content, "本地文件浏览",
                "从 /sdcard 开始浏览文件夹；进入含图片或视频的目录后可直接在阅读器中打开。");
        addInfo(content, "当前目录",
                mLocalDir == null ? "不可用" : mLocalDir.getAbsolutePath());

        LinearLayout actions = addRow(content);
        addButton(actions, "返回上级", v -> {
            File parent = mLocalDir != null ? mLocalDir.getParentFile() : null;
            if (parent != null) {
                mLocalDir = parent;
                refreshPage(MangaPage.LOCAL);
            }
        });
        addButton(actions, "漫画根目录", v -> {
            mLocalDir = mRoot;
            refreshPage(MangaPage.LOCAL);
        });
        addButton(actions, "打开阅读", v -> openReader(mLocalDir));

        if (!hasStoragePermission()) {
            addInfo(content, "权限", "存储权限未授予，请在「设置」页申请后重试。");
            return;
        }

        File[] children = mLocalDir != null ? mLocalDir.listFiles() : null;
        if (children == null) {
            addInfo(content, "目录", "无法读取该目录。");
            return;
        }

        List<File> dirs = new ArrayList<>();
        int mediaFiles = 0;
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                dirs.add(child);
            } else if (isMediaFile(child.getName())) {
                mediaFiles++;
            }
        }
        dirs.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));

        addInfo(content, "媒体文件", "当前目录图片/视频数量：" + mediaFiles);
        addInfo(content, "子目录", dirs.isEmpty() ? "无子目录" : "共 " + dirs.size() + " 个");

        int shown = 0;
        for (File dir : dirs) {
            if (shown++ >= MAX_LOCAL_ROWS) {
                addInfo(content, "提示", "仅显示前 " + MAX_LOCAL_ROWS + " 个目录。");
                break;
            }
            Button row = new Button(getContext());
            row.setText(dir.getName() + " /");
            row.setAllCaps(false);
            row.setTextSize(18f);
            row.setTextColor(0xFFFFFFFF);
            row.setBackgroundColor(0xFF23232C);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(52));
            row.setOnClickListener(v -> {
                mLocalDir = dir;
                refreshPage(MangaPage.LOCAL);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            content.addView(row, lp);
        }
    }

    // ------------------------------------------------------- source configs

    /** Alternating key / label pairs used to persist a source configuration. */
    private String[] configFields(MangaPage page) {
        if (page == MangaPage.SMB) {
            return new String[]{
                    "host", "主机地址 / IP",
                    "share", "共享名",
                    "domain", "域（可留空）",
                    "user", "用户名",
                    "pass", "密码",
                    "path", "远程路径"};
        }
        if (page == MangaPage.WEBDAV) {
            return new String[]{
                    "url", "WebDAV 地址",
                    "user", "用户名",
                    "pass", "密码"};
        }
        return new String[0];
    }

    private void fillConfigPage(MangaPage page, LinearLayout content) {
        addInfo(content, page.label + " 来源",
                "协议层尚未接入，当前仅能保存连接配置；实际读取在后续垂直切片实现。");
        addInfo(content, "状态", "未接入（仅保存配置）");

        SharedPreferences prefs = getContext().getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE);
        String[] fields = configFields(page);
        final List<EditText> inputs = new ArrayList<>();

        for (int i = 0; i < fields.length; i += 2) {
            String key = fields[i];
            String label = fields[i + 1];
            TextView caption = new TextView(getContext());
            caption.setText(label);
            caption.setTextSize(17f);
            caption.setTextColor(0xFFB0B0B8);
            caption.setPadding(0, dp(8), 0, 0);
            content.addView(caption);

            EditText input = new EditText(getContext());
            input.setHint(label);
            input.setTextSize(18f);
            input.setTextColor(0xFFFFFFFF);
            input.setText(prefs.getString(page.name() + "_" + key, ""));
            if ("pass".equals(key)) {
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            content.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            inputs.add(input);
        }

        LinearLayout actions = addRow(content);
        addButton(actions, "保存配置", v -> {
            SharedPreferences.Editor editor = prefs.edit();
            for (int i = 0; i < fields.length; i += 2) {
                editor.putString(page.name() + "_" + fields[i],
                        inputs.get(i / 2).getText().toString());
            }
            editor.apply();
            Toast.makeText(getContext(), page.label + " 配置已保存", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "saved config " + page.name());
        });
        addButton(actions, "返回书架", v -> switchPage(MangaPage.LIBRARY));
    }

    private void fillExtensionsPage(LinearLayout content) {
        addInfo(content, "自定义漫画源 / 扩展应用",
                "扩展 APK 加载尚未接入。下面列出设备上名称疑似漫画扩展的已安装应用，供后续接入使用。");
        addInfo(content, "状态", "未接入（仅扫描已安装包）");

        List<String> hits = new ArrayList<>();
        try {
            PackageManager pm = getContext().getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                String name = app.packageName.toLowerCase(Locale.ROOT);
                if (name.contains("tachiyomi") || name.contains("mihon")
                        || name.contains("mangaextension") || name.contains(".extension.")) {
                    hits.add(app.packageName);
                }
            }
        } catch (Throwable ignored) {
        }
        hits.sort(String::compareTo);

        addInfo(content, "扫描结果", hits.isEmpty()
                ? "未发现疑似漫画扩展包。"
                : "发现 " + hits.size() + " 个：");
        for (String pkg : hits) {
            TextView row = new TextView(getContext());
            row.setText(pkg);
            row.setTextSize(17f);
            row.setTextColor(0xFFD0D0E0);
            row.setPadding(0, dp(3), 0, dp(3));
            content.addView(row);
        }

        LinearLayout actions = addRow(content);
        addButton(actions, "重新扫描", v -> refreshPage(MangaPage.EXTENSIONS));
        addButton(actions, "返回书架", v -> switchPage(MangaPage.LIBRARY));
    }

    // ------------------------------------------------------------- settings

    private void fillSettingsPage(LinearLayout content) {
        addInfo(content, "设置", "漫画模式状态与快捷操作。");
        addInfo(content, "存储权限", hasStoragePermission() ? "已授予" : "未授予（书架无法读取）");
        addInfo(content, "漫画根目录", mRoot == null ? "不可用" : mRoot.getAbsolutePath());
        addInfo(content, "当前页面", mCurrentPage.label);
        addInfo(content, "功能状态",
                "书架 / 本地 / 阅读器可用；SMB、WebDAV、扩展源协议层尚未接入。");

        LinearLayout actions = addRow(content);
        addButton(actions, "重新扫描书架", v -> {
            switchPage(MangaPage.LIBRARY);
            scanLibrary();
        });
        Button permission = addButton(actions, "申请存储权限", v -> {
            mPermissionRequested = false;
            requestStoragePermission();
        });
        permission.setEnabled(!hasStoragePermission());
        addButton(actions, "进入浏览器", v -> showBrowser());
    }

    private void showBrowser() {
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).showBrowserFromManga();
            Log.i(TAG, "switch to browser");
        }
    }

    // -------------------------------------------------------------- helpers

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addInfo(LinearLayout content, String title, String body) {
        TextView caption = new TextView(getContext());
        caption.setText(title);
        caption.setTextSize(21f);
        caption.setTextColor(0xFFFFFFFF);
        caption.setPadding(0, dp(8), 0, dp(2));
        content.addView(caption);

        if (body != null && !body.isEmpty()) {
            TextView text = new TextView(getContext());
            text.setText(body);
            text.setTextSize(17f);
            text.setTextColor(0xFFB0B0B8);
            text.setPadding(0, 0, 0, dp(8));
            content.addView(text);
        }
    }

    private LinearLayout addRow(LinearLayout content) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Button addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(17f);
        button.setTextColor(0xFFFFFFFF);
        button.setBackgroundColor(0xFF2A2A33);
        button.setMinWidth(dp(120));
        button.setMinHeight(dp(52));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        row.addView(button, lp);
        return button;
    }

    private static boolean isMediaFile(String name) {
        return hasExtension(name, IMAGE_EXTENSIONS) || hasExtension(name, VIDEO_EXTENSIONS);
    }

    private static File findFirstImage(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<File> images = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && hasExtension(file.getName(), IMAGE_EXTENSIONS)) {
                images.add(file);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        images.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        return images.get(0);
    }

    private static boolean containsVideo(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && hasExtension(file.getName(), VIDEO_EXTENSIONS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }
}
