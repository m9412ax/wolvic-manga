/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.GridLayoutManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.api.WSession;
import com.igalia.wolvic.manga.MangaDownloader;
import com.igalia.wolvic.manga.MangaKeyCapture;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.MangaSettingsPanel;
import com.igalia.wolvic.manga.MangaSourceManager;
import com.igalia.wolvic.manga.MangaSourceRepository;
import com.igalia.wolvic.manga.MangaWebDav;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;
import com.igalia.wolvic.ui.adapters.SourceCoverAdapter;
import com.igalia.wolvic.ui.views.CustomRecyclerView;

import eu.kanade.tachiyomi.source.ConfigurableSource;
import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.Filter;
import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Manga content panel. Page switching is NOT implemented here: the original
 * Wolvic TrayWidget is the control bar (see tray.xml / TrayWidget.setMangaMode).
 * This widget only renders the selected page's content, so the browser and
 * manga modes share one UI and one widget tree.
 */
public class MangaLibraryWidget extends UIWidget {

    private static final String TAG = "MangaHome";
    private static final int MAX_LOCAL_ROWS = 80;
    private static final float PANEL_Z_OFFSET = 40.0f;

    public enum MangaPage {
        LIBRARY("书架"),
        LOCAL("本地文件夹"),
        SMB("SMB 共享"),
        WEBDAV("网盘 WebDAV"),
        EXTENSIONS("漫画源扩展"),
        SOURCE_BROWSE("图源浏览"),
        SOURCE_MANGA("漫画详情"),
        SOURCE_SETTINGS("图源设置"),
        SOURCE_FILTERS("筛选"),
        SOURCE_OPTIONS("选择选项"),
        SOURCE_TEXT("输入文本"),
        FOLDER("文件夹"),
        SETTINGS("设置");

        final String label;

        MangaPage(String label) {
            this.label = label;
        }
    }

    private TextView mTitle;
    private FrameLayout mPageContainer;
    private LinearLayout mLibraryPage;
    private TextView mEmptyText;
    private CustomRecyclerView mGrid;
    private MangaCoverAdapter mAdapter;
    private View mDragBar;
    private MangaSettingsPanel mSettingsPanel;

    private final Map<MangaPage, View> mPageViews = new EnumMap<>(MangaPage.class);
    private final Map<MangaPage, LinearLayout> mPageContents = new EnumMap<>(MangaPage.class);

    private MangaPage mCurrentPage = MangaPage.LIBRARY;
    private MangaSettings mSettings;
    private File mRoot;
    private File mLocalDir;
    /** Folder currently shown in the thumbnail / detail view. */
    private File mFolderDir;
    private boolean mFolderDetail;
    private float mFolderThumbScale = 1.0f;
    private boolean mPermissionRequested;

    private String mWebDavUrl = "";
    private String mWebDavStatus = "未连接";

    private MangaSourceManager.LoadedSource mSourceEntry;
    private String mSourceQuery = "";
    private int mSourcePage = 1;
    private boolean mSourceLatest = false;
    private String mSourceStatus = "";
    private final List<SManga> mSourceMangas = new ArrayList<>();
    private boolean mSourceHasNext = false;
    private SManga mSourceManga;
    private String mSourceMangaStatus = "";
    private final List<SChapter> mSourceChapters = new ArrayList<>();
    private final Set<String> mExpandedPkgs = new HashSet<>();
    private boolean mExpandedInit = false;
    private MangaSourceManager.LoadedSource mSettingsSource;
    private PreferenceScreen mPrefScreen;
    private FilterList mSourceFilters;
    private boolean mSourceFiltersActive = false;
    private CustomRecyclerView mSourceGrid;
    private SourceCoverAdapter mSourceAdapter;
    private Source mSourceAdapterSource;
    private MangaPage mOptionReturn = MangaPage.SOURCE_SETTINGS;
    private String mOptionTitle = "";
    private String[] mOptionLabels = new String[0];
    private String[] mOptionValues = new String[0];
    private String mOptionCurrent = "";
    private Consumer<String> mOptionPick;
    private MangaPage mTextReturn = MangaPage.SOURCE_SETTINGS;
    private String mTextTitle = "";
    private String mTextValue = "";
    private Consumer<String> mTextSave;
    private final List<MangaWebDav.Entry> mWebDavEntries = new ArrayList<>();
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "manga-io");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public MangaLibraryWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        mSettings = MangaSettings.get(getContext());
        mSettings.ensureDefaultBindings();
        LayoutInflater.from(getContext()).inflate(R.layout.manga_library, this, true);

        mTitle = findViewById(R.id.libraryTitle);
        mPageContainer = findViewById(R.id.pageContainer);
        mLibraryPage = findViewById(R.id.libraryPage);
        mEmptyText = findViewById(R.id.emptyText);
        mGrid = findViewById(R.id.mangaGrid);
        mDragBar = findViewById(R.id.dragBar);

        // Opens the stock settings dialog straight into the manga tab. There is
        // only one settings surface now; the shelf no longer has its own page.
        View settingsShortcut = findViewById(R.id.settingsShortcutButton);
        if (settingsShortcut != null) {
            settingsShortcut.setOnClickListener(v -> {
                Context context = getContext();
                if (context instanceof VRBrowserActivity) {
                    ((VRBrowserActivity) context).showMangaSettingsTab();
                }
            });
        }

        mFolderThumbScale = Math.max(0.5f, Math.min(2.0f,
                mSettings.getFloat(MangaSettings.FOLDER_THUMB_SCALE, 1.0f)));
        mGrid.setLayoutManager(new GridLayoutManager(getContext(), spanCount()));
        mAdapter = new MangaCoverAdapter(getContext(), this::openFolder, this::resetProgress);
        mAdapter.setCompact(mSettings.integer(MangaSettings.LIBRARY_VIEW_MODE) == 1);
        mGrid.setAdapter(mAdapter);
        setupLibraryToolbar();
        setupPanelDrag();

        mRoot = new File(mSettings.text(MangaSettings.SOURCE_ROOT));
        String last = mSettings.lastDir();
        mLocalDir = (!last.isEmpty() && new File(last).isDirectory())
                ? new File(last) : Environment.getExternalStorageDirectory();
        mWebDavUrl = mSettings.text(MangaSettings.WEBDAV_URL);

        mPageViews.put(MangaPage.LIBRARY, mLibraryPage);
        for (MangaPage page : MangaPage.values()) {
            if (page == MangaPage.LIBRARY) {
                continue;
            }
            View pageView = createPage(page);
            pageView.setVisibility(GONE);
            mPageContainer.addView(pageView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            mPageViews.put(page, pageView);
        }
        switchPage(MangaPage.LIBRARY);
    }

    // ------------------------------------------------------------- dragging

    /** Grab the bottom bar to reposition the whole panel.
     *  This deliberately delegates to the SAME native WidgetMover the browser
     *  windows use: BrowserWorld::StartWidgetMove takes the controller ray,
     *  intersects it with the widget plane every frame, converts the hit point
     *  to world metres and clamps translation to +/-1.5m / +/-1.0m. That is why
     *  browser windows glide smoothly and never fly off-screen. A Java-side
     *  onTouch delta cannot reproduce it: MotionEvent coordinates here are 2D
     *  texture pixels (and jump coordinate space on the first MOVE in VR),
     *  while translationX/Y are world units (1 m = 360 units), so adding a raw
     *  pixel delta with no world-space clamp flings the panel out of view. */
    private void setupPanelDrag() {
        if (mDragBar == null) {
            return;
        }
        mDragBar.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mWidgetManager != null) {
                        mWidgetManager.startWidgetMove(this,
                                WidgetManagerDelegate.WIDGET_MOVE_BEHAVIOUR_MANGA);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // The native mover runs in the render loop; do nothing here.
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mWidgetManager != null) {
                        mWidgetManager.finishWidgetMove();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** Long-press a cover to forget its saved reading progress. */
    private void resetProgress(File folder) {
        if (folder == null) {
            return;
        }
        MangaSettings.get(getContext()).progressPrefs().edit()
                .remove("page:" + folder.getAbsolutePath())
                .apply();
        Toast.makeText(getContext(), "已重置阅读进度：" + folder.getName(), Toast.LENGTH_SHORT).show();
        refreshCurrentPage();
        Log.i(TAG, "resetProgress " + folder.getAbsolutePath());
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
        // Lift the panel so its bottom clears the tray, which floats at the window
        // bottom. The drag bar lets the user reposition it anyway.
        float worldHeightUnits = aPlacement.worldWidth
                * ((float) aPlacement.height / (float) aPlacement.width) * 360.0f;
        // Lift the whole panel well above the tray (which floats at the window
        // bottom). The drag bar lets the user reposition it afterwards.
        aPlacement.translationY += worldHeightUnits * 0.40f;
        // Slightly in front of the browser window so the (detached) tray and the
        // manga content stay alive while the browser window is hidden.
        aPlacement.translationZ = WidgetPlacement.getWindowWorldZMeters(context) + PANEL_Z_OFFSET;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.parentHandle = -1;
        aPlacement.cylinder = false;
        aPlacement.visible = false;
        Log.i(TAG, "placement y=" + aPlacement.translationY + " z=" + aPlacement.translationZ);
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        refreshCurrentPage();
        super.show(aShowFlags);
        Log.i(TAG, "show page=" + mCurrentPage.name() + " visible=" + isVisible());
    }

    @Override
    public void onResume() {
        updateWidget();
        if (hasStoragePermission()) {
            refreshCurrentPage();
        }
        Log.i(TAG, "onResume page=" + mCurrentPage.name());
    }

    @Override
    public void setVisible(boolean aVisible) {
        super.setVisible(aVisible);
        if (aVisible) {
            refreshCurrentPage();
        }
    }

    @Override
    protected void onDismiss() {
        // Root manga panel: the system Back must not destroy it.
    }

    // ------------------------------------------------------------- pages

    /** Called by VRBrowserActivity when a manga tray tab is pressed. */
    public void showPage(String page) {
        MangaPage target = MangaPage.LIBRARY;
        try {
            target = MangaPage.valueOf(page);
        } catch (Exception ignored) {
        }
        switchPage(target);
    }

    public MangaPage getCurrentPage() {
        return mCurrentPage;
    }

    private View createPage(MangaPage page) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);

        if (page == MangaPage.SOURCE_BROWSE) {
            // Cover grid needs the whole page height; a ScrollView wrapper
            // would fight the RecyclerView for scroll gestures.
            mSourceGrid = new CustomRecyclerView(getContext());
            mSourceGrid.setLayoutManager(new GridLayoutManager(getContext(), spanCount()));
            mPageContents.put(page, content);
            fillPage(page, content);
            return content;
        }

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

    /** Each page keeps its own ScrollView, so hiding and re-showing it used to
     *  preserve the PREVIOUS folder's scroll offset. Always start at the top
     *  when entering a page / opening a different folder. */
    private void scrollPageToTop(MangaPage page) {
        View view = mPageViews.get(page);
        if (view instanceof ScrollView) {
            final ScrollView scroll = (ScrollView) view;
            scroll.post(() -> scroll.scrollTo(0, 0));
        }
    }

    private void openFolderView(File dir, boolean detail) {
        mFolderDir = dir;
        mFolderDetail = detail;
        switchPage(MangaPage.FOLDER);   // switchPage resets the scroll
    }

    private void refreshFolderPage(boolean toTop) {
        refreshPage(MangaPage.FOLDER);
        if (toTop) {
            scrollPageToTop(MangaPage.FOLDER);
        }
    }

    private void fillPage(MangaPage page, LinearLayout content) {
        switch (page) {
            case LOCAL:
                fillLocalPage(content);
                break;
            case SMB:
                fillSmbPage(content);
                break;
            case WEBDAV:
                fillWebDavPage(content);
                break;
            case EXTENSIONS:
                fillExtensionsPage(content);
                break;
            case SOURCE_BROWSE:
                fillSourceBrowsePage(content);
                break;
            case SOURCE_MANGA:
                fillSourceMangaPage(content);
                break;
            case SOURCE_SETTINGS:
                fillSourceSettingsPage(content);
                break;
            case SOURCE_FILTERS:
                fillSourceFiltersPage(content);
                break;
            case SOURCE_OPTIONS:
                fillSourceOptionsPage(content);
                break;
            case SOURCE_TEXT:
                fillSourceTextPage(content);
                break;
            case FOLDER:
                fillFolderPage(content);
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
        } else {
            refreshPage(mCurrentPage);
        }
    }

    private void switchPage(MangaPage page) {
        // Leaving the settings page cancels any in-progress key capture.
        if (mCurrentPage == MangaPage.SETTINGS && page != MangaPage.SETTINGS) {
            MangaKeyCapture.stop();
            if (mSettingsPanel != null) {
                mSettingsPanel.cancelCapture();
            }
        }
        mCurrentPage = page;
        for (Map.Entry<MangaPage, View> entry : mPageViews.entrySet()) {
            entry.getValue().setVisibility(entry.getKey() == page ? VISIBLE : GONE);
        }
        mTitle.setText("漫画 · " + page.label);
        refreshCurrentPage();
        scrollPageToTop(page);
        Log.i(TAG, "switchPage " + page.name());
    }

    // --------------------------------------------------------- bookshelf

    private int spanCount() {
        if (mSettings.integer(MangaSettings.LIBRARY_VIEW_MODE) == 1) {
            return 1;
        }
        return Math.max(2, Math.min(6, mSettings.integer(MangaSettings.SOURCE_SPAN_COUNT)));
    }

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
                            showEmptyState("需要存储权限才能读取漫画根目录。请在设置中允许后重新进入。");
                        }
                    }
                });
    }

    private int mScanGeneration = 0;

    /** Directory enumeration and cover discovery run off the UI thread: a
     *  large manga tree must never block app startup or freeze the loading
     *  screen. Only the adapter update is posted back to the main thread. */
    private void scanLibrary() {
        if (!hasStoragePermission()) {
            showEmptyState("需要存储权限才能读取漫画根目录。");
            requestStoragePermission();
            return;
        }
        mRoot = new File(mSettings.text(MangaSettings.SOURCE_ROOT));
        mGrid.setLayoutManager(new GridLayoutManager(getContext(), spanCount()));
        final int generation = ++mScanGeneration;
        final String rootPath = mRoot.getAbsolutePath();
        mIoExecutor.execute(() -> {
            final List<MangaCoverAdapter.MangaItem> items = buildLibraryItems();
            mMain.post(() -> {
                if (generation != mScanGeneration || mCurrentPage != MangaPage.LIBRARY) {
                    return;
                }
                mAdapter.update(items);
                if (items.isEmpty()) {
                    showEmptyState("未找到漫画。请把漫画文件夹放到 " + rootPath);
                } else {
                    mEmptyText.setVisibility(GONE);
                    mGrid.setVisibility(VISIBLE);
                }
                Log.i(TAG, "scanLibrary items=" + items.size() + " root=" + rootPath);
            });
        });
    }

    private List<MangaCoverAdapter.MangaItem> buildLibraryItems() {
        final File root = new File(mSettings.text(MangaSettings.SOURCE_ROOT));
        List<File> dirs = new ArrayList<>();
        File[] top = root.listFiles();
        if (top != null) {
            for (File child : top) {
                if (child.isDirectory()) {
                    dirs.add(child);
                }
            }
        }
        for (String saved : mSettings.bookshelf()) {
            File extra = new File(saved);
            if (extra.isDirectory() && !dirs.contains(extra)) {
                dirs.add(extra);
            }
        }
        dirs.sort((a, b) -> mSettings.bool(MangaSettings.SOURCE_NATURAL_SORT)
                ? MangaCoverAdapter.naturalCompare(a.getName(), b.getName())
                : a.getName().compareToIgnoreCase(b.getName()));
        // Hard ceiling: an accidental root with thousands of folders must not
        // turn into an unbounded I/O walk.
        if (dirs.size() > 200) {
            dirs = new ArrayList<>(dirs.subList(0, 200));
        }

        List<MangaCoverAdapter.MangaItem> items = new ArrayList<>();
        String[] images = mSettings.imageExtensions();
        String[] videos = mSettings.videoExtensions();
        boolean includeHidden = mSettings.bool(MangaSettings.SOURCE_INCLUDE_HIDDEN);
        for (File dir : dirs) {
            if (!includeHidden && dir.getName().startsWith(".")) {
                continue;
            }
            File cover = findCoverRecursive(dir, images, 2);
            boolean hasVideo = hasMediaRecursive(dir, videos, 2);
            if (cover == null && !hasVideo) {
                continue;
            }
            items.add(new MangaCoverAdapter.MangaItem(dir, dir.getName(), cover, hasVideo));
        }
        return items;
    }

    private void collectChapterDirs(File dir, boolean recursive, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        boolean hasMedia = false;
        List<File> subDirs = new ArrayList<>();
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                subDirs.add(child);
            } else if (isMedia(child.getName())) {
                hasMedia = true;
            }
        }
        if (hasMedia) {
            out.add(dir);
        }
        if (recursive && depth < 3) {
            for (File sub : subDirs) {
                collectChapterDirs(sub, true, depth + 1, out);
            }
        } else if (!recursive) {
            out.addAll(subDirs);
        }
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
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).openMangaReader(folder);
            Log.i(TAG, "openReader " + folder.getAbsolutePath());
        }
    }

    // ---------------------------------------------- bookshelf toolbar

    private void setupLibraryToolbar() {
        // Defer adding the extra row until after the initial layout pass. A
        // synchronous insertion into a weighted LinearLayout during startup
        // forces an extra full measure while the RecyclerView is binding.
        post(this::buildLibraryToolbar);
    }

    private void buildLibraryToolbar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mLibraryPage.addView(bar, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button viewToggle = new Button(getContext());
        viewToggle.setAllCaps(false);
        viewToggle.setTextSize(16f);
        viewToggle.setTextColor(0xFFFFFFFF);
        viewToggle.setBackgroundColor(0xFF2A3A5A);
        viewToggle.setMinWidth(dp(160));
        viewToggle.setText(mSettings.integer(MangaSettings.LIBRARY_VIEW_MODE) == 1
                ? "▦ 切换图标视图" : "☰ 切换列表视图");
        viewToggle.setOnClickListener(v -> {
            int next = mSettings.integer(MangaSettings.LIBRARY_VIEW_MODE) == 1 ? 0 : 1;
            mSettings.put(MangaSettings.LIBRARY_VIEW_MODE, next);
            mGrid.setLayoutManager(new GridLayoutManager(getContext(), spanCount()));
            mAdapter.setCompact(next == 1);
            mAdapter.notifyDataSetChanged();
            viewToggle.setText(next == 1 ? "▦ 切换图标视图" : "☰ 切换列表视图");
        });
        bar.addView(viewToggle);

        Button cont = new Button(getContext());
        cont.setText("▶ 继续阅读");
        cont.setAllCaps(false);
        cont.setTextSize(16f);
        cont.setTextColor(0xFFFFFFFF);
        cont.setBackgroundColor(0xFF2A5A3A);
        cont.setMinWidth(dp(160));
        cont.setOnClickListener(v -> {
            String path = mSettings.lastReadFolder();
            File folder = path.isEmpty() ? null : new File(path);
            if (folder == null || !folder.isDirectory()) {
                Toast.makeText(getContext(), "还没有阅读记录", Toast.LENGTH_SHORT).show();
                return;
            }
            continueReading(folder);
        });
        bar.addView(cont);
    }

    // ------------------------------------------- folder / bookshelf views

    /** A bookshelf entry is one manga; its sub-folders are chapters. Entries
     *  already on the bookshelf open their detail card first. */
    private void openFolder(File folder) {
        if (folder == null) {
            return;
        }
        openFolderView(folder, mSettings.isInBookshelf(folder.getAbsolutePath()));
    }

    private void openReaderAt(File folder, File file) {
        if (folder == null) {
            return;
        }
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).openMangaReader(folder,
                    file == null ? null : file.getAbsolutePath());
        }
    }

    private void continueReading(File folder) {
        if (folder == null) {
            return;
        }
        int saved = progressPrefs().getInt("page:" + folder.getAbsolutePath(), 0);
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).openMangaReader(folder, saved);
        }
    }

    private SharedPreferences progressPrefs() {
        return getContext().getSharedPreferences(
                MangaSettings.PROGRESS_PREFS, Context.MODE_PRIVATE);
    }

    private int folderColumns() {
        return Math.max(2, Math.min(8,
                Math.round(5.0f / Math.max(0.4f, mFolderThumbScale))));
    }

    private void adjustFolderThumb(float delta) {
        mFolderThumbScale = Math.max(0.5f, Math.min(2.0f,
                Math.round((mFolderThumbScale + delta) * 10f) / 10f));
        mSettings.put(MangaSettings.FOLDER_THUMB_SCALE, mFolderThumbScale);
        refreshPage(MangaPage.FOLDER);
    }

    /** Generic per-source "default position" controls. The same helper can be
     *  used by SMB / WebDAV once those sources get a real browser. */
    private void addDefaultDirControls(LinearLayout content, String source, File current) {
        String def = mSettings.defaultDir(source);
        addInfo(content, "默认位置 · " + source,
                def.isEmpty() ? "未设置（下次进入默认恢复到上次退出的位置）" : def);
        LinearLayout row = addRow(content);
        addButton(row, "★ 跳到默认位置", v -> {
            String target = mSettings.defaultDir(source);
            if (target.isEmpty()) {
                Toast.makeText(getContext(), "尚未设置默认位置", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(target);
            if (!dir.isDirectory()) {
                Toast.makeText(getContext(), "默认位置已不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            mLocalDir = dir;
            mSettings.setLastDir(dir.getAbsolutePath());
            openFolderView(dir, false);
        });
        addButton(row, "设为默认位置", v -> {
            if (current == null) {
                return;
            }
            mSettings.setDefaultDir(source, current.getAbsolutePath());
            Toast.makeText(getContext(), "已设为默认位置：" + current.getName(),
                    Toast.LENGTH_SHORT).show();
            refreshPage(mCurrentPage);
        });
        addButton(row, "清除默认", v -> {
            mSettings.setDefaultDir(source, "");
            refreshPage(mCurrentPage);
        });
    }

    private void fillFolderPage(LinearLayout content) {
        final File folder = mFolderDir;
        if (folder == null || !folder.isDirectory()) {
            addInfo(content, "文件夹", "目录不可用，请从书架或本地文件夹重新进入。");
            return;
        }
        final String path = folder.getAbsolutePath();
        addInfo(content, "📁 " + folder.getName(), path);

        LinearLayout actions = addRow(content);
        File parent = folder.getParentFile();
        if (parent != null) {
            addButton(actions, "返回上级", v -> {
                openFolderView(parent, false);
            });
        }
        addButton(actions, "▶ 继续阅读", v -> continueReading(folder));
        addButton(actions, "打开首页", v -> openReaderAt(folder, null));
        if (mSettings.isInBookshelf(path)) {
            addButton(actions, "从书架移除", v -> {
                mSettings.removeFromBookshelf(path);
                mFolderDetail = false;
                refreshFolderPage(false);
            });
        } else {
            addButton(actions, "＋ 加入书架", v -> {
                mSettings.addToBookshelf(path);
                refreshFolderPage(false);
            });
        }
        addButton(actions, "设为默认位置", v -> {
            mSettings.setDefaultDir("LOCAL", path);
            Toast.makeText(getContext(), "已设为默认位置", Toast.LENGTH_SHORT).show();
        });

        if (mFolderDetail) {
            int saved = progressPrefs().getInt("page:" + path, 0);
            addInfo(content, "漫画详情",
                    "已加入书架。一个文件夹即一部漫画，里面的子文件夹作为章节。"
                            + "上次阅读到第 " + (saved + 1) + " 页。");
            LinearLayout detail = addRow(content);
            addButton(detail, "▶ 继续阅读（第 " + (saved + 1) + " 页）",
                    v -> continueReading(folder));
            addButton(detail, "浏览文件 / 章节", v -> {
                mFolderDetail = false;
                refreshFolderPage(true);
            });
            listChapters(content, folder);
            return;
        }

        listChapters(content, folder);
        listFolderFiles(content, folder);

        LinearLayout zoom = addRow(content);
        addButton(zoom, "缩略图 −", v -> adjustFolderThumb(-0.2f));
        addButton(zoom, "缩略图 ＋", v -> adjustFolderThumb(0.2f));
        addInfo(content, "缩略图大小",
                "当前 " + mFolderThumbScale + "×，每行 " + folderColumns()
                        + " 个；大小设置会保存，下次进入自动同步。");
    }

    private void listChapters(LinearLayout content, File folder) {
        File[] children = folder.listFiles();
        List<File> dirs = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    dirs.add(child);
                }
            }
        }
        dirs.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        if (dirs.isEmpty()) {
            return;
        }
        addInfo(content, "章节（子文件夹）", "共 " + dirs.size() + " 个，点击进入该章节。");
        int shown = 0;
        for (File chapter : dirs) {
            if (shown++ >= MAX_LOCAL_ROWS) {
                addInfo(content, "提示", "仅显示前 " + MAX_LOCAL_ROWS + " 个章节。");
                break;
            }
            Button row = new Button(getContext());
            row.setText("📂 " + chapter.getName());
            row.setAllCaps(false);
            row.setTextSize(17f);
            row.setTextColor(0xFFFFFFFF);
            row.setBackgroundColor(0xFF232C38);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(50));
            row.setOnClickListener(v -> {
                openFolderView(chapter, false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            content.addView(row, lp);
        }
    }

    private void listFolderFiles(LinearLayout content, File folder) {
        File[] children = folder.listFiles();
        List<File> media = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && isMedia(child.getName())) {
                    media.add(child);
                }
            }
        }
        media.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        if (media.isEmpty()) {
            addInfo(content, "文件", "此目录没有图片或视频，可进入上方章节。");
            return;
        }
        addInfo(content, "文件缩略图",
                "共 " + media.size() + " 个文件，点击缩略图直接跳到该页打开。");
        final int cols = folderColumns();
        final int cellHeight = (int) (140 * mFolderThumbScale);
        final int limit = Math.min(media.size(), 120);
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(cols);
        grid.setUseDefaultMargins(false);
        for (int i = 0; i < limit; i++) {
            final File file = media.get(i);
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundColor(0xFF24242C);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            cell.setClickable(true);

            ImageView thumb = new ImageView(getContext());
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF303038);
            cell.addView(thumb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(cellHeight)));

            TextView label = new TextView(getContext());
            label.setText((i + 1) + ". " + file.getName());
            label.setTextColor(0xFFE0E0E8);
            label.setTextSize(13f);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(label);

            // Plain (unweighted) specs: weighted GridLayout cells inside a
            // ScrollView trigger a very expensive repeated measure pass.
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / cols), GridLayout.spec(i % cols));
            gp.setMargins(dp(4), dp(4), dp(4), dp(4));
            cell.setLayoutParams(gp);
            cell.setOnClickListener(v -> openReaderAt(folder, file));
            loadThumb(thumb, file);
            grid.addView(cell);
        }
        content.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (media.size() > limit) {
            addInfo(content, "提示",
                    "缩略图仅显示前 " + limit + " 个文件，其余页可用阅读器跳页对话框访问。");
        }
    }

    private void loadThumb(ImageView view, File file) {
        view.setTag(file.getAbsolutePath());
        if (!MangaSettings.hasExtension(file.getName(), mSettings.imageExtensions())) {
            return; // video files keep the placeholder tile
        }
        final String path = file.getAbsolutePath();
        mIoExecutor.execute(() -> {
            final Bitmap bitmap = MangaCoverAdapter.decodeScaled(file, 240, 240);
            mMain.post(() -> {
                if (bitmap == null) {
                    return;
                }
                if (path.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
                }
            });
        });
    }

    private File findCoverRecursive(File dir, String[] images, int depth) {
        File direct = findFirstMedia(dir, images);
        if (direct != null || depth <= 0) {
            return direct;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        List<File> subDirs = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                subDirs.add(child);
            }
        }
        subDirs.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        for (File sub : subDirs) {
            File found = findCoverRecursive(sub, images, depth - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean hasMediaRecursive(File dir, String[] extensions, int depth) {
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        List<File> subDirs = new ArrayList<>();
        for (File child : children) {
            if (child.isFile() && MangaSettings.hasExtension(child.getName(), extensions)) {
                return true;
            }
            if (child.isDirectory()) {
                subDirs.add(child);
            }
        }
        if (depth <= 0) {
            return false;
        }
        for (File sub : subDirs) {
            if (hasMediaRecursive(sub, extensions, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- local

    private void fillLocalPage(LinearLayout content) {
        addInfo(content, "本地文件浏览", "进入含图片或视频的目录后可直接在阅读器中打开。");
        addInfo(content, "当前目录", mLocalDir == null ? "不可用" : mLocalDir.getAbsolutePath());
        addDefaultDirControls(content, "LOCAL", mLocalDir);

        LinearLayout actions = addRow(content);
        addButton(actions, "返回上级", v -> {
            File parent = mLocalDir != null ? mLocalDir.getParentFile() : null;
            if (parent != null) {
                mLocalDir = parent;
                mSettings.setLastDir(parent.getAbsolutePath());
                refreshPage(MangaPage.LOCAL);
            }
        });
        addButton(actions, "漫画根目录", v -> {
            mLocalDir = mRoot;
            mSettings.setLastDir(mRoot.getAbsolutePath());
            refreshPage(MangaPage.LOCAL);
        });
        addButton(actions, "打开阅读器", v -> openReader(mLocalDir));

        if (!hasStoragePermission()) {
            addInfo(content, "权限", "存储权限未授予，请在「设置」中申请后重试。");
            return;
        }
        File[] children = mLocalDir != null ? mLocalDir.listFiles() : null;
        if (children == null) {
            addInfo(content, "目录", "无法读取该目录。");
            return;
        }
        List<File> dirs = new ArrayList<>();
        int media = 0;
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                dirs.add(child);
            } else if (isMedia(child.getName())) {
                media++;
            }
        }
        dirs.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        addInfo(content, "媒体文件", "当前目录图片/视频数量：" + media);
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
                mSettings.setLastDir(dir.getAbsolutePath());
                // Entering a folder from the local browser always opens the
                // thumbnail view (bookshelf entries show their detail card).
                openFolderView(dir, false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            content.addView(row, lp);
        }
    }

    // -------------------------------------------------------------- SMB

    private void fillSmbPage(LinearLayout content) {
        addDefaultDirControls(content, "SMB",
                new File(mSettings.text(MangaSettings.SMB_MOUNT)));
        addInfo(content, "SMB 共享", "配置保存在「设置 → 漫画 · 图源 → SMB 共享」。");
        addInfo(content, "协议状态",
                "本版本未内置 SMB 协议栈；可以直接打开系统已挂载的 SMB 本地路径，或使用网盘 WebDAV 页。");

        String[] fields = {
                MangaSettings.SMB_HOST, "主机 / IP",
                MangaSettings.SMB_SHARE, "共享名",
                MangaSettings.SMB_DOMAIN, "域（可留空）",
                MangaSettings.SMB_USER, "用户名",
                MangaSettings.SMB_PASS, "密码",
                MangaSettings.SMB_PATH, "远程路径",
                MangaSettings.SMB_MOUNT, "本地挂载点（可直接阅读）"};
        final List<EditText> inputs = bindConfigFields(content, fields);

        LinearLayout actions = addRow(content);
        addButton(actions, "保存配置", v -> {
            saveConfigFields(fields, inputs);
            Toast.makeText(getContext(), "SMB 配置已保存", Toast.LENGTH_SHORT).show();
        });
        addButton(actions, "测试 445 端口", v -> {
            String host = mSettings.text(MangaSettings.SMB_HOST);
            addInfo(content, "端口测试", "正在连接 " + host + ":445 ...");
            mIoExecutor.execute(() -> {
                boolean reachable = false;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, 445), 3000);
                    reachable = true;
                } catch (Exception ignored) {
                }
                final boolean result = reachable;
                mMain.post(() -> {
                    Toast.makeText(getContext(), result ? host + ":445 可达" : host + ":445 不可达",
                            Toast.LENGTH_SHORT).show();
                });
            });
        });
        addButton(actions, "从挂载点打开", v -> {
            File mount = new File(mSettings.text(MangaSettings.SMB_MOUNT));
            if (mount.isDirectory()) {
                openReader(mount);
            } else {
                Toast.makeText(getContext(), "挂载点不是可访问目录：" + mount.getAbsolutePath(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ----------------------------------------------------------- WebDAV

    private void fillWebDavPage(LinearLayout content) {
        addDefaultDirControls(content, "WEBDAV",
                new File(mSettings.text(MangaSettings.DOWNLOAD_DIR)));
        addInfo(content, "网盘 WebDAV",
                "真实 PROPFIND 列目录 + HTTP GET 下载；配置、格式过滤、并发与仅 WiFi 均取自漫画设置。");
        addInfo(content, "当前目录", mWebDavUrl.isEmpty() ? "（未配置地址）" : mWebDavUrl);
        addInfo(content, "状态", mWebDavStatus);

        String[] fields = {
                MangaSettings.WEBDAV_URL, "WebDAV 地址",
                MangaSettings.WEBDAV_USER, "用户名",
                MangaSettings.WEBDAV_PASS, "密码"};
        final List<EditText> inputs = bindConfigFields(content, fields);

        LinearLayout actions = addRow(content);
        addButton(actions, "保存配置", v -> {
            saveConfigFields(fields, inputs);
            mWebDavUrl = mSettings.text(MangaSettings.WEBDAV_URL);
            refreshPage(MangaPage.WEBDAV);
        });
        addButton(actions, "连接 / 列出", v -> {
            saveConfigFields(fields, inputs);
            mWebDavUrl = mSettings.text(MangaSettings.WEBDAV_URL);
            connectWebDav();
        });
        addButton(actions, "返回上级", v -> {
            int slash = mWebDavUrl.lastIndexOf('/');
            if (slash > 8) {
                String trimmed = mWebDavUrl.endsWith("/")
                        ? mWebDavUrl.substring(0, mWebDavUrl.length() - 1) : mWebDavUrl;
                int parentSlash = trimmed.lastIndexOf('/');
                if (parentSlash > 8) {
                    mWebDavUrl = trimmed.substring(0, parentSlash + 1);
                    connectWebDav();
                }
            }
        });
        addButton(actions, "下载当前目录媒体", v -> downloadCurrentWebDav());
        addButton(actions, "打开本地下载目录", v ->
                openReader(new File(mSettings.text(MangaSettings.DOWNLOAD_DIR))));

        if (mWebDavEntries.isEmpty()) {
            addInfo(content, "目录内容", "点击「连接 / 列出」获取远程文件。");
            return;
        }
        for (MangaWebDav.Entry entry : mWebDavEntries) {
            Button row = new Button(getContext());
            row.setText((entry.directory ? "[目录] " : "[文件] ") + entry.name
                    + (entry.size > 0 ? "  " + (entry.size / 1024) + " KB" : ""));
            row.setAllCaps(false);
            row.setTextSize(17f);
            row.setTextColor(0xFFFFFFFF);
            row.setBackgroundColor(0xFF23232C);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(50));
            row.setOnClickListener(v -> {
                if (entry.directory) {
                    try {
                        mWebDavUrl = MangaWebDav.resolve(mWebDavUrl, entry.href);
                        if (!mWebDavUrl.endsWith("/")) {
                            mWebDavUrl += "/";
                        }
                        connectWebDav();
                    } catch (Exception e) {
                        mWebDavStatus = "路径解析失败：" + e.getMessage();
                        refreshPage(MangaPage.WEBDAV);
                    }
                } else {
                    downloadSingleWebDav(entry);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            content.addView(row, lp);
        }
    }

    private void connectWebDav() {
        final String url = mWebDavUrl;
        final String user = mSettings.text(MangaSettings.WEBDAV_USER);
        final String pass = mSettings.text(MangaSettings.WEBDAV_PASS);
        if (url.isEmpty()) {
            mWebDavStatus = "请先在上方填写 WebDAV 地址";
            refreshPage(MangaPage.WEBDAV);
            return;
        }
        mWebDavStatus = "正在连接 " + url;
        refreshPage(MangaPage.WEBDAV);
        mIoExecutor.execute(() -> {
            try {
                List<MangaWebDav.Entry> result = MangaWebDav.list(url, user, pass);
                mMain.post(() -> {
                    mWebDavEntries.clear();
                    mWebDavEntries.addAll(result);
                    mWebDavStatus = "已列出 " + result.size() + " 项";
                    Log.i(TAG, "webdav list ok=" + result.size());
                    refreshPage(MangaPage.WEBDAV);
                });
            } catch (Exception e) {
                final String message = e.getMessage();
                mMain.post(() -> {
                    mWebDavStatus = "连接失败：" + message;
                    mWebDavEntries.clear();
                    Log.w(TAG, "webdav list failed: " + message);
                    refreshPage(MangaPage.WEBDAV);
                });
            }
        });
    }

    private List<MangaWebDav.Entry> mediaEntries() {
        List<MangaWebDav.Entry> media = new ArrayList<>();
        String[] images = mSettings.imageExtensions();
        String[] videos = mSettings.videoExtensions();
        for (MangaWebDav.Entry entry : mWebDavEntries) {
            if (!entry.directory && (MangaSettings.hasExtension(entry.name, images)
                    || MangaSettings.hasExtension(entry.name, videos))) {
                media.add(entry);
            }
        }
        return media;
    }

    private void downloadCurrentWebDav() {
        final List<MangaWebDav.Entry> media = mediaEntries();
        if (media.isEmpty()) {
            Toast.makeText(getContext(), "当前目录没有可下载的图片/视频", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = folderNameFromUrl(mWebDavUrl);
        File destination = new File(mSettings.text(MangaSettings.DOWNLOAD_DIR), name);
        new MangaDownloader(getContext()).download(media, mWebDavUrl,
                mSettings.text(MangaSettings.WEBDAV_USER),
                mSettings.text(MangaSettings.WEBDAV_PASS),
                destination, status -> {
                    mWebDavStatus = status;
                    if (status.startsWith("下载完成")
                            && mSettings.bool(MangaSettings.DOWNLOAD_AUTO_NEXT)) {
                        for (MangaWebDav.Entry entry : mWebDavEntries) {
                            if (entry.directory) {
                                try {
                                    mWebDavUrl = MangaWebDav.resolve(mWebDavUrl, entry.href);
                                    if (!mWebDavUrl.endsWith("/")) {
                                        mWebDavUrl += "/";
                                    }
                                    connectWebDav();
                                } catch (Exception ignored) {
                                }
                                break;
                            }
                        }
                    }
                    Toast.makeText(getContext(), status, Toast.LENGTH_SHORT).show();
                });
    }

    private void downloadSingleWebDav(MangaWebDav.Entry entry) {
        String name = folderNameFromUrl(mWebDavUrl);
        File destination = new File(new File(mSettings.text(MangaSettings.DOWNLOAD_DIR), name),
                entry.name);
        mIoExecutor.execute(() -> {
            try {
                String url = MangaWebDav.resolve(mWebDavUrl, entry.href);
                MangaWebDav.download(url, mSettings.text(MangaSettings.WEBDAV_USER),
                        mSettings.text(MangaSettings.WEBDAV_PASS), destination, null);
                mMain.post(() -> {
                    mWebDavStatus = "已下载 " + entry.name;
                    Toast.makeText(getContext(), "已下载 " + entry.name, Toast.LENGTH_SHORT).show();
                    openReader(destination.getParentFile());
                });
            } catch (Exception e) {
                mMain.post(() -> {
                    mWebDavStatus = "下载失败：" + e.getMessage();
                    refreshPage(MangaPage.WEBDAV);
                });
            }
        });
    }

    private static String folderNameFromUrl(String url) {
        String clean = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : "webdav";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    // -------------------------------------------------------- extensions

    private void fillExtensionsPage(LinearLayout content) {
        addInfo(content, "自定义漫画源 / 扩展应用",
                "已安装的 Mihon/Tachiyomi 扩展自动信任加载（lib 1.4/1.6）。多源应用默认折叠，点应用名展开；源名优先显示扩展内置名称。");
        List<MangaSourceManager.LoadedSource> sources;
        try {
            sources = MangaSourceManager.INSTANCE.init(getContext());
        } catch (Throwable t) {
            Log.e("MangaLibrary", "source manager init failed", t);
            addInfo(content, "加载失败", String.valueOf(t));
            sources = new ArrayList<>();
        }
        Map<String, List<MangaSourceManager.LoadedSource>> groups = new LinkedHashMap<>();
        for (MangaSourceManager.LoadedSource entry : sources) {
            List<MangaSourceManager.LoadedSource> list = groups.get(entry.getPkgName());
            if (list == null) {
                list = new ArrayList<>();
                groups.put(entry.getPkgName(), list);
            }
            list.add(entry);
        }
        if (!mExpandedInit) {
            mExpandedInit = true;
            for (Map.Entry<String, List<MangaSourceManager.LoadedSource>> g : groups.entrySet()) {
                if (g.getValue().size() == 1) {
                    mExpandedPkgs.add(g.getKey());
                }
            }
        }
        addInfo(content, "已加载图源", sources.size() + " 个源 / " + groups.size() + " 个应用");
        for (Map.Entry<String, List<MangaSourceManager.LoadedSource>> g : groups.entrySet()) {
            final String pkg = g.getKey();
            List<MangaSourceManager.LoadedSource> list = g.getValue();
            MangaSourceManager.LoadedSource first = list.get(0);
            boolean expanded = mExpandedPkgs.contains(pkg);
            LinearLayout header = addRow(content);
            if (first.getIcon() != null) {
                ImageView icon = new ImageView(getContext());
                icon.setImageDrawable(first.getIcon());
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(44));
                ip.setMargins(0, dp(4), dp(8), dp(4));
                header.addView(icon, ip);
            }
            String label = list.size() == 1 ? first.getSource().getName() : first.getAppLabel();
            addButton(header, label + (list.size() > 1 ? "（" + list.size() + " 源）" : "")
                    + (expanded ? " ▲" : " ▼"), v -> {
                if (mExpandedPkgs.contains(pkg)) {
                    mExpandedPkgs.remove(pkg);
                } else {
                    mExpandedPkgs.add(pkg);
                }
                refreshPage(MangaPage.EXTENSIONS);
            });
            if (!expanded) {
                continue;
            }
            for (MangaSourceManager.LoadedSource entry : list) {
                LinearLayout row = addRow(content);
                addButton(row, entry.getSource().getName() + " [" + entry.getSource().getLang() + "]",
                        v -> openSourceBrowse(entry));
                if (entry.getSource() instanceof ConfigurableSource) {
                    addButton(row, "设置", v -> openSourceSettings(entry));
                }
                addButton(row, "试读", v -> MangaSourceManager.INSTANCE.testFetch(entry));
            }
        }
        List<MangaSourceManager.LoadFailure> failures = MangaSourceManager.INSTANCE.getFailures();
        if (!failures.isEmpty()) {
            addInfo(content, "未识别扩展", failures.size() + " 个 · 点名称查看错误摘要，完整堆栈见 logcat(MangaSource)");
            for (MangaSourceManager.LoadFailure f : failures) {
                LinearLayout row = addRow(content);
                addButton(row, "⚠ " + f.getAppLabel(), v ->
                        Toast.makeText(getContext(), f.getPkgName() + "\n" + f.getError(), Toast.LENGTH_LONG).show());
            }
        }

        File extDir = new File(mSettings.text(MangaSettings.SOURCE_ROOT), "Extensions");
        File[] apks = extDir.exists() ? extDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".apk")) : null;
        addInfo(content, "本地 APK", extDir.getAbsolutePath()
                + " · " + (apks == null ? 0 : apks.length) + " 个");
        if (apks != null) {
            for (File apk : apks) {
                LinearLayout row = addRow(content);
                addButton(row, "安装 " + apk.getName(), v -> installApk(apk));
            }
        }
        LinearLayout actions = addRow(content);
        addButton(actions, "重新扫描", v -> {
            mIoExecutor.execute(() -> {
                MangaSourceManager.INSTANCE.reload(getContext());
                mMain.post(() -> refreshPage(MangaPage.EXTENSIONS));
            });
        });
    }

    // ------------------------------------------- extension source browsing

    private void openSourceBrowse(MangaSourceManager.LoadedSource entry) {
        mSourceEntry = entry;
        mSourceQuery = "";
        mSourcePage = 1;
        mSourceLatest = false;
        mSourceMangas.clear();
        mSourceHasNext = false;
        mSourceFilters = null;
        mSourceFiltersActive = false;
        switchPage(MangaPage.SOURCE_BROWSE);
        startSourceBrowse();
    }

    private void startSourceBrowse() {
        if (mSourceEntry == null) {
            return;
        }
        mSourceStatus = "加载中…";
        refreshPage(MangaPage.SOURCE_BROWSE);
        Source source = mSourceEntry.getSource();
        MangaSourceRepository.Callback<MangasPage> cb = (page, error) -> {
            if (page == null) {
                mSourceStatus = "加载失败：" + error;
                mSourceMangas.clear();
                mSourceHasNext = false;
            } else {
                mSourceMangas.clear();
                mSourceMangas.addAll(page.getMangas());
                mSourceHasNext = page.getHasNextPage();
                mSourceStatus = "第 " + mSourcePage + " 页 · " + mSourceMangas.size() + " 部";
            }
            refreshPage(MangaPage.SOURCE_BROWSE);
        };
        if (mSourceLatest) {
            MangaSourceRepository.INSTANCE.latest(source, mSourcePage, cb);
        } else {
            MangaSourceRepository.INSTANCE.browse(source, mSourcePage, mSourceQuery,
                    mSourceFiltersActive ? mSourceFilters : null, cb);
        }
    }

    private void fillSourceBrowsePage(LinearLayout content) {
        if (mSourceEntry == null) {
            addInfo(content, "未选择图源", "请先到“漫画源扩展”页选择一个图源。");
            return;
        }
        Source source = mSourceEntry.getSource();
        addInfo(content, source.getName(), mSourceEntry.getAppLabel() + " · " + source.getLang());
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回扩展", v -> switchPage(MangaPage.EXTENSIONS));
        addButton(nav, "筛选", v -> openSourceFilters());
        if (source instanceof ConfigurableSource) {
            addButton(nav, "设置", v -> openSourceSettings(mSourceEntry));
        }

        LinearLayout searchRow = addRow(content);
        EditText input = new EditText(getContext());
        input.setHint("搜索关键词");
        input.setText(mSourceQuery);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF808088);
        input.setTextSize(17f);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        searchRow.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addButton(searchRow, "搜索", v -> {
            mSourceQuery = input.getText().toString().trim();
            mSourcePage = 1;
            mSourceLatest = false;
            startSourceBrowse();
        });
        addButton(searchRow, "热门", v -> {
            mSourceQuery = "";
            mSourceLatest = false;
            mSourcePage = 1;
            startSourceBrowse();
        });
        if (source.getSupportsLatest()) {
            addButton(searchRow, "最新", v -> {
                mSourceQuery = "";
                mSourceLatest = true;
                mSourcePage = 1;
                startSourceBrowse();
            });
        }

        LinearLayout pager = addRow(content);
        if (mSourcePage > 1) {
            addButton(pager, "上一页", v -> {
                mSourcePage--;
                startSourceBrowse();
            });
        }
        if (mSourceHasNext) {
            addButton(pager, "下一页", v -> {
                mSourcePage++;
                startSourceBrowse();
            });
        }
        addInfo(content, "状态", mSourceStatus
                + (mSourceFiltersActive ? " · 已应用筛选" : ""));

        if (mSourceAdapter == null || mSourceAdapterSource != source) {
            mSourceAdapterSource = source;
            mSourceAdapter = new SourceCoverAdapter(getContext(), source, this::openSourceManga);
            mSourceGrid.setAdapter(mSourceAdapter);
        }
        mSourceGrid.setLayoutManager(new GridLayoutManager(getContext(), Math.max(2, spanCount())));
        mSourceAdapter.update(mSourceMangas);
        content.addView(mSourceGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void searchSourceBy(String query) {
        mSourceQuery = query;
        mSourceLatest = false;
        mSourcePage = 1;
        switchPage(MangaPage.SOURCE_BROWSE);
        startSourceBrowse();
    }

    // ---------------------------------------------------- source filters

    private void openSourceFilters() {
        if (mSourceEntry == null) {
            return;
        }
        if (mSourceFilters != null) {
            switchPage(MangaPage.SOURCE_FILTERS);
            return;
        }
        MangaSourceRepository.INSTANCE.fetchFilters(mSourceEntry.getSource(), (filters, error) -> {
            if (filters == null) {
                Toast.makeText(getContext(), "读取筛选失败：" + error, Toast.LENGTH_LONG).show();
                return;
            }
            mSourceFilters = filters;
            applyDefaultSort(filters);
            switchPage(MangaPage.SOURCE_FILTERS);
        });
    }

    /** User default: every catalogue sorts newest-first unless the extension
     *  says otherwise; pick the matching option of any sort-like filter. */
    @SuppressWarnings("unchecked")
    private void applyDefaultSort(FilterList filters) {
        for (Object o : filters.getList()) {
            Filter f = (Filter) o;
            if (f instanceof Filter.Select) {
                Filter.Select sel = (Filter.Select) f;
                String name = sel.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("sort") && !sel.getName().contains("排序")) {
                    continue;
                }
                Object[] vals = sel.getValues();
                for (int i = 0; i < vals.length; i++) {
                    String s = String.valueOf(vals[i]);
                    if (s.contains("新到旧") || s.contains("最新")
                            || s.toLowerCase(Locale.ROOT).contains("newest")) {
                        sel.setState(i);
                        break;
                    }
                }
            } else if (f instanceof Filter.Sort) {
                Filter.Sort sort = (Filter.Sort) f;
                if (sort.getState() != null) {
                    continue;
                }
                String[] vals = sort.getValues();
                for (int i = 0; i < vals.length; i++) {
                    String s = vals[i];
                    if (s.contains("新到旧") || s.contains("最新")
                            || s.toLowerCase(Locale.ROOT).contains("newest")) {
                        sort.setState(new Filter.Sort.Selection(i, false));
                        break;
                    }
                }
            }
        }
    }

    private void fillSourceFiltersPage(LinearLayout content) {
        if (mSourceEntry == null || mSourceFilters == null) {
            addInfo(content, "无筛选", "请先到图源浏览页点“筛选”。");
            return;
        }
        addInfo(content, mSourceEntry.getSource().getName() + " · 筛选",
                "选项来自扩展内置分类，可跨组多选；应用后回到浏览并按筛选刷新。");
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回浏览", v -> switchPage(MangaPage.SOURCE_BROWSE));
        addButton(nav, "应用筛选", v -> {
            mSourceFiltersActive = true;
            mSourcePage = 1;
            switchPage(MangaPage.SOURCE_BROWSE);
            startSourceBrowse();
        });
        addButton(nav, "重置", v -> {
            mSourceFilters = null;
            mSourceFiltersActive = false;
            openSourceFilters();
        });
        if (mSourceFilters.getList().isEmpty()) {
            addInfo(content, "无内置筛选", "该扩展没有提供筛选选项。");
        }
        renderFilters(mSourceFilters.getList(), content);
    }

    @SuppressWarnings("unchecked")
    private void renderFilters(List<?> filters, LinearLayout content) {
        for (Object o : filters) {
            Filter f = (Filter) o;
            if (f instanceof Filter.Group) {
                endChips();
                addInfo(content, f.getName(), null);
                renderFilters((List<?>) ((Filter.Group) f).getState(), content);
                endChips();
            } else if (f instanceof Filter.Select) {
                endChips();
                Filter.Select sel = (Filter.Select) f;
                addInfo(content, f.getName(), null);
                Object[] vals = sel.getValues();
                int state = (Integer) sel.getState();
                for (int i = 0; i < vals.length; i++) {
                    final int idx = i;
                    addChip(content, String.valueOf(vals[i]) + (i == state ? " ✓" : ""), v -> {
                        sel.setState(idx);
                        refreshPage(MangaPage.SOURCE_FILTERS);
                    });
                }
                endChips();
            } else if (f instanceof Filter.CheckBox) {
                boolean st = (Boolean) f.getState();
                addChip(content, f.getName() + (st ? " ✓" : ""), v -> {
                    f.setState(!st);
                    refreshPage(MangaPage.SOURCE_FILTERS);
                });
            } else if (f instanceof Filter.TriState) {
                int st = (Integer) f.getState();
                String suffix = st == Filter.TriState.STATE_INCLUDE ? " ✓"
                        : st == Filter.TriState.STATE_EXCLUDE ? " ✗" : "";
                addChip(content, f.getName() + suffix, v -> {
                    f.setState((st + 1) % 3);
                    refreshPage(MangaPage.SOURCE_FILTERS);
                });
            } else if (f instanceof Filter.Sort) {
                endChips();
                Filter.Sort sort = (Filter.Sort) f;
                addInfo(content, f.getName(), null);
                Filter.Sort.Selection sel = (Filter.Sort.Selection) sort.getState();
                String[] vals = sort.getValues();
                for (int i = 0; i < vals.length; i++) {
                    final int idx = i;
                    boolean on = sel != null && sel.getIndex() == i;
                    addChip(content, vals[i] + (on ? (sel.getAscending() ? " ↑" : " ↓") : ""), v -> {
                        Filter.Sort.Selection cur = (Filter.Sort.Selection) sort.getState();
                        boolean asc = cur != null && cur.getIndex() == idx && !cur.getAscending();
                        sort.setState(new Filter.Sort.Selection(idx, asc));
                        refreshPage(MangaPage.SOURCE_FILTERS);
                    });
                }
                endChips();
            } else if (f instanceof Filter.Text) {
                endChips();
                Filter.Text text = (Filter.Text) f;
                LinearLayout row = addRow(content);
                EditText input = new EditText(getContext());
                input.setHint(f.getName());
                input.setText((String) text.getState());
                input.setTextColor(0xFFFFFFFF);
                input.setHintTextColor(0xFF808088);
                input.setTextSize(17f);
                row.addView(input, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                addButton(row, "设", v -> {
                    text.setState(input.getText().toString());
                    refreshPage(MangaPage.SOURCE_FILTERS);
                });
            } else if (f instanceof Filter.Header) {
                endChips();
                addInfo(content, f.getName(), null);
            } else if (f instanceof Filter.Separator) {
                endChips();
                View spacer = new View(getContext());
                content.addView(spacer, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));
            }
        }
        endChips();
    }

    private LinearLayout mChipRow;
    private int mChipCount;

    private Button addChip(LinearLayout content, String text, View.OnClickListener listener) {
        if (mChipRow == null || mChipCount >= 3) {
            mChipRow = addRow(content);
            mChipCount = 0;
        }
        mChipCount++;
        Button button = addButton(mChipRow, text, listener);
        button.setMinWidth(dp(90));
        return button;
    }

    private void endChips() {
        mChipRow = null;
        mChipCount = 0;
    }

    // ------------------------------------------- per-source settings UI

    private void openSourceSettings(MangaSourceManager.LoadedSource entry) {
        mSettingsSource = entry;
        mPrefScreen = null;
        Source source = entry.getSource();
        if (source instanceof ConfigurableSource) {
            try {
                PreferenceManager pm = new PreferenceManager(getContext());
                pm.setSharedPreferencesName("source_" + source.getId());
                pm.setSharedPreferencesMode(Context.MODE_PRIVATE);
                PreferenceScreen screen = pm.createPreferenceScreen(getContext());
                ((ConfigurableSource) source).setupPreferenceScreen(screen);
                mPrefScreen = screen;
            } catch (Throwable t) {
                Log.e(TAG, "setupPreferenceScreen failed: " + entry.getPkgName(), t);
                Toast.makeText(getContext(), "读取扩展设置失败：" + t, Toast.LENGTH_LONG).show();
            }
        }
        switchPage(MangaPage.SOURCE_SETTINGS);
    }

    private void fillSourceSettingsPage(LinearLayout content) {
        if (mSettingsSource == null) {
            addInfo(content, "未选择图源", "");
            return;
        }
        Source source = mSettingsSource.getSource();
        addInfo(content, source.getName() + " · 设置",
                mSettingsSource.getPkgName() + " · v" + mSettingsSource.getVersionName()
                        + " · lang=" + source.getLang() + " · id=" + source.getId());
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回扩展", v -> switchPage(MangaPage.EXTENSIONS));
        addButton(nav, "浏览该源", v -> openSourceBrowse(mSettingsSource));
        if (mPrefScreen == null) {
            addInfo(content, "无设置选项", "该扩展没有提供可配置选项（ConfigurableSource）。");
            return;
        }
        renderPrefGroup(mPrefScreen, content);
    }

    private void renderPrefGroup(PreferenceGroup group, LinearLayout content) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            String title = p.getTitle() == null ? String.valueOf(p.getKey()) : p.getTitle().toString();
            CharSequence summary = p.getSummary();
            if (p instanceof PreferenceGroup) {
                addInfo(content, title, summary == null ? null : summary.toString());
                renderPrefGroup((PreferenceGroup) p, content);
            } else if (p instanceof TwoStatePreference) {
                TwoStatePreference tp = (TwoStatePreference) p;
                LinearLayout row = addRow(content);
                addPrefTitle(row, title, summary);
                addButton(row, tp.isChecked() ? "开" : "关", v -> {
                    tp.setChecked(!tp.isChecked());
                    refreshPage(MangaPage.SOURCE_SETTINGS);
                });
            } else if (p instanceof ListPreference) {
                ListPreference lp = (ListPreference) p;
                LinearLayout row = addRow(content);
                addPrefTitle(row, title, summary);
                CharSequence entry = lp.getEntry();
                addButton(row, entry == null ? "选择…" : entry.toString(), v -> openOptions(
                        title, lp.getEntries(), lp.getEntryValues(), lp.getValue(),
                        value -> {
                            lp.setValue(value);
                            refreshPage(MangaPage.SOURCE_SETTINGS);
                        },
                        MangaPage.SOURCE_SETTINGS));
            } else if (p instanceof EditTextPreference) {
                EditTextPreference ep = (EditTextPreference) p;
                LinearLayout row = addRow(content);
                addPrefTitle(row, title, summary);
                String text = ep.getText();
                addButton(row, text == null || text.isEmpty() ? "输入…" : text, v -> openText(
                        title, text == null ? "" : text,
                        value -> {
                            ep.setText(value);
                            refreshPage(MangaPage.SOURCE_SETTINGS);
                        },
                        MangaPage.SOURCE_SETTINGS));
            } else {
                addInfo(content, title, summary == null ? null : summary.toString());
            }
        }
    }

    private void addPrefTitle(LinearLayout row, String title, CharSequence summary) {
        TextView tv = new TextView(getContext());
        tv.setText(summary == null || summary.length() == 0 ? title : title + "\n" + summary);
        tv.setTextSize(17f);
        tv.setTextColor(0xFFFFFFFF);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void openOptions(String title, CharSequence[] labels, CharSequence[] values,
                             String current, Consumer<String> pick, MangaPage returnPage) {
        mOptionTitle = title;
        mOptionLabels = new String[labels == null ? 0 : labels.length];
        mOptionValues = new String[values == null ? 0 : values.length];
        for (int i = 0; i < mOptionLabels.length; i++) {
            mOptionLabels[i] = String.valueOf(labels[i]);
        }
        for (int i = 0; i < mOptionValues.length; i++) {
            mOptionValues[i] = String.valueOf(values[i]);
        }
        mOptionCurrent = current == null ? "" : current;
        mOptionPick = pick;
        mOptionReturn = returnPage;
        switchPage(MangaPage.SOURCE_OPTIONS);
    }

    private void fillSourceOptionsPage(LinearLayout content) {
        addInfo(content, mOptionTitle, "当前：" + mOptionCurrent);
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回", v -> switchPage(mOptionReturn));
        for (int i = 0; i < mOptionLabels.length && i < mOptionValues.length; i++) {
            final String value = mOptionValues[i];
            LinearLayout row = addRow(content);
            addButton(row, mOptionLabels[i] + (value.equals(mOptionCurrent) ? " ✓" : ""), v -> {
                if (mOptionPick != null) {
                    mOptionPick.accept(value);
                }
                switchPage(mOptionReturn);
            });
        }
    }

    private void openText(String title, String value, Consumer<String> save, MangaPage returnPage) {
        mTextTitle = title;
        mTextValue = value;
        mTextSave = save;
        mTextReturn = returnPage;
        switchPage(MangaPage.SOURCE_TEXT);
    }

    private void fillSourceTextPage(LinearLayout content) {
        addInfo(content, mTextTitle, null);
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回", v -> switchPage(mTextReturn));
        EditText input = new EditText(getContext());
        input.setText(mTextValue);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(17f);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout save = addRow(content);
        addButton(save, "保存", v -> {
            if (mTextSave != null) {
                mTextSave.accept(input.getText().toString());
            }
            switchPage(mTextReturn);
        });
    }

    private void openSourceManga(SManga manga) {
        if (mSourceEntry == null) {
            return;
        }
        mSourceManga = manga;
        mSourceChapters.clear();
        mSourceMangaStatus = "加载详情…";
        switchPage(MangaPage.SOURCE_MANGA);
        MangaSourceRepository.INSTANCE.details(mSourceEntry.getSource(), manga, (update, error) -> {
            if (update == null) {
                mSourceMangaStatus = "详情加载失败：" + error;
            } else {
                mSourceManga = update.getManga();
                mSourceChapters.clear();
                mSourceChapters.addAll(update.getChapters());
                mSourceMangaStatus = mSourceChapters.size() + " 个章节";
            }
            refreshPage(MangaPage.SOURCE_MANGA);
        });
    }

    private void fillSourceMangaPage(LinearLayout content) {
        if (mSourceManga == null || mSourceEntry == null) {
            addInfo(content, "未选择漫画", "");
            return;
        }
        SManga manga = mSourceManga;
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回浏览", v -> switchPage(MangaPage.SOURCE_BROWSE));
        addButton(nav, "刷新详情", v -> openSourceManga(manga));

        LinearLayout head = addRow(content);
        ImageView cover = new ImageView(getContext());
        cover.setBackgroundColor(0xFF26262E);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(120), dp(160));
        cp.setMargins(0, dp(4), dp(12), dp(4));
        head.addView(cover, cp);
        MangaSourceRepository.INSTANCE.loadCover(mSourceEntry.getSource(), manga.getThumbnail_url(),
                (bitmap, error) -> {
                    if (bitmap != null) {
                        cover.setImageBitmap(bitmap);
                    }
                });
        LinearLayout meta = new LinearLayout(getContext());
        meta.setOrientation(LinearLayout.VERTICAL);
        head.addView(meta, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(getContext());
        title.setText(manga.getTitle());
        title.setTextSize(22f);
        title.setTextColor(0xFFFFFFFF);
        meta.addView(title);
        TextView status = new TextView(getContext());
        status.setText(mangaStatusText(manga.getStatus()) + " · " + mSourceMangaStatus);
        status.setTextSize(16f);
        status.setTextColor(0xFFB0B0B8);
        meta.addView(status);

        String author = manga.getAuthor();
        if (author != null && !author.trim().isEmpty()) {
            addInfo(content, "作者（点名字搜索该作者全部作品）", null);
            for (String name : author.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    addChip(content, trimmed, v -> searchSourceBy(trimmed));
                }
            }
            endChips();
        }
        String genre = manga.getGenre();
        if (genre != null && !genre.trim().isEmpty()) {
            addInfo(content, "标签（点标签搜索同类作品）", null);
            for (String tag : genre.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    addChip(content, trimmed, v -> searchSourceBy(trimmed));
                }
            }
            endChips();
        }
        addInfo(content, "简介", manga.getDescription());

        LinearLayout actions = addRow(content);
        addButton(actions, "▶ 开始阅读", v -> startSourceReading());
        addButton(actions, "▶▶ 继续阅读", v -> continueSourceReading());

        addInfo(content, "章节列表", mSourceMangaStatus);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        for (SChapter chapter : sortedChapters()) {
            LinearLayout row = addRow(content);
            File dir = MangaSourceRepository.INSTANCE.chapterDir(
                    getContext(), mSourceEntry.getSource(), manga, chapter);
            File[] cached = dir.listFiles();
            boolean hasCache = cached != null && cached.length > 0;
            String date = chapter.getDate_upload() > 0
                    ? fmt.format(new Date(chapter.getDate_upload())) : "";
            addButton(row, (hasCache ? "✓ " : "") + chapter.getName()
                    + (date.isEmpty() ? "" : "  ·  " + date), v -> readSourceChapter(chapter, false));
        }
    }

    private String mangaStatusText(int status) {
        switch (status) {
            case 1: return "连载中";
            case 2: return "已完结";
            case 3: return "授权中";
            case 4: return "出版完结";
            case 5: return "已取消";
            case 6: return "休刊中";
            default: return "状态未知";
        }
    }

    /** Reading order: chapter number when the extension provides it, else
     *  upload date, else the source's own order. */
    private List<SChapter> sortedChapters() {
        List<SChapter> list = new ArrayList<>(mSourceChapters);
        list.sort((a, b) -> {
            float na = a.getChapter_number();
            float nb = b.getChapter_number();
            boolean ha = na >= 0;
            boolean hb = nb >= 0;
            if (ha && hb && na != nb) {
                return Float.compare(na, nb);
            }
            if (ha != hb) {
                return ha ? -1 : 1;
            }
            long da = a.getDate_upload();
            long db = b.getDate_upload();
            if (da > 0 && db > 0 && da != db) {
                return Long.compare(da, db);
            }
            return 0;
        });
        return list;
    }

    private String srcLastKey() {
        return "srclast:" + mSourceEntry.getSource().getId() + ":" + mSourceManga.getUrl();
    }

    private void startSourceReading() {
        List<SChapter> list = sortedChapters();
        if (list.isEmpty()) {
            Toast.makeText(getContext(), "暂无章节", Toast.LENGTH_SHORT).show();
            return;
        }
        readSourceChapter(list.get(0), true);
    }

    private void continueSourceReading() {
        String last = progressPrefs().getString(srcLastKey(), "");
        if (last.isEmpty()) {
            Toast.makeText(getContext(), "这部漫画还没有阅读记录", Toast.LENGTH_SHORT).show();
            return;
        }
        for (SChapter chapter : mSourceChapters) {
            if (chapter.getUrl().equals(last)) {
                readSourceChapter(chapter, false);
                return;
            }
        }
        Toast.makeText(getContext(), "记录的章节不在当前章节列表中", Toast.LENGTH_SHORT).show();
    }

    private void readSourceChapter(SChapter chapter, boolean fromStart) {
        if (mSourceEntry == null || mSourceManga == null) {
            return;
        }
        File dir = MangaSourceRepository.INSTANCE.chapterDir(
                getContext(), mSourceEntry.getSource(), mSourceManga, chapter);
        int startPage = 0;
        if (!fromStart && chapter.getUrl().equals(progressPrefs().getString(srcLastKey(), ""))) {
            startPage = progressPrefs().getInt("page:" + dir.getAbsolutePath(), 0);
        }
        final int resumePage = startPage;
        mSourceMangaStatus = "准备下载…";
        refreshPage(MangaPage.SOURCE_MANGA);
        MangaSourceRepository.INSTANCE.downloadChapter(
                getContext(), mSourceEntry.getSource(), mSourceManga, chapter,
                (done, total) -> {
                    mSourceMangaStatus = "下载中 " + done + "/" + total;
                    refreshPage(MangaPage.SOURCE_MANGA);
                },
                (folder, error) -> {
                    if (folder == null) {
                        mSourceMangaStatus = "下载失败：" + error;
                        refreshPage(MangaPage.SOURCE_MANGA);
                        Toast.makeText(getContext(), "下载失败：" + error, Toast.LENGTH_LONG).show();
                    } else {
                        mSourceMangaStatus = "下载完成";
                        progressPrefs().edit().putString(srcLastKey(), chapter.getUrl()).apply();
                        openReaderAtPage(folder, resumePage);
                    }
                });
    }

    private void openReaderAtPage(File folder, int page) {
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).openMangaReader(folder, page);
        }
    }

    private void installApk(File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(getContext(),
                    getContext().getPackageName() + ".provider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "无法启动安装：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ----------------------------------------------------------- settings

    private void fillSettingsPage(LinearLayout content) {
        // One settings surface only: the stock Wolvic settings dialog hosts the
        // manga tab. This shelf page keeps the very same panel so both entry
        // points render identical controls (no second, divergent editor).
        MangaSettingsPanel panel = new MangaSettingsPanel(getContext());
        panel.setSubPageOpener(this::openMangaSubPage);
        content.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mSettingsPanel = panel;

        addInfo(content, "存储权限", hasStoragePermission() ? "已授予" : "未授予（书架无法读取）");
        LinearLayout actions = addRow(content);
        addButton(actions, "重新扫描书架", v -> scanLibrary());
        Button permission = addButton(actions, "申请存储权限", v -> {
            mPermissionRequested = false;
            requestStoragePermission();
        });
        permission.setEnabled(!hasStoragePermission());
        addButton(actions, "切换到浏览器", v -> {
            Context context = getContext();
            if (context instanceof VRBrowserActivity) {
                ((VRBrowserActivity) context).onMangaTrayAction("BROWSER");
            }
        });
    }

    /** Routes the manga settings panel's sub-page buttons. */
    private void openMangaSubPage(String key) {
        Context context = getContext();
        if (!(context instanceof VRBrowserActivity)) {
            return;
        }
        VRBrowserActivity activity = (VRBrowserActivity) context;
        switch (key) {
            case "READER":
            case "SOURCES":
            case "DOWNLOADS":
            case "BACKUP":
            case "ADVANCED":
                activity.showMangaSettingsSubPage(key);
                break;
            default:
                activity.onMangaTrayAction(key);
                break;
        }
    }

    // ------------------------------------------------------------- helpers

    private List<EditText> bindConfigFields(LinearLayout content, String[] fields) {
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
            input.setText(mSettings.text(key));
            if (key.toLowerCase(Locale.ROOT).contains("pass")) {
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            content.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            inputs.add(input);
        }
        return inputs;
    }

    private void saveConfigFields(String[] fields, List<EditText> inputs) {
        for (int i = 0; i < fields.length; i += 2) {
            int index = i / 2;
            if (index < inputs.size()) {
                mSettings.put(fields[i], inputs.get(index).getText().toString());
            }
        }
        Log.i(TAG, "saved config fields=" + (fields.length / 2));
    }

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

    private boolean isMedia(String name) {
        return MangaSettings.hasExtension(name, mSettings.imageExtensions())
                || MangaSettings.hasExtension(name, mSettings.videoExtensions());
    }

    private File findFirstMedia(File dir, String[] images) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<File> found = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && MangaSettings.hasExtension(file.getName(), images)) {
                found.add(file);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        return found.get(0);
    }

    private boolean containsMedia(File dir, String[] videos) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && MangaSettings.hasExtension(file.getName(), videos)) {
                return true;
            }
        }
        return false;
    }

    /** Floating grab bar: see VRBrowserActivity.applyUiMode(). */
    public static class MangaDragBarWidget extends UIWidget {

        private UIWidget mTarget;
        private boolean mAdded;

        public MangaDragBarWidget(Context aContext) {
            super(aContext);
            buildView();
        }

        private void buildView() {
            setBackgroundColor(0x00000000);

            LinearLayout bar = new LinearLayout(getContext());
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setBackgroundColor(0xCC2A2A33);

            View grip = new View(getContext());
            grip.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(dp(96), dp(6));
            gripParams.setMargins(0, 0, dp(12), 0);
            bar.addView(grip, gripParams);

            TextView label = new TextView(getContext());
            label.setText("拖动移动窗口");
            label.setTextColor(0xFFD0D0DC);
            label.setTextSize(14f);
            bar.addView(label);

            addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            bar.setOnTouchListener((view, event) -> {
                if (mTarget == null || mWidgetManager == null) {
                    return true;
                }
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mWidgetManager.startWidgetMove(mTarget,
                                WidgetManagerDelegate.WIDGET_MOVE_BEHAVIOUR_MANGA);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mWidgetManager.finishWidgetMove();
                        return true;
                    default:
                        // The native mover runs in the render loop; no Java deltas.
                        return true;
                }
            });
        }

        @Override
        protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
            aPlacement.width = dp(320);
            aPlacement.height = dp(30);
            aPlacement.worldWidth = 0.8f;
            aPlacement.anchorX = 0.5f;
            aPlacement.anchorY = 0.5f;
            aPlacement.parentAnchorX = 0.5f;
            aPlacement.parentAnchorY = 0.5f;
            aPlacement.parentHandle = -1;
            aPlacement.translationX = 0f;
            aPlacement.translationY = 0f;
            aPlacement.translationZ = 0f;
            aPlacement.cylinder = false;
            aPlacement.visible = false;
        }

        /** Re-parent under the given manga surface and place the bar just below
         *  that surface's bottom edge, in the gap above the tray. */
        public void attachTo(UIWidget target) {
            if (target == null) {
                detach();
                return;
            }
            mTarget = target;
            WidgetPlacement t = target.getPlacement();
            WidgetPlacement p = getPlacement();
            float targetWorldHeight = t.worldWidth
                    * ((float) t.height / (float) Math.max(1, t.width)) * 360.0f;
            float barWorldHeight = p.worldWidth
                    * ((float) p.height / (float) Math.max(1, p.width)) * 360.0f;

            p.parentHandle = target.getHandle();
            p.parentAnchorX = 0.5f;
            p.parentAnchorY = 0.5f;
            p.translationX = 0f;
            p.translationY = -(targetWorldHeight * 0.5f) - (barWorldHeight * 0.5f) - 6f;
            p.translationZ = 0f;
            p.visible = true;

            if (!mAdded) {
                mAdded = true;
                mWidgetManager.addWidget(this);
            } else {
                mWidgetManager.updateWidget(this);
            }
        }

        /** Keeps the floating bar at a fixed world position and size when
         *  a canvas zoom grows the parent manga surface upwards. */
        public void onMangaParentResized(float deltaParentY, float deltaParentZ) {
            if (mTarget == null) {
                return;
            }
            WidgetPlacement p = getPlacement();
            p.translationY -= deltaParentY;
            p.translationZ -= deltaParentZ;
            p.parentAnchorX = 0.5f;
            p.parentAnchorY = 0.5f;
            // Pin the original quad: the control bar / grab bar must never
            // scale together with the manga canvas.
            p.worldWidth = 0.8f;
            p.width = dp(320);
            p.height = dp(30);
            if (mAdded && mWidgetManager != null) {
                mWidgetManager.updateWidget(this);
            }
        }

        public void detach() {
            mTarget = null;
            WidgetPlacement p = getPlacement();
            p.visible = false;
            p.parentHandle = -1;
            if (mAdded && mWidgetManager != null) {
                mWidgetManager.updateWidget(this);
            }
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

}
