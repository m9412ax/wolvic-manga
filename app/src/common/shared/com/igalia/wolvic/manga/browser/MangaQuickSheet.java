/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.igalia.wolvic.R;

import java.util.ArrayList;
import java.util.List;

/**
 * In-panel floating sheet, modelled on NextPlayer's "快捷设置" dialog.
 *
 * <p>It is NOT an Android {@link android.app.Dialog}: the manga panel is a
 * widget texture in VR, and a separate window would never be composited into
 * it. Instead the sheet is added as the topmost child of the page container,
 * covering it with a scrim plus a rounded card, so it genuinely floats above
 * the page that opened it while staying inside the same surface.
 *
 * <p>Content is declarative and re-rendered on every pick ({@link #rerender()}),
 * which keeps checkmarks / selected states in sync without holding references
 * to individual widgets.
 */
public class MangaQuickSheet extends FrameLayout {

    /** Fills the sheet body. Re-invoked on every state change. */
    public interface Renderer {
        void render(MangaQuickSheet sheet);
    }

    public interface DismissListener {
        /** 确定 pressed. */
        void onConfirm();

        /** 取消 / scrim pressed. */
        default void onCancel() { }

        /** 重置 pressed (only offered when the sheet was opened with reset). */
        default void onReset() { }
    }

    public interface IntPick {
        void onPick(int index);
    }

    public interface TextPick {
        void onPick(String value);
    }

    // Palette: identical values to the rest of the manga UI so the sheet does
    // not look like a foreign component pasted on top.
    private static final int COLOR_TEXT = 0xFFF2F6F5;
    private static final int COLOR_MUTED = 0xFF9AA5A3;
    private static final int COLOR_ACCENT = 0xFF80D5D2;
    private static final int COLOR_SEGMENT_FILL = 0xFF3B4560;
    private static final int COLOR_CHIP_FILL = 0xFF232B2E;
    private static final int COLOR_STROKE = 0x33FFFFFF;

    private final LinearLayout mFooter;
    private final ScrollView mScroll;
    private final TextView mTitle;
    /** Where the builder methods append; switched while rendering a column. */
    private LinearLayout mBody;

    private Renderer mRenderer;
    private DismissListener mListener;

    public MangaQuickSheet(@NonNull Context context) {
        super(context);
        setClickable(true);
        LayoutInflater.from(context).inflate(R.layout.manga_quick_sheet, this, true);
        mBody = findViewById(R.id.sheetBody);
        mFooter = findViewById(R.id.sheetFooter);
        mScroll = findViewById(R.id.sheetScroll);
        mTitle = findViewById(R.id.sheetTitle);
        findViewById(R.id.sheetScrim).setOnClickListener(v -> cancel());
        // The sheet is measured after it is attached, so the first cap attempt
        // (made while rendering) has no width yet: retry on every layout pass.
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> capHeight());
    }

    /**
     * Adds the sheet on top of [host], replacing any sheet already there.
     */
    public static MangaQuickSheet open(@NonNull ViewGroup host,
                                       @NonNull CharSequence title,
                                       @Nullable Renderer renderer,
                                       @NonNull DismissListener listener) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            if (host.getChildAt(i) instanceof MangaQuickSheet) {
                host.removeViewAt(i);
            }
        }
        MangaQuickSheet sheet = new MangaQuickSheet(host.getContext());
        sheet.mRenderer = renderer;
        sheet.mListener = listener;
        sheet.mTitle.setText(title);
        host.addView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.render();
        return sheet;
    }

    /** Re-runs the renderer: the cheapest way to refresh every checkmark. */
    public void render() {
        mBody.removeAllViews();
        if (mRenderer != null) {
            mRenderer.render(this);
        }
        capHeight();
    }

    public void rerender() {
        render();
    }

    /** Adds 重置 / 取消 / 确定. Call after the initial {@link #open}. */
    public MangaQuickSheet withFooter(boolean withReset) {
        mFooter.removeAllViews();
        if (withReset) {
            mFooter.addView(footerButton("重置", COLOR_MUTED, () -> {
                if (mListener != null) {
                    mListener.onReset();
                }
                render();
            }));
        }
        mFooter.addView(footerButton("取消", COLOR_MUTED, this::cancel));
        mFooter.addView(footerButton("确定", COLOR_ACCENT, () -> {
            if (mListener != null) {
                mListener.onConfirm();
            }
            dismiss();
        }));
        capHeight();
        return this;
    }

    public void dismiss() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
    }

    private void cancel() {
        if (mListener != null) {
            mListener.onCancel();
        }
        dismiss();
    }

    // ------------------------------------------------------------ builders

    /**
     * Splits the body into two side-by-side columns. The manga panel is a
     * landscape 16:9-ish window, so stacking everything vertically would make
     * the sheet taller than the panel and hide the lower sections.
     */
    public MangaQuickSheet columns(Renderer left, Renderer right) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colLeft = new LinearLayout(getContext());
        colLeft.setOrientation(LinearLayout.VERTICAL);
        LinearLayout colRight = new LinearLayout(getContext());
        colRight.setOrientation(LinearLayout.VERTICAL);
        row.addView(colLeft, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = dp(18);
        row.addView(colRight, rightLp);
        mBody.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout host = mBody;
        mBody = colLeft;
        if (left != null) {
            left.render(this);
        }
        mBody = colRight;
        if (right != null) {
            right.render(this);
        }
        mBody = host;
        return this;
    }

    /** Section heading, e.g. 媒体布局 / 排序 / 字段. */
    public MangaQuickSheet section(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setTextColor(COLOR_MUTED);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(6);
        mBody.addView(tv, lp);
        return this;
    }

    public MangaQuickSheet divider() {
        View line = new View(getContext());
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(9);
        mBody.addView(line, lp);
        return this;
    }

    public MangaQuickSheet hint(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF7C8987);
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        mBody.addView(tv, lp);
        return this;
    }

    /**
     * Segmented pill: rounded containers with the selected entry filled.
     * Mirrors NextPlayer's 媒体浏览模式 / 媒体布局 rows.
     *
     * <p>A row only ever holds 2-4 entries: extension sources can expose twenty
     * genres, and squeezing them into one row made every label unreadable and
     * untappable, so long lists wrap into several stacked rows.
     */
    public MangaQuickSheet segments(String[] labels, int selected, IntPick pick) {
        if (labels == null || labels.length == 0) {
            return this;
        }
        int total = 0;
        for (String label : labels) {
            total += label == null ? 0 : label.length();
        }
        int avg = total / Math.max(1, labels.length);
        int perRow = labels.length <= 4 ? labels.length : 3;
        if (avg > 7) {
            perRow = Math.min(perRow, 2);
        }
        for (int start = 0; start < labels.length; start += perRow) {
            int end = Math.min(labels.length, start + perRow);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(pill(0x00000000, COLOR_STROKE, 22));
            row.setPadding(dp(3), dp(3), dp(3), dp(3));
            for (int i = start; i < end; i++) {
                final int index = i;
                TextView item = new TextView(getContext());
                item.setText(i == selected ? "✓  " + labels[i] : labels[i]);
                item.setTextSize(16f);
                item.setSingleLine(true);
                item.setEllipsize(TextUtils.TruncateAt.END);
                item.setGravity(Gravity.CENTER);
                item.setHeight(dp(38));
                item.setPadding(dp(6), 0, dp(6), 0);
                item.setTextColor(i == selected ? COLOR_TEXT : 0xFFC0C9C7);
                item.setBackground(i == selected
                        ? pill(COLOR_SEGMENT_FILL, 0x00000000, 19)
                        : pill(0x00000000, 0x00000000, 19));
                item.setOnClickListener(v -> {
                    pick.onPick(index);
                    render();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(item, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(6);
            mBody.addView(row, rowLp);
        }
        return this;
    }

    /** Small caption under a control, e.g. the current value. */
    public MangaQuickSheet caption(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF7C8987);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        mBody.addView(tv, lp);
        return this;
    }

    /**
     * Round icon chips with a label underneath, like NextPlayer's 排序 keys
     * (标题 / 日期 / 大小).
     */
    public MangaQuickSheet roundChips(String[] glyphs, String[] labels,
                                      int selected, IntPick pick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView glyph = new TextView(getContext());
            glyph.setText(glyphs != null && i < glyphs.length ? glyphs[i] : "");
            glyph.setTextSize(17f);
            glyph.setTypeface(Typeface.DEFAULT_BOLD);
            glyph.setGravity(Gravity.CENTER);
            glyph.setTextColor(on ? COLOR_TEXT : 0xFFAEB8B6);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(on ? COLOR_SEGMENT_FILL : COLOR_CHIP_FILL);
            circle.setStroke(Math.max(1, dp(1)), on ? COLOR_ACCENT : COLOR_STROKE);
            glyph.setBackground(circle);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(46), dp(46));
            gp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(glyph, gp);

            TextView label = new TextView(getContext());
            label.setText(labels[i]);
            label.setTextSize(12f);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(on ? COLOR_TEXT : 0xFF97A2A0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(label, lp);

            cell.setOnClickListener(v -> {
                pick.onPick(index);
                render();
            });
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(cell, cellLp);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        rowLp.bottomMargin = dp(6);
        mBody.addView(row, rowLp);
        return this;
    }

    /**
     * Checkbox chips that wrap onto as many lines as needed (NextPlayer's 字段
     * row): ○ unchecked, ✓ checked.
     */
    public MangaQuickSheet toggleChips(String[] labels, boolean[] checked, IntPick pick) {
        String[] texts = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            boolean on = checked != null && i < checked.length && checked[i];
            texts[i] = (on ? "✓  " : "○  ") + labels[i];
        }
        return toggleChipsRaw(texts, checked, pick);
    }

    /** Same row, but the caller supplies the full chip text (tri-state marks). */
    public MangaQuickSheet toggleChipsRaw(String[] texts, boolean[] checked, IntPick pick) {
        FlowLayout flow = new FlowLayout(getContext(), dp(8));
        for (int i = 0; i < texts.length; i++) {
            final int index = i;
            boolean on = checked != null && i < checked.length && checked[i];
            TextView chip = new TextView(getContext());
            chip.setText(texts[i]);
            chip.setTextSize(15f);
            chip.setSingleLine(true);
            chip.setTextColor(on ? COLOR_TEXT : 0xFFB4BFBD);
            chip.setBackground(pill(on ? 0xFF323C4C : COLOR_CHIP_FILL,
                    on ? COLOR_ACCENT : COLOR_STROKE, 19));
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setOnClickListener(v -> {
                pick.onPick(index);
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            lp.bottomMargin = dp(8);
            flow.addView(chip, lp);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(2);
        mBody.addView(flow, rowLp);
        return this;
    }

    /** Text input row (extension filter text fields). */
    public MangaQuickSheet textRow(String hintText, String value, TextPick pick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = new EditText(getContext());
        input.setHint(hintText);
        input.setText(value == null ? "" : value);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(0xFF808A88);
        input.setTextSize(15f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(pill(COLOR_CHIP_FILL, COLOR_STROKE, 18));
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView apply = new TextView(getContext());
        apply.setText("确定");
        apply.setTextSize(15f);
        apply.setTextColor(COLOR_ACCENT);
        apply.setPadding(dp(16), dp(10), dp(8), dp(10));
        apply.setOnClickListener(v -> {
            pick.onPick(input.getText().toString());
            render();
        });
        row.addView(apply, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        mBody.addView(row, lp);
        return this;
    }

    public MangaQuickSheet space(int dp) {
        View v = new View(getContext());
        mBody.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        return this;
    }

    // ------------------------------------------------------------- internal

    private TextView footerButton(String text, int color, Runnable action) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    /**
     * Caps the scrollable body so the footer (取消 / 确定) always stays inside
     * the card. Without it a long body would either push the footer out of the
     * panel or clamp the card and hide it.
     */
    private void capHeight() {
        post(() -> {
            ViewGroup.LayoutParams lp = mScroll.getLayoutParams();
            android.view.ViewParent parent = getParent();
            int parentH = parent instanceof View ? ((View) parent).getHeight() : getHeight();
            if (parentH <= 0) {
                parentH = getResources().getDisplayMetrics().heightPixels;
            }
            int w = mScroll.getWidth();
            if (w <= 0) {
                return;   // not laid out yet; the next render() will retry
            }
            int maxCard = Math.max(dp(180), Math.round(parentH * 0.92f));
            int chrome = dp(18) + mTitle.getHeight() + dp(17)
                    + mFooter.getHeight() + dp(16);
            int avail = Math.max(dp(100), maxCard - chrome);
            mScroll.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int target = Math.min(mScroll.getMeasuredHeight(), avail);
            if (lp.height != target) {
                lp.height = target;
                mScroll.setLayoutParams(lp);
            }
        });
    }

    /** Emulates measured-width wrapping for chips (no FlowLayout in AOSP). */
    static final class FlowLayout extends LinearLayout {
        private final int mGap;

        FlowLayout(Context context, int gapPx) {
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
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (lineX > 0 && lineX + cw > width) {
                    totalH += lineH;
                    lineX = 0;
                    lineH = 0;
                }
                lineX += cw;
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
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > getPaddingLeft() && x + cw > width) {
                    x = getPaddingLeft();
                    y += lineH;
                    lineH = 0;
                }
                int left = x + lp.leftMargin;
                int top = y + lp.topMargin;
                child.layout(left, top,
                        left + child.getMeasuredWidth(),
                        top + child.getMeasuredHeight());
                x += cw;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    private GradientDrawable pill(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            d.setStroke(Math.max(1, dp(1)), stroke);
        }
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
