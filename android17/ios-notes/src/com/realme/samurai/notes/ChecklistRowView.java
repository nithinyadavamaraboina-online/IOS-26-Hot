package com.realme.samurai.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

/**
 * Interactive iOS 26 Notes Checklist Row (`ChecklistRowView`).
 *
 * Tap `○` -> spring-animated glowing warm gold (`#FFB800` / `#FFD60A`) `●` checkmark with
 * strikethrough text and tactile AW8697 click haptic.
 */
public final class ChecklistRowView extends LinearLayout {

    public interface RowCallbacks {
        void onChanged();
        void onEnterInsertAfter(ChecklistRowView currentRow);
        void onDeleteRow(ChecklistRowView currentRow);
    }

    private final NotesDatabase.ChecklistItem mItem;
    private final CheckOrbView mCheckOrb;
    private final EditText mEditText;
    private final TextView mRemoveBtn;
    private boolean mDarkTheme;

    public ChecklistRowView(
            Context context,
            NotesDatabase.ChecklistItem item,
            boolean darkTheme,
            RowCallbacks callbacks) {
        super(context);
        mItem = item;
        mDarkTheme = darkTheme;
        float d = context.getResources().getDisplayMetrics().density;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(0, Math.round(5f * d), 0, Math.round(5f * d));

        mCheckOrb = new CheckOrbView(context, item.checked);
        mCheckOrb.setOnClickListener(v -> {
            mItem.checked = !mItem.checked;
            SamuraiGlassPrefs.hapticClick(context, v);
            mCheckOrb.setChecked(mItem.checked, true);
            syncTextAppearance();
            if (callbacks != null) callbacks.onChanged();
        });
        LayoutParams orbLp = new LayoutParams(Math.round(30f * d), Math.round(30f * d));
        orbLp.rightMargin = Math.round(10f * d);
        addView(mCheckOrb, orbLp);

        mEditText = new EditText(context);
        mEditText.setBackground(null);
        mEditText.setText(item.text);
        mEditText.setHint("Checklist item");
        mEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
        mEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mEditText.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        mEditText.setPadding(0, Math.round(4f * d), 0, Math.round(4f * d));
        mEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mItem.text = s != null ? s.toString() : "";
                if (callbacks != null) callbacks.onChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        mEditText.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterKey = event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
                if (callbacks != null) callbacks.onEnterInsertAfter(this);
                return true;
            }
            return false;
        });
        addView(mEditText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        mRemoveBtn = new TextView(context);
        mRemoveBtn.setText("\u2715");
        mRemoveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mRemoveBtn.setPadding(Math.round(10f * d), Math.round(4f * d), Math.round(4f * d), Math.round(4f * d));
        mRemoveBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            if (callbacks != null) callbacks.onDeleteRow(this);
        });
        addView(mRemoveBtn, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        syncTextAppearance();
    }

    public NotesDatabase.ChecklistItem getItem() {
        return mItem;
    }

    public EditText getEditText() {
        return mEditText;
    }

    public void updateTheme(boolean dark) {
        mDarkTheme = dark;
        mCheckOrb.invalidate();
        syncTextAppearance();
    }

    private void syncTextAppearance() {
        int primary = mDarkTheme ? 0xFFF5F6FA : 0xFF161822;
        int muted = mDarkTheme ? 0x80EBEBF5 : 0x7A3C3C43;
        mEditText.setTextColor(mItem.checked ? muted : primary);
        mEditText.setHintTextColor(muted);
        int flags = mEditText.getPaintFlags();
        if (mItem.checked) {
            mEditText.setPaintFlags(flags | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            mEditText.setPaintFlags(flags & (~Paint.STRIKE_THRU_TEXT_FLAG));
        }
        mRemoveBtn.setTextColor(muted);
    }

    private final class CheckOrbView extends View {
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCheckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mCheckPath = new Path();
        private final SamuraiSpring mSpring;
        private float mProgress;

        CheckOrbView(Context ctx, boolean checked) {
            super(ctx);
            mProgress = checked ? 1f : 0f;
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mCheckPaint.setStyle(Paint.Style.STROKE);
            mCheckPaint.setStrokeCap(Paint.Cap.ROUND);
            mCheckPaint.setStrokeJoin(Paint.Join.ROUND);

            mSpring = new SamuraiSpring(mProgress, (val, vel) -> {
                mProgress = Math.max(0f, Math.min(1.15f, val));
                invalidate();
            }).setTuning(34f, 0.70f);
        }

        void setChecked(boolean checked, boolean animate) {
            if (animate) {
                mSpring.animateTo(checked ? 1f : 0f);
            } else {
                mSpring.snapTo(checked ? 1f : 0f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float baseR = Math.min(cx, cy) - 2.8f * d;

            // Unchecked subtle ring
            mStrokePaint.setStrokeWidth(1.8f * d);
            mStrokePaint.setColor(mDarkTheme ? 0x66EBEBF5 : 0x553C3C43);
            canvas.drawCircle(cx, cy, baseR, mStrokePaint);

            if (mProgress > 0.02f) {
                float r = baseR * Math.min(1.08f, mProgress);
                // Glowing warm gold halo + core (#FFD60A / #FFB800)
                mFillPaint.setColor(0x36FFD60A);
                canvas.drawCircle(cx, cy, r + 2.2f * d, mFillPaint);

                mFillPaint.setColor(mDarkTheme ? 0xFFFFD60A : 0xFFFFB800);
                canvas.drawCircle(cx, cy, r, mFillPaint);

                // Crisp checkmark glyph
                mCheckPaint.setStrokeWidth(2.1f * d);
                mCheckPaint.setColor(0xFF1A1400);
                mCheckPath.reset();
                mCheckPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                mCheckPath.lineTo(cx - r * 0.10f, cy + r * 0.34f);
                mCheckPath.lineTo(cx + r * 0.45f, cy - r * 0.28f);
                canvas.drawPath(mCheckPath, mCheckPaint);
            }
        }
    }
}
