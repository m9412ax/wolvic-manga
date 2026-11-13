/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.api.WSession;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;
import com.igalia.wolvic.ui.views.CustomRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Default manga bookshelf. Each immediate subdirectory of /sdcard/Manga is one
 * manga. The stock browser is available through the browser button.
 */
public class MangaLibraryWidget extends UIWidget {

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "webm", "avi"};
    private static final int SPAN_COUNT = 4;

    private CustomRecyclerView mGrid;
    private TextView mEmptyText;
    private MangaCoverAdapter mAdapter;
    private File mRoot;
    private boolean mPermissionRequested;

    public MangaLibraryWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        LayoutInflater.from(getContext()).inflate(R.layout.manga_library, this, true);

        mGrid = findViewById(R.id.mangaGrid);
        mEmptyText = findViewById(R.id.emptyText);
        Button browserButton = findViewById(R.id.browserButton);

        mGrid.setLayoutManager(new GridLayoutManager(getContext(), SPAN_COUNT));
        mAdapter = new MangaCoverAdapter(getContext(), this::openReader);
        mGrid.setAdapter(mAdapter);

        if (browserButton != null) {
            browserButton.setOnClickListener(v -> {
                Context context = getContext();
                if (context instanceof VRBrowserActivity) {
                    ((VRBrowserActivity) context).showBrowserFromManga();
                }
            });
        }

        mRoot = new File(Environment.getExternalStorageDirectory(), "Manga");
        scanLibrary();
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        aPlacement.width = 1200;
        aPlacement.height = 800;
        aPlacement.worldWidth = 1.6f;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.translationZ = -0.2f;
        aPlacement.cylinder = false;
        aPlacement.visible = false;
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        scanLibrary();
        super.show(aShowFlags);
    }

    @Override
    public void onResume() {
        if (isVisible() && hasStoragePermission()) {
            scanLibrary();
        }
    }

    @Override
    protected void onDismiss() {
        // This is the root manga view. UIWidget's default would remove it and
        // leave the hidden browser window behind, so keep the shelf visible.
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
                        scanLibrary();
                    }

                    @Override
                    public void reject() {
                        mPermissionRequested = false;
                        showEmptyState("需要存储权限才能读取 /sdcard/Manga。请在系统设置中允许后重新进入。 ");
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
