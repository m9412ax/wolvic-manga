/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * All manga settings in one reusable view. Embedded in the second tab of the
 * stock Wolvic settings dialog and (for backwards compatibility) in the shelf's
 * settings page, so there is only ever one keybinding editor implementation.
 */
public class MangaSettingsPanel extends LinearLayout {

    /** Opens one of the manga sub pages. Keys are interpreted by the host. */
    public interface SubPageOpener {
        void open(String key);
    }

    private final MangaSettings mSettings;
    private SubPageOpener mOpener;

    private TextView mStatusView;
    private Button mCaptureToggle;
    private Button[] mScopeButtons = new Button[2];
    private Button[] mActionButtons;

    public MangaSettingsPanel(Context context) {
        super(context);
        mSettings = MangaSettings.get(context);
        mSettings.ensureDefaultBindings();
        setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        rebuild();
    }

    public void setSubPageOpener(SubPageOpener opener) {
        mOpener = opener;
    }

    /** Stop an in-progress key capture (e.g. when the settings dialog closes). */
    public void cancelCapture() {
        MangaKeyCapture.reset();
    }

    @Override
    protected void onDetachedFromWindow() {
        // SettingsWidget.updateUI() rebuilds its entire view tree and detaches
        // this panel. Do NOT disarm capture here: that silently killed a running
        // capture in earlier builds. The static state survives the rebuild.
        super.onDetachedFromWindow();
    }

    public void rebuild() {
        removeAllViews();
        if (MangaKeyCapture.addFlow) {
            renderAddFlow();
        } else {
            // Keep this on the first screen: options buried at the bottom of
            // the VR scroll view are effectively unreachable.
            buildScrollSpeedSection();
            buildKeybindingSection();
            buildMangaSections();
        }
    }

    /** Continuous-mode thumbstick scroll speed. Read live by the reader, so
     *  changing it here takes effect immediately without reopening the page. */
    private void buildScrollSpeedSection() {
        final float speed = mSettings.getFloat(MangaSettings.READER_SCROLL_SPEED, 2.0f);
        addInfo("连续模式 · 摇杆滚动速度",
                "摇杆上下滑动时图片的滚动倍率（仅纵向连续模式生效）。当前 "
                        + speed + " 倍，数值越大滚动越快。");
        LinearLayout row = addRow();
        addButton(row, "－ 减慢", v -> {
            float next = Math.max(0.25f, Math.round((speed - 0.25f) * 100f) / 100f);
            mSettings.put(MangaSettings.READER_SCROLL_SPEED, next);
            rebuild();
        });
        addButton(row, "＋ 加快", v -> {
            float next = Math.min(8.0f, Math.round((speed + 0.25f) * 100f) / 100f);
            mSettings.put(MangaSettings.READER_SCROLL_SPEED, next);
            rebuild();
        });
        addButton(row, "恢复默认", v -> {
            mSettings.put(MangaSettings.READER_SCROLL_SPEED, 2.0f);
            rebuild();
        });
    }

    // --------------------------------------------------- keybinding settings

    private void buildKeybindingSection() {
        addInfo("自定义快捷键",
                "可添加任意数量；支持单键或组合键，作用范围可选「全局」或「仅看图」。");
        addInfo("两种缩放的区别",
                "画布放大/缩小(持久)：改变整个窗口的大小，切换图片、下次打开都保持；"
                        + "图片放大/缩小(临时)：只缩放画面内容，窗口大小不变，切换图片后恢复默认。");

        List<MangaSettings.KeyBinding> bindings = mSettings.getBindings();
        if (bindings.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("（暂无快捷键，点击下方添加）");
            empty.setTextColor(0xFF808088);
            empty.setTextSize(16f);
            addView(empty);
        }
        for (int i = 0; i < bindings.size(); i++) {
            final int index = i;
            MangaSettings.KeyBinding b = bindings.get(i);
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(getContext());
            StringBuilder keys = new StringBuilder();
            for (int k : b.keys) {
                if (keys.length() > 0) {
                    keys.append(" + ");
                }
                keys.append(KeyEvent.keyCodeToString(k)).append("(").append(k).append(")");
            }
            label.setText(MangaSettings.actionLabel(b.action) + "  |  "
                    + keys + "  |  " + MangaSettings.scopeLabel(b.scope));
            label.setTextSize(16f);
            label.setTextColor(0xFFE0E0E8);
            label.setPadding(0, dp(4), dp(8), dp(4));
            line.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(getContext());
            remove.setText("删除");
            remove.setAllCaps(false);
            remove.setTextSize(15f);
            remove.setTextColor(0xFFFFFFFF);
            remove.setBackgroundColor(0xFF3A2226);
            remove.setOnClickListener(v -> {
                mSettings.removeBinding(index);
                rebuild();
            });
            line.addView(remove);
            addView(line, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        LinearLayout addLine = addRow();
        addButton(addLine, "＋ 添加快捷键", v -> {
            MangaKeyCapture.beginAddFlow();
            rebuild();
        });
    }

    /** The add-shortcut editor. Capture controls are deliberately the very
     *  first thing on screen, followed by scope, then action, then save. */
    private void renderAddFlow() {
        MangaKeyCapture.setRefresh(this::updateCaptureStatus);

        LinearLayout top = row(this);
        mCaptureToggle = bigButton("▶ 开始捕获");
        mCaptureToggle.setOnClickListener(v -> {
            if (MangaKeyCapture.active) {
                MangaKeyCapture.stopCapture();
            } else {
                MangaKeyCapture.restartCapture();
            }
            updateCaptureStatus();
        });
        top.addView(mCaptureToggle, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 2f));
        Button clear = bigButton("清空按键");
        clear.setOnClickListener(v -> {
            MangaKeyCapture.keys.clear();
            updateCaptureStatus();
        });
        top.addView(clear, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        mStatusView = new TextView(getContext());
        mStatusView.setTextSize(17f);
        mStatusView.setTextColor(0xFFFFD080);
        mStatusView.setBackgroundColor(0xFF202430);
        mStatusView.setPadding(dp(10), dp(10), dp(10), dp(10));
        addView(mStatusView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addInfo("① 按键捕获",
                "点最上方「开始捕获」→ 按 1 个键即单键，按住多个键即组合键 → 捕获完点「停止捕获」。支持扳机 / 侧握 / A·B·X·Y / 摇杆按压 / 菜单键 / 系统按键。");

        // Scope sits immediately after capture so it is visible on the first
        // screen. Two explicit buttons with a clear selected state.
        addView(caption("② 选择生效范围（点击后高亮）"));
        LinearLayout scopeRow = row(this);
        mScopeButtons[0] = bigButton("仅看图生效");
        mScopeButtons[0].setOnClickListener(v -> {
            MangaKeyCapture.flowScope = MangaSettings.SCOPE_READER;
            updateCaptureStatus();
        });
        mScopeButtons[1] = bigButton("全局生效");
        mScopeButtons[1].setOnClickListener(v -> {
            MangaKeyCapture.flowScope = MangaSettings.SCOPE_GLOBAL;
            updateCaptureStatus();
        });
        for (Button b : mScopeButtons) {
            scopeRow.addView(b, new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f));
        }

        addView(caption("③ 选择功能（点击后高亮）"));
        String[] actions = MangaSettings.allActions();
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        addView(grid, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mActionButtons = new Button[actions.length];
        for (int r = 0; r * 3 < actions.length; r++) {
            LinearLayout line = row(grid);
            for (int col = 0; col < 3; col++) {
                int i = r * 3 + col;
                if (i >= actions.length) {
                    line.addView(new View(getContext()),
                            new LinearLayout.LayoutParams(0, 1, 1f));
                    continue;
                }
                final String act = actions[i];
                final Button b = gridButton(MangaSettings.actionLabel(act));
                b.setOnClickListener(v -> {
                    MangaKeyCapture.flowAction = act;
                    updateCaptureStatus();
                });
                line.addView(b, new LinearLayout.LayoutParams(
                        0, LayoutParams.WRAP_CONTENT, 1f));
                mActionButtons[i] = b;
            }
        }

        addView(caption("④ 保存"));
        LinearLayout confirmRow = row(this);
        addButton(confirmRow, "确认保存", v -> {
            if (MangaKeyCapture.keys.isEmpty()) {
                updateCaptureStatus();
                mStatusView.setText(mStatusView.getText()
                        + "\n⚠ 请先点「开始捕获」并按下至少一个键，再点「停止捕获」");
                return;
            }
            mSettings.addBinding(new MangaSettings.KeyBinding(
                    MangaKeyCapture.flowAction,
                    MangaKeyCapture.capturedKeys(),
                    MangaKeyCapture.flowScope));
            MangaKeyCapture.reset();
            rebuild();
        });
        addButton(confirmRow, "取消", v -> {
            MangaKeyCapture.reset();
            rebuild();
        });
        updateCaptureStatus();
    }

    /** Refresh the always-visible status line and every selected state. This is
     *  also the capture callback, so a key press is visible immediately. */
    private void updateCaptureStatus() {
        if (mStatusView == null) {
            return;
        }
        StringBuilder key = new StringBuilder();
        for (Integer code : MangaKeyCapture.keys) {
            if (key.length() > 0) {
                key.append("  +  ");
            }
            key.append(KeyEvent.keyCodeToString(code))
                    .append(" (").append(code).append(")");
        }
        String keyText = key.length() == 0 ? "（尚未按键）" : key.toString();
        String state = MangaKeyCapture.active
                ? "● 捕获中 — 现在按手柄键" : "○ 捕获已停止 / 未开始";
        mStatusView.setText("状态：" + state
                + "\n已捕获：" + keyText
                + "\n功能：" + MangaSettings.actionLabel(MangaKeyCapture.flowAction)
                + "    范围：" + MangaSettings.scopeLabel(MangaKeyCapture.flowScope));
        if (mCaptureToggle != null) {
            mCaptureToggle.setText(MangaKeyCapture.active ? "■ 停止捕获" : "▶ 开始捕获");
            mCaptureToggle.setBackgroundColor(
                    MangaKeyCapture.active ? 0xFF8A3A2A : 0xFF2A6A3A);
        }
        if (mScopeButtons[0] != null && mScopeButtons[1] != null) {
            mScopeButtons[0].setBackgroundColor(MangaKeyCapture.flowScope == MangaSettings.SCOPE_READER
                    ? 0xFF3A5A8A : 0xFF2A2A33);
            mScopeButtons[1].setBackgroundColor(MangaKeyCapture.flowScope == MangaSettings.SCOPE_GLOBAL
                    ? 0xFF3A5A8A : 0xFF2A2A33);
        }
        if (mActionButtons != null) {
            String[] actions = MangaSettings.allActions();
            for (int i = 0; i < mActionButtons.length && i < actions.length; i++) {
                if (mActionButtons[i] != null) {
                    mActionButtons[i].setBackgroundColor(
                            actions[i].equals(MangaKeyCapture.flowAction)
                                    ? 0xFF3A5A8A : 0xFF2A2A33);
                }
            }
        }
    }

    private TextView caption(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(17f);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** Horizontal row with a definite width, optionally inside another parent. */
    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return r;
    }

    private Button gridButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(48));
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackgroundColor(0xFF2A2A33);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private Button bigButton(String text) {
        Button b = gridButton(text);
        b.setTextSize(17f);
        b.setMinHeight(dp(64));
        return b;
    }

    // ------------------------------------------------------ other manga settings

    private void buildMangaSections() {
        addInfo("漫画设置", "根目录：" + mSettings.text(MangaSettings.SOURCE_ROOT));

        LinearLayout sub = addRow();
        addButton(sub, "阅读", v -> open("READER"));
        addButton(sub, "图源", v -> open("SOURCES"));
        addButton(sub, "下载", v -> open("DOWNLOADS"));
        addButton(sub, "备份与进度", v -> open("BACKUP"));
        addButton(sub, "高级", v -> open("ADVANCED"));

        addInfo("漫画图源", "本地 / SMB / WebDAV / 扩展 APK 配置页");
        LinearLayout sources = addRow();
        addButton(sources, "本地文件夹", v -> open("LOCAL"));
        addButton(sources, "SMB 共享", v -> open("SMB"));
        addButton(sources, "网盘 WebDAV", v -> open("WEBDAV"));
        addButton(sources, "漫画源扩展", v -> open("EXTENSIONS"));
    }

    private void open(String key) {
        if (mOpener != null) {
            mOpener.open(key);
        }
    }

    // ------------------------------------------------------------- helpers

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addInfo(String title, String body) {
        TextView caption = new TextView(getContext());
        caption.setText(title);
        caption.setTextSize(21f);
        caption.setTextColor(0xFFFFFFFF);
        caption.setPadding(0, dp(10), 0, dp(2));
        addView(caption);

        if (body != null && !body.isEmpty()) {
            TextView text = new TextView(getContext());
            text.setText(body);
            text.setTextSize(16f);
            text.setTextColor(0xFFB0B0B8);
            text.setPadding(0, 0, 0, dp(8));
            addView(text);
        }
    }

    private LinearLayout addRow() {
        return row(this);
    }

    private Button addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15f);
        button.setTextColor(0xFFFFFFFF);
        button.setBackgroundColor(0xFF2A2A33);
        button.setMinWidth(dp(100));
        button.setMinHeight(dp(50));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(button, lp);
        return button;
    }
}
