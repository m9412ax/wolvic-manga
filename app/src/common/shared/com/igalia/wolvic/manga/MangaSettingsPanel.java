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
    private TextView mCaptureKeyView;

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

        addInfo("添加快捷键", "① 选择功能  ② 选择生效范围  ③ 按下手柄任意键捕获  ④ 确认保存");
        addInfo("提示", "已支持手柄全部按键：扳机、侧握、A/B/X/Y、摇杆按压、菜单键。摇杆左右翻页、上下缩放；按住侧握 + 摇杆调整窗口大小和距离。");

        TextView actionCaption = new TextView(getContext());
        actionCaption.setText("功能：");
        actionCaption.setTextSize(17f);
        actionCaption.setTextColor(0xFFFFFFFF);
        addView(actionCaption);

        LinearLayout actionRow = addRow();
        String[] actions = MangaSettings.allActions();
        Button[] actionButtons = new Button[actions.length];
        for (int i = 0; i < actions.length; i++) {
            final String act = actions[i];
            final Button b = new Button(getContext());
            b.setText(MangaSettings.actionLabel(act));
            b.setAllCaps(false);
            b.setTextSize(14f);
            b.setTextColor(0xFFFFFFFF);
            b.setMinHeight(dp(44));
            b.setOnClickListener(v -> {
                mCaptureAction = act;
                for (Button ab : actionButtons) {
                    ab.setBackgroundColor(0xFF2A2A33);
                }
                b.setBackgroundColor(0xFF3A5A8A);
            });
            b.setBackgroundColor(i == 0 ? 0xFF3A5A8A : 0xFF2A2A33);
            actionButtons[i] = b;
            actionRow.addView(b);
        }

        final Button scopeBtn = new Button(getContext());
        scopeBtn.setAllCaps(false);
        scopeBtn.setTextSize(16f);
        scopeBtn.setTextColor(0xFFFFFFFF);
        scopeBtn.setMinHeight(dp(48));
        scopeBtn.setOnClickListener(v -> {
            mCaptureScope = mCaptureScope == MangaSettings.SCOPE_READER
                    ? MangaSettings.SCOPE_GLOBAL : MangaSettings.SCOPE_READER;
            scopeBtn.setText("生效范围：" + MangaSettings.scopeLabel(mCaptureScope));
        });
        scopeBtn.setText("生效范围：" + MangaSettings.scopeLabel(mCaptureScope));
        addView(scopeBtn);

        TextView keyView = new TextView(getContext());
        keyView.setText("按键：未捕获（按下手柄任意键）");
        keyView.setTextSize(18f);
        keyView.setTextColor(0xFFFFD080);
        keyView.setPadding(0, dp(10), 0, dp(10));
        addView(keyView);
        mCaptureKeyView = keyView;

        MangaKeyCapture.start(code -> {
            mCaptureKey = code;
            if (mCaptureKeyView != null) {
                mCaptureKeyView.setText("按键：" + KeyEvent.keyCodeToString(code) + " (" + code + ")");
            }
        });

        LinearLayout confirmRow = addRow();
        addButton(confirmRow, "确认保存", v -> {
            if (mCaptureKey == 0) {
                if (mCaptureKeyView != null) {
                    mCaptureKeyView.setText("请先按下任意键再保存");
                }
                return;
            }
            mSettings.addBinding(new MangaSettings.KeyBinding(mCaptureAction, mCaptureKey, mCaptureScope));
            MangaKeyCapture.stop();
            rebuild();
        });
        addButton(confirmRow, "取消", v -> {
            MangaKeyCapture.stop();
            rebuild();
        });
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
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
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
