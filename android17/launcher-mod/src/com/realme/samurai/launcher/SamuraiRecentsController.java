package com.realme.samurai.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.quickstep.FullscreenDrawParams;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.views.ClearAllButton;
import com.android.quickstep.views.IconAppChipView;
import com.android.quickstep.views.OverviewActionsView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;

import java.util.WeakHashMap;

/**
 * iOS 26 Stacked-Deck App Switcher controller for Quickstep RecentsView:
 * - 30dp task card corner radius & >= 12dp window transition corner radius
 * - Overlapping right-to-left cascading card deck (RecentsView.isRtl()-aware) applied in
 *   TaskView.applyScale()/applyTranslationX() so nonGridTranslationX / mPageScrolls stay 100% untouched
 * - Strictly monotonic Z-elevation across arbitrary task counts + elevated 19.2dp Z & smooth alpha fade
 *   during vertical/secondary swipe-up task dismissal
 * - Clean [Icon] App Title header (no Material chip background or dropdown arrow)
 * - Hidden Android "Screenshot | Select" OverviewActionsView & capsule ClearAllButton
 */
public final class SamuraiRecentsController {
    public static final float TASK_CORNER_RADIUS_DP = 30.0f;
    public static final float WINDOW_CORNER_RADIUS_DP = 32.0f;
    public static final float DECK_OVERLAP_FRAC = 0.34f;
    public static final float BASE_ELEVATION_DP = 18.0f;
    public static final float STEP_ELEVATION_DP = 1.5f;
    public static final float DRAG_ELEVATION_DP = 19.2f;

    // Resource-ID-range key for ClearAllButton.setTag(int, Object) so View.mTag stays untouched
    private static final int CLEAR_ALL_TAG_KEY = 0x79530024;

    // Per-TaskView state: [scaleMul, txAddPx, baseDeckAlpha, dismissFadeMul, activeDismissDrag]
    private static final WeakHashMap<TaskView, float[]> sDeckState = new WeakHashMap<>();
    private static boolean sInUpdateCurve = false;

    private SamuraiRecentsController() {}

    private static float[] getOrCreateDeckStateLocked(TaskView tv) {
        float[] entry = sDeckState.get(tv);
        if (entry == null) {
            entry = new float[] {1.0f, 0.0f, 1.0f, 1.0f, 0.0f};
            sDeckState.put(tv, entry);
        }
        return entry;
    }

    private static float updateTaskDeckCurveState(
            TaskView tv, float scaleMul, float txAdd, float baseDeckAlpha, boolean[] outDragging) {
        if (tv == null) {
            if (outDragging != null && outDragging.length > 0) {
                outDragging[0] = false;
            }
            return 1.0f;
        }
        synchronized (sDeckState) {
            float[] entry = getOrCreateDeckStateLocked(tv);
            entry[0] = scaleMul;
            entry[1] = txAdd;
            entry[2] = baseDeckAlpha;
            if (outDragging != null && outDragging.length > 0) {
                outDragging[0] = entry[4] > 0.5f;
            }
            return entry[3];
        }
    }

    public static void clearTaskDeckState(TaskView tv) {
        if (tv == null) {
            return;
        }
        synchronized (sDeckState) {
            float[] entry = sDeckState.get(tv);
            if (entry != null) {
                entry[0] = 1.0f;
                entry[1] = 0.0f;
                entry[2] = 1.0f;
                entry[3] = 1.0f;
                entry[4] = 0.0f;
            }
        }
    }

    public static void elevateDraggedTaskZ(TaskView tv) {
        if (tv == null) {
            return;
        }
        synchronized (sDeckState) {
            float[] entry = getOrCreateDeckStateLocked(tv);
            entry[4] = 1.0f;
        }
        float d = tv.getResources().getDisplayMetrics().density;
        if (d <= 0f) {
            d = 2.75f;
        }
        float minDragZ = DRAG_ELEVATION_DP * d;
        if (tv.getTranslationZ() < minDragZ) {
            tv.setTranslationZ(minDragZ);
        }
    }

    public static void onTaskDismissTranslationY(TaskView tv, float dismissTy) {
        if (tv == null) {
            return;
        }
        RecentsView rv = tv.getRecentsView();
        RecentsPagedOrientationHandler orient = (rv != null) ? rv.getPagedOrientationHandler() : null;
        boolean natural = (orient == null) || orient.isLayoutNaturalToLauncher();
        if (!natural) {
            return;
        }
        float span = Math.max(1.0f, tv.getHeight() * 0.75f);
        float progress = (dismissTy < -0.5f) ? clamp01(-dismissTy / span) : 0.0f;
        applyDismissProgressFade(tv, progress);
    }

    public static void onTaskDismissTranslationX(TaskView tv, float dismissTx) {
        if (tv == null) {
            return;
        }
        RecentsView rv = tv.getRecentsView();
        RecentsPagedOrientationHandler orient = (rv != null) ? rv.getPagedOrientationHandler() : null;
        boolean natural = (orient == null) || orient.isLayoutNaturalToLauncher();
        if (natural) {
            return;
        }
        float span = Math.max(1.0f, tv.getWidth() * 0.75f);
        float progress = (Math.abs(dismissTx) > 0.5f) ? clamp01(Math.abs(dismissTx) / span) : 0.0f;
        applyDismissProgressFade(tv, progress);
    }

    private static void applyDismissProgressFade(TaskView tv, float progress) {
        float fadeMul = 1.0f - 0.92f * progress;
        float baseAlpha;
        synchronized (sDeckState) {
            float[] entry = getOrCreateDeckStateLocked(tv);
            entry[3] = fadeMul;
            baseAlpha = entry[2];
        }
        tv.setStableAlpha(clamp01(baseAlpha * fadeMul));
    }

    public static void onTaskResetViewTransforms(TaskView tv) {
        if (tv == null) {
            return;
        }
        synchronized (sDeckState) {
            float[] entry = sDeckState.get(tv);
            if (entry != null) {
                entry[3] = 1.0f;
                entry[4] = 0.0f;
            }
        }
        if (sInUpdateCurve) {
            return;
        }
        RecentsView rv = tv.getRecentsView();
        if (rv != null) {
            onRecentsUpdateCurve(rv);
        } else {
            tv.setStableAlpha(1.0f);
        }
    }

    public static void onTaskBeingDismissedChanged(TaskView tv, boolean beingDismissed) {
        if (tv == null || beingDismissed) {
            return;
        }
        synchronized (sDeckState) {
            float[] entry = sDeckState.get(tv);
            if (entry != null) {
                entry[3] = 1.0f;
                entry[4] = 0.0f;
            }
        }
        if (!sInUpdateCurve) {
            RecentsView rv = tv.getRecentsView();
            if (rv != null) {
                onRecentsUpdateCurve(rv);
            }
        }
    }

    public static void onDismissAnimationEnds(RecentsView recents) {
        if (recents == null) {
            return;
        }
        int count = recents.getTaskViewCount();
        synchronized (sDeckState) {
            for (int i = 0; i < count; i++) {
                TaskView tv = recents.getTaskViewAt(i);
                if (tv == null) {
                    continue;
                }
                float[] entry = sDeckState.get(tv);
                if (entry != null) {
                    entry[3] = 1.0f;
                    entry[4] = 0.0f;
                }
            }
        }
        if (!sInUpdateCurve) {
            onRecentsUpdateCurve(recents);
        }
    }

    public static float adjustTaskScale(TaskView tv, float baseScale) {
        if (tv == null) {
            return baseScale;
        }
        float mul = 1.0f;
        synchronized (sDeckState) {
            float[] entry = sDeckState.get(tv);
            if (entry != null) {
                mul = entry[0];
            }
        }
        return baseScale * mul;
    }

    public static float adjustTaskTranslationX(TaskView tv, float baseTx) {
        if (tv == null) {
            return baseTx;
        }
        float add = 0.0f;
        synchronized (sDeckState) {
            float[] entry = sDeckState.get(tv);
            if (entry != null) {
                add = entry[1];
            }
        }
        return baseTx + add;
    }

    public static float zDp(int index) {
        if (index < 0) {
            return BASE_ELEVATION_DP;
        }
        if (index < 11) {
            return BASE_ELEVATION_DP - index * STEP_ELEVATION_DP;
        }
        return 0.5f / (1 + (index - 11));
    }

    public static void onUpdateCornerRadius(FullscreenDrawParams params, Context context) {
        if (params == null || context == null) {
            return;
        }
        float d = context.getResources().getDisplayMetrics().density;
        if (d <= 0f) {
            d = 2.75f;
        }
        params.taskCornerRadius = TASK_CORNER_RADIUS_DP * d;
        if (params.windowCornerRadius < 12.0f * d) {
            params.windowCornerRadius = WINDOW_CORNER_RADIUS_DP * d;
        }
        if (params.currentCornerRadius == 0f) {
            params.currentCornerRadius = params.taskCornerRadius;
        }
    }

    public static void onOverviewActionsInflated(OverviewActionsView actionsView) {
        hideOverviewActions(actionsView);
    }

    public static void onOverviewActionsHiddenFlags(OverviewActionsView actionsView) {
        hideOverviewActions(actionsView);
    }

    private static void hideOverviewActions(OverviewActionsView actionsView) {
        if (actionsView == null) {
            return;
        }
        actionsView.setVisibility(View.GONE);
        actionsView.setAlpha(0f);
        LinearLayout buttons = actionsView.mActionButtons;
        if (buttons != null) {
            buttons.setVisibility(View.GONE);
            buttons.setAlpha(0f);
        }
    }

    public static void styleIconAppChip(IconAppChipView chip) {
        if (chip == null) {
            return;
        }
        chip.setBackground(null);
        chip.setElevation(0f);
        chip.setTranslationZ(0f);
        chip.setClipToOutline(false);
        chip.setOutlineProvider(null);
        ImageView arrow = chip.iconArrowView;
        if (arrow != null) {
            arrow.setVisibility(View.GONE);
        }
        TextView title = chip.appTitle;
        if (title != null) {
            float d = chip.getResources().getDisplayMetrics().density;
            if (d <= 0f) {
                d = 2.75f;
            }
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            title.setTextColor(0xFFFFFFFF);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setShadowLayer(Math.max(1.0f, 3.0f * d), 0f, 1.0f * d, 0x88000000);
        }
    }

    public static void styleClearAllButton(ClearAllButton btn) {
        if (btn == null || Boolean.TRUE.equals(btn.getTag(CLEAR_ALL_TAG_KEY))) {
            return;
        }
        btn.setTag(CLEAR_ALL_TAG_KEY, Boolean.TRUE);
        float d = btn.getResources().getDisplayMetrics().density;
        if (d <= 0f) {
            d = 2.75f;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(22.0f * d);
        pill.setColor(0x38FFFFFF);
        pill.setStroke(Math.max(1, Math.round(1.0f * d)), 0x52FFFFFF);
        btn.setBackground(pill);
        int hPad = Math.round(18.0f * d);
        int vPad = Math.round(9.0f * d);
        btn.setPadding(hPad, vPad, hPad, vPad);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.0f);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public static void onRecentsUpdateCurve(RecentsView recents) {
        if (recents == null || sInUpdateCurve) {
            return;
        }
        int count = recents.getTaskViewCount();
        int recentsW = recents.getWidth();
        if (count <= 0 || recentsW <= 0) {
            return;
        }
        sInUpdateCurve = true;
        try {
            float d = recents.getResources().getDisplayMetrics().density;
            if (d <= 0f) {
                d = 2.75f;
            }
            float dragZPx = DRAG_ELEVATION_DP * d;
            boolean[] draggingFlag = new boolean[1];

            RecentsPagedOrientationHandler orient = recents.getPagedOrientationHandler();
            boolean naturalLayout = (orient == null) || orient.isLayoutNaturalToLauncher();
            boolean splitActive = recents.isSplitSelectionActive();

            float deckStrength = (!naturalLayout || splitActive)
                    ? 0f
                    : clamp01(1f - recents.mFullscreenProgress)
                            * clamp01(1f - recents.mTaskModalness)
                            * clamp01(1f - recents.mGridProgress);
            float contentAlpha = clamp01(recents.mContentAlpha);

            if (deckStrength <= 0.002f) {
                for (int i = 0; i < count; i++) {
                    TaskView tv = recents.getTaskViewAt(i);
                    if (tv == null) {
                        continue;
                    }
                    float fadeMul = updateTaskDeckCurveState(tv, 1.0f, 0.0f, contentAlpha, draggingFlag);
                    tv.applySamuraiDeckTransform();
                    tv.setTranslationZ(draggingFlag[0] ? dragZPx : 0f);
                    tv.setStableAlpha(clamp01(contentAlpha * fadeMul));
                }
                return;
            }

            if (count == 1) {
                TaskView tv = recents.getTaskViewAt(0);
                if (tv != null) {
                    float fadeMul = updateTaskDeckCurveState(tv, 1.0f, 0.0f, contentAlpha, draggingFlag);
                    tv.applySamuraiDeckTransform();
                    float baseZ = BASE_ELEVATION_DP * d * deckStrength;
                    tv.setTranslationZ(draggingFlag[0] ? Math.max(baseZ, dragZPx) : baseZ);
                    tv.setStableAlpha(clamp01(contentAlpha * fadeMul));
                }
                return;
            }

            float viewportCenterX = recents.getScrollX() + recentsW * 0.5f;
            boolean rtl = recents.isRtl();
            float pullSign = rtl ? 1f : -1f;

            for (int i = 0; i < count; i++) {
                TaskView tv = recents.getTaskViewAt(i);
                if (tv == null) {
                    continue;
                }
                int tvW = tv.getWidth();
                if (tvW <= 0) {
                    clearTaskDeckState(tv);
                    continue;
                }
                float cardCenterX = (tv.getLeft() + tv.getRight()) * 0.5f;
                float spanPx = Math.max(1f, tvW + 16.0f * d);
                float rel = (rtl ? (viewportCenterX - cardCenterX) : (cardCenterX - viewportCenterX)) / spanPx;

                float targetScale = (rel >= 0f)
                        ? Math.max(0.84f, 1.0f - 0.055f * Math.min(rel, 3.0f))
                        : Math.min(1.03f, 1.0f - 0.03f * Math.max(rel, -1.0f));
                float targetTx = (rel > 0f)
                        ? (pullSign * Math.min(rel, 3.2f) * tvW * DECK_OVERLAP_FRAC)
                        : (pullSign * Math.min(-rel, 1.0f) * tvW * 0.12f);
                float stackAlpha = (rel <= 2.15f)
                        ? 1f
                        : clamp01(1f - (rel - 2.15f) / 0.85f);

                float scaleMul = 1f + (targetScale - 1f) * deckStrength;
                float txAdd = targetTx * deckStrength;
                float baseDeckAlpha = contentAlpha * (1f + (stackAlpha - 1f) * deckStrength);
                float fadeMul = updateTaskDeckCurveState(tv, scaleMul, txAdd, baseDeckAlpha, draggingFlag);

                tv.applySamuraiDeckTransform();
                float baseZ = zDp(i) * d * deckStrength;
                tv.setTranslationZ(draggingFlag[0] ? Math.max(baseZ, dragZPx) : baseZ);
                tv.setStableAlpha(clamp01(baseDeckAlpha * fadeMul));
            }
        } finally {
            sInUpdateCurve = false;
        }
    }

    public static void onResetTaskVisuals(RecentsView recents, TaskView tv) {
        if (tv != null) {
            clearTaskDeckState(tv);
            tv.setTranslationZ(0f);
            tv.applySamuraiDeckTransform();
        }
        if (recents != null && !sInUpdateCurve) {
            onRecentsUpdateCurve(recents);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
