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

    private String mCaptureAction = MangaSettings.ACTION_NEXT_PAGE;
    private int mCaptureScope = MangaSettings.SCOPE_READER;
    private int mCaptureKey = 0;
    private TextView mStatusView;
    private Button[] mScopeButtons = new Button[2];

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
        MangaKeyCapture.stop();
    }

    @Override
    protected void onDetachedFromWindow() {
        MangaKeyCapture.stop();
        super.onDetachedFromWindow();
    }

    public void rebuild() {
        MangaKeyCapture.stop();
        removeAllViews();
        buildKeybindingSection();
        buildMangaSections();
    }

    // --------------------------------------------------- keybinding settings

    private void buildKeybindingSection() {
        addInfo("自定义快捷键",
                "可添加任意数量；支持「全局」或「仅看图」生效。添加时按下手柄任意键即可捕获并显示键值。");
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
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(getContext());
            label.setText(MangaSettings.actionLabel(b.action) + "  |  "
                    + KeyEvent.keyCodeToString(b.keyCode) + " (" + b.keyCode + ")  |  "
                    + MangaSettings.scopeLabel(b.scope));
            label.setTextSize(16f);
            label.setTextColor(0xFFE0E0E8);
            label.setPadding(0, dp(4), dp(8), dp(4));
            row.addView(label);
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
            row.addView(remove);
            addView(row);
        }
        LinearLayout addRow = addRow();
        addButton(addRow, "添加快捷键", v -> startCaptureFlow());
    }

    private void startCaptureFlow() {
        mCaptureAction = MangaSettings.ACTION_NEXT_PAGE;
        mCaptureScope = MangaSettings.SCOPE_READER;
        mCaptureKey = 0;
        removeAllViews();

        addInfo("添加快捷键",
                "① 选择功能 → ② 选择生效范围 → ③ 点「开始捕获」再按任意手柄键 → ④ 确认保存");

        // Always-visible status line: the user can tell at a glance whether a
        // selection registered and whether capture is actually armed.
        mStatusView = new TextView(getContext());
        mStatusView.setTextSize(17f);
        mStatusView.setTextColor(0xFFFFD080);
        mStatusView.setBackgroundColor(0xFF202430);
        mStatusView.setPadding(dp(10), dp(8), dp(10), dp(8));
        addView(mStatusView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        updateCaptureStatus("尚未开始捕获");

        TextView actionCaption = caption("① 选择功能（点击后高亮）");
        addView(actionCaption);

        // A 3-column grid instead of one 9-button row: the old row was ~900dp
        // wide, overflowed the settings window and could not be scrolled.
        String[] actions = MangaSettings.allActions();
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        addView(grid, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        final Button[] actionButtons = new Button[actions.length];
        for (int row = 0; row * 3 < actions.length; row++) {
            LinearLayout line = row(grid);
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                if (i >= actions.length) {
                    View spacer = new View(getContext());
                    line.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
                    continue;
                }
                final String act = actions[i];
                final Button b = gridButton(MangaSettings.actionLabel(act));
                b.setOnClickListener(v -> {
                    mCaptureAction = act;
                    for (Button other : actionButtons) {
                        if (other != null) {
                            other.setBackgroundColor(0xFF2A2A33);
                        }
                    }
                    b.setBackgroundColor(0xFF3A5A8A);
                    updateCaptureStatus(MangaKeyCapture.active ? "捕获中…" : "尚未开始捕获");
                });
                b.setBackgroundColor(act.equals(mCaptureAction) ? 0xFF3A5A8A : 0xFF2A2A33);
                line.addView(b, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                actionButtons[i] = b;
            }
        }

        addView(caption("② 选择生效范围（点击后高亮）"));
        LinearLayout scopeRow = row(this);
        mScopeButtons[0] = gridButton("仅看图生效");
        mScopeButtons[0].setOnClickListener(v -> {
            mCaptureScope = MangaSettings.SCOPE_READER;
            updateScopeButtons();
        });
        mScopeButtons[1] = gridButton("全局生效");
        mScopeButtons[1].setOnClickListener(v -> {
            mCaptureScope = MangaSettings.SCOPE_GLOBAL;
            updateScopeButtons();
        });
        for (Button b : mScopeButtons) {
            scopeRow.addView(b, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        updateScopeButtons();

        addView(caption("③ 按键捕获（支持扳机 / 侧握 / A·B·X·Y / 摇杆按压 / 菜单键）"));
        LinearLayout captureRow = row(this);
        addButton(captureRow, "开始捕获", v -> beginCapture());
        addButton(captureRow, "重新捕获", v -> beginCapture());

        LinearLayout confirmRow = row(this);
        addButton(confirmRow, "确认保存", v -> {
            if (mCaptureKey == 0) {
                updateCaptureStatus("请先点「开始捕获」并按下任意键");
                return;
            }
            mSettings.addBinding(new MangaSettings.KeyBinding(
                    mCaptureAction, mCaptureKey, mCaptureScope));
            MangaKeyCapture.stop();
            rebuild();
        });
        addButton(confirmRow, "取消", v -> {
            MangaKeyCapture.stop();
            rebuild();
        });
    }

    private void beginCapture() {
        mCaptureKey = 0;
        MangaKeyCapture.start(code -> {
            mCaptureKey = code;
            updateCaptureStatus("已捕获 ✔ 现在点「确认保存」");
        });
        updateCaptureStatus("捕获中…请按任意手柄键");
    }

    private void updateScopeButtons() {
        if (mScopeButtons[0] == null || mScopeButtons[1] == null) {
            return;
        }
        mScopeButtons[0].setBackgroundColor(mCaptureScope == MangaSettings.SCOPE_READER
                ? 0xFF3A5A8A : 0xFF2A2A33);
        mScopeButtons[1].setBackgroundColor(mCaptureScope == MangaSettings.SCOPE_GLOBAL
                ? 0xFF3A5A8A : 0xFF2A2A33);
        updateCaptureStatus(MangaKeyCapture.active ? "捕获中…" : "尚未开始捕获");
    }

    private void updateCaptureStatus(String state) {
        if (mStatusView == null) {
            return;
        }
        String key = mCaptureKey == 0 ? "未捕获"
                : KeyEvent.keyCodeToString(mCaptureKey) + " (" + mCaptureKey + ")";
        mStatusView.setText("功能：" + MangaSettings.actionLabel(mCaptureAction)
                + "    范围：" + MangaSettings.scopeLabel(mCaptureScope)
                + "\n按键：" + key + "    状态：" + state);
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
        b.setMinHeight(dp(44));
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackgroundColor(0xFF2A2A33);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);
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
