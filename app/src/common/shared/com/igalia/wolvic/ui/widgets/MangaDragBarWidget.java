/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.igalia.wolvic.R;

/**
 * A standalone grab bar floating in the empty gap between the manga surface
 * and the tray control bar, exactly where the stock browser window's handle
 * lives. It is NOT drawn inside the manga UI texture.
 *
 * <p>The bar, the tray and the manga surface are all linked in world space:
 * the bar and the tray are children of the manga widget, and pressing the bar
 * starts the native {@code WidgetMover} on that manga widget. The render loop
 * therefore moves the page UI, the bar and the control bar together, with the
 * same ray/plane intersection and world-space clamps as a browser window.</p>
 */
public class MangaDragBarWidget extends UIWidget {

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
                            WidgetManagerDelegate.WIDGET_MOVE_BEHAVIOUR_GENERAL);
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
