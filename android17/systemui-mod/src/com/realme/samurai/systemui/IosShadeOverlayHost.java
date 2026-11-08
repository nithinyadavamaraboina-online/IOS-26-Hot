package com.realme.samurai.systemui;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.realme.samurai.glass.SamuraiGlassPrefs;

/**
 * Full-screen high-Z overlay host (`IosShadeOverlayHost`) attached inside
 * `NotificationShadeWindowView`.
 *
 * Coordinates:
 * 1. `IosLockScreenOverlay` (Spatial Lock Screen clock, UDFPS crystal ring, Torch/Camera orbs)
 * 2. `DynamicIslandView` (Lock Screen mirror when `PhoneStatusBarView` is hidden on Keyguard)
 * 3. `IosControlCenterView` (Top-Right `x >= 0.58f * width`, `y < 42dp` pull-down Control Center)
 * 4. `ExpandedIslandPlatterView` (`362dp x 148dp` interactive Dynamic Island platter)
 */
public final class IosShadeOverlayHost extends FrameLayout {

    private final DynamicIslandController mIslandController;
    private final IosLockScreenOverlay mLockOverlay;
    private final DynamicIslandView mLockScreenIsland;
    private final IosControlCenterView mControlCenter;
    private final ExpandedIslandPlatterView mExpandedPlatter;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private boolean mKeyguardShowing = false;
    private boolean mKeyguardOccluded = false;
    private boolean mKeyguardGoingAway = false;
    private float mDozeAmount = 0f;

    private boolean mTrackingCcPull = false;
    private boolean mCcPullStartedCommitted = false;
    private boolean mTrackingIslandGesture = false;
    private float mPullDownStartX;
    private float mPullDownStartY;
    private VelocityTracker mPullVelocity;

    public IosShadeOverlayHost(
            Context context,
            DynamicIslandController islandController,
            SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mIslandController = islandController;
        mPrefs = prefs;

        setClipChildren(false);
        setClipToPadding(false);
        setElevation(SystemUiGlassHelper.dp(context, 100f));
        setTranslationZ(SystemUiGlassHelper.dp(context, 100f));

        mLockOverlay = new IosLockScreenOverlay(context, prefs);
        addView(mLockOverlay, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mLockScreenIsland = new DynamicIslandView(context, islandController, prefs);
        LayoutParams lockDiLp = new LayoutParams(
                SystemUiGlassHelper.dpInt(context, 250f),
                SystemUiGlassHelper.dpInt(context, 38f),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lockDiLp.topMargin = SystemUiGlassHelper.dpInt(context, 6f);
        mLockScreenIsland.setVisibility(GONE);
        addView(mLockScreenIsland, lockDiLp);

        mControlCenter = new IosControlCenterView(context, islandController, prefs);
        addView(mControlCenter, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mExpandedPlatter = new ExpandedIslandPlatterView(context, islandController, prefs);
        addView(mExpandedPlatter, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        mLockOverlay.updatePrefs(prefs);
        mLockScreenIsland.updatePrefs(prefs);
        mControlCenter.updatePrefs(prefs);
        mExpandedPlatter.updatePrefs(prefs);
        syncLockIslandVisibility();
    }

    public void setKeyguardState(
            boolean showing,
            boolean occluded,
            boolean goingAway,
            boolean canDismiss,
            float dozeAmount) {
        mKeyguardShowing = showing;
        mKeyguardOccluded = occluded;
        mKeyguardGoingAway = goingAway;
        mDozeAmount = dozeAmount;
        mLockOverlay.setKeyguardState(showing, occluded, goingAway, canDismiss, dozeAmount);
        syncLockIslandVisibility();
    }

    private void syncLockIslandVisibility() {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        boolean showOnKeyguard = snap.dynamicIslandEnabled
                && mKeyguardShowing
                && !mKeyguardOccluded
                && !mKeyguardGoingAway
                && mDozeAmount < 0.85f;
        mLockScreenIsland.setVisibility(showOnKeyguard ? VISIBLE : GONE);
    }

    public void collapseControlCenter(boolean animated) {
        mTrackingCcPull = false;
        mCcPullStartedCommitted = false;
        recyclePullVelocity();
        mControlCenter.collapse(animated);
    }

    public boolean requiresFullScreenTouch() {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        if (!snap.fullOsMode) return false;
        return mTrackingCcPull
                || mControlCenter.isShowingOrAnimating()
                || mExpandedPlatter.isShowingOrAnimating();
    }

    /**
     * Entry-point for `NotificationShadeWindowView.dispatchTouchEvent`.
     * Returns true ONLY when an iOS 26 interactive overlay owns the touch stream.
     * Returns false for all stock gestures (Top-Left Notification pull-down, Lock Screen
     * vertical swipe-up/bouncer, Goodix G3 UDFPS sensor, notification cards).
     */
    public boolean handleShadeDispatchTouch(MotionEvent ev) {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        if (!snap.fullOsMode) {
            return false;
        }

        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();

        // 1. Expanded Dynamic Island Platter (362dp x 148dp)
        if (snap.dynamicIslandEnabled && mExpandedPlatter.isShowingOrAnimating()) {
            return mExpandedPlatter.handleTouch(ev);
        }

        // 2. Active Control Center pull gesture in flight
        if (mTrackingCcPull) {
            return continueCcPullGesture(ev);
        }

        // 3. Control Center already open -> dispatch directly into Control Center
        if (snap.controlCenterEnabled && mControlCenter.isShowingOrAnimating()) {
            return mControlCenter.dispatchTouchEvent(ev);
        }

        // 3.5 Active Dynamic Island compact pill gesture in flight
        if (mTrackingIslandGesture) {
            return continueIslandPillGesture(ev);
        }

        // 4. Check for active Dynamic Island center tap OR Top-Right Control Center pull-down
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        float topZonePx = SystemUiGlassHelper.dp(getContext(), 42f);
        boolean onCenterIsland = snap.dynamicIslandEnabled
                && mIslandController.getState().active
                && y < topZonePx
                && Math.abs(x - w * 0.5f) <= SystemUiGlassHelper.dp(getContext(), 108f);
        if (action == MotionEvent.ACTION_DOWN && onCenterIsland) {
            mTrackingIslandGesture = true;
            mPullDownStartX = x;
            mPullDownStartY = y;
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN
                && snap.controlCenterEnabled
                && y < topZonePx
                && x >= (0.58f * w)) {
            mTrackingCcPull = true;
            mCcPullStartedCommitted = false;
            mPullDownStartX = x;
            mPullDownStartY = y;
            recyclePullVelocity();
            mPullVelocity = VelocityTracker.obtain();
            mPullVelocity.addMovement(ev);
            mControlCenter.beginTopRightPull();
            SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
            return true;
        }

        // 5. Lock Screen Bottom-Left Flashlight & Bottom-Right Camera 52dp Orbs
        if (snap.lockScreenEnabled && mLockOverlay.handleOrbTouch(ev)) {
            return true;
        }

        // 6. Passively observe Lock Screen vertical swipe to smoothly fade Spatial Clock
        if (snap.lockScreenEnabled) {
            mLockOverlay.observePassThroughTouch(ev);
        }

        return false;
    }

    public boolean handleShadeInterceptTouch(MotionEvent ev) {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        if (!snap.fullOsMode) return false;
        return mTrackingCcPull
                || mTrackingIslandGesture
                || (snap.controlCenterEnabled && mControlCenter.isShowingOrAnimating())
                || (snap.dynamicIslandEnabled && mExpandedPlatter.isShowingOrAnimating());
    }

    private boolean continueIslandPillGesture(MotionEvent ev) {
        int action = ev.getActionMasked();
        float dx = ev.getX() - mPullDownStartX;
        float dy = ev.getY() - mPullDownStartY;
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        if (action == MotionEvent.ACTION_MOVE) {
            if (dy > slop * 1.4f && Math.abs(dy) > Math.abs(dx)) {
                mTrackingIslandGesture = false;
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                mIslandController.setExpanded(true);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            mTrackingIslandGesture = false;
            if (Math.hypot(dx, dy) <= slop * 1.8f || dy > 0f) {
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                mIslandController.setExpanded(!mIslandController.isExpanded());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            mTrackingIslandGesture = false;
            return true;
        }
        return true;
    }

    private boolean continueCcPullGesture(MotionEvent ev) {
        if (mPullVelocity == null) {
            mPullVelocity = VelocityTracker.obtain();
        }
        mPullVelocity.addMovement(ev);
        int action = ev.getActionMasked();
        float dy = ev.getY() - mPullDownStartY;
        float dx = ev.getX() - mPullDownStartX;
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        if (action == MotionEvent.ACTION_MOVE) {
            if (!mCcPullStartedCommitted && (dy > slop * 0.6f || Math.hypot(dx, dy) > slop)) {
                mCcPullStartedCommitted = true;
                SamuraiGlassPrefs.hapticTick(this);
            }
            float fullPullDist = Math.max(1f, SystemUiGlassHelper.dp(getContext(), 280f));
            float rawProgress = Math.max(0f, dy / fullPullDist);
            mControlCenter.setInteractivePullProgress(rawProgress);
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            mPullVelocity.computeCurrentVelocity(1000);
            float vy = mPullVelocity.getYVelocity();
            boolean committed = mCcPullStartedCommitted;
            mTrackingCcPull = false;
            mCcPullStartedCommitted = false;
            recyclePullVelocity();
            if (!committed && Math.hypot(dx, dy) < slop) {
                // Quick tap on top-right status bar opens Control Center smoothly too
                mControlCenter.openWithVelocity(SystemUiGlassHelper.dp(getContext(), 900f));
            } else {
                mControlCenter.finishInteractivePull(vy);
            }
            SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            mTrackingCcPull = false;
            mCcPullStartedCommitted = false;
            recyclePullVelocity();
            mControlCenter.collapse(true);
            SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
            return true;
        }
        return true;
    }

    private void recyclePullVelocity() {
        if (mPullVelocity != null) {
            mPullVelocity.recycle();
            mPullVelocity = null;
        }
    }
}
