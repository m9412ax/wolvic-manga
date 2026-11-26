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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
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
import com.igalia.wolvic.manga.MangaFileCore;
import com.igalia.wolvic.manga.MangaKeyCapture;
import com.igalia.wolvic.manga.MangaReadingState;
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
import com.igalia.wolvic.manga.browser.MangaFileBrowser;
import com.igalia.wolvic.manga.browser.MangaQuickSheet;
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
        HISTORY("历史记录"),
        LOCAL("本地文件夹"),
        SMB("SMB 共享"),
        WEBDAV("网盘 WebDAV"),
        EXTENSIONS("漫画源扩展"),
        SOURCE_BROWSE("图源浏览"),
        SOURCE_MANGA("漫画详情"),
        SOURCE_SETTINGS("图源设置"),
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
    private TextView mPanelSource;
    private FrameLayout mPageContainer;
    private LinearLayout mLibraryPage;
    private TextView mEmptyText;
    private CustomRecyclerView mGrid;
    private MangaCoverAdapter mAdapter;
    private View mDragBar;
    private MangaSettingsPanel mSettingsPanel;

    private final Map<MangaPage, View> mPageViews = new EnumMap<>(MangaPage.class);
    /** Full-height browser host for LOCAL / SMB / 115 (below the header). */
    private final Map<MangaPage, FrameLayout> mPageBrowsers = new EnumMap<>(MangaPage.class);
    /** Browser currently attached, so header actions can drive its selection. */
    private MangaFileBrowser mCurrentBrowser;
    /** 历史 page category: 0 全部, 1 本地, 2 SMB, 3 115, 4 扩展图源. */
    private int mHistoryTab;
    /** Scrollable header above the browser (config forms, login UI, errors). */
    private final Map<MangaPage, ScrollView> mPageHeaders = new EnumMap<>(MangaPage.class);
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

        // The old 28sp title row and its shortcut button are gone.
        mTitle = null;
        mPanelSource = findViewById(R.id.panelSourceLabel);
        mPageContainer = findViewById(R.id.pageContainer);
        mLibraryPage = findViewById(R.id.libraryPage);
        mEmptyText = findViewById(R.id.emptyText);
        mGrid = findViewById(R.id.mangaGrid);
        mDragBar = findViewById(R.id.dragBar);

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
        mAdapter.setCardWidth(gridCardWidthPx(spanCount()));
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
        // Bottom padding is kept minimal: the grid should use every pixel down
        // to the small source label under the panel.
        content.setPadding(pad, pad, pad, dp(6));

        if (page == MangaPage.SOURCE_BROWSE) {
            // Cover grid needs the whole page height; a ScrollView wrapper
            // would fight the RecyclerView for scroll gestures.
            mSourceGrid = new CustomRecyclerView(getContext());
            mSourceGrid.setLayoutManager(new GridLayoutManager(getContext(), spanCount()));
            mPageContents.put(page, content);
            fillPage(page, content);
            return content;
        }

        // Local / SMB / 115 use the full-height unified browser: a compact
        // scrollable header (connection state + actions) on top, the file grid
        // below. A RecyclerView nested in a ScrollView fights it for scrolling,
        // so these pages get their own two-part layout.
        if (page == MangaPage.LOCAL || page == MangaPage.SMB
                || page == MangaPage.WEBDAV || page == MangaPage.HISTORY) {
            LinearLayout root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            final ScrollView header = new ScrollView(getContext());
            header.addView(content, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));
            FrameLayout host = new FrameLayout(getContext());
            root.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(host, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            mPageBrowsers.put(page, host);
            mPageHeaders.put(page, header);
            mPageContents.put(page, content);
            fillPage(page, content);
            syncHeaderVisibility(content);
            return root;
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
        FrameLayout browserHost = mPageBrowsers.get(page);
        if (browserHost != null) {
            browserHost.removeAllViews();
        }
        fillPage(page, content);
        syncHeaderVisibility(content);
        updateHeaderSize(page);
    }

    /**
     * The header above a browser may only take a slice of the page, otherwise
     * the grid gets squeezed. When there is no browser (config forms, 115 QR
     * login, SMB settings) the header owns the whole page instead, so long
     * forms stay usable and scrollable.
     */
    private void updateHeaderSize(MangaPage page) {
        final ScrollView header = mPageHeaders.get(page);
        final FrameLayout host = mPageBrowsers.get(page);
        final LinearLayout content = mPageContents.get(page);
        if (header == null) {
            return;
        }
        header.post(() -> {
            boolean hasBrowser = host != null && host.getChildCount() > 0;
            ViewGroup.LayoutParams lp = header.getLayoutParams();
            int parentH = ((View) header.getParent()).getHeight();
            int target;
            if (!hasBrowser) {
                target = LinearLayout.LayoutParams.MATCH_PARENT;
            } else {
                int max = parentH * 2 / 5;
                int contentH = content == null ? 0 : content.getHeight();
                target = (max > 0 && contentH > max)
                        ? max : LinearLayout.LayoutParams.WRAP_CONTENT;
            }
            if (lp.height != target) {
                lp.height = target;
                header.setLayoutParams(lp);
            }
        });
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
            case HISTORY:
                fillHistoryPage(content);
                break;
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
                || mCurrentPage == MangaPage.SOURCE_SETTINGS
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

    /** Removes any floating quick sheet (page changes must not keep it). */
    private void dismissQuickSheet() {
        if (mPageContainer == null) {
            return;
        }
        for (int i = mPageContainer.getChildCount() - 1; i >= 0; i--) {
            if (mPageContainer.getChildAt(i) instanceof MangaQuickSheet) {
                mPageContainer.removeViewAt(i);
            }
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
        dismissQuickSheet();
        mCurrentPage = page;
        if (page == MangaPage.LIBRARY && mGrid != null && mAdapter != null) {
            // Re-derive columns from the shared thumbnail size: changing the
            // slider must affect the shelf the next time it is shown.
            int span = spanCount();
            mGrid.setLayoutManager(new GridLayoutManager(getContext(), span));
            mAdapter.setCardWidth(gridCardWidthPx(span));
        }
        for (Map.Entry<MangaPage, View> entry : mPageViews.entrySet()) {
            entry.getValue().setVisibility(entry.getKey() == page ? VISIBLE : GONE);
        }
        // The old 28sp title row is gone. The location is now a small centred
        // label in the empty strip at the bottom of the panel.
        if (mPanelSource != null) {
            mPanelSource.setText(sourceLabel(page));
        }
        if (mTitle != null) {
            mTitle.setText(page.label);
        }
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

    /** Card width in px for a grid with [span] columns. */
    private int gridCardWidthPx(int span) {
        int width = getWidth();
        if (width <= 0) {
            width = Math.round(800 * getResources().getDisplayMetrics().density);
        }
        return Math.max(1, width / Math.max(1, span) - dp(16));
    }

    private int spanCount() {
        if (mSettings.integer(MangaSettings.LIBRARY_VIEW_MODE) == 1) {
            return 1;
        }
        // One global control: the same tile width drives the shelf, the file
        // browsers and the extension cover grid.
        int size = Math.max(90, Math.min(280, mSettings.integer(MangaSettings.THUMB_SIZE)));
        int widthPx = getWidth();
        int widthDp = widthPx > 0
                ? (int) (widthPx / getResources().getDisplayMetrics().density)
                : 800;
        return Math.max(2, Math.min(10, widthDp / size));
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
        // A folder that directly contains pages IS a chapter of the manga, not
        // the manga itself. Opening it must stay inside that chapter and only
        // restore its own page - jumping to whichever chapter the whole book
        // was left at is exactly what made "继续阅读" open the wrong chapter.
        final boolean folderIsChapter = hasDirectMedia(folder);
        int index = Math.max(0, chapters.indexOf(folder));
        int page = 0;
        float frac = 0f;
        if (resume) {
            float[] saved = MangaReaderWidget.readChapterProgress(
                    progressPrefs(), "local:" + root.getAbsolutePath());
            if (saved != null && !folderIsChapter) {
                index = Math.max(0, Math.min((int) saved[0], chapters.size() - 1));
                page = (int) saved[1];
                frac = saved[2];
            } else if (saved != null) {
                if ((int) saved[0] == index) {
                    page = (int) saved[1];
                    frac = saved[2];
                } else {
                    page = progressPrefs().getInt(
                            "page:" + chapters.get(index).getAbsolutePath(), 0);
                }
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
        if (!chapters.isEmpty() && index < chapters.size()) {
            noteHistory(HIST_LOCAL, "local:" + root.getAbsolutePath(), root.getName(),
                    root.getAbsolutePath(), chapters.get(index).getAbsolutePath());
        }
        ((VRBrowserActivity) context).openMangaReaderSession(session);
        Log.i(TAG, "openLocalSession root=" + root.getAbsolutePath()
                + " chapters=" + chapters.size() + " index=" + index + " page=" + page);
    }

    /** True when [dir] holds image/video files itself (so it is a chapter). */
    private boolean hasDirectMedia(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return false;
        }
        String[] images = mSettings.imageExtensions();
        String[] videos = mSettings.videoExtensions();
        for (File file : files) {
            if (file.isFile() && (MangaSettings.hasExtension(file.getName(), images)
                    || MangaSettings.hasExtension(file.getName(), videos))) {
                return true;
            }
        }
        return false;
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

    /** Video prefetch buffer in bytes, clamped to the app-heap-safe range.
     *  Old builds allowed up to 256 MB which OOM-crashed on open. */
    private int videoBufferBytes() {
        int mb = mSettings.integer(MangaSettings.VIDEO_BUFFER_MB);
        mb = Math.max(4, Math.min(20, mb));
        return mb * 1024 * 1024;
    }

    /** Builds a remote source with the SAME sort config the file browser uses,
     *  so the reader's page order == the folder listing order. */
    private RemoteManga smbManga(MangaSmb.Auth auth, String path) {
        return new SmbRemoteManga(auth, path,
                concat(mSettings.imageExtensions(), mSettings.videoExtensions()),
                mSettings.videoExtensions(),
                mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST),
                videoBufferBytes());
    }

    private RemoteManga p115Manga(String cookie, String cid, String title) {
        return new Netdisk115RemoteManga(cookie, cid, title,
                concat(mSettings.imageExtensions(), mSettings.videoExtensions()),
                mSettings.videoExtensions(),
                mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST),
                videoBufferBytes());
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
        openRemoteManga(manga, startPageName, false);
    }

    /** @param fromStart true = "开始阅读" (chapter 1, page 1), false = resume. */
    private void openRemoteManga(final RemoteManga manga, final String startPageName,
                                final boolean fromStart) {
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
                if (!fromStart) {
                    float[] saved = MangaReaderWidget.readChapterProgress(
                            progressPrefs(), "remote:" + manga.key());
                    if (saved != null) {
                        index = Math.max(0, Math.min((int) saved[0], chapters.size() - 1));
                        page = (int) saved[1];
                        frac = saved[2];
                    }
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
                    if (fi >= 0 && fi < chapters.size()) {
                        noteHistory(manga.key().startsWith("115:") ? HIST_115 : HIST_SMB,
                                manga.key(), manga.title(), manga.key(),
                                manga.chapterKey(chapters.get(fi)));
                    }
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
            addButton(actions, "返回上级", v -> openFolderView(parent, false));
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
        // The browser owns navigation, sorting and settings, so this page has
        // no header at all: only blocking problems (permission / unreadable
        // directory) are printed above it.
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

        // Unified browser: folders and media in one card grid with breadcrumb,
        // sortable view modes and size/date metadata.
        List<MangaFileBrowser.Entry> entries = new ArrayList<>();
        for (File dir : dirs) {
            MangaFileBrowser.Entry e = new MangaFileBrowser.Entry(
                    dir.getName(), dir.getAbsolutePath(), true);
            int childMedia = 0;
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.isFile() && isMedia(kid.getName())) {
                        childMedia++;
                    }
                }
            }
            e.meta = childMedia > 0 ? ("文件夹 · " + childMedia + " 个媒体") : "文件夹";
            e.modifiedMs = dir.lastModified();
            entries.add(e);
        }
        File[] localFiles = mLocalDir != null ? mLocalDir.listFiles() : null;
        if (localFiles != null) {
            for (File f : localFiles) {
                if (!f.isFile() || !isMedia(f.getName())) {
                    continue;
                }
                MangaFileBrowser.Entry e = new MangaFileBrowser.Entry(
                        f.getName(), f.getAbsolutePath(), false);
                e.size = f.length();
                e.modifiedMs = f.lastModified();
                e.video = MangaSettings.hasExtension(f.getName(), mSettings.videoExtensions());
                e.thumb = e.video ? null : f;
                e.badge = e.video ? "▶" : null;
                entries.add(e);
            }
        }
        applyReadStates(entries, mLocalDir == null ? null : mLocalDir.getAbsolutePath(),
                path -> path);
        final MangaFileBrowser localBrowser = new MangaFileBrowser(getContext());
        localBrowser.setData(entries,
                mLocalDir == null ? "本地" : mLocalDir.getName(),
                localCrumbs(mLocalDir), null);
        localBrowser.setCanGoBack(mLocalDir != null && mRoot != null && !mLocalDir.equals(mRoot));
        localBrowser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onSortChanged(int sortMode) {
                mSettings.put(MangaSettings.SORT_MODE, sortMode);
            }

            @Override
            public void onOpen(MangaFileBrowser.Entry entry) {
                openReaderAt(mLocalDir, new File(entry.path));
            }

            @Override
            public void onNavigate(String path) {
                File target = new File(path);
                if (target.isDirectory()) {
                    mLocalDir = target;
                    mSettings.setLastDir(target.getAbsolutePath());
                    refreshPage(MangaPage.LOCAL);
                }
            }

            @Override
            public void onBack() {
                File parent = mLocalDir != null ? mLocalDir.getParentFile() : null;
                if (parent != null) {
                    mLocalDir = parent;
                    mSettings.setLastDir(parent.getAbsolutePath());
                    refreshPage(MangaPage.LOCAL);
                }
            }
        });
        finishBrowser(localBrowser, MangaPage.LOCAL, () -> switchPage(MangaPage.SETTINGS));
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
            if (smbConfigured()) {
                // Everything that used to sit in a header row on the browse
                // page lives here now; the page itself is pure browser.
                LinearLayout quick = addRow(content);
                addButton(quick, "▷ 在线阅读当前目录", v -> {
                    mSmbConfigOpen = false;
                    refreshPage(MangaPage.SMB);
                    readSmbFolderOnline(mSmbPath);
                });
                addButton(quick, "＋ 加入书架", v -> {
                    String key = SHELF_SMB + "smb://" + mSettings.text(MangaSettings.SMB_HOST)
                            + "/" + mSettings.text(MangaSettings.SMB_SHARE)
                            + (mSmbPath.isEmpty() ? "" : "/" + mSmbPath);
                    addRemoteToShelf(key);
                });
                addButton(quick, "切换共享", v -> {
                    mSettings.put(MangaSettings.SMB_SHARE, "");
                    mSmbShares.clear();
                    mSmbStatus = "";
                    mSmbConfigOpen = false;
                    refreshPage(MangaPage.SMB);
                });
            }

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

        // No header at all when connected: status, path and sorting all live in
        // the browser (summary line + quick sheet). Only a real failure is
        // surfaced, and it goes to the browser's own status strip.
        String smbStatus = null;
        if (mSmbStatus != null && (mSmbStatus.startsWith("连接失败")
                || mSmbStatus.startsWith("列出共享失败"))) {
            smbStatus = mSmbStatus;
        }

        // Unified browser: identical cards / breadcrumb / sorting as local files.
        final String[] smbVideoExt = mSettings.videoExtensions();
        List<MangaFileBrowser.Entry> entries = new ArrayList<>();
        for (MangaSmb.Entry entry : mSmbEntries) {
            MangaFileBrowser.Entry e = new MangaFileBrowser.Entry(
                    entry.name, entry.path, entry.directory);
            e.size = entry.size;
            e.modifiedMs = entry.modifiedMs;
            e.video = !entry.directory && MangaSettings.hasExtension(entry.name, smbVideoExt);
            e.badge = e.video ? "▶" : null;
            entries.add(e);
        }
        List<MangaFileBrowser.Crumb> crumbs = new ArrayList<>();
        crumbs.add(new MangaFileBrowser.Crumb("SMB", ""));
        crumbs.add(new MangaFileBrowser.Crumb(share, ""));
        if (!mSmbPath.isEmpty()) {
            StringBuilder acc = new StringBuilder();
            for (String part : mSmbPath.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (acc.length() > 0) {
                    acc.append('/');
                }
                acc.append(part);
                crumbs.add(new MangaFileBrowser.Crumb(part, acc.toString()));
            }
        }
        final String smbHost = mSettings.text(MangaSettings.SMB_HOST);
        applyReadStates(entries, SmbRemoteManga.keyFor(smbHost, share, mSmbPath),
                path -> SmbRemoteManga.keyFor(smbHost, share, path));
        final MangaFileBrowser smbBrowser = new MangaFileBrowser(getContext());
        smbBrowser.setData(entries,
                smbHost + " / " + share,
                crumbs, smbStatus);
        smbBrowser.setCanGoBack(!mSmbPath.isEmpty());
        smbBrowser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onSortChanged(int sortMode) {
                mSettings.put(MangaSettings.SORT_MODE, sortMode);
            }

            @Override
            public void onOpen(MangaFileBrowser.Entry entry) {
                // Open the WHOLE current folder as a manga, positioned at this
                // image, so up/down page turns keep working.
                openRemoteManga(smbManga(smbAuth(), mSmbPath), entry.name);
            }

            @Override
            public void onNavigate(String path) {
                mSmbPath = path;
                connectSmb();
            }

            @Override
            public void onBack() {
                int slash = mSmbPath.lastIndexOf('/');
                mSmbPath = slash > 0 ? mSmbPath.substring(0, slash) : "";
                connectSmb();
            }
        });
        finishBrowser(smbBrowser, MangaPage.SMB, () -> {
            mSmbConfigOpen = true;
            refreshPage(MangaPage.SMB);
        });
    }

    // ------------------------------------------------------------ history

    private static final String HIST_ALL = "";
    private static final String HIST_LOCAL = "LOCAL";
    private static final String HIST_SMB = "SMB";
    private static final String HIST_115 = "115";
    private static final String HIST_EXT = "EXT";

    private String historyTabKey() {
        switch (mHistoryTab) {
            case 1: return HIST_LOCAL;
            case 2: return HIST_SMB;
            case 3: return HIST_115;
            case 4: return HIST_EXT;
            default: return HIST_ALL;
        }
    }

    /**
     * 历史记录: every manga that was opened, grouped by source. The 扩展图源
     * category lives in its own tab that only appears when selected, exactly
     * like the other source sub-pages. Rows are plain list rows so the reading
     * progress bar has room; layout and header buttons match the file browser.
     */
    private void fillHistoryPage(LinearLayout content) {
        LinearLayout tabs = addRow(content);
        String[] labels = {"全部", "本地文件", "SMB", "115", "扩展图源"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            addButton(tabs, (mHistoryTab == i ? "✓ " : "") + labels[i], v -> {
                mHistoryTab = index;
                refreshPage(MangaPage.HISTORY);
            });
        }

        final MangaReadingState state = MangaReadingState.get(getContext());
        final String want = historyTabKey();
        List<MangaFileBrowser.Entry> entries = new ArrayList<>();
        for (MangaReadingState.Record record : state.records()) {
            if (!want.isEmpty() && !want.equals(record.source)) {
                continue;
            }
            MangaFileBrowser.Entry entry = new MangaFileBrowser.Entry(
                    record.title, record.id, false);
            entry.menuable = true;
            entry.percent = record.percent();
            entry.glyph = historyGlyph(record.source);
            entry.meta = historyMeta(record);
            entry.tag = record;
            entries.add(entry);
        }
        final MangaFileBrowser browser = new MangaFileBrowser(getContext());
        browser.setListMode(true);
        browser.setData(entries, "历史记录", null,
                entries.isEmpty() ? "还没有打开过漫画。" : null);
        browser.setCanGoBack(false);
        browser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onOpen(MangaFileBrowser.Entry entry) {
                if (entry.tag instanceof MangaReadingState.Record) {
                    openHistoryRecord((MangaReadingState.Record) entry.tag);
                }
            }

            @Override
            public void onNavigate(String path) {
                // 历史 rows never navigate; folders are opened from the menu.
            }

            @Override
            public void onMenu(MangaFileBrowser.Entry entry) {
                if (entry.tag instanceof MangaReadingState.Record) {
                    showHistoryMenu((MangaReadingState.Record) entry.tag, browser);
                }
            }
        });
        browser.setOnDeleteRequested(list -> {
            List<String> ids = new ArrayList<>();
            for (MangaFileBrowser.Entry entry : list) {
                ids.add(entry.path);
            }
            state.removeRecords(ids);
            browser.onEntriesDeleted();
            Toast.makeText(getContext(), "已移除 " + ids.size() + " 条记录",
                    Toast.LENGTH_SHORT).show();
            refreshPage(MangaPage.HISTORY);
        });
        List<MangaFileBrowser.Action> actions = new ArrayList<>();
        actions.add(new MangaFileBrowser.Action("🗑 清除全部", () -> confirmClearHistory(state)));
        actions.add(new MangaFileBrowser.Action("☑ 多选",
                () -> browser.setSelectionMode(true)));
        browser.setHeaderActions(actions);
        browser.setSheetHost(mPageContainer);
        attachBrowser(MangaPage.HISTORY, browser);
    }

    private String historyGlyph(String source) {
        if (HIST_SMB.equals(source)) {
            return "▤";
        }
        if (HIST_115.equals(source)) {
            return "☁";
        }
        if (HIST_EXT.equals(source)) {
            return "◎";
        }
        return "▣";
    }

    private String historySourceName(String source) {
        if (HIST_LOCAL.equals(source)) {
            return "本地文件";
        }
        if (HIST_SMB.equals(source)) {
            return "SMB";
        }
        if (HIST_115.equals(source)) {
            return "115 网盘";
        }
        if (HIST_EXT.equals(source)) {
            return "扩展图源";
        }
        return "漫画";
    }

    private String historyMeta(MangaReadingState.Record record) {
        StringBuilder sb = new StringBuilder(historySourceName(record.source));
        if (record.total > 0) {
            sb.append(" · ").append(record.percent()).append('%')
                    .append(" · ").append(Math.min(record.page + 1, record.total))
                    .append('/').append(record.total).append(" 页");
        } else if (record.page > 0) {
            sb.append(" · 第 ").append(record.page + 1).append(" 页");
        }
        if (record.ts > 0) {
            sb.append(" · ").append(MangaFileBrowser.formatDate(record.ts));
        }
        return sb.toString();
    }

    /** Records (or refreshes) the 历史 row of a manga about to be opened. */
    private void noteHistory(String source, String id, String title, String path,
                             String chapterKey) {
        if (id == null || id.isEmpty() || chapterKey == null) {
            return;
        }
        MangaReadingState state = MangaReadingState.get(getContext());
        MangaReadingState.Record record = new MangaReadingState.Record();
        record.id = id;
        record.title = title == null || title.isEmpty() ? id : title;
        record.source = source;
        record.path = path == null ? "" : path;
        record.chapterKey = chapterKey;
        MangaReadingState.Chapter chapter = state.chapter(chapterKey);
        if (chapter != null) {
            record.page = chapter.page;
            record.total = chapter.total;
        }
        state.noteRecord(record);
    }

    private void openHistoryRecord(MangaReadingState.Record record) {
        if (HIST_LOCAL.equals(record.source)) {
            File dir = new File(record.path);
            if (!dir.isDirectory()) {
                Toast.makeText(getContext(), "文件夹不存在：" + record.path,
                        Toast.LENGTH_LONG).show();
                return;
            }
            openLocalSession(dir, null, true);
            return;
        }
        if (HIST_SMB.equals(record.source)) {
            String prefix = "smb://" + mSettings.text(MangaSettings.SMB_HOST) + "/"
                    + mSettings.text(MangaSettings.SMB_SHARE) + "/";
            if (!record.path.startsWith(prefix)) {
                Toast.makeText(getContext(), "SMB 主机或共享已改变，请重新连接。",
                        Toast.LENGTH_LONG).show();
                return;
            }
            openRemoteManga(smbManga(smbAuth(), record.path.substring(prefix.length())),
                    null, false);
            return;
        }
        if (HIST_115.equals(record.source)) {
            String cid = record.path.startsWith("115:")
                    ? record.path.substring(4) : record.path;
            String cookie = mSettings.text(MangaSettings.P115_COOKIE);
            openRemoteManga(p115Manga(cookie, cid, record.title), null, false);
            return;
        }
        Toast.makeText(getContext(), "扩展图源的漫画请从「漫画源扩展」进入。",
                Toast.LENGTH_LONG).show();
    }

    private void openHistoryFolder(MangaReadingState.Record record) {
        if (HIST_LOCAL.equals(record.source)) {
            File dir = new File(record.path);
            if (dir.isDirectory()) {
                mLocalDir = dir;
                mSettings.setLastDir(dir.getAbsolutePath());
                switchPage(MangaPage.LOCAL);
            }
            return;
        }
        if (HIST_SMB.equals(record.source)) {
            String prefix = "smb://" + mSettings.text(MangaSettings.SMB_HOST) + "/"
                    + mSettings.text(MangaSettings.SMB_SHARE) + "/";
            if (record.path.startsWith(prefix)) {
                mSmbPath = record.path.substring(prefix.length());
                mSmbAutoConnected = true;
                switchPage(MangaPage.SMB);
                connectSmb();
            }
            return;
        }
        if (HIST_115.equals(record.source)) {
            String cid = record.path.startsWith("115:")
                    ? record.path.substring(4) : record.path;
            mNetdiskMode = "115";
            mNetdiskAtRoot = false;
            mP115Cid = cid;
            mP115Title = record.title;
            mP115ParentStack.clear();
            switchPage(MangaPage.WEBDAV);
            listP115Dir(cid);
        }
    }

    private void showHistoryMenu(final MangaReadingState.Record record,
                                 final MangaFileBrowser browser) {
        if (mPageContainer == null) {
            return;
        }
        MangaQuickSheet sheet = MangaQuickSheet.open(mPageContainer, record.title, s -> {
            s.section("进度");
            s.caption(historyMeta(record));
            s.divider();
            s.toggleChipsRaw(new String[]{"▶  继续阅读"}, new boolean[]{false}, i -> {
                s.dismiss();
                mMain.post(() -> openHistoryRecord(record));
            });
            s.toggleChipsRaw(new String[]{"📂  打开所在文件夹"}, new boolean[]{false}, i -> {
                s.dismiss();
                mMain.post(() -> openHistoryFolder(record));
            });
            s.toggleChipsRaw(new String[]{"🗑  删除记录（不删文件）"}, new boolean[]{false}, i -> {
                s.dismiss();
                MangaReadingState.get(getContext()).removeRecord(record.id);
                mMain.post(() -> refreshPage(MangaPage.HISTORY));
            });
        }, new MangaQuickSheet.DismissListener() {
            @Override
            public void onConfirm() {
            }
        });
        sheet.withFooter(false);
    }

    private void confirmClearHistory(final MangaReadingState state) {
        if (mPageContainer == null) {
            return;
        }
        MangaQuickSheet sheet = MangaQuickSheet.open(mPageContainer, "清除全部记录", s -> {
            s.section("历史记录");
            s.caption("共 " + state.records().size() + " 条");
            s.divider();
            s.hint("只清空这个列表，不会删除任何文件，也不会清除阅读进度。");
        }, new MangaQuickSheet.DismissListener() {
            @Override
            public void onConfirm() {
                state.clearRecords();
                refreshPage(MangaPage.HISTORY);
            }
        });
        sheet.withFooter(false);
    }

    // ------------------------------------------------ read state & deletion

    /** Maps a browser path to the canonical read-state key. */
    private interface PathKey {
        String key(String path);
    }

    /**
     * Frames every card: folders take the aggregate of the chapters inside
     * them (blue = still reading, green = everything finished), files are green
     * once the reader has passed them. The file order is the reader's own
     * (videos first + the shared sort), so the mark matches what "read" means.
     */
    private void applyReadStates(List<MangaFileBrowser.Entry> entries,
                                 @Nullable String chapterKey, PathKey keyer) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        MangaReadingState state = MangaReadingState.get(getContext());
        for (MangaFileBrowser.Entry entry : entries) {
            if (entry.directory) {
                String key = keyer == null ? entry.path : keyer.key(entry.path);
                entry.state = state.stateOfFolder(key);
            }
        }
        if (chapterKey == null) {
            return;
        }
        MangaReadingState.Chapter chapter = state.chapter(chapterKey);
        if (chapter == null) {
            return;
        }
        List<MangaFileCore.Entry> media = new ArrayList<>();
        for (MangaFileBrowser.Entry entry : entries) {
            if (!entry.directory) {
                media.add(new MangaFileCore.Entry(entry.name, entry.path, false,
                        entry.size, entry.modifiedMs));
            }
        }
        media = MangaFileCore.sortForListing(media,
                mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST),
                mSettings.videoExtensions());
        java.util.Set<String> read = new java.util.HashSet<>();
        for (int i = 0; i < media.size() && i <= chapter.page; i++) {
            read.add(media.get(i).id);
        }
        for (MangaFileBrowser.Entry entry : entries) {
            if (!entry.directory && entry.state == 0 && read.contains(entry.path)) {
                entry.state = MangaReadingState.DONE;
            }
        }
    }

    /**
     * The reader closed after the user changed chapters: park the browser on
     * the chapter that was actually on screen instead of the entry folder.
     */
    public void onReaderClosed(@Nullable String chapterKey) {
        if (chapterKey == null || chapterKey.isEmpty()) {
            return;
        }
        if (chapterKey.startsWith("smb://")) {
            String prefix = "smb://" + mSettings.text(MangaSettings.SMB_HOST) + "/"
                    + mSettings.text(MangaSettings.SMB_SHARE) + "/";
            if (!chapterKey.startsWith(prefix)) {
                return;
            }
            mSmbPath = chapterKey.substring(prefix.length());
            mSmbAutoConnected = true;
            switchPage(MangaPage.SMB);
            connectSmb();
            return;
        }
        if (chapterKey.startsWith("115:")) {
            String cid = chapterKey.substring(4);
            if (cid.isEmpty() || "0".equals(cid)) {
                return;
            }
            mNetdiskMode = "115";
            mNetdiskAtRoot = false;
            mP115Cid = cid;
            mP115Title = cid;
            switchPage(MangaPage.WEBDAV);
            listP115Dir(cid);
            return;
        }
        File dir = new File(chapterKey);
        if (!dir.isDirectory()) {
            dir = dir.getParentFile();
        }
        if (dir != null && dir.isDirectory()) {
            mLocalDir = dir;
            mSettings.setLastDir(dir.getAbsolutePath());
            switchPage(MangaPage.LOCAL);
        }
    }

    /** Top-right 选择: switches the visible browser into multi-select. */
    private void startSelection() {
        if (mCurrentBrowser != null) {
            mCurrentBrowser.setSelectionMode(true);
        }
    }

    /** Wires the browser's multi-select delete to this page's source. */
    private void attachDelete(final MangaFileBrowser browser, final MangaPage page) {
        browser.setOnDeleteRequested(entries -> confirmDelete(browser, page, entries));
    }

    private void confirmDelete(final MangaFileBrowser browser, final MangaPage page,
                               final List<MangaFileBrowser.Entry> entries) {
        if (entries == null || entries.isEmpty() || mPageContainer == null) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < entries.size() && i < 4; i++) {
            if (i > 0) {
                names.append("、");
            }
            names.append(entries.get(i).name);
        }
        if (entries.size() > 4) {
            names.append(" 等 ").append(entries.size()).append(" 项");
        }
        final boolean remote = page == MangaPage.SMB || page == MangaPage.WEBDAV;
        MangaQuickSheet sheet = MangaQuickSheet.open(mPageContainer, "删除", s -> {
            s.section("将删除");
            s.caption(names.toString());
            s.divider();
            s.hint(remote ? "会同时从 SMB / 网盘上删除，且无法恢复。"
                    : "会从本地存储删除，且无法恢复。");
        }, new MangaQuickSheet.DismissListener() {
            @Override
            public void onConfirm() {
                performDelete(browser, page, entries);
            }
        });
        sheet.withFooter(false);
    }

    private void performDelete(final MangaFileBrowser browser, final MangaPage page,
                               final List<MangaFileBrowser.Entry> entries) {
        final Context context = getContext();
        mIoExecutor.execute(() -> {
            int done = 0;
            String error = null;
            try {
                if (page == MangaPage.LOCAL) {
                    for (MangaFileBrowser.Entry entry : entries) {
                        if (deleteRecursiveLocal(new File(entry.path))) {
                            done++;
                        }
                        MangaReadingState.get(context).forgetPrefix(entry.path);
                    }
                } else if (page == MangaPage.SMB) {
                    List<String> paths = new ArrayList<>();
                    for (MangaFileBrowser.Entry entry : entries) {
                        paths.add(entry.path);
                    }
                    MangaSmb.delete(smbAuth(), paths);
                    done = entries.size();
                    for (MangaFileBrowser.Entry entry : entries) {
                        MangaReadingState.get(context).forgetPrefix(
                                SmbRemoteManga.keyFor(mSettings.text(MangaSettings.SMB_HOST),
                                        mSettings.text(MangaSettings.SMB_SHARE), entry.path));
                    }
                } else if (page == MangaPage.WEBDAV && "115".equals(mNetdiskMode)) {
                    String cookie = mSettings.text(MangaSettings.P115_COOKIE);
                    List<String> ids = new ArrayList<>();
                    for (MangaFileBrowser.Entry entry : entries) {
                        if (entry.tag instanceof Manga115Api.DirEntry) {
                            Manga115Api.DirEntry dir = (Manga115Api.DirEntry) entry.tag;
                            ids.add(!dir.fileId.isEmpty() ? dir.fileId : dir.cid);
                        } else {
                            ids.add(entry.path);
                        }
                    }
                    Manga115Api.deleteFiles(cookie, mP115Cid, ids);
                    done = entries.size();
                    for (String id : ids) {
                        MangaReadingState.get(context).forgetPrefix("115:" + id);
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "delete failed: " + error);
            }
            final int deleted = done;
            final String failure = error;
            mMain.post(() -> {
                if (failure != null) {
                    Toast.makeText(getContext(), "删除失败：" + failure, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), "已删除 " + deleted + " 项", Toast.LENGTH_SHORT).show();
                }
                if (browser != null) {
                    browser.onEntriesDeleted();
                }
                refreshPage(page);
            });
        });
    }

    private static boolean deleteRecursiveLocal(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursiveLocal(child);
                }
            }
        }
        return file.delete();
    }

    private Button newButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        applyBrowserButtonStyle(b);
        b.setTextSize(16f);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(50));
        return b;
    }

    /** Short location name shown in the bottom strip. */
    private String sourceLabel(MangaPage page) {
        switch (page) {
            case LIBRARY: return "书架";
            case HISTORY: return "历史记录";
            case LOCAL: return "本地文件";
            case SMB: return "SMB 共享";
            case WEBDAV: return "网盘";
            case EXTENSIONS: return "漫画源";
            case FOLDER: return "文件夹";
            case SETTINGS: return "设置";
            default: return page.label;
        }
    }

    /** An empty header must not reserve blank space above the browser. */
    private void syncHeaderVisibility(LinearLayout content) {
        android.view.ViewParent parent = content.getParent();
        if (!(parent instanceof ScrollView)) {
            return;
        }
        ScrollView header = (ScrollView) parent;
        boolean hasContent = false;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i).getVisibility() == View.VISIBLE) {
                hasContent = true;
                break;
            }
        }
        header.setVisibility(hasContent ? View.VISIBLE : View.GONE);
    }

    /** Applies the shared sort settings, wires the gear button, then attaches. */
    private void finishBrowser(MangaFileBrowser browser, MangaPage page, Runnable settings) {
        browser.setSortConfig(mSettings.integer(MangaSettings.SORT_MODE),
                mSettings.bool(MangaSettings.SORT_VIDEO_FIRST),
                mSettings.bool(MangaSettings.SORT_FOLDER_FIRST));
        browser.setThumbRatio(mSettings.integer(MangaSettings.THUMB_RATIO));
        browser.setThumbSize(mSettings.integer(MangaSettings.THUMB_SIZE));
        browser.setOnSettingsClick(settings);
        // The quick sheet floats over the whole panel, not over the grid only.
        browser.setSheetHost(mPageContainer);
        if (page == MangaPage.LOCAL || page == MangaPage.SMB
                || (page == MangaPage.WEBDAV && "115".equals(mNetdiskMode))) {
            attachDelete(browser, page);
        }
        browser.setHeaderActions(browserHeaderActions(page));
        attachBrowser(page, browser);
    }

    /**
     * 默认位置 / 加入书架 / 开始阅读 / 继续阅读 for the folder currently shown.
     * They sit in a framed group at the top right of the browser header, next to
     * the ⚙ button, and re-evaluate on every folder change because the browser
     * is rebuilt by refreshPage().
     */
    private List<MangaFileBrowser.Action> browserHeaderActions(MangaPage page) {
        List<MangaFileBrowser.Action> out = new ArrayList<>();
        if (page == MangaPage.LOCAL) {
            final File cur = mLocalDir;
            if (cur == null || !cur.isDirectory()) {
                return out;
            }
            final String path = cur.getAbsolutePath();
            out.add(new MangaFileBrowser.Action("★ 默认位置", () -> toggleLocalDefault(cur)));
            out.add(new MangaFileBrowser.Action(
                    mSettings.isInBookshelf(path) ? "★ 已在书架" : "＋ 加入书架",
                    () -> toggleLocalShelf(cur)));
            out.add(new MangaFileBrowser.Action("▶ 开始阅读",
                    () -> openLocalSession(cur, null, false)));
            out.add(new MangaFileBrowser.Action("▶▶ 继续阅读", () -> continueReading(cur)));
            out.add(new MangaFileBrowser.Action("☑ 选择", this::startSelection));
            return out;
        }
        if (page == MangaPage.SMB) {
            if (!smbConfigured() || mSmbConfigOpen) {
                return out;
            }
            out.add(new MangaFileBrowser.Action("★ 默认位置", this::toggleSmbDefault));
            out.add(new MangaFileBrowser.Action("＋ 加入书架", () -> addRemoteToShelf(smbShelfKey())));
            out.add(new MangaFileBrowser.Action("▶ 开始阅读",
                    () -> openRemoteManga(smbManga(smbAuth(), mSmbPath), null, true)));
            out.add(new MangaFileBrowser.Action("▶▶ 继续阅读",
                    () -> openRemoteManga(smbManga(smbAuth(), mSmbPath), null, false)));
            out.add(new MangaFileBrowser.Action("☑ 选择", this::startSelection));
            return out;
        }
        if (page == MangaPage.WEBDAV) {
            if (mNetdiskAtRoot) {
                return out;
            }
            if ("115".equals(mNetdiskMode)) {
                out.add(new MangaFileBrowser.Action("★ 默认位置", this::setP115DefaultDir));
                out.add(new MangaFileBrowser.Action("＋ 加入书架",
                        () -> addRemoteToShelf(SHELF_115 + mP115Cid + "|" + mP115Title)));
                out.add(new MangaFileBrowser.Action("▶ 开始阅读", () -> {
                    String cookie = mSettings.text(MangaSettings.P115_COOKIE);
                    openRemoteManga(p115Manga(cookie, mP115Cid, mP115Title), null, true);
                }));
                out.add(new MangaFileBrowser.Action("▶▶ 继续阅读", () -> {
                    String cookie = mSettings.text(MangaSettings.P115_COOKIE);
                    openRemoteManga(p115Manga(cookie, mP115Cid, mP115Title), null, false);
                }));
                out.add(new MangaFileBrowser.Action("☑ 选择", this::startSelection));
            } else {
                out.add(new MangaFileBrowser.Action("★ 默认位置", () -> {
                    mSettings.setDefaultDir("WEBDAV", mWebDavUrl);
                    Toast.makeText(getContext(), "已设为 WebDAV 默认位置",
                            Toast.LENGTH_SHORT).show();
                }));
                out.add(new MangaFileBrowser.Action("▶ 开始阅读",
                        this::downloadCurrentWebDav));
            }
        }
        return out;
    }

    private String smbShelfKey() {
        return SHELF_SMB + "smb://" + mSettings.text(MangaSettings.SMB_HOST)
                + "/" + mSettings.text(MangaSettings.SMB_SHARE)
                + (mSmbPath.isEmpty() ? "" : "/" + mSmbPath);
    }

    /** Sets the current local folder as default, or jumps to the saved one. */
    private void toggleLocalDefault(File current) {
        if (current == null) {
            return;
        }
        String path = current.getAbsolutePath();
        String def = mSettings.defaultDir("LOCAL");
        if (def.isEmpty() || def.equals(path)) {
            mSettings.setDefaultDir("LOCAL", path);
            Toast.makeText(getContext(), "已设为默认位置", Toast.LENGTH_SHORT).show();
            refreshPage(MangaPage.LOCAL);
            return;
        }
        File dir = new File(def);
        if (!dir.isDirectory()) {
            Toast.makeText(getContext(), "默认位置已不存在，已重新设为当前目录",
                    Toast.LENGTH_SHORT).show();
            mSettings.setDefaultDir("LOCAL", path);
            refreshPage(MangaPage.LOCAL);
            return;
        }
        mLocalDir = dir;
        mSettings.setLastDir(def);
        refreshPage(MangaPage.LOCAL);
    }

    private void toggleLocalShelf(File folder) {
        if (folder == null) {
            return;
        }
        String path = folder.getAbsolutePath();
        if (mSettings.isInBookshelf(path)) {
            mSettings.removeFromBookshelf(path);
            Toast.makeText(getContext(), "已从书架移除", Toast.LENGTH_SHORT).show();
        } else {
            mSettings.addToBookshelf(path);
            Toast.makeText(getContext(), "已加入书架", Toast.LENGTH_SHORT).show();
        }
        refreshPage(MangaPage.LOCAL);
    }

    /** SMB: remember the current remote path, or jump to the saved one. */
    private void toggleSmbDefault() {
        String def = mSettings.text(MangaSettings.SMB_DEFAULT_PATH);
        if (def == null || def.isEmpty() || def.equals(mSmbPath)) {
            mSettings.put(MangaSettings.SMB_DEFAULT_PATH, mSmbPath);
            Toast.makeText(getContext(), "已设为默认位置", Toast.LENGTH_SHORT).show();
            return;
        }
        mSmbPath = def;
        connectSmb();
    }

    private void setP115DefaultDir() {
        mSettings.put(MangaSettings.P115_DEFAULT_CID, mP115Cid);
        mSettings.put(MangaSettings.P115_DEFAULT_TITLE, mP115Title);
        Toast.makeText(getContext(), "已设为 115 默认目录", Toast.LENGTH_SHORT).show();
    }

    /** Attaches a finished browser to the reserved full-height slot. */
    private void attachBrowser(MangaPage page, View browser) {
        FrameLayout host = mPageBrowsers.get(page);
        if (host == null) {
            return;
        }
        host.removeAllViews();
        host.addView(browser, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (browser instanceof MangaFileBrowser) {
            mCurrentBrowser = (MangaFileBrowser) browser;
        }
        updateHeaderSize(page);
    }

    /** Breadcrumb trail from the manga root down to the current directory. */
    private List<MangaFileBrowser.Crumb> localCrumbs(File dir) {
        List<MangaFileBrowser.Crumb> crumbs = new ArrayList<>();
        if (dir == null) {
            return crumbs;
        }
        List<File> chain = new ArrayList<>();
        File cur = dir;
        int guard = 0;
        while (cur != null && guard++ < 40) {
            chain.add(0, cur);
            if (mRoot != null && cur.equals(mRoot)) {
                break;
            }
            cur = cur.getParentFile();
        }
        for (File f : chain) {
            crumbs.add(new MangaFileBrowser.Crumb(f.getName(), f.getAbsolutePath()));
        }
        return crumbs;
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
        openSingleRemoteFile(displayName, displayName, fetcher);
    }

    private void openSingleRemoteFile(final String displayName, final String cacheKey,
                                      final SingleFileFetcher fetcher) {
        File base = getContext().getExternalCacheDir();
        if (base == null) base = getContext().getCacheDir();
        // Hash the stable path (not a timestamp): reopening the same file reuses
        // its cached copy instead of piling up a fresh folder every time.
        final File singleDir = new File(new File(base, "remote-single"),
                stableHash(cacheKey));
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
        openSingleRemoteFile(entry.name, "smb:" + entry.path, dest ->
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
                Manga115Api.clearDirectUrlCache();
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
        final int p115Sort = mSettings.integer(MangaSettings.SORT_MODE);
        final boolean p115FolderFirst = mSettings.bool(MangaSettings.SORT_FOLDER_FIRST);
        final java.util.Comparator<Manga115Api.DirEntry> p115Base =
                SortUtil.comparator(p115Sort,
                        (Manga115Api.DirEntry e) -> e.name,
                        (Manga115Api.DirEntry e) -> e.modifiedMs,
                        (Manga115Api.DirEntry e) -> Math.max(0, e.size));
        final String[] videoExt = mSettings.videoExtensions();
        mP115Entries.sort((a, b) -> {
            if (p115FolderFirst && a.isDir != b.isDir) return a.isDir ? -1 : 1;
            if (mSettings.bool(MangaSettings.SORT_VIDEO_FIRST) && !a.isDir && !b.isDir) {
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
        // Unified browser, same cards / breadcrumb / sorting as local and SMB.
        List<MangaFileBrowser.Entry> entries = new ArrayList<>();
        for (Manga115Api.DirEntry e : mP115Entries) {
            String childId = !e.fileId.isEmpty() ? e.fileId : e.cid;
            MangaFileBrowser.Entry item = new MangaFileBrowser.Entry(
                    e.name, e.isDir ? childId : e.name, e.isDir);
            item.size = e.size;
            item.modifiedMs = e.modifiedMs;
            item.video = !e.isDir && MangaSettings.hasExtension(e.name, videoExt);
            item.badge = item.video ? "▶" : null;
            item.tag = e;
            entries.add(item);
        }
        List<MangaFileBrowser.Crumb> crumbs = new ArrayList<>();
        crumbs.add(new MangaFileBrowser.Crumb("115 网盘", "0"));
        if (!"0".equals(mP115Cid)) {
            crumbs.add(new MangaFileBrowser.Crumb(
                    mP115Title == null ? mP115Cid : mP115Title, mP115Cid));
        }
        applyReadStates(entries, "115:" + mP115Cid, path -> "115:" + path);
        final MangaFileBrowser p115Browser = new MangaFileBrowser(getContext());
        String p115Status = (mP115DirStatus != null && mP115DirStatus.startsWith("读取"))
                ? mP115DirStatus : null;
        p115Browser.setData(entries,
                mP115Title == null ? "115 网盘" : mP115Title, crumbs, p115Status);
        p115Browser.setCanGoBack(!"0".equals(mP115Cid));
        p115Browser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onSortChanged(int sortMode) {
                mSettings.put(MangaSettings.SORT_MODE, sortMode);
            }

            @Override
            public void onOpen(MangaFileBrowser.Entry entry) {
                String cookie = mSettings.text(MangaSettings.P115_COOKIE);
                openRemoteManga(p115Manga(cookie, mP115Cid, mP115Title), entry.name);
            }

            @Override
            public void onNavigate(String path) {
                if ("0".equals(path)) {
                    mNetdiskAtRoot = true;
                    refreshPage(MangaPage.WEBDAV);
                    return;
                }
                String title = path;
                for (Manga115Api.DirEntry e : mP115Entries) {
                    String childId = !e.fileId.isEmpty() ? e.fileId : e.cid;
                    if (path.equals(childId)) {
                        title = e.name;
                        break;
                    }
                }
                mP115ParentStack.push(new String[]{mP115Cid, mP115Title});
                mP115Cid = path;
                mP115Title = title;
                mSettings.put(MangaSettings.P115_LAST_CID, mP115Cid);
                mSettings.put(MangaSettings.P115_LAST_TITLE, mP115Title);
                listP115Dir(mP115Cid);
            }

            @Override
            public void onBack() {
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
            }
        });
        finishBrowser(p115Browser, MangaPage.WEBDAV, () -> {
            mNetdiskSettingsOpen = true;
            refreshPage(MangaPage.WEBDAV);
        });
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
                // A failed listing (e.g. after heavy reading) is usually an
                // expired / rate-limited cookie. Clear stale direct links and
                // tell the user to re-login instead of silently showing an
                // empty folder ("看不了目录").
                boolean auth = msg != null && (msg.contains("errno=990001")
                        || msg.contains("errno=990002") || msg.contains("登录")
                        || msg.contains("401") || msg.contains("403"));
                if (auth) {
                    Manga115Api.clearDirectUrlCache();
                }
                final String status = auth
                        ? "登录态已失效，请到设置里重新扫码登录（" + msg + "）"
                        : "读取失败：" + msg;
                mMain.post(() -> {
                    mP115DirStatus = status;
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
                    Manga115Api.clearDirectUrlCache();
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
        // No header row on the browse pages: ⚙ in the browser header opens the
        // settings fold, so the netdisk page starts directly with its browser.
        if (mNetdiskSettingsOpen) {
            LinearLayout nav = addRow(content);
            addButton(nav, "← 返回网盘", v -> {
                mNetdiskSettingsOpen = false;
                refreshPage(MangaPage.WEBDAV);
            });

            LinearLayout quick = addRow(content);
            addButton(quick, "▷ 在线阅读当前目录", v -> {
                mNetdiskSettingsOpen = false;
                refreshPage(MangaPage.WEBDAV);
                if ("webdav".equals(mNetdiskMode)) {
                    downloadCurrentWebDav();
                } else {
                    readP115Online(mP115Cid, mP115Title);
                }
            });
            addButton(quick, "＋ 115 加入书架", v ->
                    addRemoteToShelf(SHELF_115 + mP115Cid + "|" + mP115Title));
            fill115LoginSection(content);
            fillWebDavSettings(content);
            return;
        }

        // Pure file-manager view: a top-level drive list, then the open drive.
        String cookie115 = mSettings.text(MangaSettings.P115_COOKIE);
        boolean has115 = !cookie115.isEmpty();
        boolean hasWebdav = !mSettings.text(MangaSettings.WEBDAV_URL).trim().isEmpty();

        if (mNetdiskAtRoot) {
            // Drive picker as cards, not a stack of buttons: the browser is the
            // one place where every source looks and behaves the same.
            List<MangaFileBrowser.Entry> drives = new ArrayList<>();
            MangaFileBrowser.Entry cloud = new MangaFileBrowser.Entry(
                    has115 ? "115 网盘" : "115 网盘（未登录）",
                    has115 ? "115" : "115-login", true);
            cloud.meta = has115 ? "云端文件 · 已登录" : "点 ⚙ 设置 扫码登录";
            cloud.glyph = "☁";
            drives.add(cloud);
            if (hasWebdav) {
                MangaFileBrowser.Entry dav = new MangaFileBrowser.Entry(
                        "WebDAV 网盘", "webdav", true);
                dav.meta = mSettings.text(MangaSettings.WEBDAV_URL);
                dav.glyph = "☁";
                drives.add(dav);
            }
            List<MangaFileBrowser.Crumb> crumbs = new ArrayList<>();
            crumbs.add(new MangaFileBrowser.Crumb("网盘", ""));
            final MangaFileBrowser driveBrowser = new MangaFileBrowser(getContext());
            driveBrowser.setData(drives, "网盘", crumbs,
                    has115 || hasWebdav ? null : "尚未配置网盘：在 ⚙ 设置 里扫码登录 115 或填写 WebDAV 地址。");
            driveBrowser.setCanGoBack(false);
            driveBrowser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onSortChanged(int sortMode) {
                mSettings.put(MangaSettings.SORT_MODE, sortMode);
            }

                @Override
                public void onOpen(MangaFileBrowser.Entry entry) {
                    onNavigate(entry.path);
                }

                @Override
                public void onNavigate(String path) {
                    if ("115-login".equals(path)) {
                        mNetdiskSettingsOpen = true;
                        refreshPage(MangaPage.WEBDAV);
                        return;
                    }
                    if ("115".equals(path)) {
                        mNetdiskMode = "115";
                        mNetdiskAtRoot = false;
                        String def = mSettings.text(MangaSettings.P115_DEFAULT_CID);
                        mP115Cid = def.isEmpty() ? "0" : def;
                        mP115Title = mSettings.text(MangaSettings.P115_DEFAULT_TITLE);
                        if (mP115Title.isEmpty()) {
                            mP115Title = "115网盘";
                        }
                        mP115ParentStack.clear();
                        listP115Dir(mP115Cid);
                        return;
                    }
                    if ("webdav".equals(path)) {
                        mNetdiskMode = "webdav";
                        mNetdiskAtRoot = false;
                        mWebDavUrl = mSettings.text(MangaSettings.WEBDAV_URL);
                        connectWebDav();
                    }
                }
            });
            finishBrowser(driveBrowser, MangaPage.WEBDAV, () -> {
                mNetdiskSettingsOpen = true;
                refreshPage(MangaPage.WEBDAV);
            });
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
        if (mWebDavEntries.isEmpty()) {
            addInfo(content, "目录内容", statusOrHint("空目录或尚未连接成功。"));
            return;
        }
        List<MangaFileBrowser.Entry> entries = new ArrayList<>();
        for (MangaWebDav.Entry entry : mWebDavEntries) {
            MangaFileBrowser.Entry e = new MangaFileBrowser.Entry(
                    entry.name, entry.href, entry.directory);
            e.size = entry.size;
            e.video = !entry.directory
                    && MangaSettings.hasExtension(entry.name, mSettings.videoExtensions());
            e.badge = e.video ? "▶" : null;
            e.tag = entry;
            entries.add(e);
        }
        List<MangaFileBrowser.Crumb> crumbs = new ArrayList<>();
        crumbs.add(new MangaFileBrowser.Crumb("WebDAV", ""));
        String trimmedUrl = mWebDavUrl.endsWith("/")
                ? mWebDavUrl.substring(0, mWebDavUrl.length() - 1) : mWebDavUrl;
        int lastSlash = trimmedUrl.lastIndexOf('/');
        if (lastSlash > 8) {
            crumbs.add(new MangaFileBrowser.Crumb(trimmedUrl.substring(lastSlash + 1), ""));
        }
        final MangaFileBrowser davBrowser = new MangaFileBrowser(getContext());
        davBrowser.setData(entries, "WebDAV", crumbs, statusOrHint(null));
        davBrowser.setCanGoBack(true);
        davBrowser.setCallback(new MangaFileBrowser.Callback() {
            @Override
            public void onSortChanged(int sortMode) {
                mSettings.put(MangaSettings.SORT_MODE, sortMode);
            }

            @Override
            public void onOpen(MangaFileBrowser.Entry entry) {
                if (entry.tag instanceof MangaWebDav.Entry) {
                    downloadSingleWebDav((MangaWebDav.Entry) entry.tag);
                }
            }

            @Override
            public void onNavigate(String href) {
                // A folder tile carries its href; the root crumb carries "".
                if (href == null || href.isEmpty()) {
                    mNetdiskAtRoot = true;
                    refreshPage(MangaPage.WEBDAV);
                    return;
                }
                try {
                    mWebDavUrl = MangaWebDav.resolve(mWebDavUrl, href);
                    if (!mWebDavUrl.endsWith("/")) {
                        mWebDavUrl += "/";
                    }
                    connectWebDav();
                } catch (Exception e) {
                    mWebDavStatus = "路径解析失败：" + e.getMessage();
                    refreshPage(MangaPage.WEBDAV);
                }
            }

            @Override
            public void onBack() {
                String trimmed = mWebDavUrl.endsWith("/")
                        ? mWebDavUrl.substring(0, mWebDavUrl.length() - 1) : mWebDavUrl;
                int parentSlash = trimmed.lastIndexOf('/');
                if (parentSlash > 8) {
                    mWebDavUrl = trimmed.substring(0, parentSlash + 1);
                    connectWebDav();
                } else {
                    mNetdiskAtRoot = true;
                    refreshPage(MangaPage.WEBDAV);
                }
            }
        });
        finishBrowser(davBrowser, MangaPage.WEBDAV, () -> {
            mNetdiskSettingsOpen = true;
            refreshPage(MangaPage.WEBDAV);
        });
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
        if (mTitle != null) {
            mTitle.setText(source.getName());
        }
        if (mPanelSource != null) {
            mPanelSource.setText(source.getName());
        }
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
        int sourceSpan = Math.max(2, spanCount());
        mSourceGrid.setLayoutManager(new GridLayoutManager(getContext(), sourceSpan));
        mSourceAdapter.setCardWidth(gridCardWidthPx(sourceSpan));
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
        if (mSourceFilters != null && mSourceFilters.getList() != null) {
            showSourceFilterSheet();
            return;
        }
        MangaSourceRepository.INSTANCE.fetchFilters(mSourceEntry.getSource(), (filters, error) -> {
            if (filters == null) {
                Toast.makeText(getContext(), "读取筛选失败：" + error, Toast.LENGTH_LONG).show();
                return;
            }
            mSourceFilters = filters;
            applyDefaultSort(filters);
            mMain.post(this::showSourceFilterSheet);
        });
    }

    /** Floating 筛选 window over the browse page: same sheet language as the
     *  file-manager quick settings, no extra page in between. */
    private void showSourceFilterSheet() {
        if (mSourceEntry == null || mSourceFilters == null || mPageContainer == null) {
            return;
        }
        final FilterList filters = mSourceFilters;
        MangaQuickSheet sheet = MangaQuickSheet.open(mPageContainer,
                mSourceEntry.getSource().getName() + " · 筛选",
                s -> {
                    if (filters.getList().isEmpty()) {
                        s.hint("该扩展没有提供筛选选项。");
                    } else {
                        renderFilterSheet(s, filters.getList());
                    }
                },
                new MangaQuickSheet.DismissListener() {
                    @Override
                    public void onConfirm() {
                        mSourceFiltersActive = true;
                        mSourcePage = 1;
                        startSourceBrowse();
                    }

                    @Override
                    public void onReset() {
                        resetFilterStates(filters);
                        mSourceFiltersActive = false;
                    }
                });
        sheet.withFooter(true);
    }

    @SuppressWarnings("unchecked")
    private void resetFilterStates(FilterList filters) {
        for (Object o : filters.getList()) {
            resetFilterState((Filter) o);
        }
        applyDefaultSort(filters);
    }

    @SuppressWarnings("unchecked")
    private void resetFilterState(Filter f) {
        if (f instanceof Filter.Group) {
            for (Object o : (java.util.List<?>) ((Filter.Group) f).getState()) {
                resetFilterState((Filter) o);
            }
        } else if (f instanceof Filter.Select) {
            ((Filter.Select) f).setState(0);
        } else if (f instanceof Filter.CheckBox) {
            ((Filter.CheckBox) f).setState(false);
        } else if (f instanceof Filter.TriState) {
            ((Filter.TriState) f).setState(Filter.TriState.STATE_IGNORE);
        } else if (f instanceof Filter.Text) {
            ((Filter.Text) f).setState("");
        } else if (f instanceof Filter.Sort) {
            ((Filter.Sort) f).setState(new Filter.Sort.Selection(0, true));
        }
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

    @SuppressWarnings("unchecked")
    private void renderFilterSheet(MangaQuickSheet s, java.util.List<?> filters) {
        java.util.List<Filter> toggles = new ArrayList<>();
        for (Object o : filters) {
            Filter f = (Filter) o;
            if (f instanceof Filter.CheckBox || f instanceof Filter.TriState) {
                toggles.add(f);
                continue;
            }
            flushToggleChips(s, toggles);
            if (f instanceof Filter.Group) {
                s.divider();
                s.section(f.getName());
                renderFilterSheet(s, (java.util.List<?>) ((Filter.Group) f).getState());
            } else if (f instanceof Filter.Header) {
                s.divider();
                s.section(f.getName());
            } else if (f instanceof Filter.Separator) {
                s.divider();
            } else if (f instanceof Filter.Select) {
                Filter.Select sel = (Filter.Select) f;
                Object[] vals = sel.getValues();
                String[] labels = new String[vals.length];
                for (int i = 0; i < vals.length; i++) {
                    labels[i] = String.valueOf(vals[i]);
                }
                int state = (Integer) sel.getState();
                s.section(f.getName());
                s.segments(labels,
                        Math.max(0, Math.min(state, Math.max(0, labels.length - 1))),
                        sel::setState);
            } else if (f instanceof Filter.Sort) {
                renderSortFilter(s, (Filter.Sort) f);
            } else if (f instanceof Filter.Text) {
                Filter.Text text = (Filter.Text) f;
                s.section(f.getName());
                s.textRow(f.getName(), (String) text.getState(), text::setState);
            }
        }
        flushToggleChips(s, toggles);
    }

    /** Consecutive checkbox / tristate filters become one wrapping 字段 row. */
    private void flushToggleChips(MangaQuickSheet s, java.util.List<Filter> toggles) {
        if (toggles.isEmpty()) {
            return;
        }
        String[] texts = new String[toggles.size()];
        boolean[] on = new boolean[toggles.size()];
        for (int i = 0; i < toggles.size(); i++) {
            Filter f = toggles.get(i);
            if (f instanceof Filter.CheckBox) {
                boolean checked = Boolean.TRUE.equals(((Filter.CheckBox) f).getState());
                texts[i] = f.getName();
                on[i] = checked;
            } else {
                int st = (Integer) f.getState();
                texts[i] = (st == Filter.TriState.STATE_INCLUDE ? "✓ "
                        : st == Filter.TriState.STATE_EXCLUDE ? "✗ " : "○ ") + f.getName();
                on[i] = st != Filter.TriState.STATE_IGNORE;
            }
        }
        java.util.List<Filter> snapshot = new ArrayList<>(toggles);
        toggles.clear();
        s.toggleChipsRaw(texts, on, i -> {
            Filter f = snapshot.get(i);
            if (f instanceof Filter.CheckBox) {
                ((Filter.CheckBox) f).setState(
                        !Boolean.TRUE.equals(((Filter.CheckBox) f).getState()));
            } else {
                int st = (Integer) f.getState();
                ((Filter.TriState) f).setState((st + 1) % 3);
            }
        });
    }

    /** Sort filters follow NextPlayer: round key chips + an ascending pair. */
    private void renderSortFilter(MangaQuickSheet s, Filter.Sort sort) {
        String[] vals = sort.getValues();
        if (vals == null || vals.length == 0) {
            return;
        }
        Filter.Sort.Selection sel = sort.getState();
        int idx = sel == null ? 0 : Math.max(0, Math.min(sel.getIndex(), vals.length - 1));
        boolean asc = sel == null || sel.getAscending();
        String[] glyphs = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            glyphs[i] = vals[i] == null || vals[i].isEmpty()
                    ? "?" : vals[i].substring(0, 1);
        }
        s.section(sort.getName());
        s.roundChips(glyphs, vals, idx, i -> {
            Filter.Sort.Selection cur = sort.getState();
            boolean a = cur == null || cur.getAscending();
            sort.setState(new Filter.Sort.Selection(i, a));
        });
        s.segments(new String[]{"↑ 升序", "↓ 降序"}, asc ? 0 : 1, i -> {
            Filter.Sort.Selection cur = sort.getState();
            int index = cur == null ? 0 : cur.getIndex();
            sort.setState(new Filter.Sort.Selection(index, i == 0));
        });
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
        if (index >= 0 && index < session.chapterKeys.size()) {
            noteHistory(HIST_EXT, "src:" + mSourceEntry.getSource().getId() + ":"
                            + mSourceManga.getUrl(),
                    mSourceManga.getTitle(), srcProgressKey(),
                    session.chapterKeys.get(index));
        }
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

    /** NextPlayer dark surface: rounded, low-contrast, teal-on-hover. */
    private void applyBrowserButtonStyle(Button button) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A2120);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), 0xFF2F3A38);
        button.setBackground(bg);
        button.setAllCaps(false);
        button.setTextColor(0xFFDDE4E3);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setStateListAnimator(null);
    }

    private Button addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        applyBrowserButtonStyle(button);
        button.setTextSize(16f);
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
