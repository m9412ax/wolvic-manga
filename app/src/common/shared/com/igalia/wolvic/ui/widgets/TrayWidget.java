/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.VectorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ObservableBoolean;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.PlatformActivityPlugin;
import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.VRBrowserApplication;
import com.igalia.wolvic.audio.AudioEngine;
import com.igalia.wolvic.browser.BookmarksStore;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.engine.Session;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.databinding.TrayBinding;
import com.igalia.wolvic.downloads.Download;
import com.igalia.wolvic.downloads.DownloadsManager;
import com.igalia.wolvic.ui.viewmodel.TrayViewModel;
import com.igalia.wolvic.ui.viewmodel.WindowViewModel;
import com.igalia.wolvic.ui.views.UIButton;
import com.igalia.wolvic.ui.widgets.MangaReaderWidget;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;
import com.igalia.wolvic.ui.widgets.settings.SettingsWidget;
import com.igalia.wolvic.utils.ConnectivityReceiver;
import com.igalia.wolvic.utils.DeviceType;
import com.igalia.wolvic.utils.LocaleUtils;
import com.igalia.wolvic.utils.ViewUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class TrayWidget extends UIWidget implements WidgetManagerDelegate.UpdateListener, PlatformActivityPlugin.TrayDelegate,
        DownloadsManager.DownloadsListener, ConnectivityReceiver.Delegate,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int ICON_ANIMATION_DURATION = 200;

    public static final int MODE_BROWSER = 0;
    public static final int MODE_MANGA = 1;
    public static final int MODE_READER = 2;

    private static final int TAB_ADDED_NOTIFICATION_ID = 0;
    private static final int TAB_SENT_NOTIFICATION_ID = 1;
    private static final int BOOKMARK_ADDED_NOTIFICATION_ID = 2;
    private static final int DOWNLOAD_COMPLETED_NOTIFICATION_ID = 3;
    private static final int WIFI_NOTIFICATION_ID = 4;
    private static final int LEFT_CONTROLLER_NOTIFICATION_ID = 5;
    private static final int RIGHT_CONTROLLER_NOTIFICATION_ID = 6;
    private static final int HEADSET_NOTIFICATION_ID = 7;
    private static final int TIME_NOTIFICATION_ID = 8;
    private static final int WEB_APP_ADDED_NOTIFICATION_ID = 9;

    private WindowViewModel mViewModel;
    private TrayViewModel mTrayViewModel;
    private TrayBinding mBinding;
    /** Reader scrubber is visible only while the pointer hovers the reader
     *  controls. Delayed hide lets the pointer travel into the scrubber. */
    // The reader scrubber is now permanent in reader mode, so there is no
    // hover-based hide/show any more. Kept as no-ops to avoid touching callers.
    private boolean mReaderProgressHovered;
    private final Runnable mHideProgressRunnable = () -> { };
    private AudioEngine mAudio;
    private SettingsWidget mSettingsWidget;
    private List<TrayListener> mTrayListeners;
    private int mMinPadding;
    private int mMaxPadding;
    private Session mSession;
    private WindowWidget mAttachedWindow;
    private boolean mIsWindowAttached;
    private BroadcastReceiver mBroadcastReceiver;
    private int mLastWifiLevel = -1;
    private String mWifiSSID;
    private int mHeadsetBatteryLevel;
    private int mLeftControllerBatteryLevel;
    private int mRightControllerBatteryLevel;
    private ConnectivityReceiver mConnectivityReceived;
    private int mMangaMode = MODE_BROWSER;
    private String mMangaPage = "LIBRARY";
    /** True while the browser window is temporarily shown just to host the settings dialog. */
    private boolean mSettingsUsedWindow;
    /** Original tray dimensions, used to pin its size during a manga canvas zoom. */
    private float mBaseTrayWorldWidth = -1f;
    private int mBaseTrayWidth = -1;
    private int mBaseTrayHeight = -1;
    /** True only while parented to a manga surface, not to a browser window. */
    private boolean mParentedToManga = false;

    public TrayWidget(Context aContext) {
        super(aContext);
        initialize(aContext);
    }

    public TrayWidget(Context aContext, AttributeSet aAttrs) {
        super(aContext, aAttrs);
        initialize(aContext);
    }

    public TrayWidget(Context aContext, AttributeSet aAttrs, int aDefStyle) {
        super(aContext, aAttrs, aDefStyle);
        initialize(aContext);
    }

    private void initialize(Context aContext) {
        // Downloads icon progress clipping doesn't work if HW acceleration is enabled.
        setIsHardwareAccelerationEnabled(false);

        mTrayViewModel = new ViewModelProvider(
                (VRBrowserActivity)getContext(),
                ViewModelProvider.AndroidViewModelFactory.getInstance(((VRBrowserActivity) getContext()).getApplication()))
                .get(TrayViewModel.class);

        mTrayViewModel.getIsVisible().observe((VRBrowserActivity) getContext(), mIsVisibleObserver);
        mTrayViewModel.setHeadsetBatteryLevel(R.drawable.ic_icon_statusbar_indicator_10);
        mTrayViewModel.setTabsButtonInTray(SettingsStore.getInstance(getContext()).getTabsLocation() == SettingsStore.TABS_LOCATION_TRAY);

        updateUI();

        mIsWindowAttached = false;

        mTrayListeners = new ArrayList<>();

        mMinPadding = WidgetPlacement.pixelDimension(getContext(), R.dimen.tray_icon_padding_min);
        mMaxPadding = WidgetPlacement.pixelDimension(getContext(), R.dimen.tray_icon_padding_max);

        mAudio = AudioEngine.fromContext(aContext);

        mWidgetManager.addUpdateListener(this);
        mWidgetManager.getServicesProvider().getDownloadsManager().addListener(this);

        mConnectivityReceived = ((VRBrowserApplication)getContext().getApplicationContext()).getConnectivityReceiver();
        mConnectivityReceived.addListener(this);

        PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);

        mWifiSSID = getContext().getString(R.string.tray_wifi_no_connection);

        updateTime();
        OnConnectivityChanged(ConnectivityReceiver.isNetworkAvailable(getContext()));

        if (DeviceType.getType() == DeviceType.OculusQuest) {
            mTrayViewModel.setLeftControllerIcon(R.drawable.ic_icon_statusbar_leftcontroller);
            mTrayViewModel.setRightControllerIcon(R.drawable.ic_icon_statusbar_rightcontroller);
        }
    }

    public void updateUI() {
        removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(getContext());

        // Inflate this data binding layout
        mBinding = DataBindingUtil.inflate(inflater, R.layout.tray, this, true);
        mBinding.setLifecycleOwner((VRBrowserActivity)getContext());
        mBinding.setTraymodel(mTrayViewModel);
        mBinding.setViewmodel(mViewModel);

        mBinding.privateButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.privateButton.setOnClickListener(view -> {
            if (isImmersive()) {
                return;
            }
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            notifyPrivateBrowsingClicked();
            view.requestFocusFromTouch();
        });

        mBinding.settingsButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.settingsButton.setOnClickListener(view -> {
            if (isImmersive()) {
                return;
            }
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            toggleSettingsDialog();
            if (mSettingsWidget.isVisible()) {
                view.requestFocusFromTouch();
            }
        });

        mBinding.tabsButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.tabsButton.setOnClickListener(view -> {
            if (isImmersive()) {
                return;
            }
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            view.requestFocusFromTouch();
            notifyTabsClicked();
        });

        mBinding.addwindowButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.addwindowButton.setOnClickListener(view -> {
            if (isImmersive()) {
                return;
            }
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            view.requestFocusFromTouch();

            notifyAddWindowClicked();
        });

        mBinding.bookmarksButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.bookmarksButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            notifyBookmarksClicked();
            view.requestFocusFromTouch();
        });

        mBinding.downloadsButton.setOnHoverListener(mButtonScaleHoverListener);
        mBinding.downloadsButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            notifyDownloadsClicked();
            view.requestFocusFromTouch();
        });

        // Manga page tabs and reader controls live in the SAME tray widget and
        // reuse the stock UIButton style/tooltip/background, so the browser and
        // manga modes are one UI instead of two separate applications.
        bindMangaButton(mBinding.mangaHomeButton, "MANGA");
        bindMangaButton(mBinding.mangaShelfButton, "LIBRARY");
        bindMangaButton(mBinding.mangaLocalButton, "LOCAL");
        bindMangaButton(mBinding.mangaSmbButton, "SMB");
        bindMangaButton(mBinding.mangaWebDavButton, "WEBDAV");
        bindMangaButton(mBinding.mangaExtensionsButton, "EXTENSIONS");
        bindMangaButton(mBinding.mangaBrowserButton, "BROWSER");
        bindMangaButton(mBinding.mangaSettingsButton, "SETTINGS");
        bindMangaButton(mBinding.readerBackButton, "READER_BACK");
        bindMangaButton(mBinding.readerPrevButton, "READER_PREV");
        bindMangaButton(mBinding.readerNextButton, "READER_NEXT");
        bindMangaButton(mBinding.readerGotoButton, "READER_GOTO");
        bindMangaButton(mBinding.readerScaleButton, "READER_SCALE");
        bindMangaButton(mBinding.readerDirectionButton, "READER_DIRECTION");
        bindMangaButton(mBinding.readerBrightnessButton, "READER_BRIGHTNESS");
        bindMangaButton(mBinding.readerAutoButton, "READER_AUTO");
        bindMangaButton(mBinding.readerDoubleButton, "READER_DOUBLE");
        setupReaderProgress();
        applyMangaMode();

        mBinding.wifi.setOnHoverListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                NotificationManager.Notification notification = new NotificationManager.Builder(TrayWidget.this)
                        .withView(mBinding.wifi)
                        .withDensity(R.dimen.tray_tooltip_density)
                        .withLayout(R.layout.tooltip)
                        .withString(mWifiSSID)
                        .withAutoHide(false)
                        .withMargin(-15.0f)
                        .withPosition(NotificationManager.Notification.TOP).build();
                NotificationManager.show(WIFI_NOTIFICATION_ID, notification);

            } else if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                NotificationManager.hide(WIFI_NOTIFICATION_ID);
            }

            return false;
        });

        mBinding.leftController.setOnHoverListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                NotificationManager.Notification notification = new NotificationManager.Builder(TrayWidget.this)
                        .withView(mBinding.leftController)
                        .withDensity(R.dimen.tray_tooltip_density)
                        .withLayout(R.layout.tooltip)
                        .withString(getContext().getString(
                                R.string.tray_status_left_controller,
                                String.format(
                                        LocaleUtils.getDisplayLanguage(
                                                getContext()).getLocale(),
                                        "%d%%",
                                        mLeftControllerBatteryLevel
                                )
                        ))
                        .withAutoHide(false)
                        .withMargin(-15.0f)
                        .withPosition(NotificationManager.Notification.TOP).build();
                NotificationManager.show(LEFT_CONTROLLER_NOTIFICATION_ID, notification);

            } else if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                NotificationManager.hide(LEFT_CONTROLLER_NOTIFICATION_ID);
            }

            return false;
        });

        mBinding.rightController.setOnHoverListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                NotificationManager.Notification notification = new NotificationManager.Builder(TrayWidget.this)
                        .withView(mBinding.rightController)
                        .withDensity(R.dimen.tray_tooltip_density)
                        .withLayout(R.layout.tooltip)
                        .withString(getContext().getString(
                                R.string.tray_status_right_controller,
                                String.format(
                                        LocaleUtils.getDisplayLanguage(
                                                getContext()).getLocale(),
                                        "%d%%",
                                        mRightControllerBatteryLevel
                                )
                        ))
                        .withAutoHide(false)
                        .withMargin(-15.0f)
                        .withPosition(NotificationManager.Notification.TOP).build();
                NotificationManager.show(RIGHT_CONTROLLER_NOTIFICATION_ID, notification);

            } else if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                NotificationManager.hide(RIGHT_CONTROLLER_NOTIFICATION_ID);
            }

            return false;
        });

        mBinding.headset.setOnHoverListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                NotificationManager.Notification notification = new NotificationManager.Builder(TrayWidget.this)
                        .withView(mBinding.headset)
                        .withDensity(R.dimen.tray_tooltip_density)
                        .withLayout(R.layout.tooltip)
                        .withString(getContext().getString(
                                DeviceType.isTetheredDevice() ? R.string.tray_status_phone : R.string.tray_status_headset,
                                String.format(
                                        LocaleUtils.getDisplayLanguage(
                                                getContext()).getLocale(),
                                        "%d%%",
                                        mHeadsetBatteryLevel
                                )
                        ))
                        .withAutoHide(false)
                        .withMargin(-15.0f)
                        .withPosition(NotificationManager.Notification.TOP).build();
                NotificationManager.show(HEADSET_NOTIFICATION_ID, notification);

            } else if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                NotificationManager.hide(HEADSET_NOTIFICATION_ID);
            }

            return false;
        });

        mBinding.time.setOnHoverListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                NotificationManager.Notification notification = new NotificationManager.Builder(TrayWidget.this)
                        .withView(mBinding.time)
                        .withDensity(R.dimen.tray_tooltip_density)
                        .withLayout(R.layout.tooltip)
                        .withString(getFormattedDate())
                        .withAutoHide(false)
                        .withMargin(-15.0f)
                        .withPosition(NotificationManager.Notification.TOP).build();
                NotificationManager.show(TIME_NOTIFICATION_ID, notification);

            } else if (motionEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                NotificationManager.hide(TIME_NOTIFICATION_ID);
            }

            return false;
        });

        mBinding.leftController.setVisibility(mLeftControllerBatteryLevel < 0 ? View.GONE : View.VISIBLE);
        mBinding.rightController.setVisibility(mRightControllerBatteryLevel < 0 ? View.GONE : View.VISIBLE);

        updateTime();
        updateWifi();
    }

    public void start(Context context) {
        if (mBroadcastReceiver == null) {
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if ((action != null) && action.compareTo(Intent.ACTION_TIME_TICK) == 0) {
                        updateTime();
                    }
                }
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.registerReceiver(mBroadcastReceiver, new IntentFilter(Intent.ACTION_TIME_TICK), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(mBroadcastReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        }
    }

    public void stop(Context context) {
        if (mBroadcastReceiver != null) {
            context.unregisterReceiver(mBroadcastReceiver);
        }
    }

    Observer<ObservableBoolean> mIsVisibleObserver = aVisible -> {
        if (aVisible.get()) {
            this.show(REQUEST_FOCUS);

        } else {
            this.hide(UIWidget.KEEP_WIDGET);
        }

        mWidgetManager.updateWidget(TrayWidget.this);
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateUI();

        mTrayViewModel.refresh();
    }

    private OnHoverListener mButtonScaleHoverListener = (view, motionEvent) -> {
        UIButton button = (UIButton) view;
        if (button.isActive() || button.isPrivate()) {
            return false;
        }

        int ev = motionEvent.getActionMasked();
        switch (ev) {
            case MotionEvent.ACTION_HOVER_ENTER:
                if (!button.isPressed() && ViewUtils.isInsideView(button, (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                    animateButtonPadding(button, mMinPadding, ICON_ANIMATION_DURATION);
                }
                return false;

            case MotionEvent.ACTION_HOVER_EXIT:
                if (!ViewUtils.isInsideView(button, (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                    animateButtonPadding(button, mMaxPadding, ICON_ANIMATION_DURATION);
                }
                return false;
        }

        return false;
    };

    // This animation assumes that the padding is always symmetrical.
    private void animateButtonPadding(UIButton button, int paddingEnd, int duration) {
        if (button.getPaddingLeft() == paddingEnd) {
            return;
        }

        if (button.isPressed() || !mIsWindowAttached) {
            button.setPadding(paddingEnd, paddingEnd, paddingEnd, paddingEnd);
            return;
        }

        if (button.getAnimation() != null) {
            button.getAnimation().cancel();
        }

        int paddingStart = button.getPaddingLeft();
        button.animate()
                .setDuration(duration)
                .setUpdateListener(animation -> {
                    float progress = animation.getAnimatedFraction();
                    int interpolatedPadding = (int) (paddingStart + progress * (paddingEnd - paddingStart));
                    button.setPadding(interpolatedPadding, interpolatedPadding, interpolatedPadding, interpolatedPadding);
                })
                .withEndAction(() -> {
                    if (button.isActive() || button.isPrivate()) {
                        button.setPadding(mMinPadding, mMinPadding, mMinPadding, mMinPadding);
                    }
                })
                .start();
    }

    public void addListeners(TrayListener... listeners) {
        mTrayListeners.addAll(Arrays.asList(listeners));
    }

    public void removeListeners(TrayListener... listeners) {
        mTrayListeners.removeAll(Arrays.asList(listeners));
    }

    private void notifyTabsClicked() {
        hideNotifications();
        mTrayListeners.forEach(TrayListener::onTabsClicked);
    }

    private void notifyPrivateBrowsingClicked() {
        hideNotifications();
        mTrayListeners.forEach(TrayListener::onPrivateBrowsingClicked);
    }

    private void notifyAddWindowClicked() {
        hideNotifications();
        mTrayListeners.forEach(TrayListener::onAddWindowClicked);
    }

    private void notifyBookmarksClicked() {
        hideNotifications();
        mTrayListeners.forEach(TrayListener::onBookmarksClicked);
    }

    private void notifyDownloadsClicked() {
        hideNotifications();
        mTrayListeners.forEach(TrayListener::onDownloadsClicked);
    }

    private void bindMangaButton(UIButton button, String action) {
        if (button == null) {
            return;
        }
        button.setOnHoverListener(mButtonScaleHoverListener);
        button.setOnClickListener(view -> mangaAction(action));
    }

    private void mangaAction(String action) {
        if (isImmersive()) {
            return;
        }
        if (mAudio != null) {
            mAudio.playSound(AudioEngine.Sound.CLICK);
        }
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).onMangaTrayAction(action);
        }
    }

    /** Switches the tray between the stock browser controls and manga controls. */
    public void setMangaMode(int mode) {
        mMangaMode = mode;
        applyMangaMode();
    }

    public int getMangaMode() {
        return mMangaMode;
    }

    public void setMangaPage(String page) {
        mMangaPage = page;
        applyMangaMode();
    }

    public void setReaderAutoActive(boolean active) {
        if (mBinding != null) {
            mBinding.readerAutoButton.setActiveMode(active);
        }
    }

    /** Wires the hover-revealed page scrubber: prev/next chapter buttons,
     *  a draggable seek bar that jumps to any page, and a "2/15" label. */
    private void setupReaderProgress() {
        // Hover reveal is handled in dispatchHoverEvent() at the WIDGET level.
        // A OnHoverListener on the readerButtons LinearLayout never fires in
        // VR: the controller ray hits a child UIButton, and hover events are
        // delivered only to the view actually under the pointer (unlike touch,
        // they do not bubble up to the parent container). The video bar uses
        // the same widget-level dispatchHoverEvent trick and works.
        mBinding.readerPrevChapterButton.setOnClickListener(
                v -> readerChapter(-1));
        mBinding.readerNextChapterButton.setOnClickListener(
                v -> readerChapter(1));
        mBinding.readerPageSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    private boolean mTracking;

                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress,
                                                  boolean fromUser) {
                        if (fromUser && mTracking) {
                            mBinding.readerPageLabel.setText(
                                    (progress + 1) + "/" + seekBar.getMax());
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        mTracking = true;
                        removeCallbacks(mHideProgressRunnable);
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        mTracking = false;
                        Context c = getContext();
                        if (c instanceof VRBrowserActivity) {
                            MangaReaderWidget reader =
                                    ((VRBrowserActivity) c).getMangaReader();
                            if (reader != null) {
                                reader.jumpToPage(seekBar.getProgress());
                            }
                        }
                    }
                });
    }

    /** Reveal the reader scrubber whenever the VR pointer is inside this tray
     *  while in reader mode. Hover events in VR are delivered only to the view
     *  under the ray (they do not bubble to a parent LinearLayout), so the
     *  widget-level dispatch is the reliable place to observe them — same
     *  approach as MangaReaderWidget's video bar. */
    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // The scrubber is permanently visible in reader mode; no hover-driven
        // show/hide. Just pass through so child buttons/tooltips still work.
        return super.dispatchHoverEvent(event);
    }

    private void readerChapter(int delta) {
        Context c = getContext();
        if (c instanceof VRBrowserActivity) {
            MangaReaderWidget reader = ((VRBrowserActivity) c).getMangaReader();
            if (reader != null) {
                reader.chapterDelta(delta);
            }
        }
    }

    /** Called by the reader whenever the current page / chapter changes. */
    public void updateReaderProgress(int pageIndex, int pageCount,
                                     int chapterIndex, int chapterCount) {
        if (mBinding == null) {
            return;
        }
        int total = Math.max(1, pageCount);
        int index = Math.max(0, Math.min(pageIndex, total - 1));
        SeekBar seek = mBinding.readerPageSeek;
        seek.setMax(Math.max(1, total - 1));
        seek.setProgress(index);
        mBinding.readerPageLabel.setText((index + 1) + "/" + total);
        boolean hasPrev = chapterIndex > 0;
        boolean hasNext = chapterIndex + 1 < chapterCount;
        mBinding.readerPrevChapterButton.setEnabled(hasPrev);
        mBinding.readerNextChapterButton.setEnabled(hasNext);
        mBinding.readerPrevChapterButton.setAlpha(hasPrev ? 1.0f : 0.4f);
        mBinding.readerNextChapterButton.setAlpha(hasNext ? 1.0f : 0.4f);
    }

    /** Grows the tray quad to fit the extra scrubber row in reader mode, and
     *  restores the normal height otherwise. mBaseTrayHeight is kept in sync
     *  because onMangaParentResized() re-pins to it during canvas zoom. */
    private void setTrayHeightForMode(boolean reader) {
        if (mWidgetManager == null) {
            return;
        }
        int height = WidgetPlacement.dpDimension(getContext(),
                reader ? R.dimen.tray_height_reader : R.dimen.tray_height);
        WidgetPlacement p = getPlacement();
        if (p.height == height && mBaseTrayHeight == height) {
            return;
        }
        p.height = height;
        mBaseTrayHeight = height;
        mWidgetManager.updateWidget(this);
    }

    private void applyMangaMode() {
        if (mBinding == null) {
            return;
        }
        mBinding.trayButtons.setVisibility(mMangaMode == MODE_BROWSER ? View.VISIBLE : View.GONE);
        mBinding.mangaButtons.setVisibility(mMangaMode == MODE_MANGA ? View.VISIBLE : View.GONE);
        mBinding.readerButtons.setVisibility(mMangaMode == MODE_READER ? View.VISIBLE : View.GONE);
        // Reader mode: keep the status bar (time / battery / controllers) AND
        // show the page scrubber permanently as its own row. Grow the tray
        // surface to fit three rows; other modes keep the original 66dp.
        mBinding.statusBar.setVisibility(View.VISIBLE);
        if (mMangaMode == MODE_READER) {
            mReaderProgressHovered = false;
            removeCallbacks(mHideProgressRunnable);
            mBinding.readerProgressRow.setVisibility(View.VISIBLE);
            setTrayHeightForMode(true);
        } else {
            removeCallbacks(mHideProgressRunnable);
            mBinding.readerProgressRow.setVisibility(View.GONE);
            setTrayHeightForMode(false);
        }

        mBinding.mangaShelfButton.setActiveMode("LIBRARY".equals(mMangaPage));
        mBinding.mangaLocalButton.setActiveMode("LOCAL".equals(mMangaPage));
        mBinding.mangaSmbButton.setActiveMode("SMB".equals(mMangaPage));
        mBinding.mangaWebDavButton.setActiveMode("WEBDAV".equals(mMangaPage));
        mBinding.mangaExtensionsButton.setActiveMode("EXTENSIONS".equals(mMangaPage));
        mBinding.mangaSettingsButton.setActiveMode("SETTINGS".equals(mMangaPage));
        Log.i("MangaTray", "mode=" + mMangaMode + " page=" + mMangaPage);
    }

    /**
     * In manga / reader modes the focused browser window is hidden, but the tray
     * (its child) must stay visible. Detach it to world-space so it survives the
     * window hide, and re-attach it when returning to the browser. The world
     * placement mirrors exactly where the tray sits on the window, so its on-screen
     * position does not move.
     */
    public void setMangaDetached(boolean detached) {
        WidgetPlacement p = getPlacement();
        if (detached) {
            if (p.parentHandle != -1) {
                p.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_y);
                p.translationZ = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_z);
                p.parentAnchorX = 0.5f;
                p.parentAnchorY = 0.5f;
                detachFromWindow();
                mWidgetManager.updateWidget(this);
            }
        } else {
            if (p.parentHandle == -1) {
                p.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_y)
                        - WidgetPlacement.unitFromMeters(getContext(), R.dimen.window_world_y);
                p.translationZ = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_z)
                        - WidgetPlacement.getWindowWorldZMeters(getContext());
                p.parentAnchorX = 0.5f;
                p.parentAnchorY = 0.0f;
                WindowWidget window = ((VRBrowserActivity) getContext()).getFocusedWindow();
                if (window != null) {
                    attachToWindow(window);
                }
                mWidgetManager.updateWidget(this);
            }
        }
        Log.i("MangaTray", "detached=" + detached + " parent=" + getPlacement().parentHandle);
    }

    /** In manga / reader mode the tray becomes a child of the manga surface.
     *  Moving that surface with the native WidgetMover then moves the control
     *  bar along with the page UI, exactly like a stock browser window.
     *
     *  <p>parentAnchor 0.5 makes the child translation a pure world-space
     *  offset relative to the parent centre (no anchor compensation), so the
     *  tray keeps its original on-screen position while following the drag.</p>
     *
     *  <p>Callers must only invoke this when the parent actually changes: the
     *  stored offset is relative and must NOT be recomputed after the user has
     *  dragged the manga surface.</p> */
    public void setMangaParent(UIWidget parent) {
        WidgetPlacement p = getPlacement();
        float absY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_y);
        float absZ = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_z);
        if (parent == null) {
            mParentedToManga = false;
            if (p.parentHandle != -1) {
                p.parentHandle = -1;
                p.parentAnchorX = 0.5f;
                p.parentAnchorY = 0.5f;
                p.translationX = 0f;
                p.translationY = absY;
                p.translationZ = absZ;
                mWidgetManager.updateWidget(this);
            }
            return;
        }
        WidgetPlacement t = parent.getPlacement();
        p.parentHandle = parent.getHandle();
        p.parentAnchorX = 0.5f;
        p.parentAnchorY = 0.5f;
        p.translationX = -t.translationX;
        p.translationY = absY - t.translationY;
        p.translationZ = absZ - t.translationZ;
        mParentedToManga = true;
        mWidgetManager.updateWidget(this);
        Log.i("MangaTray", "attached to manga parent=" + parent.getHandle()
                + " relY=" + p.translationY + " relZ=" + p.translationZ);
    }

    /** A canvas zoom grows the manga surface upwards around a fixed bottom edge.
     *  The tray is a child of that surface, so its centre would move up by
     *  deltaParentY and its quad could be scaled with the parent. Cancel that
     *  delta and pin the original dimensions: the control bar must stay exactly
     *  where it was, at exactly the same size - only the canvas changes. */
    public void onMangaParentResized(float deltaParentY, float deltaParentZ) {
        WidgetPlacement p = getPlacement();
        if (!mParentedToManga || p.parentHandle == -1) {
            return;
        }
        // Cancel BOTH axes: a Z shift of the parent moves the child towards
        // the viewer, which reads as the control bar growing/shrinking.
        p.translationY -= deltaParentY;
        p.translationZ -= deltaParentZ;
        // Re-pin every placement field that could make the control bar appear
        // to grow/shrink together with the canvas. These are absolute values,
        // never percentages of the parent surface.
        p.parentAnchorX = 0.5f;
        p.parentAnchorY = 0.5f;
        if (mBaseTrayWorldWidth > 0f) {
            p.worldWidth = mBaseTrayWorldWidth;
            p.width = mBaseTrayWidth;
            p.height = mBaseTrayHeight;
        }
        mWidgetManager.updateWidget(this);
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        Context context = getContext();
        aPlacement.width = WidgetPlacement.dpDimension(context, R.dimen.tray_width);
        aPlacement.height = WidgetPlacement.dpDimension(context, R.dimen.tray_height);
        aPlacement.worldWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.tray_world_width);
        aPlacement.translationY = WidgetPlacement.unitFromMeters(context, R.dimen.tray_world_y) -
                WidgetPlacement.unitFromMeters(context, R.dimen.window_world_y);
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.parentAnchorX = 0.5f;
        aPlacement.parentAnchorY = 0.0f;
        aPlacement.rotationAxisX = 1.0f;
        aPlacement.rotation = (float)Math.toRadians(-45);
        aPlacement.cylinder = false;
        aPlacement.textureScale *= aPlacement.worldWidth;
        mBaseTrayWorldWidth = aPlacement.worldWidth;
        mBaseTrayWidth = aPlacement.width;
        mBaseTrayHeight = aPlacement.height;
        updatePlacementTranslationZ();
    }

    @Override
    public void updatePlacementTranslationZ() {
        getPlacement().translationZ = WidgetPlacement.unitFromMeters(getContext(), R.dimen.tray_world_z) -
                WidgetPlacement.getWindowWorldZMeters(getContext());
    }

    @Override
    public void onResume() {
        super.onResume();

        updateTime();
    }

    @Override
    public void releaseWidget() {
        mWidgetManager.removeUpdateListener(this);
        mWidgetManager.getServicesProvider().getDownloadsManager().removeListener(this);
        mWidgetManager.getServicesProvider().getConnectivityReceiver().removeListener(this);
        PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        SessionStore.get().getBookmarkStore().removeListener(mBookmarksListener);
        mTrayListeners.clear();

        if (mTrayViewModel != null) {
            mTrayViewModel.getIsVisible().removeObserver(mIsVisibleObserver);
            mTrayViewModel = null;
        }

        super.releaseWidget();
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        if (!mWidgetPlacement.visible) {
            mWidgetPlacement.visible = true;
            mWidgetManager.addWidget(this);
        }
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        hideNotifications();

        if (mWidgetPlacement.visible) {
            mWidgetPlacement.visible = false;
            if (aHideFlags == REMOVE_WIDGET) {
                mWidgetManager.removeWidget(this);
            } else {
                mWidgetManager.updateWidget(this);
            }
        }
    }

    @Override
    public void detachFromWindow() {
        hideNotifications();
        
        if (mSession != null) {
            mSession = null;
        }
        if (mAttachedWindow != null) {
            SessionStore.get().getBookmarkStore().removeListener(mBookmarksListener);
        }
        mWidgetPlacement.parentHandle = -1;

        if (mViewModel != null) {
            mViewModel.getCurrentContentType().removeObserver(mCurrentContentTypeObserver);
            mViewModel.getIsPrivateSession().removeObserver(mIsPrivateSession);
            mViewModel = null;
        }

        mIsWindowAttached = false;
        // Drop the stale reference so a later re-attach actually re-parents the
        // tray. Without this, attachToWindow() sees the old window still set,
        // early-returns, and never restores parentHandle -> the control bar
        // silently disappears after returning from the browser.
        mAttachedWindow = null;
    }

    @Override
    public void attachToWindow(@NonNull WindowWidget aWindow) {
        // The window reference can outlive a temporary detach: setMangaParent(null)
        // clears parentHandle while mAttachedWindow still points at the same
        // window. Returning early here would leave the tray world-detached (and,
        // once the manga surface is hidden, invisible). Only skip when the
        // placement really is parented to this window.
        if (mAttachedWindow == aWindow
                && mWidgetPlacement.parentHandle == aWindow.getHandle()) {
            return;
        }
        detachFromWindow();

        mAttachedWindow = aWindow;
        mWidgetPlacement.parentHandle = aWindow.getHandle();

        // ModelView creation and observers setup
        mViewModel = new ViewModelProvider(
                (VRBrowserActivity)getContext(),
                ViewModelProvider.AndroidViewModelFactory.getInstance(((VRBrowserActivity) getContext()).getApplication()))
                .get(String.valueOf(mAttachedWindow.hashCode()), WindowViewModel.class);
        mViewModel.getCurrentContentType().observe((VRBrowserActivity) getContext(), mCurrentContentTypeObserver);
        mViewModel.getIsPrivateSession().observe((VRBrowserActivity)getContext(), mIsPrivateSession);

        mBinding.setViewmodel(mViewModel);

        SessionStore.get().getBookmarkStore().addListener(mBookmarksListener);

        mIsWindowAttached = true;
    }

    private Observer<Windows.ContentType> mCurrentContentTypeObserver = contentType -> {
        // Prevent a race condition in case the animation runs faster than the data binding.
        mBinding.bookmarksButton.setActiveMode(contentType != Windows.ContentType.WEB_CONTENT && contentType != Windows.ContentType.NEW_TAB && contentType != Windows.ContentType.DOWNLOADS);
        mBinding.downloadsButton.setActiveMode(contentType == Windows.ContentType.DOWNLOADS);

        if (contentType == Windows.ContentType.WEB_CONTENT || contentType == Windows.ContentType.NEW_TAB) {
            animateButtonPadding(mBinding.bookmarksButton, mMaxPadding, ICON_ANIMATION_DURATION);
            animateButtonPadding(mBinding.downloadsButton, mMaxPadding, ICON_ANIMATION_DURATION);
        } else if (contentType == Windows.ContentType.DOWNLOADS) {
            animateButtonPadding(mBinding.bookmarksButton, mMaxPadding, ICON_ANIMATION_DURATION);
            animateButtonPadding(mBinding.downloadsButton, mMinPadding, ICON_ANIMATION_DURATION);
        } else {
            animateButtonPadding(mBinding.bookmarksButton, mMinPadding, ICON_ANIMATION_DURATION);
            animateButtonPadding(mBinding.downloadsButton, mMaxPadding, ICON_ANIMATION_DURATION);
        }
    };

    private Observer<ObservableBoolean> mIsPrivateSession = aBoolean -> {
        if (mBinding.privateButton.isHovered() || mViewModel.getIsPrivateSession().getValue().get() == aBoolean.get()) {
            return;
        }
        if (aBoolean.get()) {
            animateButtonPadding(mBinding.privateButton, mMinPadding, ICON_ANIMATION_DURATION);
        } else {
            animateButtonPadding(mBinding.privateButton, mMaxPadding, ICON_ANIMATION_DURATION);
        }
    };

    public void toggleSettingsDialog() {
        toggleSettingsDialog(SettingsView.SettingViewType.MAIN);
    }

    /** Live state of the merged settings dialog. The activity uses this to
     *  decide whether the floating manga panels must step aside. */
    public boolean isSettingsWidgetVisible() {
        return mSettingsWidget != null && mSettingsWidget.isVisible();
    }

    private void createSettingsWidget() {
        assert mSettingsWidget == null : "SettingsWidget already created";
        mSettingsWidget = new SettingsWidget(getContext());
        mSettingsWidget.setVisibilityListener(visible -> {
            mTrayViewModel.setSettingsWidgetVisible(visible);
            // The dialog is hosted by the browser window. In manga mode the
            // browser window is hidden and the floating manga panels sit in
            // front of it, so they would cover the dialog. Let the activity
            // hide them (and keep the host window visible) while it is open,
            // then restore the exact previous UI mode when it closes.
            if (!visible) {
                mSettingsUsedWindow = false;
            }
            ((VRBrowserActivity) getContext()).onMangaSettingsVisibilityChanged(visible);
        });
    }

    /** Attaches the settings widget to a window, falling back to the focused window
     *  (and briefly showing it) when the tray is detached in manga mode. */
    private void attachSettingsToWindow() {
        if (mAttachedWindow != null) {
            mSettingsWidget.attachToWindow(mAttachedWindow);
            return;
        }
        WindowWidget window = ((VRBrowserActivity) getContext()).getFocusedWindow();
        if (window != null) {
            attachToWindow(window);
            window.setVisible(true);
            mSettingsUsedWindow = true;
        }
    }

    public void toggleSettingsDialog(@NonNull SettingsView.SettingViewType settingDialog) {
        if (mSettingsWidget == null) {
            createSettingsWidget();
        }
        if (mAttachedWindow != null) {
            mSettingsWidget.attachToWindow(mAttachedWindow);
        } else {
            attachSettingsToWindow();
        }

        if (mSettingsWidget.isVisible()) {
            mSettingsWidget.hide(KEEP_WIDGET);

        } else {
            mSettingsWidget.show(REQUEST_FOCUS, settingDialog);
        }
        // Re-apply the manga UI mode AFTER the widget state changed, so the
        // live visibility query in applyUiMode() sees the correct value.
        ((VRBrowserActivity) getContext()).onMangaSettingsVisibilityChanged(
                isSettingsWidgetVisible());
    }

    /** Switch an ALREADY VISIBLE settings dialog to a manga sub-page without
     *  re-running show()/updateUI(), which rebuilt the view tree and left the
     *  five manga buttons pointing at stale views. */
    public void showMangaSettingsView(@NonNull SettingsView.SettingViewType settingDialog) {
        android.util.Log.i("MangaSettingsPanel", "tray showMangaSettingsView " + settingDialog);
        if (mSettingsWidget == null || !mSettingsWidget.isVisible()) {
            showSettingsDialog(settingDialog);
            return;
        }
        mSettingsWidget.showView(settingDialog);
    }

    public void showSettingsDialog(@NonNull SettingsView.SettingViewType settingDialog) {
        if (mSettingsWidget == null) {
            createSettingsWidget();
        }
        if (mAttachedWindow != null) {
            mSettingsWidget.attachToWindow(mAttachedWindow);
        } else {
            attachSettingsToWindow();
        }

        mSettingsWidget.show(REQUEST_FOCUS, settingDialog);
        ((VRBrowserActivity) getContext()).onMangaSettingsVisibilityChanged(
                isSettingsWidgetVisible());
    }

    public void setAddWindowVisible(boolean aVisible) {
        mTrayViewModel.setIsMaxWindows(!aVisible);
    }

    public void setTabsWidgetVisible(boolean aVisible) {
        mTrayViewModel.setIsTabsWidgetVisible(aVisible);
    }

    // WidgetManagerDelegate.UpdateListener

    @Override
    public void onWidgetUpdate(Widget aWidget) {
        if (!aWidget.getClass().equals(KeyboardWidget.class)) {
            return;
        }

        mTrayViewModel.setIsKeyboardVisible(aWidget.isVisible());
    }

    // PlatformActivityPlugin.TrayDelegate
    @Override
    public void onAddWindowClicked() {
        mBinding.addwindowButton.performClick();
    }

    @Override
    public void onPrivateBrowsingClicked() {
        mBinding.privateButton.performClick();
    }

    @Override
    public void onBookmarksClicked() {
        mBinding.bookmarksButton.performClick();
    }

    @Override
    public void onDownloadsClicked() {
        mBinding.downloadsButton.performClick();
    }

    @Override
    public void onSettingsClicked() {
        mBinding.settingsButton.performClick();
    }



    public void showTabAddedNotification() {
        showNotification(TAB_ADDED_NOTIFICATION_ID, mBinding.tabsButton, R.string.tab_added_notification);
    }

    public void showTabSentNotification() {
        showNotification(TAB_SENT_NOTIFICATION_ID, mBinding.tabsButton, R.string.tab_sent_notification);
    }

    public void showBookmarkAddedNotification() {
        showNotification(BOOKMARK_ADDED_NOTIFICATION_ID, mBinding.bookmarksButton, R.string.bookmarks_saved_notification);
    }

    public void showWebAppAddedNotification() {
        showNotification(WEB_APP_ADDED_NOTIFICATION_ID, mBinding.bookmarksButton, R.string.web_apps_saved_notification);
    }

    public void showDownloadCompletedNotification(String filename) {
        showNotification(DOWNLOAD_COMPLETED_NOTIFICATION_ID,
                mBinding.downloadsButton,
                getContext().getString(R.string.download_completed_notification, filename));
    }

    private void showNotification(int notificationId, UIButton button, int stringRes) {
        showNotification(notificationId, button, getContext().getString(stringRes));
    }

    private void showNotification(int notificationId, UIButton button, String string) {
        if (isVisible()) {
            NotificationManager.Notification notification = new NotificationManager.Builder(this)
                    .withView(button)
                    .withDensity(R.dimen.tray_tooltip_density)
                    .withString(string)
                    .withPosition(NotificationManager.Notification.TOP)
                    .withMargin(-75.0f)
                    .withZTranslation(20.0f).build();
            NotificationManager.show(notificationId, notification);
        }
    }

    private void hideNotifications() {
        NotificationManager.hideAll();
    }

    private boolean isImmersive() {
        if (mWidgetManager != null && mWidgetManager.isWebXRPresenting()) {
            return true;
        }

        if (mViewModel != null) {
            return mViewModel.getIsFullscreen().getValue().get();
        }
        return false;
    }

    private BookmarksStore.BookmarkListener mBookmarksListener = new BookmarksStore.BookmarkListener() {
        @Override
        public void onBookmarksUpdated() {
            // Nothing to do
        }

        @Override
        public void onBookmarkAdded() {
            mWidgetManager.getWindows().showBookmarkAddedNotification();
        }
    };

    // DownloadsManager.DownloadsListener

    @Override
    public void onDownloadsUpdate(@NonNull List<Download> downloads) {
        long inProgressNum = downloads.stream().filter(item -> item.inProgress()).count();
        mTrayViewModel.setDownloadsNumber((int)inProgressNum);
        if (inProgressNum == 0) {
            mBinding.downloadsButton.setLevel(0);

        } else {
            long size = downloads.stream()
                    .filter(item -> item.getStatus() == Download.RUNNING)
                    .mapToLong(Download::getSizeBytes)
                    .sum();
            long downloaded = downloads.stream().filter(item -> item.getStatus() == Download.RUNNING)
                    .mapToLong(Download::getDownloadedBytes)
                    .sum();
            if (size > 0) {
                long percent = downloaded*100/size;
                mBinding.downloadsButton.setLevel((int) percent * 100);
            }
        }
    }

    @Override
    public void onDownloadCompleted(@NonNull Download download) {
        showDownloadCompletedNotification(download.getFilename());
    }

    private void updateTime() {
        Date currentTime = Calendar.getInstance().getTime();
        String androidDateTime = DateFormat.getTimeFormat(getContext()).format(currentTime);
        String AmPm = "";
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", LocaleUtils.getDisplayLanguage(getContext()).getLocale());
        if (!Character.isDigit(androidDateTime.charAt(androidDateTime.length() - 1))) {
            if (androidDateTime.contains(format.getDateFormatSymbols().getAmPmStrings()[Calendar.AM])) {
                AmPm = " " + format.getDateFormatSymbols().getAmPmStrings()[Calendar.AM];
            } else {
                AmPm = " " + format.getDateFormatSymbols().getAmPmStrings()[Calendar.PM];
            }
            androidDateTime = androidDateTime.replace(AmPm, "");
        }
        mTrayViewModel.setTime(androidDateTime);
        mTrayViewModel.setPm(AmPm);
    }

    @Override
    public void OnConnectivityChanged(boolean connected) {
        mTrayViewModel.setWifiConnected(connected);
        if (!connected) {
            mLastWifiLevel = -1;
            mWifiSSID = getContext().getString(R.string.tray_wifi_no_connection);
        }
    }


    private boolean updateWifiIcon(final int level) {
        try {
            Drawable icon = mBinding.wifiIcon.getDrawable();
            if (icon == null) {
                return false;
            }
            LayerDrawable layerDrawable = (LayerDrawable)icon;

            VectorDrawable drawable = (VectorDrawable) layerDrawable.findDrawableByLayerId(R.id.wifi_layer1);
            if (drawable != null) {
                drawable.setAlpha(level >= 0 ? 255 : 0);
            }
            drawable = (VectorDrawable) layerDrawable.findDrawableByLayerId(R.id.wifi_layer2);
            if (drawable != null) {
                drawable.setAlpha(level >= 1 ? 255 : 0);
            }
            drawable = (VectorDrawable) layerDrawable.findDrawableByLayerId(R.id.wifi_layer3);
            if (drawable != null) {
                drawable.setAlpha(level >= 2 ? 255 : 0);
            }
            drawable = (VectorDrawable) layerDrawable.findDrawableByLayerId(R.id.wifi_layer4);
            if (drawable != null) {
                drawable.setAlpha(level >= 3 ? 255 : 0);
            }

            return true;
        } catch (Exception e) {
            Log.e(LOGTAG, "Failed to update wifi icon");
        }

        return false;
    }

    private int getWifiSignalStrength(@NonNull WifiManager wifiManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities netCaps = cm.getNetworkCapabilities(n);
            return netCaps != null ? netCaps.getSignalStrength() : 0;
        } else {
            return wifiManager.getConnectionInfo().getRssi();
        }
    }
    
    private void updateWifi() {
        // We are collecting sensitive data here, so we should ensure the user granted permissions.
        if (!(SettingsStore.getInstance(getContext()).isTermsServiceAccepted() &&
                SettingsStore.getInstance(getContext()).isPrivacyPolicyAccepted())) {
            return;
        }

        if ((mTrayViewModel.getWifiConnected().getValue() != null) && mTrayViewModel.getWifiConnected().getValue().get()) {
            WifiManager wifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                int level;
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                    level = wifiManager.calculateSignalLevel(getWifiSignalStrength(wifiManager));
                } else {
                    level = WifiManager.calculateSignalLevel(getWifiSignalStrength(wifiManager), 4);
                }
                if (level != mLastWifiLevel && updateWifiIcon(level)) {
                    mLastWifiLevel = level;
                }
                // Getting the SSID, even if it's just to show it to the user, is considered
                // "recollection of personal information" by Huawei store in Mainland China so avoid
                // getting it.
                if (DeviceType.isHVRBuild() && DeviceType.getStoreType() == DeviceType.StoreType.MAINLAND_CHINA) {
                    mWifiSSID = getContext().getString(R.string.tray_wifi_unavailable_ssid);
                } else {
                    WifiInfo currentWifi = wifiManager.getConnectionInfo();
                    if (currentWifi != null) {
                        mWifiSSID = currentWifi.getSSID().replaceAll("\"", "");

                    } else {
                        mWifiSSID = getContext().getString(R.string.tray_wifi_no_connection);
                    }
                }
            }
        }
    }

    private int toBatteryLevel(final int level) {
        if (level > 75) {
            return R.drawable.ic_icon_statusbar_indicator;
        } else if (level > 50) {
            return R.drawable.ic_icon_statusbar_indicator_75;
        } else if (level > 25) {
            return R.drawable.ic_icon_statusbar_indicator_50;
        } else if (level > 10) {
            return R.drawable.ic_icon_statusbar_indicator_25;
        }
        return R.drawable.ic_icon_statusbar_indicator_10;
    }

    public void setBatteryLevels(final int headset, final boolean isCharging, final int leftController, final int rightController) {
        updateWifi();
        if (DeviceType.getType() == DeviceType.OculusQuest) {
            mTrayViewModel.setLeftControllerIcon(R.drawable.ic_icon_statusbar_leftcontroller);
            mTrayViewModel.setRightControllerIcon(R.drawable.ic_icon_statusbar_rightcontroller);
        }
        mTrayViewModel.setHeadsetIcon(isCharging ? R.drawable.ic_icon_statusbar_headset_charging : R.drawable.ic_icon_statusbar_headset_normal);
        mTrayViewModel.setHeadsetBatteryLevel(toBatteryLevel(headset));

        mHeadsetBatteryLevel = headset;
        mLeftControllerBatteryLevel = leftController;
        mRightControllerBatteryLevel = rightController;

        if (leftController < 0) {
            mBinding.leftController.setVisibility(View.GONE);
        } else {
            mBinding.leftController.setVisibility(View.VISIBLE);
            mTrayViewModel.setLeftControllerBatteryLevel(toBatteryLevel(leftController));
        }
        if (rightController < 0) {
            mBinding.rightController.setVisibility(View.GONE);
        } else {
            mBinding.rightController.setVisibility(View.VISIBLE);
            mTrayViewModel.setRightControllerBatteryLevel(toBatteryLevel(rightController));
        }
    }

    @NonNull
    private String getFormattedDate() {
        java.text.DateFormat format = SimpleDateFormat.getDateInstance(
                SimpleDateFormat.FULL, LocaleUtils.getDisplayLanguage(getContext()).getLocale());
        return format.format(new Date());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (Objects.equals(key, getContext().getString(R.string.settings_key_tabs_location))) {
            int value = sharedPreferences.getInt(key, SettingsStore.TABS_LOCATION_TRAY);
            mTrayViewModel.setTabsButtonInTray(value == SettingsStore.TABS_LOCATION_TRAY);
            updateUI();
        }
    }
}
