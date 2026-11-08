package com.realme.samurai.glass;

import android.view.Choreographer;

/**
 * 90Hz Choreographer-driven damped harmonic oscillator (`SamuraiSpring`) tuned to iOS 26 fluid
 * physics (default natural frequency omega_n = 28 rad/s, damping ratio zeta = 0.78).
 */
public final class SamuraiSpring {

    public interface UpdateListener {
        void onSpringUpdate(float value, float velocity);
    }

    private float mValue;
    private float mTarget;
    private float mVelocity;
    private float mOmega = 28.0f;
    private float mZeta = 0.78f;
    private boolean mRunning = false;
    private long mLastFrameNanos = 0L;
    private final UpdateListener mListener;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            float dt = (mLastFrameNanos == 0L)
                    ? (1.0f / 90.0f)
                    : ((frameTimeNanos - mLastFrameNanos) / 1_000_000_000f);
            mLastFrameNanos = frameTimeNanos;
            dt = Math.max(0.001f, Math.min(dt, 0.032f));

            // Sub-step twice for unconditional stability at high stiffness
            float halfDt = dt * 0.5f;
            for (int i = 0; i < 2; i++) {
                float disp = mValue - mTarget;
                float accel = -(mOmega * mOmega) * disp - (2.0f * mZeta * mOmega) * mVelocity;
                mVelocity += accel * halfDt;
                mValue += mVelocity * halfDt;
            }

            if (Math.abs(mValue - mTarget) < 0.0015f && Math.abs(mVelocity) < 0.01f) {
                mValue = mTarget;
                mVelocity = 0.0f;
                mRunning = false;
                mLastFrameNanos = 0L;
                if (mListener != null) {
                    mListener.onSpringUpdate(mValue, 0.0f);
                }
                return;
            }

            if (mListener != null) {
                mListener.onSpringUpdate(mValue, mVelocity);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public SamuraiSpring(float initialValue, UpdateListener listener) {
        mValue = initialValue;
        mTarget = initialValue;
        mListener = listener;
    }

    public SamuraiSpring setTuning(float omega, float zeta) {
        mOmega = Math.max(6.0f, omega);
        mZeta = Math.max(0.35f, Math.min(zeta, 1.5f));
        return this;
    }

    public void snapTo(float value) {
        cancel();
        mValue = value;
        mTarget = value;
        mVelocity = 0.0f;
        if (mListener != null) {
            mListener.onSpringUpdate(mValue, 0.0f);
        }
    }

    public void animateTo(float target) {
        mTarget = target;
        if (Math.abs(mValue - mTarget) < 0.0005f && Math.abs(mVelocity) < 0.005f) {
            mValue = mTarget;
            if (mListener != null) {
                mListener.onSpringUpdate(mValue, 0.0f);
            }
            return;
        }
        if (!mRunning) {
            mRunning = true;
            mLastFrameNanos = 0L;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    public void addImpulse(float velocityDelta) {
        mVelocity += velocityDelta;
        if (!mRunning) {
            mRunning = true;
            mLastFrameNanos = 0L;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    public void cancel() {
        if (mRunning) {
            mRunning = false;
            mLastFrameNanos = 0L;
            try {
                Choreographer.getInstance().removeFrameCallback(mFrameCallback);
            } catch (Throwable ignored) {
            }
        }
    }

    public float getValue() {
        return mValue;
    }

    public float getTarget() {
        return mTarget;
    }

    public boolean isRunning() {
        return mRunning;
    }
}
