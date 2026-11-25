/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * Media3/ExoPlayer DataSource backed by a {@link PrefetchVideoDataSource.Reader}.
 *
 * The reader is a blocking random-access byte source (SMB via smbj, or HTTP
 * Range for 115). ExoPlayer owns buffering, A/V sync, seeking and decoding;
 * this class only translates "read at position" into reader calls. Every
 * ExoPlayer seek creates a fresh DataSource instance, hence a fresh reader /
 * SMB handle, which is exactly what a random seek needs.
 */
public final class ReaderVideoDataSource extends BaseDataSource {

    /** Creates one DataSource per open()/seek. */
    public static final class Factory implements DataSource.Factory {
        private final RemoteManga source;
        private final RemoteManga.PageInfo page;
        private final Uri uri;

        public Factory(RemoteManga source, RemoteManga.PageInfo page, Uri uri) {
            this.source = source;
            this.page = page;
            this.uri = uri;
        }

        @Override
        public DataSource createDataSource() {
            return new ReaderVideoDataSource(source, page, uri);
        }
    }

    private final RemoteManga source;
    private final RemoteManga.PageInfo page;
    private final Uri uri;

    private PrefetchVideoDataSource.Reader reader;
    private long position;
    private long remaining;
    private boolean opened;

    private ReaderVideoDataSource(RemoteManga source, RemoteManga.PageInfo page, Uri uri) {
        super(/* isNetwork= */ true);
        this.source = source;
        this.page = page;
        this.uri = uri;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        reader = source.openVideoReader(page);
        if (reader == null) {
            throw new IOException("no video reader for " + dataSpec.uri);
        }
        long size;
        try {
            size = reader.size();
        } catch (Throwable t) {
            throw new IOException("size failed: " + t.getMessage(), t);
        }
        if (size <= 0) {
            throw new IOException("unknown length for " + dataSpec.uri);
        }
        position = dataSpec.position;
        if (position < 0 || position > size) {
            throw new IOException("position " + position + " outside 0.." + size);
        }
        long length = dataSpec.length == C.LENGTH_UNSET ? size - position : dataSpec.length;
        remaining = Math.min(length, size - position);
        opened = true;
        transferStarted(dataSpec);
        return remaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int want = (int) Math.min(length, remaining);
        int read;
        try {
            read = reader.readAt(position, buffer, offset, want);
        } catch (Throwable t) {
            throw new IOException("read at " + position + " failed: " + t.getMessage(), t);
        }
        if (read <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        position += read;
        remaining -= read;
        bytesTransferred(read);
        return read;
    }

    @Override
    public void close() throws IOException {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignored) {
            // Closing an already-broken SMB session must never mask playback.
        } finally {
            reader = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
