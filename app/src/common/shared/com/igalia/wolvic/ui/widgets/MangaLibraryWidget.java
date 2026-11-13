/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;
import com.igalia.wolvic.ui.views.CustomRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The manga bookshelf. This is the default landing screen; the stock browser
 * is demoted to a button. Each immediate subdirectory of /sdcard/Manga is one
 * manga. Clicking a cover opens {@link MangaReaderWidget}.
 */
public class MangaLibraryWidget extends UIWidget {

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "webm", "avi"};
    private static final int SPAN_COUNT = 4;

    private CustomRecyclerView mGrid;
    private TextView mEmptyText;
    private MangaCoverAdapter mAdapter;
    private File mRoot;

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
        // Large centered panel. width/height are in dp (see WidgetPlacement.viewWidth()).
        aPlacement.width = 1200;
        aPlacement.height = 800;
        aPlacement.worldWidth = 1.6f;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        // Sit slightly in front of the (hidden) browser window.
        aPlacement.translationZ = -0.2f;
        aPlacement.cylinder = false;
        aPlacement.visible = false;
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        // Re-scan so newly added folders show up when returning to the shelf.
        scanLibrary();
        super.show(aShowFlags);
    }

    private void scanLibrary() {
        List<MangaCoverAdapter.MangaItem> items = new ArrayList<>();
        File[] dirs = null;
        try {
            if (mRoot != null && mRoot.isDirectory()) {
                dirs = mRoot.listFiles(f -> f.isDirectory());
            }
        } catch (Throwable t) {
            dirs = null;
        }

        if (dirs != null) {
            Arrays.sort(dirs, (f1, f2) ->
                    MangaCoverAdapter.naturalCompare(f1.getName(), f2.getName()));
            for (File dir : dirs) {
                if (dir == null || !dir.isDirectory()) {
                    continue;
                }
                File cover = findFirstImage(dir);
                boolean hasVideo = containsVideo(dir);
                items.add(new MangaCoverAdapter.MangaItem(dir, dir.getName(), cover, hasVideo));
            }
        }

        if (mAdapter != null) {
            mAdapter.update(items);
        }
        boolean empty = items.isEmpty();
        if (mEmptyText != null) {
            mEmptyText.setVisibility(empty ? VISIBLE : GONE);
        }
        if (mGrid != null) {
            mGrid.setVisibility(empty ? GONE : VISIBLE);
        }
    }

    private void openReader(File folder) {
        if (folder == null) {
            return;
        }
        // Keep the shelf so we can return to it; the reader is a fresh widget.
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
        for (File f : files) {
            if (f.isFile() && hasExtension(f.getName(), IMAGE_EXTENSIONS)) {
                images.add(f);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        images.sort((f1, f2) -> MangaCoverAdapter.naturalCompare(f1.getName(), f2.getName()));
        return images.get(0);
    }

    private static boolean containsVideo(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isFile() && hasExtension(f.getName(), VIDEO_EXTENSIONS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }
}