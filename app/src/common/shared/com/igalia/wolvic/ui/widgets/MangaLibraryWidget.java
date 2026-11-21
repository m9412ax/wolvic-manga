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
import com.igalia.wolvic.manga.MangaChapterSession;
import com.igalia.wolvic.manga.MangaDownloader;
import com.igalia.wolvic.manga.MangaKeyCapture;
import com.igalia.wolvic.manga.MangaSmb;
import com.igalia.wolvic.manga.Manga115Api;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.MangaSettingsPanel;
import com.igalia.wolvic.manga.MangaSourceManager;
import com.igalia.wolvic.manga.MangaSourceRepository;
import com.igalia.wolvic.manga.MangaWebDav;
import com.igalia.wolvic.manga.RemoteManga;
import com.igalia.wolvic.manga.SmbRemoteManga;
import com.igalia.wolvic.manga.Netdisk115RemoteManga;
import com.igalia.wolvic.manga.SortUtil;
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
    // SMB2/3 browse state (path is relative to the configured share root).
    private final List<MangaSmb.Entry> mSmbEntries = new ArrayList<>();
    private String mSmbPath = "";
    private String mSmbStatus = "未连接";
    /** Once credentials are saved, entering the SMB tab auto-connects and
     *  shows the share as a folder list; the config form is hidden behind
     *  "设置". Reset on each fresh app session, not on every refresh. */
    private boolean mSmbAutoConnected = false;
    private boolean mSmbConfigOpen = false;
    /** Local-browser settings fold (default folder set/clear, etc.). */
    private boolean mLocalSettingsOpen = false;
    /** Netdisk (WebDAV/115) settings fold - all config lives here so the page
     *  itself stays a clean file-manager directory. */
    private boolean mNetdiskSettingsOpen = false;
    /** Netdisk browser is at the top-level drive list (vs inside a drive). */
    private boolean mNetdiskAtRoot = true;
    /** Which drive is currently open: "115" or "webdav". */
    private String mNetdiskMode = "";
    /** Shares discovered on the host when SMB_SHARE is left empty. */
    private final List<String> mSmbShares = new ArrayList<>();
    /** Relative path of the folder currently selected as a "manga to read". */
    private String mSmbMangaRoot = "";
    // 115 QR login state
    private Manga115Api.QrSession mP115Qr;
    private android.graphics.Bitmap mP115QrBitmap;
    /** 115 online browser state (cid "0" = root). */
    private String mP115Cid = "0";
    private String mP115Title = "115 漫画";
    /** Parent (cid,title) stack so "← 上级" returns to the real parent instead
     *  of always jumping to root. */
    private final java.util.Deque<String[]> mP115ParentStack = new java.util.ArrayDeque<>();
    private boolean mP115Restored = false;
    private final List<Manga115Api.DirEntry> mP115Entries = new ArrayList<>();
    private String mP115DirStatus = "";
    private String mP115Status = "未登录";
    private final Handler mP115PollHandler = new Handler(Looper.getMainLooper());
    private final Runnable mP115Poll = new Runnable() {
        @Override public void run() { poll115Qr(); }
    };
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "manga-io");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Timestamp guard: a VR pointer can fire several click events for one
     *  physical press; opening the reader repeatedly stacks windows. */
    private long mLastOpenReaderMs = 0L;

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
        mAdapter = new MangaCoverAdapter(getContext(),
                new MangaCoverAdapter.OnMangaClickListener() {
                    @Override
                    public void onMangaClick(File folder) {
                        openFolder(folder);
                    }
                    @Override
                    public void onRemoteMangaClick(String remoteKey) {
                        openRemoteFromShelf(remoteKey);
                    }
                }, this::resetProgress);
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
        float sizeFactor = MangaSettings.librarySizeFactor(
                MangaSettings.get(context).integer(MangaSettings.LIBRARY_SIZE));
        int windowWidth = (int) (SettingsStore.getInstance(context).getWindowWidth() * sizeFactor);
        int windowHeight = (int) (SettingsStore.getInstance(context).getWindowHeight() * sizeFactor);

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
        applyLibrarySize();
        if (hasStoragePermission()) {
            refreshCurrentPage();
        }
        Log.i(TAG, "onResume page=" + mCurrentPage.name());
    }

    /** Re-applies the 小/中/大 window size and cover scale. Same native path
     *  as the browser window resize (placement -> updateWidgetNative). */
    public void applyLibrarySize() {
        initializeWidgetPlacement(mWidgetPlacement);
        updateWidget();
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (mSourceAdapter != null) {
            mSourceAdapter.notifyDataSetChanged();
        }
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

    /** Handles the controller's back button while the bookshelf is showing.
     *  Mirrors the on-screen "返回" buttons: step up one folder / leave a
     *  source sub-page, otherwise fall back to the main bookshelf. */
    public boolean goBack() {
        if (mCurrentPage == MangaPage.FOLDER) {
            File parent = mFolderDir != null ? mFolderDir.getParentFile() : null;
            if (parent != null) {
                openFolderView(parent, false);
                return true;
            }
            switchPage(MangaPage.LIBRARY);
            return true;
        }
        if (mCurrentPage == MangaPage.LOCAL) {
            File parent = mLocalDir != null ? mLocalDir.getParentFile() : null;
            if (parent != null) {
                mLocalDir = parent;
                mSettings.setLastDir(parent.getAbsolutePath());
                refreshPage(MangaPage.LOCAL);
                return true;
            }
            switchPage(MangaPage.LIBRARY);
            return true;
        }
        if (mCurrentPage == MangaPage.WEBDAV) {
            int slash = mWebDavUrl == null ? -1 : mWebDavUrl.lastIndexOf('/', Math.max(0, mWebDavUrl.length() - 2));
            if (slash > 8) {
                mWebDavUrl = mWebDavUrl.substring(0, slash + 1);
                refreshPage(MangaPage.WEBDAV);
                return true;
            }
            switchPage(MangaPage.LIBRARY);
            return true;
        }
        if (mCurrentPage == MangaPage.SOURCE_BROWSE || mCurrentPage == MangaPage.SOURCE_MANGA
                || mCurrentPage == MangaPage.SOURCE_SETTINGS || mCurrentPage == MangaPage.SOURCE_FILTERS
                || mCurrentPage == MangaPage.SOURCE_OPTIONS || mCurrentPage == MangaPage.SOURCE_TEXT) {
            switchPage(MangaPage.EXTENSIONS);
            return true;
        }
        if (mCurrentPage != MangaPage.LIBRARY) {
            switchPage(MangaPage.LIBRARY);
            return true;
        }
        return false; // already at the bookshelf root
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
        // SMB: once configured, auto-connect on entry and show the share as a
        // plain folder list (no repeated setup screen).
        if (page == MangaPage.SMB && smbConfigured() && !mSmbAutoConnected) {
            mSmbAutoConnected = true;
            mSmbConfigOpen = false;
            String def = mSettings.text(MangaSettings.SMB_DEFAULT_PATH);
            mSmbPath = def.isEmpty() ? mSettings.text(MangaSettings.SMB_PATH) : def;
            connectSmb();
        }
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
        // Remote (SMB/115) bookshelf entries are tagged strings, not paths.
        List<String> remoteShelfKeys = new ArrayList<>();
        for (String saved : mSettings.bookshelf()) {
            if (saved.startsWith(SHELF_SMB) || saved.startsWith(SHELF_115)) {
                remoteShelfKeys.add(saved);
                continue;
            }
            File extra = new File(saved);
            if (extra.isDirectory() && !dirs.contains(extra)) {
                dirs.add(extra);
            }
        }
        final int libSort = mSettings.integer(MangaSettings.SORT_MODE);
        final java.util.Comparator<File> libCmp =
                SortUtil.comparator(libSort, File::getName, File::lastModified);
        dirs.sort(libCmp);
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
        // Remote entries first (online, no local folder / cover).
        for (String key : remoteShelfKeys) {
            // folder may be @NonNull in MangaItem; use a harmless placeholder.
            items.add(0, new MangaCoverAdapter.MangaItem(
                    new File("/remote"), remoteShelfTitle(key), null, false, key));
        }
        return items;
    }

    private void showEmptyState(String message) {
        mAdapter.update(new ArrayList<>());
        mEmptyText.setText(message.trim());
        mEmptyText.setVisibility(VISIBLE);
        mGrid.setVisibility(GONE);
    }

    private void openReader(File folder) {
        openLocalSession(folder, null, true);
    }

    /** A local manga is one folder whose media sub-folders are chapters.
     *  Opening a chapter folder directly resolves its parent manga so page
     *  turns can cross into sibling chapters. */
    private void openLocalSession(File folder, String startFilePath, boolean resume) {
        if (folder == null) {
            return;
        }
        long nowOpen = System.currentTimeMillis();
        if (nowOpen - mLastOpenReaderMs < 900L) {
            Log.i(TAG, "openLocalSession suppressed duplicate click");
            return;
        }
        mLastOpenReaderMs = nowOpen;
        Context context = getContext();
        if (!(context instanceof VRBrowserActivity)) {
            return;
        }
        File root = folder;
        List<File> chapters = mediaSubDirs(folder);
        if (chapters.size() < 2) {
            File parent = folder.getParentFile();
            List<File> siblings = mediaSubDirs(parent);
            if (siblings.size() >= 2 && siblings.contains(folder)) {
                root = parent;
                chapters = siblings;
            } else {
                chapters = new ArrayList<>();
                chapters.add(folder);
            }
        }
        int index = Math.max(0, chapters.indexOf(folder));
        int page = 0;
        float frac = 0f;
        if (resume) {
            float[] saved = MangaReaderWidget.readChapterProgress(
                    progressPrefs(), "local:" + root.getAbsolutePath());
            if (saved != null) {
                index = Math.max(0, Math.min((int) saved[0], chapters.size() - 1));
                page = (int) saved[1];
                frac = saved[2];
            } else {
                page = progressPrefs().getInt(
                        "page:" + chapters.get(index).getAbsolutePath(), 0);
            }
        } else if (startFilePath != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (startFilePath.startsWith(
                        chapters.get(i).getAbsolutePath() + File.separator)) {
                    index = i;
                    break;
                }
            }
        } else {
            index = 0;
        }
        MangaChapterSession session = MangaChapterSession.local(
                root, chapters, index, page, frac, startFilePath);
        ((VRBrowserActivity) context).openMangaReaderSession(session);
        Log.i(TAG, "openLocalSession root=" + root.getAbsolutePath()
                + " chapters=" + chapters.size() + " index=" + index + " page=" + page);
    }

    private List<File> mediaSubDirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) {
            return out;
        }
        String[] images = mSettings.imageExtensions();
        String[] videos = mSettings.videoExtensions();
        boolean includeHidden = mSettings.bool(MangaSettings.SOURCE_INCLUDE_HIDDEN);
        for (File child : children) {
            if (!child.isDirectory()
                    || (!includeHidden && child.getName().startsWith("."))) {
                continue;
            }
            if (hasMediaRecursive(child, images, 2) || hasMediaRecursive(child, videos, 2)) {
                out.add(child);
            }
        }
        out.sort((a, b) -> mSettings.bool(MangaSettings.SOURCE_NATURAL_SORT)
                ? MangaCoverAdapter.naturalCompare(a.getName(), b.getName())
                : a.getName().compareToIgnoreCase(b.getName()));
        return out;
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

        final String[] sizeNames = {"小", "中", "大"};
        Button sizeToggle = new Button(getContext());
        sizeToggle.setAllCaps(false);
        sizeToggle.setTextSize(16f);
        sizeToggle.setTextColor(0xFFFFFFFF);
        sizeToggle.setBackgroundColor(0xFF4A3A6A);
        sizeToggle.setMinWidth(dp(140));
        sizeToggle.setText("⤢ 视图：" + sizeNames[mSettings.integer(MangaSettings.LIBRARY_SIZE)]);
        sizeToggle.setOnClickListener(v -> {
            int next = (mSettings.integer(MangaSettings.LIBRARY_SIZE) + 1) % 3;
            mSettings.put(MangaSettings.LIBRARY_SIZE, next);
            applyLibrarySize();
            sizeToggle.setText("⤢ 视图：" + sizeNames[next]);
        });
        bar.addView(sizeToggle);

        final Button sortToggle = new Button(getContext());
        sortToggle.setAllCaps(false);
        sortToggle.setTextSize(16f);
        sortToggle.setTextColor(0xFFFFFFFF);
        sortToggle.setBackgroundColor(0xFF5A4A2A);
        sortToggle.setMinWidth(dp(180));
        sortToggle.setText("↕ " + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)));
        sortToggle.setOnClickListener(v -> {
            int next = SortUtil.next(mSettings.integer(MangaSettings.SORT_MODE));
            mSettings.put(MangaSettings.SORT_MODE, next);
            sortToggle.setText("↕ " + SortUtil.label(next));
            scanLibrary();
        });
        bar.addView(sortToggle);

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

    // ------------------------------------------- ONLINE remote manga (SMB/115)

    private static final String SHELF_SMB = "remote:smb:";
    private static final String SHELF_115 = "remote:115:";

    /** Cycles the shared sort mode and refreshes the relevant listing page. */
    private void cycleSortAndApply(MangaPage page) {
        int next = SortUtil.next(mSettings.integer(MangaSettings.SORT_MODE));
        mSettings.put(MangaSettings.SORT_MODE, next);
        Toast.makeText(getContext(), "排序：" + SortUtil.label(next), Toast.LENGTH_SHORT).show();
        if (page == MangaPage.LIBRARY) {
            scanLibrary();
        } else {
            refreshPage(page);
        }
    }

    /** Stable cache dir for one remote chapter: <cache>/remote-manga/<hash(key)>/<chapterIdx>. */
    private File remoteChapterDir(RemoteManga manga, int chapterIndex) {
        File base = getContext().getExternalCacheDir();
        if (base == null) base = getContext().getCacheDir();
        return new File(new File(base, "remote-manga"),
                stableHash(manga.key()) + File.separator + chapterIndex);
    }

    private static String stableHash(String s) {
        int h = (s == null ? 0 : s.hashCode()) & 0x7fffffff;
        return String.format(java.util.Locale.ROOT, "%08x", h);
    }

    /** Builds a remote source with the SAME sort config the file browser uses,
     *  so the reader's page order == the folder listing order. */
    private RemoteManga smbManga(MangaSmb.Auth auth, String path) {
        return new SmbRemoteManga(auth, path,
                concat(mSettings.imageExtensions(), mSettings.videoExtensions()),
                mSettings.videoExtensions(),
                mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST));
    }

    private RemoteManga p115Manga(String cookie, String cid, String title) {
        return new Netdisk115RemoteManga(cookie, cid, title,
                concat(mSettings.imageExtensions(), mSettings.videoExtensions()),
                mSettings.videoExtensions(),
                mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST));
    }

    /** Opens a remote manga ONLINE: chapters are listed first (no image
     *  download), then the reader fetches pages on demand with the same
     *  layered/concurrent pipeline as extension sources. */
    private void openRemoteManga(final RemoteManga manga) {
        openRemoteManga(manga, null);
    }

    /** [startPageName] positions the reader at a specific image of the folder
     *  (used when the user taps one file: open the WHOLE manga at that page). */
    private void openRemoteManga(final RemoteManga manga, final String startPageName) {
        // Guard against repeated VR clicks opening the same reader many times.
        long now = System.currentTimeMillis();
        if (now - mLastOpenReaderMs < 900L) {
            Log.i(TAG, "openRemoteManga suppressed duplicate click");
            return;
        }
        mLastOpenReaderMs = now;
        Toast.makeText(getContext(), "正在读取 " + manga.title() + " 的章节…", Toast.LENGTH_SHORT).show();
        mIoExecutor.execute(() -> {
            try {
                final List<RemoteManga.ChapterInfo> chapters = manga.listChapters();
                if (chapters.isEmpty()) {
                    mMain.post(() -> {
                        Toast.makeText(getContext(), "该文件夹内没有子章节", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                final List<File> dirs = new ArrayList<>();
                for (int i = 0; i < chapters.size(); i++) {
                    dirs.add(remoteChapterDir(manga, i));
                }
                // Restore reading progress by the same key the reader saves.
                int index = 0;
                int page = 0;
                float frac = 0f;
                float[] saved = MangaReaderWidget.readChapterProgress(
                        progressPrefs(), "remote:" + manga.key());
                if (saved != null) {
                    index = Math.max(0, Math.min((int) saved[0], chapters.size() - 1));
                    page = (int) saved[1];
                    frac = saved[2];
                }
                // If the user tapped a specific file, position the reader at
                // that image instead of at the saved/first page.
                if (startPageName != null && !startPageName.isEmpty()) {
                    int[] found = locateRemotePage(manga, chapters, startPageName);
                    if (found != null) {
                        index = found[0];
                        page = found[1];
                        frac = 0f;
                    }
                }
                final int fi = index, fp = page;
                final float ff = frac;
                mMain.post(() -> {
                    MangaChapterSession session = MangaChapterSession.remote(
                            getContext(), manga, chapters, dirs, fi, fp, ff);
                    ((VRBrowserActivity) getContext()).openMangaReaderSession(session);
                    Log.i(TAG, "openRemoteManga " + manga.key()
                            + " chapters=" + chapters.size() + " index=" + fi);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                mMain.post(() -> Toast.makeText(getContext(),
                        "在线读取失败：" + msg, Toast.LENGTH_LONG).show());
                Log.w(TAG, "openRemoteManga failed: " + msg);
            }
        });
    }

    /** Finds (chapterIndex, pageIndex) of a tapped file by name. Runs on the IO
     *  thread. Returns null when not found (caller keeps saved position). */
    private int[] locateRemotePage(RemoteManga manga,
                                   List<RemoteManga.ChapterInfo> chapters,
                                   String startPageName) {
        for (int c = 0; c < chapters.size(); c++) {
            try {
                List<RemoteManga.PageInfo> pages = manga.listPages(chapters.get(c));
                for (int p = 0; p < pages.size(); p++) {
                    String hint = pages.get(p).nameHint;
                    if (hint != null && (hint.equals(startPageName)
                            || hint.endsWith("/" + startPageName)
                            || startPageName.equals(new File(hint).getName()))) {
                        return new int[]{c, p};
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "locateRemotePage chapter " + c + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** Adds a remote manga to the bookshelf as a tagged string (no download). */
    private void addRemoteToShelf(String shelfKey) {
        mSettings.addToBookshelf(shelfKey);
        Toast.makeText(getContext(), "已加入书架", Toast.LENGTH_SHORT).show();
    }

    /** Re-opens a remote bookshelf entry by reconnecting to SMB/115. */
    private void openRemoteFromShelf(String shelfKey) {
        if (shelfKey.startsWith(SHELF_SMB)) {
            final String key = shelfKey.substring(SHELF_SMB.length());
            // key = smb://host/share/path
            String rest = key.substring("smb://".length());
            int slash = rest.indexOf('/');
            final String host = slash > 0 ? rest.substring(0, slash) : rest;
            final String shareAndPath = slash > 0 ? rest.substring(slash + 1) : "";
            int s2 = shareAndPath.indexOf('/');
            final String share = s2 > 0 ? shareAndPath.substring(0, s2) : shareAndPath;
            final String path = s2 > 0 ? shareAndPath.substring(s2 + 1) : "";
            final MangaSmb.Auth auth = new MangaSmb.Auth(host, share,
                    mSettings.text(MangaSettings.SMB_DOMAIN),
                    mSettings.text(MangaSettings.SMB_USER),
                    mSettings.text(MangaSettings.SMB_PASS));
            openRemoteManga(smbManga(auth, path));
        } else if (shelfKey.startsWith(SHELF_115)) {
            String rest = shelfKey.substring(SHELF_115.length());
            int sep = rest.indexOf('|');
            final String cid = sep > 0 ? rest.substring(0, sep) : rest;
            final String title = sep > 0 ? rest.substring(sep + 1) : "115 网盘";
            String cookie = mSettings.text(MangaSettings.P115_COOKIE);
            if (cookie == null || cookie.isEmpty()) {
                Toast.makeText(getContext(), "请先登录 115", Toast.LENGTH_SHORT).show();
                switchPage(MangaPage.WEBDAV);
                return;
            }
            openRemoteManga(p115Manga(cookie, cid, title));
        }
    }

    /** Parses a remote shelf key into a display title. */
    private String remoteShelfTitle(String shelfKey) {
        if (shelfKey.startsWith(SHELF_SMB)) {
            String key = shelfKey.substring(SHELF_SMB.length());
            int i = key.lastIndexOf('/');
            return i > 0 ? key.substring(i + 1) : key;
        }
        if (shelfKey.startsWith(SHELF_115)) {
            String rest = shelfKey.substring(SHELF_115.length());
            int sep = rest.indexOf('|');
            return sep > 0 ? rest.substring(sep + 1) : "115 网盘";
        }
        return "在线漫画";
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
        openLocalSession(folder, file == null ? null : file.getAbsolutePath(), false);
    }

    private void continueReading(File folder) {
        openLocalSession(folder, null, true);
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
    /** Compact default-location control: just one "★ 默认位置" button that
     *  jumps to the saved default (or sets the current folder as default when
     *  none is saved). Set/clear live in the settings section. */
    private void addDefaultDirControls(LinearLayout content, String source, File current) {
        String def = mSettings.defaultDir(source);
        LinearLayout row = addRow(content);
        addButton(row, def.isEmpty() ? "★ 设为默认位置" : "★ 默认位置：" + fileNameOf(def), v -> {
            String target = mSettings.defaultDir(source);
            if (target.isEmpty()) {
                if (current == null) return;
                mSettings.setDefaultDir(source, current.getAbsolutePath());
                Toast.makeText(getContext(), "已设为默认位置", Toast.LENGTH_SHORT).show();
                refreshPage(mCurrentPage);
                return;
            }
            File dir = new File(target);
            if (!dir.isDirectory()) {
                Toast.makeText(getContext(), "默认位置已不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            gotoDefaultDir(source, dir);
        });
    }

    private static String fileNameOf(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf(File.separator);
        return i > 0 ? path.substring(i + 1) : path;
    }

    /** Applies a default directory for a given source, using the right nav state. */
    private void gotoDefaultDir(String source, File dir) {
        if ("LOCAL".equals(source)) {
            mLocalDir = dir;
            mSettings.setLastDir(dir.getAbsolutePath());
            openFolderView(dir, false);
        } else if ("SMB".equals(source)) {
            mSmbPath = "";
            connectSmb();
        } else {
            refreshPage(mCurrentPage);
        }
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
        final java.util.Comparator<File> mediaBase = SortUtil.comparator(
                mSettings.integer(MangaSettings.SORT_MODE), File::getName, File::lastModified);
        java.util.Comparator<File> mediaCmp = mediaBase;
        if (mSettings.bool(MangaSettings.SORT_VIDEO_FIRST)) {
            final String[] videoExt = mSettings.videoExtensions();
            final java.util.Comparator<File> base = mediaBase;
            mediaCmp = (a, b) -> {
                boolean va = MangaSettings.hasExtension(a.getName(), videoExt);
                boolean vb = MangaSettings.hasExtension(b.getName(), videoExt);
                if (va != vb) return va ? -1 : 1;
                return base.compare(a, b);
            };
        }
        media.sort(mediaCmp);
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
        addInfo(content, "当前目录", mLocalDir == null ? "不可用" : mLocalDir.getAbsolutePath());
        addDefaultDirControls(content, "LOCAL", mLocalDir);
        if (mLocalSettingsOpen) {
            String def = mSettings.defaultDir("LOCAL");
            addInfo(content, "设置", "默认位置：" + (def.isEmpty() ? "未设置（默认恢复到上次位置）" : def));
            LinearLayout setRow = addRow(content);
            addButton(setRow, "设为默认位置", v -> {
                if (mLocalDir == null) return;
                mSettings.setDefaultDir("LOCAL", mLocalDir.getAbsolutePath());
                Toast.makeText(getContext(), "已设为默认位置", Toast.LENGTH_SHORT).show();
                mLocalSettingsOpen = false;
                refreshPage(MangaPage.LOCAL);
            });
            addButton(setRow, "清除默认", v -> {
                mSettings.setDefaultDir("LOCAL", "");
                mLocalSettingsOpen = false;
                refreshPage(MangaPage.LOCAL);
            });
            addButton(setRow, "收起设置", v -> {
                mLocalSettingsOpen = false;
                refreshPage(MangaPage.LOCAL);
            });
        }

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
        addButton(actions, "排序：" + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)),
                v -> cycleSortAndApply(MangaPage.LOCAL));
        addButton(actions, mLocalSettingsOpen ? "收起设置" : "⚙ 设置", v -> {
            mLocalSettingsOpen = !mLocalSettingsOpen;
            refreshPage(MangaPage.LOCAL);
        });

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
        final int localSort = mSettings.integer(MangaSettings.SORT_MODE);
        final java.util.Comparator<File> localCmp =
                SortUtil.comparator(localSort, File::getName, File::lastModified);
        dirs.sort(localCmp);
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

    private boolean smbConfigured() {
        // Share name is optional: when empty we enumerate all shares on the host.
        String host = mSettings.text(MangaSettings.SMB_HOST);
        return !host.trim().isEmpty();
    }

    private MangaSmb.Auth smbAuth() {
        return new MangaSmb.Auth(
                mSettings.text(MangaSettings.SMB_HOST),
                mSettings.text(MangaSettings.SMB_SHARE),
                mSettings.text(MangaSettings.SMB_DOMAIN),
                mSettings.text(MangaSettings.SMB_USER),
                mSettings.text(MangaSettings.SMB_PASS));
    }

    private void fillSmbPage(LinearLayout content) {
        String share = mSettings.text(MangaSettings.SMB_SHARE);
        boolean shareEmpty = share == null || share.trim().isEmpty();

        if (!smbConfigured() || mSmbConfigOpen) {
            addInfo(content, "SMB2/3 共享设置",
                    "填写主机 IP、账号密码并保存。共享名可留空——留空时会自动列出主机上的所有共享供你选择。");
            String[] fields = {
                    MangaSettings.SMB_HOST, "主机 / IP",
                    MangaSettings.SMB_SHARE, "共享名（留空=列出全部）",
                    MangaSettings.SMB_DOMAIN, "域（可留空）",
                    MangaSettings.SMB_USER, "用户名",
                    MangaSettings.SMB_PASS, "密码",
                    MangaSettings.SMB_PATH, "起始远程路径（可留空=共享根）"};
            final List<EditText> inputs = bindConfigFields(content, fields);
            LinearLayout actions = addRow(content);
            addButton(actions, "保存并连接", v -> {
                saveConfigFields(fields, inputs);
                mSmbPath = mSettings.text(MangaSettings.SMB_PATH);
                mSmbConfigOpen = false;
                mSmbAutoConnected = true;
                mSmbShares.clear();
                connectSmb();
            });
            if (smbConfigured()) {
                addButton(actions, "清除默认位置", v -> {
                    mSettings.put(MangaSettings.SMB_DEFAULT_PATH, "");
                    Toast.makeText(getContext(), "已清除 SMB 默认位置", Toast.LENGTH_SHORT).show();
                });
                addButton(actions, "取消", v -> {
                    mSmbConfigOpen = false;
                    refreshPage(MangaPage.SMB);
                });
            }
            return;
        }

        // If no share chosen yet: show discovered shares (or a "list shares" button).
        if (shareEmpty) {
            addInfo(content, "SMB 主机 · " + mSettings.text(MangaSettings.SMB_HOST), mSmbStatus);
            LinearLayout actions0 = addRow(content);
            addButton(actions0, "列出共享", v -> listSmbShares());
            addButton(actions0, "设置", v -> {
                mSmbConfigOpen = true;
                refreshPage(MangaPage.SMB);
            });
            if (mSmbShares.isEmpty()) {
                addInfo(content, "共享列表", "点「列出共享」查看该主机上可访问的共享；也可在设置里直接填共享名。");
            } else {
                addInfo(content, "可访问的共享（点选进入）", "共 " + mSmbShares.size() + " 个");
                for (final String name : mSmbShares) {
                    Button row = newButton("▣ " + name);
                    row.setOnClickListener(v -> {
                        mSettings.put(MangaSettings.SMB_SHARE, name);
                        mSmbPath = "";
                        connectSmb();
                    });
                    content.addView(row, wrapParams());
                }
            }
            return;
        }

        // Configured with a share: folder-style browser.
        addInfo(content, "SMB 共享 · " + mSettings.text(MangaSettings.SMB_HOST) + " / " + share,
                mSmbStatus);
        addInfo(content, "当前目录", mSmbPath.isEmpty() ? "共享根目录" : "/" + mSmbPath);
        addInfo(content, "看图提示", "进入任意文件夹后点「▷ 在线阅读」：首图秒开、边看边缓存、支持书签与预载，不会整本下载。点「＋ 加入书架」可长期收藏。");

        LinearLayout actions = addRow(content);
        if (!mSmbPath.isEmpty()) {
            addButton(actions, "← 上级", v -> {
                int slash = mSmbPath.lastIndexOf('/');
                mSmbPath = slash > 0 ? mSmbPath.substring(0, slash) : "";
                connectSmb();
            });
        }
        addButton(actions, "刷新", v -> connectSmb());
        addButton(actions, "排序：" + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)),
                v -> cycleSortAndApply(MangaPage.SMB));
        String smbDef = mSettings.text(MangaSettings.SMB_DEFAULT_PATH);
        addButton(actions, smbDef.isEmpty() ? "★ 设为默认位置" : "★ 默认位置", v -> {
            String target = mSettings.text(MangaSettings.SMB_DEFAULT_PATH);
            if (target.isEmpty()) {
                mSettings.put(MangaSettings.SMB_DEFAULT_PATH, mSmbPath);
                Toast.makeText(getContext(), "已设为 SMB 默认位置", Toast.LENGTH_SHORT).show();
            } else {
                mSmbPath = target;
                connectSmb();
            }
        });
        addButton(actions, "▷ 在线阅读", v -> readSmbFolderOnline(mSmbPath));
        addButton(actions, "＋ 加入书架", v -> {
            String key = SHELF_SMB + "smb://" + mSettings.text(MangaSettings.SMB_HOST)
                    + "/" + mSettings.text(MangaSettings.SMB_SHARE)
                    + (mSmbPath.isEmpty() ? "" : "/" + mSmbPath);
            addRemoteToShelf(key);
        });
        addButton(actions, "切换共享", v -> {
            mSettings.put(MangaSettings.SMB_SHARE, "");
            mSmbShares.clear();
            mSmbStatus = "";
            refreshPage(MangaPage.SMB);
        });
        addButton(actions, "设置", v -> {
            mSmbConfigOpen = true;
            refreshPage(MangaPage.SMB);
        });

        // Apply the shared sort mode (name/date, asc/desc). Folders always first.
        final int smbSort = mSettings.integer(MangaSettings.SORT_MODE);
        final java.util.Comparator<MangaSmb.Entry> smbBase =
                SortUtil.comparator(smbSort,
                        (MangaSmb.Entry e) -> e.name, (MangaSmb.Entry e) -> e.modifiedMs);
        final String[] smbVideoExt = mSettings.videoExtensions();
        mSmbEntries.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            if (mSettings.bool(MangaSettings.SORT_VIDEO_FIRST) && !a.directory) {
                boolean va = MangaSettings.hasExtension(a.name, smbVideoExt);
                boolean vb = MangaSettings.hasExtension(b.name, smbVideoExt);
                if (va != vb) return va ? -1 : 1;
            }
            return smbBase.compare(a, b);
        });
        if (mSmbEntries.isEmpty()) {
            addInfo(content, "目录内容", statusOrHint("空目录或尚未连接成功。"));
            return;
        }
        for (MangaSmb.Entry entry : mSmbEntries) {
            Button row = newButton((entry.directory ? "▣ " : "▤ ") + entry.name
                    + (entry.directory ? "" : "  " + (entry.size / 1024) + " KB"));
            row.setTextColor(entry.directory ? 0xFF8EC5FF : 0xFFFFFFFF);
            final String entryPath = entry.path;
            row.setOnClickListener(v -> {
                if (entry.directory) {
                    mSmbPath = entryPath;
                    connectSmb();
                } else {
                    // Open the WHOLE current folder as a manga, positioned at
                    // this image - so up/down page turns work (not single-file).
                    openRemoteManga(smbManga(smbAuth(), mSmbPath), entry.name);
                }
            });
            content.addView(row, wrapParams());
        }
    }

    private Button newButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xFF23232C);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(50));
        return b;
    }

    private LinearLayout.LayoutParams wrapParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private String statusOrHint(String fallback) {
        if (mSmbStatus == null || mSmbStatus.isEmpty()
                || mSmbStatus.startsWith("正在连接")) {
            return fallback;
        }
        return mSmbStatus;
    }

    private void listSmbShares() {
        final MangaSmb.Auth auth = smbAuth();
        if (auth.host.isEmpty()) {
            mSmbStatus = "请先填写主机 IP";
            refreshPage(MangaPage.SMB);
            return;
        }
        mSmbStatus = "正在列出 " + auth.host + " 的共享…";
        refreshPage(MangaPage.SMB);
        mIoExecutor.execute(() -> {
            try {
                final List<String> shares = MangaSmb.listShares(auth);
                mMain.post(() -> {
                    mSmbShares.clear();
                    mSmbShares.addAll(shares);
                    mSmbStatus = "发现 " + shares.size() + " 个共享";
                    Log.i(TAG, "smb shares=" + shares.size());
                    refreshPage(MangaPage.SMB);
                });
            } catch (Exception e) {
                final String message = e.getMessage();
                mMain.post(() -> {
                    mSmbStatus = "列出共享失败：" + message
                            + "（可在设置里手动填共享名）";
                    Log.w(TAG, "smb listShares failed: " + message);
                    refreshPage(MangaPage.SMB);
                });
            }
        });
    }

    private void connectSmb() {
        final MangaSmb.Auth auth = smbAuth();
        final String path = mSmbPath;
        if (auth.host.isEmpty() || auth.share.isEmpty()) {
            // No share chosen: fall back to share enumeration.
            listSmbShares();
            return;
        }
        mSmbStatus = "正在连接 " + auth.host + "/" + auth.share;
        refreshPage(MangaPage.SMB);
        mIoExecutor.execute(() -> {
            try {
                final List<MangaSmb.Entry> result = MangaSmb.list(auth, path);
                mMain.post(() -> {
                    mSmbEntries.clear();
                    mSmbEntries.addAll(result);
                    mSmbStatus = "已列出 " + result.size() + " 项";
                    Log.i(TAG, "smb list ok=" + result.size());
                    refreshPage(MangaPage.SMB);
                });
            } catch (Exception e) {
                final String message = e.getMessage();
                mMain.post(() -> {
                    mSmbStatus = "连接失败：" + message;
                    mSmbEntries.clear();
                    Log.w(TAG, "smb list failed: " + message);
                    refreshPage(MangaPage.SMB);
                });
            }
        });
    }

    /** Mirrors an SMB folder tree to local cache, then opens it with the
     *  normal local reader (chapters = sub-folders, bookmarks + preload work). */
    /** Opens the current SMB folder as an ONLINE manga (on-demand pages). */
    private void readSmbFolderOnline(String remotePath) {
        final MangaSmb.Auth auth = smbAuth();
        if (auth.share.isEmpty()) {
            mSmbStatus = "请先选择一个共享";
            refreshPage(MangaPage.SMB);
            return;
        }
        openRemoteManga(smbManga(auth, remotePath));
    }

    private void readSmbFolderAsManga(String remotePath) {
        final MangaSmb.Auth auth = smbAuth();
        if (auth.share.isEmpty()) {
            mSmbStatus = "请先选择一个共享";
            refreshPage(MangaPage.SMB);
            return;
        }
        final String mangaRoot = remotePath == null ? "" : remotePath;
        String leaf = mangaRoot.isEmpty() ? auth.share
                : mangaRoot.substring(mangaRoot.lastIndexOf('/') + 1);
        final String mangaName = MangaSmb.safePublicName(leaf);
        // Stable local path -> stable bookmark keys.
        final java.io.File localRoot = new java.io.File(
                new java.io.File(mSettings.text(MangaSettings.DOWNLOAD_DIR), "SMB"), mangaName);
        mSmbStatus = "正在下载《" + mangaName + "》到本地…";
        refreshPage(MangaPage.SMB);
        mIoExecutor.execute(() -> {
            try {
                String[] media = concat(mSettings.imageExtensions(), mSettings.videoExtensions());
                final int chapters = MangaSmb.mirrorManga(auth, mangaRoot, localRoot, media,
                        (done, total) -> {
                            if (done % 5 == 0 || done == total) {
                                mMain.post(() -> {
                                    mSmbStatus = "下载 " + done + "/" + total + " 页";
                                    refreshPage(MangaPage.SMB);
                                });
                            }
                        });
                mMain.post(() -> {
                    mSmbStatus = "《" + mangaName + "》已缓存 " + chapters + " 章";
                    if (chapters > 0) {
                        openMangaRoot(localRoot);
                    } else {
                        Toast.makeText(getContext(), "该文件夹内没有可阅读的图片/视频",
                                Toast.LENGTH_SHORT).show();
                        refreshPage(MangaPage.SMB);
                    }
                });
            } catch (Exception e) {
                final String message = e.getMessage();
                mMain.post(() -> {
                    mSmbStatus = "下载失败：" + message;
                    Log.w(TAG, "smb mirror failed: " + message);
                    refreshPage(MangaPage.SMB);
                });
            }
        });
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Downloads one remote file into its own cache folder and opens it in the
     *  reader as a single-page manga - like double-clicking a file on a PC. */
    private interface SingleFileFetcher { void fetch(File dest) throws Exception; }

    private void openSingleRemoteFile(final String displayName, final SingleFileFetcher fetcher) {
        File base = getContext().getExternalCacheDir();
        if (base == null) base = getContext().getCacheDir();
        final File singleDir = new File(new File(base, "remote-single"),
                stableHash(displayName + System.currentTimeMillis()));
        Toast.makeText(getContext(), "正在打开 " + displayName, Toast.LENGTH_SHORT).show();
        mIoExecutor.execute(() -> {
            try {
                if (!singleDir.exists() && !singleDir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录");
                }
                String ext = "jpg";
                int dot = displayName.lastIndexOf('.');
                if (dot > 0 && dot < displayName.length() - 1) {
                    ext = displayName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                }
                File dest = new File(singleDir, "0000." + ext);
                fetcher.fetch(dest);
                if (!dest.exists() || dest.length() == 0) {
                    throw new IllegalStateException("文件下载为空");
                }
                mMain.post(() -> openMangaRoot(singleDir));
            } catch (Exception e) {
                final String msg = e.getMessage();
                mMain.post(() -> Toast.makeText(getContext(),
                        "打开文件失败：" + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Opens a local folder as a manga root without the "single chapter -> parent"
     *  fallback in openLocalSession (our mirrored root must stay the root). */
    private void openMangaRoot(File folder) {
        if (folder == null) return;
        Context context = getContext();
        if (!(context instanceof VRBrowserActivity)) return;
        List<File> chapters = mediaSubDirs(folder);
        if (chapters.isEmpty()) {
            chapters = new ArrayList<>();
            chapters.add(folder);
        }
        int page = 0;
        float frac = 0f;
        int index = 0;
        float[] saved = MangaReaderWidget.readChapterProgress(
                progressPrefs(), "local:" + folder.getAbsolutePath());
        if (saved != null) {
            index = Math.max(0, Math.min((int) saved[0], chapters.size() - 1));
            page = (int) saved[1];
            frac = saved[2];
        }
        MangaChapterSession session = MangaChapterSession.local(
                folder, chapters, index, page, frac, null);
        ((VRBrowserActivity) context).openMangaReaderSession(session);
        Log.i(TAG, "openMangaRoot " + folder.getAbsolutePath()
                + " chapters=" + chapters.size());
    }

    private void downloadSingleSmb(MangaSmb.Entry entry) {
        final MangaSmb.Auth auth = smbAuth();
        openSingleRemoteFile(entry.name, dest ->
                MangaSmb.download(auth, entry.path, dest, null));
    }

    // ----------------------------------------------------------- WebDAV

    // ----------------------------------------------------------- 115 netdisk

    /** 115 account settings (QR login / status). Shown only inside the
     *  "⚙ 设置" fold so the netdisk page itself is a clean file manager. */
    private void fill115LoginSection(LinearLayout content) {
        String cookie = mSettings.text(MangaSettings.P115_COOKIE);
        boolean loggedIn = !cookie.isEmpty();
        if (!mP115Restored) {
            mP115Restored = true;
            String lastCid = mSettings.text(MangaSettings.P115_LAST_CID);
            if (!lastCid.isEmpty()) {
                mP115Cid = lastCid;
                mP115Title = mSettings.text(MangaSettings.P115_LAST_TITLE);
                if (mP115Title.isEmpty()) mP115Title = "115 漫画";
            }
        }
        addInfo(content, "115 网盘（扫码登录）",
                loggedIn
                        ? "已登录。返回目录列表即可浏览；点文件夹进入，点图片进入看图。"
                        : "用 115 手机 App 扫码登录，登录态保存在本机。");
        addInfo(content, "115 状态", mP115Status);

        LinearLayout row = addRow(content);
        if (loggedIn) {
            addButton(row, "校验登录", v -> verify115Cookie());
            addButton(row, "重新扫码", v -> start115Login());
            addButton(row, "退出登录", v -> {
                mSettings.put(MangaSettings.P115_COOKIE, "");
                mP115Status = "已退出登录";
                mP115Qr = null;
                mP115PollHandler.removeCallbacks(mP115Poll);
                clearP115QrBitmap();
                mNetdiskAtRoot = true;
                refreshPage(MangaPage.WEBDAV);
            });
        } else {
            addButton(row, "扫码登录", v -> start115Login());
        }

        // Default folder for 115 (a cid + title). Set to the directory the user
        // is currently standing in, so entering the netdisk jumps straight there.
        if (loggedIn) {
            String defCid = mSettings.text(MangaSettings.P115_DEFAULT_CID);
            addInfo(content, "115 默认目录",
                    defCid.isEmpty() ? "未设置（进入时加载根目录）"
                            : mSettings.text(MangaSettings.P115_DEFAULT_TITLE));
            LinearLayout defRow = addRow(content);
            addButton(defRow, "设为当前目录", v -> {
                mSettings.put(MangaSettings.P115_DEFAULT_CID, mP115Cid);
                mSettings.put(MangaSettings.P115_DEFAULT_TITLE, mP115Title);
                Toast.makeText(getContext(), "已设为 115 默认目录", Toast.LENGTH_SHORT).show();
            });
            addButton(defRow, "清除默认", v -> {
                mSettings.put(MangaSettings.P115_DEFAULT_CID, "");
                mSettings.put(MangaSettings.P115_DEFAULT_TITLE, "");
                refreshPage(MangaPage.WEBDAV);
            });
        }

        if (mP115Qr != null) {
            // Generate once and cache: refreshing the page must not re-create
            // a large bitmap on every poll (that path caused a native crash).
            if (mP115QrBitmap == null && mP115Qr.qrContent != null) {
                mP115QrBitmap = qrBitmap(mP115Qr.qrContent, 720);
            }
            if (mP115QrBitmap != null) {
                android.widget.ImageView qrImage = new android.widget.ImageView(getContext());
                qrImage.setImageBitmap(mP115QrBitmap);
                qrImage.setAdjustViewBounds(true);
                int qrDp = dp(440);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(qrDp, qrDp);
                ip.gravity = Gravity.CENTER_HORIZONTAL;
                ip.setMargins(0, dp(12), 0, dp(12));
                qrImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                content.addView(qrImage, ip);
                addInfo(content, "请用 115 App 扫码", "扫码后在手机上确认，本页面会自动登录。");
            } else {
                addInfo(content, "二维码生成失败", "请用手机打开：\n" + mP115Qr.qrContent);
            }
        }
    }

    /** Pure 115 directory browser: folders first, then media files, with a
     *  compact action row. No config/login UI lives here. */
    private void fill115Browser(LinearLayout content) {
        addInfo(content, "115 · " + (mP115Title == null ? "网盘" : mP115Title),
                "0".equals(mP115Cid) ? "（根目录）" : mP115Cid);
        if (!mP115DirStatus.isEmpty()) {
            addInfo(content, "状态", mP115DirStatus);
        }
        LinearLayout dirActions = addRow(content);
        addButton(dirActions, "⌂ 网盘列表", v -> {
            mNetdiskAtRoot = true;
            refreshPage(MangaPage.WEBDAV);
        });
        if (!"0".equals(mP115Cid)) {
            addButton(dirActions, "← 上级", v -> {
                if (!mP115ParentStack.isEmpty()) {
                    String[] parent = mP115ParentStack.pop();
                    mP115Cid = parent[0];
                    mP115Title = parent[1];
                } else {
                    mP115Cid = "0";
                    mP115Title = "115 网盘";
                }
                mSettings.put(MangaSettings.P115_LAST_CID, mP115Cid);
                mSettings.put(MangaSettings.P115_LAST_TITLE, mP115Title);
                listP115Dir(mP115Cid);
            });
        }
        addButton(dirActions, "刷新", v -> listP115Dir(mP115Cid));
        addButton(dirActions, "↕ " + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)),
                v -> cycleSortAndApply(MangaPage.WEBDAV));
        addButton(dirActions, "▷ 在线阅读", v -> readP115Online(mP115Cid, mP115Title));
        addButton(dirActions, "＋ 书架", v ->
                addRemoteToShelf(SHELF_115 + mP115Cid + "|" + mP115Title));

        final int p115Sort = mSettings.integer(MangaSettings.SORT_MODE);
        final java.util.Comparator<Manga115Api.DirEntry> p115Base =
                SortUtil.comparator(p115Sort,
                        (Manga115Api.DirEntry e) -> e.name,
                        (Manga115Api.DirEntry e) -> e.modifiedMs);
        final String[] videoExt = mSettings.videoExtensions();
        mP115Entries.sort((a, b) -> {
            if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
            if (mSettings.bool(MangaSettings.SORT_VIDEO_FIRST) && !a.isDir) {
                boolean va = MangaSettings.hasExtension(a.name, videoExt);
                boolean vb = MangaSettings.hasExtension(b.name, videoExt);
                if (va != vb) return va ? -1 : 1;
            }
            return p115Base.compare(a, b);
        });
        if (mP115Entries.isEmpty()) {
            addInfo(content, "目录内容",
                    (mP115DirStatus == null || mP115DirStatus.isEmpty())
                            ? "空目录或尚未读取成功。" : mP115DirStatus);
            return;
        }
        for (final Manga115Api.DirEntry e : mP115Entries) {
            Button entryRow = newButton((e.isDir ? "▣ " : "▤ ") + e.name
                    + (e.isDir ? "" : "  " + (e.size / 1024) + " KB"));
            entryRow.setTextColor(e.isDir ? 0xFF8EC5FF : 0xFFFFFFFF);
            entryRow.setOnClickListener(v -> {
                if (e.isDir) {
                    String childId = !e.fileId.isEmpty() ? e.fileId : e.cid;
                    mP115ParentStack.push(new String[]{mP115Cid, mP115Title});
                    mP115Cid = childId;
                    mP115Title = e.name;
                    mSettings.put(MangaSettings.P115_LAST_CID, mP115Cid);
                    mSettings.put(MangaSettings.P115_LAST_TITLE, mP115Title);
                    listP115Dir(mP115Cid);
                } else {
                    String cookie = mSettings.text(MangaSettings.P115_COOKIE);
                    openRemoteManga(p115Manga(cookie, mP115Cid, mP115Title), e.name);
                }
            });
            content.addView(entryRow, wrapParams());
        }
    }

    private void listP115Dir(final String cid) {
        final String cookie = mSettings.text(MangaSettings.P115_COOKIE);
        if (cookie == null || cookie.isEmpty()) return;
        mP115DirStatus = "正在读取 115 目录…";
        refreshPage(MangaPage.WEBDAV);
        mIoExecutor.execute(() -> {
            try {
                final List<Manga115Api.DirEntry> result = Manga115Api.listDir(cookie, cid);
                mMain.post(() -> {
                    mP115Entries.clear();
                    mP115Entries.addAll(result);
                    mP115DirStatus = "已列出 " + result.size() + " 项";
                    refreshPage(MangaPage.WEBDAV);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                mMain.post(() -> {
                    mP115DirStatus = "读取失败：" + msg;
                    mP115Entries.clear();
                    refreshPage(MangaPage.WEBDAV);
                });
            }
        });
    }

    private void readP115Online(String cid, String title) {
        String cookie = mSettings.text(MangaSettings.P115_COOKIE);
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(getContext(), "请先登录 115", Toast.LENGTH_SHORT).show();
            return;
        }
        openRemoteManga(p115Manga(cookie, cid, title));
    }

    private void clearP115QrBitmap() {
        if (mP115QrBitmap != null && !mP115QrBitmap.isRecycled()) {
            mP115QrBitmap.recycle();
        }
        mP115QrBitmap = null;
    }

    private void start115Login() {
        mNetdiskSettingsOpen = true; // QR lives in the settings fold
        mP115Status = "正在获取二维码…";
        clearP115QrBitmap();
        mP115Qr = null;
        refreshPage(MangaPage.WEBDAV);
        mIoExecutor.execute(() -> {
            try {
                final Manga115Api.QrSession session = Manga115Api.startQr();
                mMain.post(() -> {
                    mP115Qr = session;
                    mP115Status = "等待扫码…";
                    refreshPage(MangaPage.WEBDAV);
                    mP115PollHandler.removeCallbacks(mP115Poll);
                    mP115PollHandler.postDelayed(mP115Poll, 1200L);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                mMain.post(() -> {
                    mP115Status = "获取二维码失败：" + msg;
                    refreshPage(MangaPage.WEBDAV);
                });
            }
        });
    }

    private void poll115Qr() {
        final Manga115Api.QrSession session = mP115Qr;
        if (session == null) return;
        mIoExecutor.execute(() -> {
            try {
                final int status = Manga115Api.pollQr(session);
                mMain.post(() -> {
                    if (mP115Qr != session) return;
                    if (status == Manga115Api.STATUS_CONFIRMED) {
                        mP115Status = "已扫码，正在登录…";
                        refreshPage(MangaPage.WEBDAV);
                        exchange115Cookie(session);
                    } else if (status == Manga115Api.STATUS_FAILED) {
                        mP115Status = "二维码已失效，请重新扫码";
                        mP115Qr = null;
                        clearP115QrBitmap();
                        mP115PollHandler.removeCallbacks(mP115Poll);
                        refreshPage(MangaPage.WEBDAV);
                    } else {
                        if (status == Manga115Api.STATUS_SCANNED) {
                            mP115Status = "已扫描，请在手机上确认…";
                        }
                        mP115PollHandler.postDelayed(mP115Poll, 1500L);
                    }
                });
            } catch (Exception e) {
                // Transient poll errors: keep retrying.
                mMain.post(() -> mP115PollHandler.postDelayed(mP115Poll, 2000L));
            }
        });
    }

    private void exchange115Cookie(final Manga115Api.QrSession session) {
        final String device = mSettings.text(MangaSettings.P115_DEVICE);
        mIoExecutor.execute(() -> {
            try {
                final String cookie = Manga115Api.exchangeCookie(session, device);
                mMain.post(() -> {
                    mSettings.put(MangaSettings.P115_COOKIE, cookie);
                    mP115Status = "登录成功，已保存登录态";
                    mP115Qr = null;
                    clearP115QrBitmap();
                    mP115PollHandler.removeCallbacks(mP115Poll);
                    mNetdiskSettingsOpen = false;
                    mNetdiskAtRoot = true;
                    Toast.makeText(getContext(), "115 登录成功", Toast.LENGTH_SHORT).show();
                    refreshPage(MangaPage.WEBDAV);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                mMain.post(() -> {
                    mP115Status = "登录失败：" + msg;
                    mP115Qr = null;
                    clearP115QrBitmap();
                    mP115PollHandler.removeCallbacks(mP115Poll);
                    refreshPage(MangaPage.WEBDAV);
                });
            }
        });
    }

    private void verify115Cookie() {
        final String cookie = mSettings.text(MangaSettings.P115_COOKIE);
        mP115Status = "正在校验…";
        refreshPage(MangaPage.WEBDAV);
        mIoExecutor.execute(() -> {
            final boolean valid = Manga115Api.validate(cookie);
            mMain.post(() -> {
                mP115Status = valid ? "登录态有效" : "登录已过期，请重新扫码";
                if (!valid) mSettings.put(MangaSettings.P115_COOKIE, "");
                refreshPage(MangaPage.WEBDAV);
            });
        });
    }

    /** Renders QR text to a bitmap with zxing. */
    private android.graphics.Bitmap qrBitmap(String text, int size) {
        try {
            if (text == null || text.isEmpty()) {
                return null;
            }
            // Keep the module count modest: 115's qrcode payload can be a long
            // URL; a fixed 320px canvas with L correction stays small and safe.
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(
                    com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            Log.i(TAG, "QR bitmap ok " + w + "x" + h);
            return bmp;
        } catch (Throwable t) {
            // WriterException / OOM / any native issue must never kill the app.
            Log.w(TAG, "QR bitmap failed: " + t);
            return null;
        }
    }

    private void fillWebDavPage(LinearLayout content) {
        // Only the sort + settings toggles stay on the page. Everything else
        // (login forms, WebDAV URL/user/pass, default folders) is in ⚙ 设置.
        LinearLayout top = addRow(content);
        addButton(top, mNetdiskSettingsOpen ? "✓ 收起设置" : "⚙ 设置", v -> {
            mNetdiskSettingsOpen = !mNetdiskSettingsOpen;
            refreshPage(MangaPage.WEBDAV);
        });
        addButton(top, "↕ " + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)),
                v -> cycleSortAndApply(MangaPage.WEBDAV));

        if (mNetdiskSettingsOpen) {
            fill115LoginSection(content);
            fillWebDavSettings(content);
            return;
        }

        // Pure file-manager view: a top-level drive list, then the open drive.
        String cookie115 = mSettings.text(MangaSettings.P115_COOKIE);
        boolean has115 = !cookie115.isEmpty();
        boolean hasWebdav = !mSettings.text(MangaSettings.WEBDAV_URL).trim().isEmpty();

        if (mNetdiskAtRoot) {
            addInfo(content, "网盘", "点文件夹进入；登录与默认目录在 ⚙ 设置 中。");
            if (has115) {
                Button drive = newButton("▣ 115网盘");
                drive.setTextColor(0xFF8EC5FF);
                drive.setOnClickListener(v -> {
                    mNetdiskMode = "115";
                    mNetdiskAtRoot = false;
                    String def = mSettings.text(MangaSettings.P115_DEFAULT_CID);
                    mP115Cid = def.isEmpty() ? "0" : def;
                    mP115Title = mSettings.text(MangaSettings.P115_DEFAULT_TITLE);
                    if (mP115Title.isEmpty()) mP115Title = "115网盘";
                    mP115ParentStack.clear();
                    listP115Dir(mP115Cid);
                });
                content.addView(drive, wrapParams());
            } else {
                Button drive = newButton("▣ 115网盘（未登录 · 点 ⚙ 设置 扫码）");
                drive.setOnClickListener(v -> {
                    mNetdiskSettingsOpen = true;
                    refreshPage(MangaPage.WEBDAV);
                });
                content.addView(drive, wrapParams());
            }
            if (hasWebdav) {
                Button drive = newButton("▣ WebDAV 网盘");
                drive.setTextColor(0xFF8EC5FF);
                drive.setOnClickListener(v -> {
                    mNetdiskMode = "webdav";
                    mNetdiskAtRoot = false;
                    mWebDavUrl = mSettings.text(MangaSettings.WEBDAV_URL);
                    connectWebDav();
                });
                content.addView(drive, wrapParams());
            }
            if (!has115 && !hasWebdav) {
                addInfo(content, "没有已配置的网盘", "在 ⚙ 设置 里扫码登录 115 或填写 WebDAV 地址。");
            }
            return;
        }

        if ("115".equals(mNetdiskMode)) {
            fill115Browser(content);
        } else {
            fillWebDavBrowser(content);
        }
    }

    /** WebDAV connection settings - only rendered inside the ⚙ 设置 fold. */
    private void fillWebDavSettings(LinearLayout content) {
        addInfo(content, "WebDAV 设置",
                "真实 PROPFIND 列目录 + HTTP GET 下载；格式过滤与并发取自漫画设置。");
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
        addButton(actions, "打开本地下载目录", v ->
                openReader(new File(mSettings.text(MangaSettings.DOWNLOAD_DIR))));
    }

    /** Pure WebDAV directory browser (no config UI). */
    private void fillWebDavBrowser(LinearLayout content) {
        addInfo(content, "WebDAV · 当前目录",
                mWebDavUrl.isEmpty() ? "（未配置地址）" : mWebDavUrl);
        addInfo(content, "状态", mWebDavStatus);
        LinearLayout actions = addRow(content);
        addButton(actions, "⌂ 网盘列表", v -> {
            mNetdiskAtRoot = true;
            refreshPage(MangaPage.WEBDAV);
        });
        addButton(actions, "← 上级", v -> {
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
        addButton(actions, "刷新", v -> connectWebDav());
        addButton(actions, "↕ " + SortUtil.label(mSettings.integer(MangaSettings.SORT_MODE)),
                v -> cycleSortAndApply(MangaPage.WEBDAV));
        addButton(actions, "▷ 在线阅读", v -> downloadCurrentWebDav());

        if (mWebDavEntries.isEmpty()) {
            addInfo(content, "目录内容", statusOrHint("空目录或尚未连接成功。"));
            return;
        }
        for (MangaWebDav.Entry entry : mWebDavEntries) {
            Button row = newButton((entry.directory ? "▣ " : "▤ ") + entry.name
                    + (entry.size > 0 ? "  " + (entry.size / 1024) + " KB" : ""));
            row.setOnClickListener(v -> {
                if (entry.directory) {
                    try {
                        mWebDavUrl = MangaWebDav.resolve(mWebDavUrl, entry.href);
                        if (!mWebDavUrl.endsWith("/")) mWebDavUrl += "/";
                        connectWebDav();
                    } catch (Exception e) {
                        mWebDavStatus = "路径解析失败：" + e.getMessage();
                        refreshPage(MangaPage.WEBDAV);
                    }
                } else {
                    downloadSingleWebDav(entry);
                }
            });
            content.addView(row, wrapParams());
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
                "已安装的 Mihon/Tachiyomi 扩展自动信任加载（lib 1.4/1.6）。单源应用直接列出；多源应用点名称展开。源名优先显示扩展内置名称。");
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
        addInfo(content, "已加载图源", sources.size() + " 个源 / " + groups.size() + " 个应用");
        for (Map.Entry<String, List<MangaSourceManager.LoadedSource>> g : groups.entrySet()) {
            final String pkg = g.getKey();
            List<MangaSourceManager.LoadedSource> list = g.getValue();
            MangaSourceManager.LoadedSource first = list.get(0);
            if (list.size() == 1) {
                // Single-source package: one flat row, no collapse layer.
                LinearLayout row = addRow(content);
                if (first.getIcon() != null) {
                    ImageView icon = new ImageView(getContext());
                    icon.setImageDrawable(first.getIcon());
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
                    ip.setMargins(0, dp(4), dp(8), dp(4));
                    row.addView(icon, ip);
                }
                addButton(row, first.getSource().getName() + " [" + first.getSource().getLang() + "]",
                        v -> openSourceBrowse(first));
                if (first.getSource() instanceof ConfigurableSource) {
                    addButton(row, "设置", v -> openSourceSettings(first));
                }
                continue;
            }
            boolean expanded = mExpandedPkgs.contains(pkg);
            LinearLayout header = addRow(content);
            if (first.getIcon() != null) {
                ImageView icon = new ImageView(getContext());
                icon.setImageDrawable(first.getIcon());
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(44));
                ip.setMargins(0, dp(4), dp(8), dp(4));
                header.addView(icon, ip);
            }
            addButton(header, first.getAppLabel() + "（" + list.size() + " 源）"
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
                Log.w(TAG, "browse cb error: " + error);
            } else {
                mSourceMangas.clear();
                mSourceMangas.addAll(page.getMangas());
                mSourceHasNext = page.getHasNextPage();
                mSourceStatus = "第 " + mSourcePage + " 页 · " + mSourceMangas.size() + " 部";
                Log.i(TAG, "browse cb: " + mSourceMangas.size() + " manga, hasNext=" + mSourceHasNext
                        + (mSourceMangas.isEmpty() ? "" : ", first=" + mSourceMangas.get(0).getTitle()));
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
        mTitle.setText("漫画 · " + source.getName());
        // Compact two-row header: the cover grid must keep the bulk of the
        // window height. A tall header starved the weighted grid to 0px on
        // the small window, which looked like "count shown but no manga".
        LinearLayout nav = addRow(content);
        addButton(nav, "← 返回", v -> switchPage(MangaPage.EXTENSIONS));
        addButton(nav, "筛选", v -> openSourceFilters());
        if (source instanceof ConfigurableSource) {
            addButton(nav, "设置", v -> openSourceSettings(mSourceEntry));
        }
        TextView status = new TextView(getContext());
        status.setText(mSourceStatus + (mSourceFiltersActive ? " · 已筛选" : ""));
        status.setTextSize(15f);
        status.setTextColor(0xFFB0B0B8);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(dp(10), 0, 0, 0);
        nav.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout searchRow = addRow(content);
        EditText input = new EditText(getContext());
        input.setHint("搜索关键词");
        input.setText(mSourceQuery);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF808088);
        input.setTextSize(16f);
        input.setSingleLine(true);
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
        if (mSourcePage > 1) {
            addButton(searchRow, "↑页", v -> {
                mSourcePage--;
                startSourceBrowse();
            });
        }
        if (mSourceHasNext) {
            addButton(searchRow, "↓页", v -> {
                mSourcePage++;
                startSourceBrowse();
            });
        }

        if (mSourceAdapter == null || mSourceAdapterSource != source) {
            mSourceAdapterSource = source;
            mSourceAdapter = new SourceCoverAdapter(getContext(), source, this::openSourceManga);
            mSourceGrid.setAdapter(mSourceAdapter);
        }
        mSourceGrid.setLayoutManager(new GridLayoutManager(getContext(), Math.max(2, spanCount())));
        mSourceAdapter.update(mSourceMangas);
        content.addView(mSourceGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final int items = mSourceMangas.size();
        mSourceGrid.post(() -> Log.i(TAG, "browse grid: items=" + items
                + " adapter=" + mSourceAdapter.getItemCount()
                + " w=" + mSourceGrid.getWidth() + " h=" + mSourceGrid.getHeight()
                + " children=" + mSourceGrid.getChildCount()
                + " visible=" + mSourceGrid.isShown()));
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

    private ChipFlowLayout mChipRow;

    /** Lays chips out left-to-right and wraps ONLY when the next chip would
     *  exceed the available width. The old code hard-coded "3 per row", which
     *  wasted the wider bookshelf/detail panel and produced many short rows. */
    private static final class ChipFlowLayout extends LinearLayout {
        private final int mGap;

        ChipFlowLayout(Context context, int gapPx) {
            super(context);
            mGap = gapPx;
            setOrientation(HORIZONTAL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec)
                    - getPaddingLeft() - getPaddingRight();
            int lineX = 0;
            int lineH = 0;
            int totalH = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) child.getLayoutParams();
                int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (lineX > 0 && lineX + cw > width) {
                    totalH += lineH + mGap;
                    lineX = 0;
                    lineH = 0;
                }
                lineX += cw + mGap;
                lineH = Math.max(lineH, ch);
            }
            totalH += lineH;
            setMeasuredDimension(
                    resolveSize(View.MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
                    resolveSize(totalH + getPaddingTop() + getPaddingBottom(),
                            heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l - getPaddingLeft() - getPaddingRight();
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int lineH = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) child.getLayoutParams();
                int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > getPaddingLeft() && x + cw > width) {
                    x = getPaddingLeft();
                    y += lineH + mGap;
                    lineH = 0;
                }
                int left = x + lp.leftMargin;
                int top = y + lp.topMargin;
                child.layout(left, top,
                        left + child.getMeasuredWidth(),
                        top + child.getMeasuredHeight());
                x += cw + mGap;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    private Button addChip(LinearLayout content, String text, View.OnClickListener listener) {
        if (mChipRow == null) {
            mChipRow = new ChipFlowLayout(getContext(), dp(4));
            content.addView(mChipRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        Button button = addButton(mChipRow, text, listener);
        button.setMinWidth(dp(90));
        return button;
    }

    private void endChips() {
        mChipRow = null;
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

    private String srcProgressKey() {
        return "src:" + mSourceEntry.getSource().getId() + ":" + mSourceManga.getUrl();
    }

    private void startSourceReading() {
        List<SChapter> list = sortedChapters();
        if (list.isEmpty()) {
            Toast.makeText(getContext(), "暂无章节", Toast.LENGTH_SHORT).show();
            return;
        }
        openSourceSession(list, 0, 0, 0f);
    }

    private void continueSourceReading() {
        List<SChapter> list = sortedChapters();
        if (list.isEmpty()) {
            Toast.makeText(getContext(), "暂无章节", Toast.LENGTH_SHORT).show();
            return;
        }
        float[] saved = MangaReaderWidget.readChapterProgress(progressPrefs(), srcProgressKey());
        if (saved == null) {
            Toast.makeText(getContext(), "这部漫画还没有阅读记录", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = Math.max(0, Math.min((int) saved[0], list.size() - 1));
        openSourceSession(list, index, (int) saved[1], saved[2]);
    }

    private void readSourceChapter(SChapter chapter, boolean fromStart) {
        if (mSourceEntry == null || mSourceManga == null) {
            return;
        }
        List<SChapter> list = sortedChapters();
        int index = list.indexOf(chapter);
        if (index < 0) {
            index = 0;
        }
        int page = 0;
        float frac = 0f;
        if (!fromStart) {
            float[] saved = MangaReaderWidget.readChapterProgress(progressPrefs(), srcProgressKey());
            if (saved != null && (int) saved[0] == index) {
                page = (int) saved[1];
                frac = saved[2];
            }
        }
        openSourceSession(list, index, page, frac);
    }

    private void openSourceSession(List<SChapter> chapters, int index, int page, float frac) {
        Context context = getContext();
        if (!(context instanceof VRBrowserActivity)) {
            return;
        }
        MangaChapterSession session = MangaChapterSession.source(context,
                mSourceEntry.getSource(), mSourceManga, chapters, index, page, frac);
        ((VRBrowserActivity) context).openMangaReaderSession(session);
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
