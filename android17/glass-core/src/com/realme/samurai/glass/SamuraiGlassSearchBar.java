package com.realme.samurai.glass;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating Bottom iOS 26 Liquid Glass Search Capsule (`SamuraiGlassSearchBar`), matching the
 * signature bottom-anchored Spotlight/Settings/Notes search pill on iOS 26.
 */
public class SamuraiGlassSearchBar extends SamuraiGlassCard {

    public interface OnQueryChangeListener {
        void onQueryChanged(String query);
    }

    private final EditText mInput;
    private final TextView mClearButton;
    private final TextView mLensIcon;

    public SamuraiGlassSearchBar(
            Context context,
            SamuraiGlassPrefs.Snapshot prefs,
            String hintText,
            OnQueryChangeListener listener) {
        super(context, prefs);
        float d = context.getResources().getDisplayMetrics().density;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setCornerRadiusDp(25f);
        int padH = Math.round(16f * d);
        int padV = Math.round(10f * d);
        setPadding(padH, padV, padH, padV);

        mLensIcon = new TextView(context);
        mLensIcon.setText("\u2315");
        mLensIcon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        addView(mLensIcon);

        mInput = new EditText(context);
        mInput.setBackground(null);
        mInput.setSingleLine(true);
        mInput.setHint(hintText != null ? hintText : "Search");
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        int gap = Math.round(10f * d);
        mInput.setPadding(gap, 0, gap, 0);
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(mInput, lp);

        mClearButton = new TextView(context);
        mClearButton.setText("\u2715");
        mClearButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mClearButton.setVisibility(GONE);
        mClearButton.setPadding(Math.round(6f * d), 0, Math.round(4f * d), 0);
        mClearButton.setOnClickListener(v -> {
            mInput.setText("");
            SamuraiGlassPrefs.hapticTick(this);
        });
        addView(mClearButton);

        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s != null ? s.toString() : "";
                mClearButton.setVisibility(q.isEmpty() ? GONE : VISIBLE);
                if (listener != null) {
                    listener.onQueryChanged(q);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        applyTextColors(prefs != null && prefs.darkTheme);
    }

    public EditText getEditText() {
        return mInput;
    }

    @Override
    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        super.updatePrefs(prefs);
        applyTextColors(prefs != null && prefs.darkTheme);
    }

    private void applyTextColors(boolean dark) {
        int primary = dark ? 0xFFF5F5FA : 0xFF14161F;
        int secondary = dark ? 0x99EBEBF5 : 0x8A3C3C43;
        mLensIcon.setTextColor(secondary);
        mInput.setTextColor(primary);
        mInput.setHintTextColor(secondary);
        mClearButton.setTextColor(secondary);
    }
}
