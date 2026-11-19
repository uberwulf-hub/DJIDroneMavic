package com.saulls.airpilot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Transparent layer over the live video. In select mode the pilot drags a box around the
 * subject; while tracking it draws the box the drone is following.
 */
public class TrackOverlay extends View {

    public interface OnBoxListener {
        void onBoxDrawn(RectF screenBox);
    }

    public interface OnSubjectListener {
        void onSubjectPicked(int index);
    }

    private java.util.List<RectF> subjects;
    private int[] subjectIndexes;
    private OnSubjectListener subjectListener;
    private final Paint subjectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setOnSubjectListener(OnSubjectListener l) {
        subjectListener = l;
    }

    /** Subject-sensing mode: show detected subjects (view coords) to tap, or null to leave it. */
    public void setSubjects(java.util.List<RectF> rects, int[] indexes) {
        subjects = rects;
        subjectIndexes = indexes;
        if (rects != null) {
            selecting = false;
            target = null;
        }
        setVisibility(subjects != null || selecting || target != null ? VISIBLE : GONE);
        invalidate();
    }

    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean selecting;
    private float x0, y0, x1, y1;
    private boolean dragging;
    private RectF target;     // in this view's coordinates
    private int targetColor = Color.GREEN;
    private OnBoxListener listener;

    public TrackOverlay(Context c, AttributeSet a) {
        super(c, a);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(dp(3));
        fill.setColor(Color.parseColor("#33FFFFFF"));
        hint.setColor(Color.WHITE);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20, getResources().getDisplayMetrics()));
        hint.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        hint.setFakeBoldText(true);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public void setOnBoxListener(OnBoxListener l) {
        listener = l;
    }

    public void setSelecting(boolean on) {
        selecting = on;
        dragging = false;
        if (on) target = null;
        setVisibility(on || target != null ? VISIBLE : GONE);
        invalidate();
    }

    public boolean isSelecting() {
        return selecting;
    }

    /** Box the drone reports it's tracking (view coordinates), or null to hide. */
    public void setTarget(RectF r, int color) {
        target = r;
        targetColor = color;
        setVisibility(selecting || target != null ? VISIBLE : GONE);
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (subjects != null) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                for (int i = 0; i < subjects.size(); i++) {
                    RectF r = new RectF(subjects.get(i));
                    r.inset(-dp(12), -dp(12));
                    if (r.contains(e.getX(), e.getY())) {
                        int idx = subjectIndexes[i];
                        subjects = null;
                        invalidate();
                        if (subjectListener != null) subjectListener.onSubjectPicked(idx);
                        break;
                    }
                }
            }
            return true;
        }
        if (!selecting) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = x1 = e.getX();
                y0 = y1 = e.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                x1 = e.getX();
                y1 = e.getY();
                break;
            case MotionEvent.ACTION_UP:
                x1 = e.getX();
                y1 = e.getY();
                dragging = false;
                RectF r = new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
                if (r.width() < dp(30) || r.height() < dp(30)) {
                    // A tap: make a person-shaped box around it.
                    float w = dp(70), h = dp(140);
                    r = new RectF(x1 - w / 2, y1 - h / 2, x1 + w / 2, y1 + h / 2);
                }
                selecting = false;
                if (listener != null) listener.onBoxDrawn(r);
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (subjects != null) {
            c.drawText(subjects.isEmpty() ? "Looking for people… stand 10–30 ft in front of the camera"
                    : "Tap who (or what) to track", getWidth() / 2f, getHeight() * 0.22f, hint);
            subjectPaint.setStyle(Paint.Style.STROKE);
            subjectPaint.setStrokeWidth(dp(3));
            subjectPaint.setColor(Color.parseColor("#4FC3F7"));
            for (RectF r : subjects) c.drawRect(r, subjectPaint);
            return;
        }
        if (selecting) {
            c.drawText("Draw a box around the person to track (or tap them)",
                    getWidth() / 2f, getHeight() * 0.22f, hint);
            if (dragging) {
                RectF r = new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
                c.drawRect(r, fill);
                box.setColor(Color.WHITE);
                c.drawRect(r, box);
            }
        } else if (target != null) {
            box.setColor(targetColor);
            c.drawRect(target, box);
        }
    }
}
