/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.igalia.wolvic.R;
import com.igalia.wolvic.manga.MangaBackup;
import com.igalia.wolvic.manga.MangaDownloader;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.ui.views.settings.ButtonSetting;
import com.igalia.wolvic.ui.views.settings.RadioGroupSetting;
import com.igalia.wolvic.ui.views.settings.SingleEditSetting;
import com.igalia.wolvic.ui.views.settings.SwitchSetting;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;

import java.util.ArrayList;
import java.util.List;

/**
 * The five manga settings groups displayed by Wolvic's stock SettingsWidget.
 * Every row is a stock settings control (SwitchSetting / RadioGroupSetting /
 * SingleEditSetting / ButtonSetting) and every value is persisted through
 * MangaSettings and consumed by the shelf / reader / sources.
 */
public class MangaOptionsView extends SettingsView
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "MangaOptions";

    private final SettingViewType mGroup;
    private final List<String> mGroupKeys = new ArrayList<>();
    private MangaSettings mSettings;
    private LinearLayout mSections;
    private TextView mStatus;
    private boolean mBuilding;
    private boolean mRegistered;

    public MangaOptionsView(@NonNull Context context, WidgetManagerDelegate manager,
                            SettingViewType group) {
        super(context, manager);
        mGroup = group;
        initialize();
    }

    @Override
    protected SettingViewType getType() {
        return mGroup;
    }

    private void initialize() {
        mSettings = MangaSettings.get(getContext());
        LayoutInflater.from(getContext()).inflate(R.layout.options_manga, this, true);
        mSections = findViewById(R.id.manga_sections);
        mScrollbar = findViewById(R.id.scrollbar);

        SettingsHeader header = findViewById(R.id.header_layout);
        header.setTitle(title());
        header.setBackClickListener(view -> onDismiss());

        SettingsFooter footer = findViewById(R.id.footer_layout);
        footer.setFooterButtonClickListener(view -> {
            resetGroup();
            buildRows();
            setStatus("已恢复「" + title() + "」的默认设置");
        });

        mStatus = new TextView(getContext());
        mStatus.setTextColor(0xFF9A9AA8);
        mStatus.setTextSize(13f);
        mStatus.setPadding(0, 12, 0, 12);
        buildRows();
        mSettings.register(this);
        mRegistered = true;
    }

    private String title() {
        switch (mGroup) {
            case MANGA_SOURCES:
                return "漫画 · 图源";
            case MANGA_DOWNLOADS:
                return "漫画 · 下载";
            case MANGA_BACKUP:
                return "漫画 · 备份与进度";
            case MANGA_ADVANCED:
                return "漫画 · 高级";
            case MANGA_READER:
            default:
                return "漫画 · 阅读";
        }
    }

    // ------------------------------------------------------------- groups

    private void buildRows() {
        if (mSections == null) {
            return;
        }
        mBuilding = true;
        mSections.removeAllViews();
        mGroupKeys.clear();
        mStatus = new TextView(getContext());
        mStatus.setTextColor(0xFF9A9AA8);
        mStatus.setTextSize(13f);
        mStatus.setPadding(0, 12, 0, 12);

        switch (mGroup) {
            case MANGA_READER:
                buildReader();
                break;
            case MANGA_SOURCES:
                buildSources();
                break;
            case MANGA_DOWNLOADS:
                buildDownloads();
                break;
            case MANGA_BACKUP:
                buildBackup();
                break;
            case MANGA_ADVANCED:
                buildAdvanced();
                break;
            default:
                break;
        }
        mSections.addView(mStatus);
        mBuilding = false;
        Log.i(TAG, "built group=" + mGroup + " controls=" + mGroupKeys.size());
    }

    private void buildReader() {
        section("翻页与阅读方向");
        radio("阅读方向", MangaSettings.READER_DIRECTION,
                new String[]{"从左到右 LTR", "从右到左 RTL", "纵向连续 Webtoon"},
                new int[]{0, 1, 2});
        switchRow("点击左右区域翻页", MangaSettings.READER_TAP_ZONES);
        switchRow("音量键翻页", MangaSettings.READER_VOLUME_KEYS);
        switchRow("自动翻页", MangaSettings.READER_AUTO_ADVANCE);
        radio("自动翻页间隔", MangaSettings.READER_AUTO_ADVANCE_SEC,
                new String[]{"4 秒", "8 秒", "15 秒", "30 秒"}, new int[]{4, 8, 15, 30});
        radio("翻页动画", MangaSettings.READER_TRANSITION,
                new String[]{"无", "淡入", "滑动"}, new int[]{0, 1, 2});

        section("显示与图像");
        radio("缩放模式", MangaSettings.READER_SCALE,
                new String[]{"适应屏幕", "拉伸填满", "居中裁剪", "原始尺寸"},
                new int[]{0, 1, 2, 3});
        radio("阅读背景", MangaSettings.READER_BACKGROUND,
                new String[]{"黑色", "白色", "灰色"}, new int[]{0, 1, 2});
        radio("画面亮度", MangaSettings.READER_BRIGHTNESS,
                new String[]{"40%", "60%", "80%", "100%"}, new int[]{40, 60, 80, 100});
        switchRow("双击缩放", MangaSettings.READER_DOUBLE_TAP_ZOOM);
        switchRow("自动裁切白边", MangaSettings.READER_CROP_BORDERS);
        switchRow("显示页码", MangaSettings.READER_SHOW_PAGE_NUMBER);
        switchRow("保持屏幕常亮", MangaSettings.READER_KEEP_AWAKE);
        radio("预加载页数", MangaSettings.READER_PRELOAD,
                new String[]{"不预载", "1 页", "2 页", "4 页"}, new int[]{0, 1, 2, 4});

        section("视频与媒体");
        switchRow("视频自动播放", MangaSettings.READER_VIDEO_AUTOPLAY);
        switchRow("播完自动切到下一页", MangaSettings.READER_VIDEO_NEXT_ON_END);
        switchRow("恢复视频播放进度", MangaSettings.READER_VIDEO_RESUME);
    }

    private void buildSources() {
        section("本地书架");
        edit("漫画根目录", MangaSettings.SOURCE_ROOT);
        switchRow("自然排序（第2页在第10页前）", MangaSettings.SOURCE_NATURAL_SORT);
        switchRow("包含隐藏文件", MangaSettings.SOURCE_INCLUDE_HIDDEN);
        switchRow("视频优先播放", MangaSettings.SOURCE_VIDEO_FIRST);
        switchRow("文件列表中视频置顶", MangaSettings.SORT_VIDEO_FIRST);
        switchRow("递归扫描子目录", MangaSettings.SOURCE_RECURSIVE);
        radio("书架列数", MangaSettings.SOURCE_SPAN_COUNT,
                new String[]{"2 列", "3 列", "4 列", "5 列", "6 列"},
                new int[]{2, 3, 4, 5, 6});
        radio("书架视图大小（窗口与封面）", MangaSettings.LIBRARY_SIZE,
                new String[]{"小", "中（默认）", "大"},
                new int[]{0, 1, 2});
        radio("封面解码尺寸", MangaSettings.SOURCE_THUMB_PX,
                new String[]{"320 px", "480 px", "720 px", "1080 px"},
                new int[]{320, 480, 720, 1080});

        section("文件格式（英文逗号分隔）");
        edit("图片扩展名", MangaSettings.SOURCE_IMAGE_FORMATS);
        edit("视频扩展名", MangaSettings.SOURCE_VIDEO_FORMATS);

        section("WebDAV 网盘");
        edit("WebDAV 地址", MangaSettings.WEBDAV_URL);
        edit("用户名", MangaSettings.WEBDAV_USER);
        edit("密码", MangaSettings.WEBDAV_PASS);

        section("SMB 共享");
        edit("主机 / IP", MangaSettings.SMB_HOST);
        edit("共享名", MangaSettings.SMB_SHARE);
        edit("域（可留空）", MangaSettings.SMB_DOMAIN);
        edit("用户名", MangaSettings.SMB_USER);
        edit("密码", MangaSettings.SMB_PASS);
        edit("远程路径", MangaSettings.SMB_PATH);
        edit("本地挂载点（可直接阅读）", MangaSettings.SMB_MOUNT);
    }

    private void buildDownloads() {
        section("下载队列");
        edit("本地下载目录", MangaSettings.DOWNLOAD_DIR);
        switchRow("仅 WiFi 下载", MangaSettings.DOWNLOAD_ONLY_WIFI);
        radio("并发数", MangaSettings.DOWNLOAD_PARALLEL,
                new String[]{"1", "2", "3", "4"}, new int[]{1, 2, 3, 4});
        radio("失败重试次数", MangaSettings.DOWNLOAD_RETRY,
                new String[]{"0", "1", "2", "3", "5"}, new int[]{0, 1, 2, 3, 5});
        switchRow("自动下载下一话", MangaSettings.DOWNLOAD_AUTO_NEXT);
        switchRow("允许清理已读章节缓存", MangaSettings.DOWNLOAD_DELETE_READ);

        section("维护");
        button("清理已读章节缓存", "立即清理", () -> {
            int removed = new MangaDownloader(getContext()).purgeReadChapters();
            setStatus("已清理 " + removed + " 个已读章节");
        });
        button("查看下载目录", "刷新状态", () -> {
            java.io.File dir = new java.io.File(mSettings.text(MangaSettings.DOWNLOAD_DIR));
            java.io.File[] chapters = dir.listFiles(java.io.File::isDirectory);
            android.content.SharedPreferences queue = mSettings.downloadQueuePrefs();
            setStatus(dir.getAbsolutePath() + " · 章节 "
                    + (chapters == null ? 0 : chapters.length)
                    + " · 上次任务 " + queue.getString("last", "无")
                    + " 完成 " + queue.getInt("completed", 0)
                    + "/失败 " + queue.getInt("failed", 0));
        });
    }

    private void buildBackup() {
        section("阅读进度");
        switchRow("保存阅读进度", MangaSettings.PROGRESS_ENABLED);
        switchRow("保存视频播放位置", MangaSettings.PROGRESS_VIDEO);
        radio("页面缓存上限", MangaSettings.CACHE_MB,
                new String[]{"64 MB", "128 MB", "256 MB", "512 MB"},
                new int[]{64, 128, 256, 512});
        radio("在线章节磁盘缓存上限", MangaSettings.SOURCE_CACHE_MB,
                new String[]{"256 MB", "512 MB", "1024 MB", "2048 MB"},
                new int[]{256, 512, 1024, 2048});
        radio("图源 DNS over HTTPS（重启生效）", MangaSettings.NET_DOH,
                new String[]{"关闭", "Cloudflare", "Google", "阿里 DNS", "DNSPod"},
                new int[]{-1, 1, 2, 5, 6});

        section("备份文件");
        button("导出设置与进度", "导出", () -> {
            try {
                java.io.File file = MangaBackup.exportTo(getContext(), MangaBackup.defaultFile());
                setStatus("已导出：" + file.getAbsolutePath());
                Toast.makeText(getContext(), "备份已导出", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                setStatus("导出失败：" + e.getMessage());
            }
        });
        button("从备份导入", "导入", () -> {
            try {
                java.io.File file = MangaBackup.defaultFile();
                if (!file.exists()) {
                    setStatus("备份文件不存在：" + file.getAbsolutePath());
                    return;
                }
                int count = MangaBackup.importFrom(getContext(), file);
                setStatus("已导入 " + count + " 项");
                Toast.makeText(getContext(), "已导入 " + count + " 项", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                setStatus("导入失败：" + e.getMessage());
            }
        });
        button("清除全部阅读进度", "清除", () -> {
            int count = MangaBackup.clearProgress(getContext());
            setStatus("已清除 " + count + " 条进度记录");
        });
    }

    private void buildAdvanced() {
        section("性能");
        switchRow("阅读器硬件加速", MangaSettings.ADV_HW_ACCEL);
        radio("页面最大解码边长", MangaSettings.ADV_MAX_DECODE_PX,
                new String[]{"800 px", "1200 px", "1600 px", "2400 px", "3200 px"},
                new int[]{800, 1200, 1600, 2400, 3200});
        switchRow("输出诊断日志", MangaSettings.ADV_LOGGING);

        section("维护");
        button("重置全部漫画设置", "重置", () -> {
            mSettings.resetAll();
            setStatus("全部漫画设置已恢复默认");
        });
        button("当前配置统计", "统计", () -> {
            int keys = mSettings.prefs().getAll().size();
            int progress = mSettings.progressPrefs().getAll().size();
            setStatus("设置项 " + keys + " · 进度记录 " + progress);
        });
    }

    // -------------------------------------------------------------- rows

    private void section(String title) {
        TextView view = (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.manga_row_section, mSections, false);
        view.setText(title);
        mSections.addView(view);
    }

    private void label(View row, String text) {
        TextView label = row.findViewById(R.id.mangaRowLabel);
        if (label != null) {
            label.setText(text);
        }
    }

    private void switchRow(String title, String key) {
        mGroupKeys.add(key);
        View row = LayoutInflater.from(getContext())
                .inflate(R.layout.manga_row_switch, mSections, false);
        label(row, title);
        SwitchSetting control = row.findViewById(R.id.mangaSwitch);
        control.setDescription(title);
        control.setValue(mSettings.bool(key), false);
        control.setOnCheckedChangeListener((button, checked, apply) -> {
            if (apply) {
                mSettings.put(key, checked);
                Log.i(TAG, key + "=" + checked);
            }
        });
        mSections.addView(row);
    }

    private void radio(String title, String key, String[] labels, int[] values) {
        mGroupKeys.add(key);
        View row = LayoutInflater.from(getContext())
                .inflate(R.layout.manga_row_radio, mSections, false);
        label(row, title);
        RadioGroupSetting control = row.findViewById(R.id.mangaRadio);
        control.setOptions(labels);
        int checked = 0;
        int current = mSettings.integer(key);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }
        control.setChecked(checked, false);
        control.setOnCheckedChangeListener((group, checkedId, apply) -> {
            if (apply && checkedId >= 0 && checkedId < values.length) {
                mSettings.put(key, values[checkedId]);
                Log.i(TAG, key + "=" + values[checkedId]);
            }
        });
        mSections.addView(row);
    }

    private void edit(String title, String key) {
        mGroupKeys.add(key);
        View row = LayoutInflater.from(getContext())
                .inflate(R.layout.manga_row_edit, mSections, false);
        label(row, title);
        final SingleEditSetting control = row.findViewById(R.id.mangaEdit);
        String fallback = MangaSettings.defaultString(key);
        control.setDefaultFirstValue(fallback);
        control.setHint1(fallback);
        control.setFirstText(mSettings.text(key));
        control.setOnSaveClickedListener(view -> {
            mSettings.put(key, control.getFirstText());
            Log.i(TAG, key + " updated");
        });
        mSections.addView(row);
    }

    private void button(String title, String text, Runnable action) {
        View row = LayoutInflater.from(getContext())
                .inflate(R.layout.manga_row_button, mSections, false);
        label(row, title);
        ButtonSetting control = row.findViewById(R.id.mangaButton);
        control.setDescription(title);
        control.setButtonText(text);
        control.setOnClickListener(view -> action.run());
        mSections.addView(row);
    }

    private void setStatus(String text) {
        if (mStatus != null) {
            mStatus.setText(text);
        }
        Log.i(TAG, text);
    }

    private void resetGroup() {
        SharedPreferences.Editor editor = mSettings.prefs().edit();
        for (String key : mGroupKeys) {
            editor.remove(key);
        }
        editor.apply();
    }

    @Override
    public void onShown() {
        buildRows();
        super.onShown();
    }

    @Override
    public void onHidden() {
        if (mRegistered) {
            mSettings.unregister(this);
            mRegistered = false;
        }
        super.onHidden();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!mBuilding && mSections != null) {
            buildRows();
        }
    }
}
